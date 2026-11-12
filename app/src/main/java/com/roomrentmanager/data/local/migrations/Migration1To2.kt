package com.roomrentmanager.data.local.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.roomrentmanager.data.local.DatabaseTriggers
import com.roomrentmanager.domain.finance.Money
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * v1 -> v2. v1 had `rooms(id, name, monthlyRent REAL)`, `tenants(id, name, phone, roomId, moveInDate)`
 * and `payments(id, tenantId, month, amount REAL, paidOn, note)`.
 *
 * Policy (see the redesign spec, section 13):
 * - Room, tenant and payment IDs are preserved. Rooms go under one "Imported Property".
 * - REAL rupees become paise via [Money.legacyRupeesToPaise] (shortest decimal form, HALF_UP).
 * - An ACTIVE tenancy is created only when the tenant's room exists, the move-in date is a valid
 *   ISO date, and the tenant is the only such tenant of that room. Shared rooms are not guessed;
 *   their tenants go to `migration_review`.
 * - No rent agreements and no rent charges are created: v1 never stored the terms or history
 *   needed to do that truthfully. The user sets up agreements after the upgrade.
 * - Every v1 payment is imported with `tenancyId = NULL` and a PENDING `migration_review` row
 *   holding its original month/date/amount/note and a suggested tenancy. It does not count as
 *   tenant credit until the user resolves it. A v1 amount that cannot be a valid payment
 *   (NaN/infinite/zero/negative) is kept only in its review row.
 * - Counts, money totals and foreign keys are validated before the staging tables are dropped.
 *   Any failure throws, which rolls back the whole upgrade and leaves v1 data untouched.
 */
object Migration1To2 : Migration(1, 2) {

    const val IMPORTED_PROPERTY_NAME = "Imported Property"

    override fun migrate(db: SupportSQLiteDatabase) {
        migrate(db, System.currentTimeMillis())
    }

    fun migrate(db: SupportSQLiteDatabase, now: Long) {
        // 1. Stage the v1 tables. Their indexes keep their names when renamed, so drop them first
        //    to free the names for v2.
        listOf("index_tenants_roomId", "index_payments_tenantId", "index_payments_month")
            .forEach { db.execSQL("DROP INDEX IF EXISTS `$it`") }
        db.execSQL("ALTER TABLE `rooms` RENAME TO `legacy_v1_rooms`")
        db.execSQL("ALTER TABLE `tenants` RENAME TO `legacy_v1_tenants`")
        db.execSQL("ALTER TABLE `payments` RENAME TO `legacy_v1_payments`")

        // 2. Create every v2 table and index before copying anything.
        SCHEMA_V2_DDL.forEach(db::execSQL)

        // 3. Copy.
        val report = Report()
        val rooms = importRooms(db, now, report)
        val tenancyByTenant = importTenants(db, now, rooms, report)
        importPayments(db, now, tenancyByTenant, report)

        // 4. Validate against the untouched staging tables.
        validate(db, report)

        // 5. Record what happened, then drop staging and install integrity triggers.
        db.insert("migration_reports", CONFLICT_ABORT, ContentValues().apply {
            put("fromVersion", 1); put("toVersion", 2); put("report", report.text()); put("createdAt", now)
        })
        db.insert("audit_log", CONFLICT_ABORT, ContentValues().apply {
            put("eventType", "DATABASE_MIGRATED"); put("entityType", "DATABASE"); putNull("entityId")
            put("description", report.summary()); put("createdAt", now)
        })
        db.execSQL("DROP TABLE `legacy_v1_payments`")
        db.execSQL("DROP TABLE `legacy_v1_tenants`")
        db.execSQL("DROP TABLE `legacy_v1_rooms`")
        DatabaseTriggers.create(db)
    }

    private class LegacyRoom(val id: Long, val name: String)

