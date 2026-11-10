package com.roomrentmanager.data.backup

import com.roomrentmanager.domain.model.BillingMode
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.MaintenanceStatus
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.PaymentStatus
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.ReminderStatus
import com.roomrentmanager.domain.model.ReviewEntityType
import com.roomrentmanager.domain.model.ReviewStatus
import com.roomrentmanager.domain.model.TenancyStatus
import java.security.MessageDigest

/**
 * Backup file format, version 1 (JSON):
 *
 * ```
 * {
 *   "format": "room-rent-manager-backup", "formatVersion": 1, "schemaVersion": 2,
 *   "exportedAt": "<ISO instant>", "exportedAtMillis": <long>, "appVersion": "<name>",
 *   "counts": { "<table>": <rows> },
 *   "sha256": "<hex digest of the canonical table data>",
 *   "tables": { "<table>": { "columns": [..], "rows": [[..], ..] } }
 * }
 * ```
 *
 * Every table is included with every column, so a restore reproduces the database exactly
 * (including IDs, history, audit log and review records). The digest is computed over a canonical
 * form of the values, not the JSON text, so it detects truncation or edits regardless of formatting.
 */
object BackupFormat {
    const val FORMAT = "room-rent-manager-backup"
    const val FORMAT_VERSION = 1

    /** Parents before children: the order rows are inserted in (and deleted in reverse). */
    val TABLES = listOf(
        "properties", "rooms", "tenants", "tenancies", "rent_agreements", "rent_ledger", "payments",
        "payment_allocations", "deposit_transactions", "expenses", "maintenance_requests",
        "app_notifications", "audit_log", "migration_review", "migration_reports",
    )

    /** Columns that must hold a valid ISO date (null allowed only where the column is nullable). */
    val DATE_COLUMNS: Map<String, List<String>> = mapOf(
        "tenancies" to listOf("moveInDate", "moveOutDate"),
        "rent_agreements" to listOf("effectiveFrom", "effectiveTo"),
        "rent_ledger" to listOf("periodStart", "periodEnd", "dueDate"),
        "deposit_transactions" to listOf("transactionDate"),
        "expenses" to listOf("expenseDate"),
        "maintenance_requests" to listOf("reportedDate", "completedDate"),
        "app_notifications" to listOf("dueDate"),
        // payments.paymentDate is deliberately not checked: imported v1 payments keep their original text.
    )

    /** Columns stored as enum names; any other value would crash the app when loaded. */
    val ENUM_COLUMNS: Map<Pair<String, String>, Set<String>> = mapOf(
        ("properties" to "propertyType") to PropertyType.entries.names(),
        ("tenancies" to "status") to TenancyStatus.entries.names(),
        ("rent_agreements" to "billingMode") to BillingMode.entries.names(),
        ("rent_ledger" to "entryType") to LedgerEntryType.entries.names(),
        ("payments" to "paymentMethod") to PaymentMethod.entries.names(),
        ("payments" to "status") to PaymentStatus.entries.names(),
        ("deposit_transactions" to "transactionType") to DepositTransactionType.entries.names(),
        ("maintenance_requests" to "priority") to MaintenancePriority.entries.names(),
        ("maintenance_requests" to "status") to MaintenanceStatus.entries.names(),
        ("app_notifications" to "status") to ReminderStatus.entries.names(),
        ("migration_review" to "entityType") to ReviewEntityType.entries.names(),
        ("migration_review" to "status") to ReviewStatus.entries.names(),
    )

    private fun <E : Enum<E>> List<E>.names() = map { it.name }.toSet()

    /** SHA-256 over a canonical, type-tagged form of every value, in table and column order. */
    fun digest(tables: Map<String, TableData>): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(s: String) = md.update(s.toByteArray(Charsets.UTF_8))
        for (name in TABLES) {
            val t = tables[name] ?: continue
            put("T:$name:${t.columns.joinToString(",")}\n")
            for (row in t.rows) {
                for (v in row) {
                    when (v) {
                        null -> put("n")
                        is Long -> put("i$v")
                        is Double -> put("r$v")
                        is String -> put("s${v.length}:$v")
                        else -> error("Unsupported value type ${v::class}")
                    }
                    put("\u001F")
                }
                put("\u001E")
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** One table's rows; values are Long, Double, String or null, in [columns] order. */
data class TableData(val columns: List<String>, val rows: List<List<Any?>>)

/** A fully validated backup, ready to restore. */
data class BackupContents(
    val schemaVersion: Int,
    val exportedAtMillis: Long,
    val appVersion: String?,
    val tables: Map<String, TableData>,
) {
    val counts: Map<String, Int> get() = BackupFormat.TABLES.associateWith { tables[it]?.rows?.size ?: 0 }
}

/** The backup cannot be restored. The message is shown to the user. */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
