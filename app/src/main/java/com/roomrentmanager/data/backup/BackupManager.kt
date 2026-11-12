package com.roomrentmanager.data.backup

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.repository.AuditRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes backups and CSV exports. Reading happens in one transaction, so the snapshot is consistent. */
class BackupManager(
    private val db: AppDatabase,
    private val clock: Clock,
    private val audit: AuditRepository,
    private val appVersion: String?,
) {
    /** Current schema columns of [table], in table order. */
    internal fun columnsOf(w: SupportSQLiteDatabase, table: String): List<String> =
        w.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    internal fun readTables(w: SupportSQLiteDatabase): Map<String, TableData> = BackupFormat.TABLES.associateWith { table ->
        val columns = columnsOf(w, table)
        val rows = w.query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table` ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) add(columns.indices.map { i -> value(c, i) })
            }
        }
        TableData(columns, rows)
    }

    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_STRING -> c.getString(i)
        else -> error("Binary data is not expected in column ${c.getColumnName(i)}")
    }

    suspend fun counts(): Map<String, Int> = db.withTransaction {
        val w = db.openHelper.writableDatabase
        BackupFormat.TABLES.associateWith { t -> w.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) } }
    }

    /** Serializes the whole database. Also used for the automatic safety copy before a restore. */
    suspend fun createBackupJson(logEvent: Boolean = true): String {
        val now = clock.millis()
        val tables = db.withTransaction { readTables(db.openHelper.writableDatabase) }
        val json = JSONObject().apply {
            put("format", BackupFormat.FORMAT)
            put("formatVersion", BackupFormat.FORMAT_VERSION)
            put("schemaVersion", db.openHelper.readableDatabase.version)
            put("exportedAt", Instant.ofEpochMilli(now).toString())
            put("exportedAtMillis", now)
            put("appVersion", appVersion ?: JSONObject.NULL)
            put("counts", JSONObject().apply { tables.forEach { (k, v) -> put(k, v.rows.size) } })
            put("sha256", BackupFormat.digest(tables))
            put("tables", JSONObject().apply {
                tables.forEach { (name, t) ->
                    put(name, JSONObject().apply {
                        put("columns", JSONArray(t.columns))
                        put("rows", JSONArray().apply { t.rows.forEach { row -> put(JSONArray().apply { row.forEach { put(it ?: JSONObject.NULL) } }) } })
                    })
                }
            })
        }
        if (logEvent) {
            audit.log("BACKUP_CREATED", "DATABASE", null, "Backup created with ${tables.values.sumOf { it.rows.size }} records.")
        }
        return json.toString()
    }

    suspend fun writeBackup(out: OutputStream) {
        val text = createBackupJson()
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Writes a ZIP with one CSV per table, for spreadsheets or an accountant. Paise columns get an
     * extra rupee column next to them. This is a readable export, not a backup: use the JSON
     * backup to restore.
     */
    suspend fun writeCsvExport(out: OutputStream) {
        val tables = db.withTransaction { readTables(db.openHelper.writableDatabase) }
        ZipOutputStream(out).use { zip ->
            for ((name, t) in tables) {
                zip.putNextEntry(ZipEntry("$name.csv"))
                val header = t.columns.flatMap { if (it.endsWith("Paise")) listOf(it, it.removeSuffix("Paise") + "Rupees") else listOf(it) }
                val text = buildString {
                    appendLine(header.joinToString(",") { csv(it) })
                    t.rows.forEach { row ->
                        val cells = t.columns.indices.flatMap { i ->
                            val v = row[i]
                            if (t.columns[i].endsWith("Paise")) listOf(v?.toString() ?: "", (v as? Long)?.let { Money.paiseToRupees(it).toPlainString() } ?: "")
                            else listOf(v?.toString() ?: "")
                        }
                        appendLine(cells.joinToString(",") { csv(it) })
                    }
                }
                // UTF-8 BOM so spreadsheet apps show the rupee sign and names correctly.
                zip.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        audit.log("DATA_EXPORTED", "DATABASE", null, "Exported ${tables.size} tables as CSV.")
    }

    companion object {
        /** RFC 4180 quoting. Text starting with =, + or @ is prefixed with an apostrophe so spreadsheets do not run it as a formula (a leading - is kept: negative amounts). */
        fun csv(value: String): String {
            val safe = if (value.isNotEmpty() && value[0] in "=+@") "'$value" else value
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
    }
}