    private fun importRooms(db: SupportSQLiteDatabase, now: Long, report: Report): Map<Long, LegacyRoom> {
        val rooms = mutableMapOf<Long, LegacyRoom>()
        var propertyId: Long? = null
        db.query("SELECT id, name, monthlyRent FROM legacy_v1_rooms ORDER BY id").use { c ->
            while (c.moveToNext()) {
                if (propertyId == null) {
                    propertyId = db.insert("properties", CONFLICT_ABORT, ContentValues().apply {
                        put("name", IMPORTED_PROPERTY_NAME); put("propertyType", "RESIDENTIAL"); putNull("address")
                        put("notes", "Created when upgrading from app version 1. Rename or reclassify as needed.")
                        put("isActive", 1); put("createdAt", now); put("updatedAt", now)
                    })
                }
                val id = c.getLong(0)
                val name = c.getString(1)
                val rent = c.getDouble(2)
                val paise = Money.legacyRupeesToPaise(rent)?.takeIf { it >= 0 }
                if (paise == null) report.notes += "Room $id ('$name'): rent $rent is not a valid amount; default rent left empty."
                else if (isRounded(rent)) report.roundedRoomRents++
                db.insert("rooms", CONFLICT_ABORT, ContentValues().apply {
                    put("id", id); put("propertyId", propertyId); put("name", name)
                    if (paise == null) putNull("defaultRentPaise") else put("defaultRentPaise", paise)
                    put("isActive", 1); putNull("notes"); put("createdAt", now); put("updatedAt", now)
                })
                rooms[id] = LegacyRoom(id, name)
            }
        }
        report.rooms = rooms.size
        return rooms
    }

    private class LegacyTenant(val id: Long, val name: String, val roomId: Long?, val moveIn: String)

    /** Returns tenantId -> (tenancyId, move-in date) for the tenancies created. */
    private fun importTenants(
        db: SupportSQLiteDatabase,
        now: Long,
        rooms: Map<Long, LegacyRoom>,
        report: Report,
    ): Map<Long, Pair<Long, LocalDate>> {
        val tenants = mutableListOf<LegacyTenant>()
        db.query("SELECT id, name, phone, roomId, moveInDate FROM legacy_v1_tenants ORDER BY id").use { c ->
            while (c.moveToNext()) {
                val t = LegacyTenant(c.getLong(0), c.getString(1), if (c.isNull(3)) null else c.getLong(3), c.getString(4))
                val phone = c.getString(2).trim()
                db.insert("tenants", CONFLICT_ABORT, ContentValues().apply {
                    put("id", t.id); put("name", t.name)
                    if (phone.isEmpty()) putNull("phone") else put("phone", phone)
                    putNull("email"); putNull("address"); putNull("notes")
                    put("isActive", 1); put("createdAt", now); put("updatedAt", now)
                })
                tenants += t
            }
        }
        report.tenants = tenants.size

        val candidates = tenants.filter { it.roomId != null }
        val tenantsPerRoom = candidates.groupBy { it.roomId!! }
        val created = mutableMapOf<Long, Pair<Long, LocalDate>>()
        for (t in candidates) {
            val room = rooms[t.roomId]
            val moveIn = parseDate(t.moveIn)
            val sharers = tenantsPerRoom.getValue(t.roomId!!).size
            val reason = when {
                room == null -> "ROOM_MISSING: v1 room ${t.roomId} no longer exists."
                moveIn == null -> "INVALID_MOVE_IN: v1 move-in date '${t.moveIn}' is not a valid date."
                sharers > 1 -> "ROOM_SHARED: room '${room.name}' had $sharers tenants in v1. Only one active tenancy per room is allowed, so none was created; start the correct one manually."
                else -> null
            }
            if (reason != null) {
                insertReview(db, now, "TENANT", t.id, reason, null, JSONObject().apply {
                    put("legacyRoomId", t.roomId); put("legacyRoomName", room?.name ?: JSONObject.NULL); put("moveInDate", t.moveIn)
                })
                report.tenantsForReview++
                continue
            }
            val validMoveIn = checkNotNull(moveIn)
            val tenancyId = db.insert("tenancies", CONFLICT_ABORT, ContentValues().apply {
                put("roomId", t.roomId); put("tenantId", t.id); put("moveInDate", validMoveIn.toString()); putNull("moveOutDate")
                put("status", "ACTIVE"); put("activeRoomId", t.roomId); put("createdAt", now); put("updatedAt", now)
            })
            created[t.id] = tenancyId to validMoveIn
        }
        report.tenancies = created.size
        return created
    }

