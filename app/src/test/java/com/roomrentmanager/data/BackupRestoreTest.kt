package com.roomrentmanager.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.backup.BackupFormat
import com.roomrentmanager.data.backup.BackupManager
import com.roomrentmanager.data.backup.InvalidBackupException
import com.roomrentmanager.data.backup.TableData
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.MaintenanceStatus
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.usecase.AgreementInput
import com.roomrentmanager.domain.usecase.ExpenseInput
import com.roomrentmanager.domain.usecase.MaintenanceInput
import com.roomrentmanager.domain.usecase.PaymentInput
import com.roomrentmanager.domain.usecase.ReminderSettings
import com.roomrentmanager.domain.usecase.StartTenancyInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupRestoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = MutableClock(LocalDate.parse("2026-04-05"))
    private lateinit var sourceDb: AppDatabase
    private lateinit var source: AppContainer
    private lateinit var targetDb: AppDatabase
    private lateinit var target: AppContainer
    private lateinit var safetyDir: File

    private fun d(s: String) = LocalDate.parse(s)

    @Before
    fun setUp() = runTest {
        safetyDir = File(context.filesDir, "test-backups-${System.nanoTime()}")
        sourceDb = inMemoryDatabase()
        source = AppContainer(sourceDb, clock, safetyDir = File(safetyDir, "source"))
        targetDb = inMemoryDatabase()
        target = AppContainer(targetDb, clock, safetyDir = File(safetyDir, "target"))
        populate(source)
        // The target already has different data, which a restore must replace completely.
        val p = target.manageProperties.saveProperty(null, "Old Place", PropertyType.COMMERCIAL, null, null)
        target.manageProperties.saveRoom(null, p, "Old Shop", 1_00, null)
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        safetyDir.deleteRecursively()
    }

    /** Every kind of record, including reversals, credits, an ended tenancy and an imported v1 payment. */
    private suspend fun populate(app: AppContainer) {
        val villa = app.manageProperties.saveProperty(null, "Green Villa", PropertyType.RESIDENTIAL, "MG Road, Pune", "Gate code 1234")
        val r1 = app.manageProperties.saveRoom(null, villa, "Room 1", 10_000_00, null)
        val r2 = app.manageProperties.saveRoom(null, villa, "Room 2", 8_000_00, null)
        val asha = app.manageTenancy.saveTenant(null, "Asha, \"A\" Rao", "98765 43210", null, null, "=HYPERLINK(\"x\")")
        val bilal = app.manageTenancy.saveTenant(null, "Bilal", null, "b@example.com", null, null)
        val t1 = app.manageTenancy.startTenancy(StartTenancyInput(r1, asha, d("2026-01-10"), AgreementInput(d("2026-01-10"), 10_000_00, 10), 20_000_00))
        val t2 = app.manageTenancy.startTenancy(StartTenancyInput(r2, bilal, d("2026-01-31"), AgreementInput(d("2026-01-31"), 8_000_00, 31)))
        val p1 = app.recordPayment(PaymentInput(t1, 15_000_00, d("2026-02-12"), PaymentMethod.UPI, "UPI-1"))
        app.recordPayment(PaymentInput(t1, 2_000_00, d("2026-03-01"), PaymentMethod.CASH, allocation = AllocationMode.KeepAsCredit))
        app.reversePayment(p1, "Bounced")
        app.recordPayment(PaymentInput(t1, 15_000_00, d("2026-03-02"), PaymentMethod.BANK_TRANSFER))
        app.adjustments.addCharge(t1, LedgerEntryType.LATE_FEE, 500_00, d("2026-03-15"), "Late fee")
        app.adjustments.addCredit(t1, 250_00, "Goodwill")
        app.adjustments.cancelCharge(app.portfolio.observePortfolioFirst().ledger.first { it.tenancyId == t2 }.id, "Waived")
        app.manageTenancy.addAgreement(t1, AgreementInput(d("2026-04-10"), 11_000_00, 10))
        app.recordDeposit(t1, DepositTransactionType.DEDUCTION, 1_000_00, d("2026-04-01"), "Broken window")
        app.manageTenancy.endTenancy(t2, d("2026-04-05"))
        app.manageExpenses.save(null, ExpenseInput(villa, r1, "Repairs", 1_234_56, d("2026-03-20"), "Window, glass", "Ravi"))
        app.manageMaintenance.save(null, MaintenanceInput(villa, r2, "Fan", "Noisy", MaintenancePriority.URGENT, MaintenanceStatus.OPEN, d("2026-04-01"), null, null))
        app.runReminders(ReminderSettings())
        val w = sourceDbOf(app).openHelper.writableDatabase
        w.execSQL("INSERT INTO payments (id, tenancyId, amountPaise, paymentDate, paymentMethod, status, createdAt) VALUES (900, NULL, 70000, 'not a date', 'OTHER', 'POSTED', 0)")
        w.execSQL("INSERT INTO migration_review (entityType, entityId, reason, legacyData, status, createdAt) VALUES ('PAYMENT', 900, 'LEGACY_PAYMENT', '{\"month\":\"2025-12\"}', 'PENDING', 0)")
        w.execSQL("INSERT INTO migration_reports (fromVersion, toVersion, report, createdAt) VALUES (1, 2, 'Validation: PASSED', 0)")
    }

    private fun sourceDbOf(app: AppContainer) = if (app === source) sourceDb else targetDb

    private suspend fun com.roomrentmanager.domain.repository.PortfolioRepository.observePortfolioFirst() = observePortfolio().first()

    private fun tables(db: AppDatabase): Map<String, TableData> =
        BackupManager(db, clock, com.roomrentmanager.data.repository.AuditRepositoryImpl(db, clock), null).readTables(db.openHelper.writableDatabase)

    @Test
    fun roundTripReproducesEveryTableExactly() = runTest {
        val json = source.backup.createBackupJson(logEvent = false)
        target.restore.restore(target.restore.inspect(json))

        val expected = tables(sourceDb)
        val actual = tables(targetDb)
        for (name in BackupFormat.TABLES - "audit_log") assertEquals("table $name", expected.getValue(name), actual.getValue(name))
        val audit = actual.getValue("audit_log").rows
        assertEquals(expected.getValue("audit_log").rows, audit.dropLast(1)) // plus the restore's own entry
        assertTrue(audit.last().contains("DATA_RESTORED"))
        assertTrue(expected.values.sumOf { it.rows.size } > 40)
    }

    @Test
    fun restoredDatabaseKeepsIntegrityRules() = runTest {
        target.restore.restore(target.restore.inspect(source.backup.createBackupJson(logEvent = false)))
        val w = targetDb.openHelper.writableDatabase
        assertThrows(SQLiteConstraintException::class.java) { w.execSQL("DELETE FROM payments") }
        assertThrows(SQLiteConstraintException::class.java) { w.execSQL("UPDATE rent_ledger SET amountPaise = 1") }
        // And the app keeps working on the restored data.
        val t = target.portfolio.observePortfolio().first().tenancies.first { it.status.name == "ACTIVE" }.id
        target.recordPayment(PaymentInput(t, 100_00, d("2026-04-05"), PaymentMethod.CASH))
    }

    // --- Rejected files leave the data untouched -------------------------------------------------

    private suspend fun assertRejected(json: String, messagePart: String) {
        val before = tables(targetDb)
        try {
            target.restore.restore(target.restore.inspect(json))
            fail("Expected the backup to be rejected ($messagePart)")
        } catch (e: InvalidBackupException) {
            assertTrue("'${e.message}' should mention '$messagePart'", e.message!!.contains(messagePart))
        }
        assertEquals(before, tables(targetDb))
        assertFalse("a failed restore must not create an undo point", target.restore.hasUndo())
    }

    /** Re-computes the digest after a deliberate edit, so the deeper checks are what reject it. */
    private fun resign(root: JSONObject): String {
        val tablesJson = root.getJSONObject("tables")
        val tables = BackupFormat.TABLES.associateWith { name ->
            val t = tablesJson.getJSONObject(name)
            val cols = t.getJSONArray("columns").let { a -> (0 until a.length()).map { a.getString(it) } }
            val rows = t.getJSONArray("rows").let { rs ->
                (0 until rs.length()).map { r ->
                    val a = rs.getJSONArray(r)
                    (0 until a.length()).map { i ->
                        when (val v = a.get(i)) {
                            JSONObject.NULL -> null
                            is Int -> v.toLong()
                            is Long -> v
                            is String -> v
                            else -> v
                        }
                    }
                }
            }
            TableData(cols, rows)
        }
        root.put("sha256", BackupFormat.digest(tables))
        return root.toString()
    }

    private suspend fun backupJson() = JSONObject(source.backup.createBackupJson(logEvent = false))

    private fun JSONObject.rowsOf(table: String): JSONArray = getJSONObject("tables").getJSONObject(table).getJSONArray("rows")
    private fun JSONObject.col(table: String, column: String): Int =
        getJSONObject("tables").getJSONObject(table).getJSONArray("columns").let { a -> (0 until a.length()).first { a.getString(it) == column } }

    @Test
    fun rejectsFilesThatAreNotBackups() = runTest {
        assertRejected("hello", "not a Room Rent Manager backup")
        assertRejected("{\"format\":\"something-else\"}", "not a Room Rent Manager backup")
        assertRejected(backupJson().apply { put("formatVersion", 2) }.toString(), "file format version")
        assertRejected(backupJson().apply { put("schemaVersion", 3) }.toString(), "newer version")
        assertRejected(backupJson().apply { put("schemaVersion", 1) }.toString(), "older version")
    }

    @Test
    fun rejectsDamagedOrEditedFiles() = runTest {
        val edited = backupJson().apply { rowsOf("payments").getJSONArray(0).put(col("payments", "amountPaise"), 1) }
        assertRejected(edited.toString(), "checksum mismatch")
        val truncated = backupJson().apply { rowsOf("rent_ledger").remove(0) }
        assertRejected(truncated.toString(), "record count does not match")
        assertRejected(backupJson().apply { getJSONObject("tables").remove("payments") }.toString(), "missing payments")
        assertRejected(backupJson().apply { getJSONObject("tables").put("secrets", JSONObject()) }.toString(), "unknown data")
    }

    @Test
    fun rejectsInvalidValuesEvenWithAValidChecksum() = runTest {
        assertRejected(resign(backupJson().apply { rowsOf("tenants").getJSONArray(0).put(col("tenants", "name"), JSONObject.NULL) }), "'name' is missing")
        assertRejected(resign(backupJson().apply { rowsOf("payments").getJSONArray(0).put(col("payments", "status"), "REFUNDED") }), "unknown value")
        assertRejected(resign(backupJson().apply { rowsOf("tenancies").getJSONArray(0).put(col("tenancies", "moveInDate"), "10/01/2026") }), "not a valid date")
        assertRejected(resign(backupJson().apply { rowsOf("payments").getJSONArray(0).put(col("payments", "amountPaise"), "lots") }), "wrong type")
        val extraColumn = backupJson().apply { getJSONObject("tables").getJSONObject("tenants").getJSONArray("columns").put("ssn") }
        assertRejected(resign(extraColumn), "columns do not match")
    }

    @Test
    fun inconsistentDataIsRolledBack() = runTest {
        // Valid file, valid checksum, but a payment allocated beyond its amount. Only the full
        // check inside the restore transaction can catch this; the old data must survive.
        val json = backupJson().apply {
            val i = col("payment_allocations", "amountPaise")
            val row = rowsOf("payment_allocations").getJSONArray(0)
            row.put(i, row.getLong(i) * 100)
        }
        assertRejected(resign(json), "inconsistent")
        val ended = backupJson().apply { rowsOf("tenancies").getJSONArray(1).put(col("tenancies", "activeRoomId"), JSONObject.NULL).put(col("tenancies", "status"), "ACTIVE") }
        assertRejected(resign(ended), "active tenancies")
    }

    // --- Safety copy and undo --------------------------------------------------------------------

    @Test
    fun restoreCanBeUndone() = runTest {
        val before = tables(targetDb)
        target.restore.restore(target.restore.inspect(source.backup.createBackupJson(logEvent = false)))
        assertTrue(target.restore.hasUndo())
        assertNotEquals(before.getValue("properties"), tables(targetDb).getValue("properties"))

        target.restore.undoLastRestore()
        val after = tables(targetDb)
        for (name in BackupFormat.TABLES - "audit_log") assertEquals("table $name", before.getValue(name), after.getValue(name))
    }

    // --- CSV export ------------------------------------------------------------------------------

    @Test
    fun csvExportHasEveryTableWithRupeeColumnsAndSafeQuoting() = runTest {
        val bytes = ByteArrayOutputStream().also { source.backup.writeCsvExport(it) }.toByteArray()
        val files = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                files[e.name] = zip.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
            }
        }
        assertEquals(BackupFormat.TABLES.map { "$it.csv" }.toSet(), files.keys)
        val expenses = files.getValue("expenses.csv").lines()
        assertTrue(expenses[0], expenses[0].contains("amountPaise,amountRupees"))
        assertTrue(expenses[1], expenses[1].contains("123456,1234.56") && expenses[1].contains("\"Window, glass\""))
        val tenants = files.getValue("tenants.csv")
        assertTrue(tenants, tenants.contains("\"Asha, \"\"A\"\" Rao\""))
        assertTrue("formula neutralised: $tenants", tenants.contains("\"'=HYPERLINK(\"\"x\"\")\""))
        assertEquals("-50000", BackupManager.csv("-50000"))
    }
}
