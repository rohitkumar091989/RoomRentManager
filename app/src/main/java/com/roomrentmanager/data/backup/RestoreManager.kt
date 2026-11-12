package com.roomrentmanager.data.backup

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.data.local.DatabaseTriggers
import com.roomrentmanager.domain.repository.AuditRepository
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.time.LocalDate

/**
 * Restores a backup made by [BackupManager].
 *
 * 1. [inspect] parses and validates the whole file without touching the database: format and schema
 *    version, exact table and column set, value types, NOT NULL columns, dates, status values,
 *    counts and the SHA-256 digest.
 * 2. [restore] first saves the current data as a safety copy, then replaces everything in ONE
 *    transaction: integrity triggers are lifted, all rows deleted and re-inserted, and the result is
 *    checked with the same rules the triggers enforce plus a foreign-key check. Any failure rolls the
 *    transaction back, so the existing data is untouched. Triggers are reinstalled before commit.
 * 3. [undoLastRestore] puts back the safety copy taken before the last successful restore.
 */
class RestoreManager(
    private val db: AppDatabase,
    private val clock: Clock,
    private val backup: BackupManager,
    private val audit: AuditRepository,
    private val safetyDir: File?,
) {
    private val undoFile get() = safetyDir?.let { File(it, "pre-restore-backup.json") }

    fun hasUndo(): Boolean = undoFile?.isFile == true

    suspend fun inspect(text: String): BackupContents {
        val w = db.openHelper.writableDatabase
        val schema = BackupFormat.TABLES.associateWith { columnInfo(w, it) }
        return parse(text, w.version, schema)
    }

    suspend fun restore(contents: BackupContents) {
        val safetyText = backup.createBackupJson(logEvent = false)
        db.withTransaction {
            val w = db.openHelper.writableDatabase
            if (contents.schemaVersion != w.version) throw InvalidBackupException("This backup is for a different database version.")
            w.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'trg_%'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.forEach { w.execSQL("DROP TRIGGER `$it`") }

            // RESTRICT foreign keys are checked row by row, so rows that point at another row of the
            // same table (reversals, contra-allocations) must go before the rows they point at.
            for (table in BackupFormat.TABLES.asReversed()) {
                SELF_REFERENCES[table]?.let { column -> w.execSQL("DELETE FROM `$table` WHERE `$column` IS NOT NULL") }
                w.execSQL("DELETE FROM `$table`")
            }
            for (table in BackupFormat.TABLES) {
                val t = contents.tables.getValue(table)
                val sql = "INSERT INTO `$table` (${t.columns.joinToString { "`$it`" }}) VALUES (${t.columns.joinToString { "?" }})"
                val idIndex = t.columns.indexOf("id")
                val statement = w.compileStatement(sql)
                // Ascending ids so self-references (reversals, contra-allocations) point backwards.
                for (row in t.rows.sortedBy { it[idIndex] as Long }) {
                    statement.clearBindings()
                    row.forEachIndexed { i, v ->
                        when (v) {
                            null -> statement.bindNull(i + 1)
                            is Long -> statement.bindLong(i + 1, v)
                            is Double -> statement.bindDouble(i + 1, v)
                            is String -> statement.bindString(i + 1, v)
                        }
                    }
                    statement.executeInsert()
                }
                statement.close()
            }
            verify(w, contents)
            DatabaseTriggers.create(w)
            audit.log(
                "DATA_RESTORED", "DATABASE", null,
                "Restored a backup made ${java.time.Instant.ofEpochMilli(contents.exportedAtMillis)} with ${contents.counts.values.sum()} records.",
            )
        }
        // Only after a successful restore does the safety copy become the undo point.
        undoFile?.let { file ->
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(safetyText)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    suspend fun undoLastRestore() {
        val file = undoFile?.takeIf { it.isFile } ?: throw InvalidBackupException("There is no earlier data to go back to.")
        restore(inspect(file.readText()))
    }

    private companion object {
        val SELF_REFERENCES = mapOf("rent_ledger" to "reversesEntryId", "payment_allocations" to "reversesAllocationId")
    }

    private data class Column(val name: String, val type: String, val notNull: Boolean)

    private fun columnInfo(w: SupportSQLiteDatabase, table: String): List<Column> =
        w.query("PRAGMA table_info(`$table`)").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Column(c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("type")).uppercase(),
                        c.getInt(c.getColumnIndexOrThrow("notnull")) == 1 || c.getInt(c.getColumnIndexOrThrow("pk")) > 0))
                }
            }
        }

    private fun parse(text: String, currentVersion: Int, schema: Map<String, List<Column>>): BackupContents {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw InvalidBackupException("This is not a Room Rent Manager backup file.", e)
        }
        fun bad(message: String): Nothing = throw InvalidBackupException(message)
        if (root.optString("format") != BackupFormat.FORMAT) bad("This is not a Room Rent Manager backup file.")
        if (root.optInt("formatVersion", -1) != BackupFormat.FORMAT_VERSION) bad("This backup uses an unsupported file format version.")
        val version = root.optInt("schemaVersion", -1)
        if (version > currentVersion) bad("This backup was made by a newer version of the app. Update the app first.")
        if (version != currentVersion) bad("This backup is from an older version of the app and cannot be restored.")
        val exportedAt = root.optLong("exportedAtMillis", -1).takeIf { it > 0 } ?: bad("The backup has no export time.")
        val tablesJson = root.optJSONObject("tables") ?: bad("The backup has no data.")
        val counts = root.optJSONObject("counts") ?: bad("The backup has no record counts.")

        val names = tablesJson.keys().asSequence().toSet()
        val missing = BackupFormat.TABLES - names
        val unknown = names - BackupFormat.TABLES.toSet()
        if (missing.isNotEmpty()) bad("The backup is incomplete: missing ${missing.joinToString()}.")
        if (unknown.isNotEmpty()) bad("The backup contains unknown data: ${unknown.joinToString()}.")

        val tables = BackupFormat.TABLES.associateWith { table ->
            val t = tablesJson.getJSONObject(table)
            val columns = t.optJSONArray("columns")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: bad("$table: no columns.")
            val expected = schema.getValue(table).associateBy { it.name }
            if (columns.toSet() != expected.keys || columns.size != expected.size) bad("$table: columns do not match this version of the app.")
            val rowsJson = t.optJSONArray("rows") ?: bad("$table: no rows.")
            val dateCols = BackupFormat.DATE_COLUMNS[table].orEmpty().map { columns.indexOf(it) }
            val rows = (0 until rowsJson.length()).map { r ->
                val a: JSONArray = rowsJson.optJSONArray(r) ?: bad("$table row ${r + 1}: not a row.")
                if (a.length() != columns.size) bad("$table row ${r + 1}: wrong number of values.")
                columns.mapIndexed { i, name ->
                    val col = expected.getValue(name)
                    val raw = a.get(i)
                    val v: Any? = when {
                        raw == JSONObject.NULL -> null
                        col.type == "INTEGER" && (raw is Int || raw is Long) -> (raw as Number).toLong()
                        col.type == "TEXT" && raw is String -> raw
                        col.type == "REAL" && raw is Number -> raw.toDouble()
                        else -> bad("$table row ${r + 1}: '$name' has the wrong type.")
                    }
                    if (v == null && col.notNull) bad("$table row ${r + 1}: '$name' is missing.")
                    val enumValues = BackupFormat.ENUM_COLUMNS[table to name]
                    if (enumValues != null && v != null && v !in enumValues) bad("$table row ${r + 1}: '$name' has an unknown value '$v'.")
                    if (i in dateCols && v != null && runCatching { LocalDate.parse(v as String) }.isFailure) {
                        bad("$table row ${r + 1}: '$name' is not a valid date.")
                    }
                    v
                }
            }
            val ids = rows.map { it[columns.indexOf("id")] }
            if (ids.toSet().size != ids.size) bad("$table: duplicate record ids.")
            if (counts.optInt(table, -1) != rows.size) bad("$table: record count does not match. The file may be truncated.")
            TableData(columns, rows)
        }
        if (root.optString("sha256") != BackupFormat.digest(tables)) bad("The backup file is damaged or was edited (checksum mismatch).")
        return BackupContents(version, exportedAt, root.optString("appVersion").takeIf { it.isNotEmpty() && it != "null" }, tables)
    }

    /** Same rules as the integrity triggers, checked over the whole restored data set. */
    private fun verify(w: SupportSQLiteDatabase, contents: BackupContents) {
        fun count(sql: String) = w.query(sql).use { it.moveToFirst(); it.getLong(0) }
        val problems = mutableListOf<String>()
        contents.counts.forEach { (table, n) -> if (count("SELECT COUNT(*) FROM `$table`") != n.toLong()) problems += "$table count" }
        w.query("PRAGMA foreign_key_check").use { if (it.count > 0) problems += "${it.count} broken links between records" }
        val checks = mapOf(
            "active tenancies" to """SELECT COUNT(*) FROM tenancies WHERE (status = 'ACTIVE') != (activeRoomId IS NOT NULL AND activeRoomId = roomId)
                OR (moveOutDate IS NOT NULL AND moveOutDate < moveInDate)""",
            "ledger signs" to """SELECT COUNT(*) FROM rent_ledger WHERE (entryType IN ('RENT_CHARGE','LATE_FEE') AND amountPaise <= 0)
                OR (entryType IN ('CREDIT','REVERSAL') AND amountPaise >= 0) OR (entryType = 'ADJUSTMENT' AND amountPaise = 0)
                OR (entryType = 'REVERSAL') != (reversesEntryId IS NOT NULL)""",
            "payment amounts" to "SELECT COUNT(*) FROM payments WHERE amountPaise <= 0",
            "payment allocations" to """SELECT COUNT(*) FROM (SELECT p.id FROM payments p JOIN payment_allocations a ON a.paymentId = p.id
                GROUP BY p.id HAVING SUM(a.amountPaise) > p.amountPaise OR SUM(a.amountPaise) < 0)""",
            "charge allocations" to """SELECT COUNT(*) FROM rent_ledger l WHERE
                (SELECT COALESCE(SUM(amountPaise), 0) FROM payment_allocations WHERE ledgerEntryId = l.id) < 0 OR
                (SELECT COALESCE(SUM(amountPaise), 0) FROM payment_allocations WHERE ledgerEntryId = l.id)
                  + (SELECT COALESCE(-SUM(amountPaise), 0) FROM rent_ledger r WHERE r.reversesEntryId = l.id) > MAX(l.amountPaise, 0)""",
            "allocation tenancies" to """SELECT COUNT(*) FROM payment_allocations a JOIN payments p ON p.id = a.paymentId
                JOIN rent_ledger l ON l.id = a.ledgerEntryId WHERE p.tenancyId IS NOT l.tenancyId""",
            "deposit amounts" to "SELECT COUNT(*) FROM deposit_transactions WHERE amountPaise = 0 OR (transactionType != 'ADJUSTMENT' AND amountPaise < 0)",
        )
        checks.forEach { (label, sql) -> if (count(sql) > 0) problems += label }
        if (problems.isNotEmpty()) {
            throw InvalidBackupException("The backup's data is inconsistent (${problems.joinToString()}). Nothing was changed.")
        }
    }
}