    private fun importPayments(
        db: SupportSQLiteDatabase,
        now: Long,
        tenancyByTenant: Map<Long, Pair<Long, LocalDate>>,
        report: Report,
    ) {
        val sql = """
            SELECT p.id, p.tenantId, p.month, p.amount, p.paidOn, p.note, t.name
            FROM legacy_v1_payments p LEFT JOIN legacy_v1_tenants t ON t.id = p.tenantId ORDER BY p.id
        """
        db.query(sql).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val tenantId = c.getLong(1)
                val month = c.getString(2)
                val amount = c.getDouble(3)
                val paidOn = c.getString(4)
                val note = c.getString(5).trim()
                val tenantName = c.getString(6)
                val paise = Money.legacyRupeesToPaise(amount)?.takeIf { it > 0 }

                val problems = mutableListOf<String>()
                if (paise == null) problems += "amount $amount is not a valid payment amount; no payment row was created"
                if (parseDate(paidOn) == null) problems += "received date '$paidOn' is not a valid date"
                val yearMonth = runCatching { YearMonth.parse(month) }.getOrNull()
                if (yearMonth == null) problems += "rent month '$month' is not a valid month"

                val tenancy = tenancyByTenant[tenantId]
                val suggested = tenancy?.takeIf { (_, moveIn) -> yearMonth != null && !yearMonth.isBefore(YearMonth.from(moveIn)) }?.first
                if (tenancy == null) problems += "tenant has no imported tenancy"
                else if (suggested == null) problems += "rent month is before the tenancy's move-in month"

                if (paise != null) {
                    db.insert("payments", CONFLICT_ABORT, ContentValues().apply {
                        put("id", id); putNull("tenancyId"); put("amountPaise", paise); put("paymentDate", paidOn)
                        put("paymentMethod", "OTHER"); putNull("reference"); if (note.isEmpty()) putNull("note") else put("note", note)
                        put("status", "POSTED"); putNull("reversalReason"); putNull("reversedAt"); put("createdAt", now)
                    })
                    report.payments++
                    report.paymentPaise += paise
                    if (isRounded(amount)) report.roundedPayments++
                } else {
                    report.paymentsKeptOnlyInReview++
                }
                val reason = "LEGACY_PAYMENT: imported from v1 for rent month $month. " +
                    (if (problems.isEmpty()) "Matches the tenant's imported tenancy; confirm to count it." else problems.joinToString("; ") + ".")
                insertReview(db, now, "PAYMENT", id, reason, suggested, JSONObject().apply {
                    put("legacyPaymentId", id); put("tenantId", tenantId); put("tenantName", tenantName ?: JSONObject.NULL)
                    put("month", month); put("amount", amount.toString()); put("paidOn", paidOn); put("note", note)
                })
            }
        }
    }

    private fun validate(db: SupportSQLiteDatabase, r: Report) {
        val failures = mutableListOf<String>()
        fun count(sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
        fun check(ok: Boolean, message: String) { if (!ok) failures += message }

        val legacyRooms = count("SELECT COUNT(*) FROM legacy_v1_rooms")
        val legacyTenants = count("SELECT COUNT(*) FROM legacy_v1_tenants")
        val legacyPayments = count("SELECT COUNT(*) FROM legacy_v1_payments")
        check(count("SELECT COUNT(*) FROM rooms") == legacyRooms, "room count differs")
        check(count("SELECT COUNT(*) FROM rooms r JOIN legacy_v1_rooms l ON l.id = r.id AND l.name = r.name") == legacyRooms, "room ids/names differ")
        check(count("SELECT COUNT(*) FROM tenants") == legacyTenants, "tenant count differs")
        check(count("SELECT COUNT(*) FROM tenants t JOIN legacy_v1_tenants l ON l.id = t.id AND l.name = t.name") == legacyTenants, "tenant ids/names differ")
        check(count("SELECT COUNT(*) FROM payments") + r.paymentsKeptOnlyInReview == legacyPayments, "payment count differs")
        check(count("SELECT COUNT(*) FROM payments p JOIN legacy_v1_payments l ON l.id = p.id") == count("SELECT COUNT(*) FROM payments"), "payment ids differ")
        check(count("SELECT COUNT(*) FROM migration_review WHERE entityType = 'PAYMENT'") == legacyPayments, "a v1 payment has no review record")
        check(count("SELECT COUNT(*) FROM tenancies") + r.tenantsForReview == count("SELECT COUNT(*) FROM legacy_v1_tenants WHERE roomId IS NOT NULL"), "tenancy count differs")
        check(count("SELECT COUNT(*) FROM tenancies WHERE status = 'ACTIVE'") == count("SELECT COUNT(DISTINCT activeRoomId) FROM tenancies"), "a room has two active tenancies")
        check(count("SELECT COUNT(*) FROM rent_ledger") == 0L && count("SELECT COUNT(*) FROM rent_agreements") == 0L, "charges or agreements were invented")

        // Money: the v2 paise total must equal the exact v1 total within the documented rounding
        // (at most half a paisa per rounded row), and must equal the row-by-row conversion.
        var legacyExact = BigDecimal.ZERO
        var legacyConverted = 0L
        db.query("SELECT amount FROM legacy_v1_payments").use { c ->
            while (c.moveToNext()) {
                val paise = Money.legacyRupeesToPaise(c.getDouble(0))?.takeIf { it > 0 } ?: continue
                legacyExact += BigDecimal(c.getDouble(0).toString())
                legacyConverted += paise
            }
        }
        val v2Total = count("SELECT COALESCE(SUM(amountPaise), 0) FROM payments")
        check(v2Total == legacyConverted && v2Total == r.paymentPaise, "payment total differs: v2=$v2Total, converted v1=$legacyConverted")
        val drift = (BigDecimal(v2Total) - legacyExact.movePointRight(2)).abs()
        check(drift <= BigDecimal(r.roundedPayments).divide(BigDecimal(2)), "payment rounding drift $drift paise exceeds the rounding rule")

        db.query("PRAGMA foreign_key_check").use { c -> check(c.count == 0, "${c.count} foreign key violations") }

        r.validation = if (failures.isEmpty()) "PASSED" else "FAILED: " + failures.joinToString("; ")
        if (failures.isNotEmpty()) throw IllegalStateException("Migration 1->2 validation failed, rolling back. " + r.text())
    }

    private fun insertReview(
        db: SupportSQLiteDatabase, now: Long, type: String, entityId: Long, reason: String, suggestedTenancyId: Long?, data: JSONObject,
    ) {
        db.insert("migration_review", CONFLICT_ABORT, ContentValues().apply {
            put("entityType", type); put("entityId", entityId); put("reason", reason); put("legacyData", data.toString())
            if (suggestedTenancyId == null) putNull("suggestedTenancyId") else put("suggestedTenancyId", suggestedTenancyId)
            put("status", "PENDING"); putNull("resolvedAt"); put("createdAt", now)
        })
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    private fun isRounded(value: Double) = BigDecimal(value.toString()).stripTrailingZeros().scale() > 2

    private class Report {
        var rooms = 0
        var tenants = 0
        var tenancies = 0
        var tenantsForReview = 0
        var payments = 0
        var paymentPaise = 0L
        var paymentsKeptOnlyInReview = 0
        var roundedPayments = 0
        var roundedRoomRents = 0
        var validation = "NOT RUN"
        val notes = mutableListOf<String>()

        fun summary() = "Upgraded from v1: $rooms rooms, $tenants tenants, $tenancies tenancies, " +
            "$payments payments (${Money.format(paymentPaise)}) awaiting review. Validation $validation."

        fun text() = buildString {
            appendLine("Rooms imported: $rooms (rents rounded to the paisa: $roundedRoomRents)")
            appendLine("Tenants imported: $tenants")
            appendLine("Active tenancies created: $tenancies")
            appendLine("Tenants needing review (no tenancy created): $tenantsForReview")
            appendLine("Payments imported: $payments, total ${Money.format(paymentPaise)} (rounded to the paisa: $roundedPayments)")
            appendLine("Payments kept only in review (invalid amount): $paymentsKeptOnlyInReview")
            appendLine("Rent agreements and charges created: 0 (v1 did not store enough history)")
            notes.forEach { appendLine(it) }
            append("Validation: $validation")
        }
    }
}
