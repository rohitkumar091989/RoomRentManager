package com.roomrentmanager.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.data.local.migrations.Migration1To2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Migration1To2Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    /** A v1 database built from the exported v1 schema, with the awkward cases v1 allowed. */
    private fun createV1Fixture() {
        helper.createDatabase(dbName, 1).apply {
            // Rooms: single-occupancy, shared, vacant, and a rent that needs rounding.
            execSQL("INSERT INTO rooms (id, name, monthlyRent) VALUES (10, 'Room A', 6000.0)")
            execSQL("INSERT INTO rooms (id, name, monthlyRent) VALUES (11, 'Room B (shared)', 8000.5)")
            execSQL("INSERT INTO rooms (id, name, monthlyRent) VALUES (12, 'Room C (vacant)', 0.285)")
            execSQL("INSERT INTO rooms (id, name, monthlyRent) VALUES (13, 'Shop 1', 15000.0)")
            // Tenants: valid single, two sharing B, one with no room, one with an invalid date.
            execSQL("INSERT INTO tenants (id, name, phone, roomId, moveInDate) VALUES (20, 'Asha', '98765', 10, '2026-03-15')")
            execSQL("INSERT INTO tenants (id, name, phone, roomId, moveInDate) VALUES (21, 'Bilal', '', 11, '2026-01-01')")
            execSQL("INSERT INTO tenants (id, name, phone, roomId, moveInDate) VALUES (22, 'Chen', '', 11, '2026-02-01')")
            execSQL("INSERT INTO tenants (id, name, phone, roomId, moveInDate) VALUES (23, 'Devi', '', NULL, '2026-01-01')")
            execSQL("INSERT INTO tenants (id, name, phone, roomId, moveInDate) VALUES (24, 'Eshan', '', 13, '15/03/2026')")
            // Payments: matched, before move-in, shared-room, roomless tenant, rounding, bad month.
            execSQL("INSERT INTO payments (id, tenantId, month, amount, paidOn, note) VALUES (30, 20, '2026-03', 6000.0, '2026-03-16', 'March')")
            execSQL("INSERT INTO payments (id, tenantId, month, amount, paidOn, note) VALUES (31, 20, '2026-04', 3000.5, '2026-04-02', '')")
            execSQL("INSERT INTO payments (id, tenantId, month, amount, paidOn, note) VALUES (32, 20, '2026-02', 100.0, '2026-02-20', 'before move-in')")
            execSQL("INSERT INTO payments (id, tenantId, month, amount, paidOn, note) VALUES (33, 21, '2026-01', 4000.25, '2026-01-05', '')")
            execSQL("INSERT INTO payments (id, tenantId, month, amount, paidOn, note) VALUES (34, 23, '2026-01', 0.285, '2026-01-09', '')")
            execSQL("INSERT INTO payments (id, tenantId, month, amount, paidOn, note) VALUES (35, 24, 'March', 15000.0, '2026-03-20', '')")
            close()
        }
    }

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun SupportSQLiteDatabase.string(sql: String): String? = query(sql).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }

    @Test
    fun migratesRealV1DatabaseAndMatchesV2Schema() {
        createV1Fixture()
        // validateDroppedTables = true: fails if the staging tables were left behind.
        val db = helper.runMigrationsAndValidate(dbName, 2, true, Migration1To2)

        // IDs preserved; rooms under one imported property; rents converted to paise.
        assertEquals(1L, db.long("SELECT COUNT(*) FROM properties"))
        assertEquals(Migration1To2.IMPORTED_PROPERTY_NAME, db.string("SELECT name FROM properties"))
        assertEquals(4L, db.long("SELECT COUNT(*) FROM rooms"))
        assertEquals(600000L, db.long("SELECT defaultRentPaise FROM rooms WHERE id = 10"))
        assertEquals(800050L, db.long("SELECT defaultRentPaise FROM rooms WHERE id = 11"))
        assertEquals(29L, db.long("SELECT defaultRentPaise FROM rooms WHERE id = 12"))
        assertEquals(5L, db.long("SELECT COUNT(*) FROM tenants"))
        assertEquals("98765", db.string("SELECT phone FROM tenants WHERE id = 20"))
        assertNull(db.string("SELECT phone FROM tenants WHERE id = 21"))

        // Only Asha gets a tenancy: Bilal/Chen share a room, Devi has none, Eshan's date is invalid.
        assertEquals(1L, db.long("SELECT COUNT(*) FROM tenancies"))
        assertEquals(20L, db.long("SELECT tenantId FROM tenancies"))
        assertEquals("ACTIVE", db.string("SELECT status FROM tenancies"))
        assertEquals(10L, db.long("SELECT activeRoomId FROM tenancies"))
        assertEquals("2026-03-15", db.string("SELECT moveInDate FROM tenancies"))
        assertEquals(3L, db.long("SELECT COUNT(*) FROM migration_review WHERE entityType = 'TENANT'"))
        assertTrue(db.string("SELECT reason FROM migration_review WHERE entityType = 'TENANT' AND entityId = 21")!!.startsWith("ROOM_SHARED"))
        assertTrue(db.string("SELECT reason FROM migration_review WHERE entityType = 'TENANT' AND entityId = 24")!!.startsWith("INVALID_MOVE_IN"))

        // Nothing invented.
        assertEquals(0L, db.long("SELECT COUNT(*) FROM rent_agreements"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM rent_ledger"))

        // Every payment preserved, unlinked, with a pending review and its original details.
        assertEquals(6L, db.long("SELECT COUNT(*) FROM payments"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM payments WHERE tenancyId IS NOT NULL"))
        assertEquals(600000L + 300050 + 10000 + 400025 + 29 + 1500000, db.long("SELECT SUM(amountPaise) FROM payments"))
        assertEquals(6L, db.long("SELECT COUNT(*) FROM migration_review WHERE entityType = 'PAYMENT' AND status = 'PENDING'"))
        val tenancyId = db.long("SELECT id FROM tenancies")
        assertEquals(tenancyId, db.long("SELECT suggestedTenancyId FROM migration_review WHERE entityType = 'PAYMENT' AND entityId = 30"))
        assertNull(db.string("SELECT suggestedTenancyId FROM migration_review WHERE entityType = 'PAYMENT' AND entityId = 32"))
        assertNull(db.string("SELECT suggestedTenancyId FROM migration_review WHERE entityType = 'PAYMENT' AND entityId = 33"))
        val legacy = JSONObject(db.string("SELECT legacyData FROM migration_review WHERE entityType = 'PAYMENT' AND entityId = 35")!!)
        assertEquals("March", legacy.getString("month"))
        assertEquals("Eshan", legacy.getString("tenantName"))
        assertEquals("March", db.string("SELECT note FROM payments WHERE id = 30"))
        assertNull(db.string("SELECT note FROM payments WHERE id = 31"))

        // Report written, validation passed, integrity intact.
        assertTrue(db.string("SELECT report FROM migration_reports")!!.contains("Validation: PASSED"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM audit_log WHERE eventType = 'DATABASE_MIGRATED'"))
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        assertEquals(0L, db.long("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'legacy_v1_%'"))
    }

    @Test
    fun migrationInstallsIntegrityTriggers() {
        createV1Fixture()
        val db = helper.runMigrationsAndValidate(dbName, 2, true, Migration1To2)
        assertThrows(SQLiteConstraintException::class.java) { db.execSQL("DELETE FROM payments WHERE id = 30") }
        assertThrows(SQLiteConstraintException::class.java) { db.execSQL("UPDATE payments SET amountPaise = 1 WHERE id = 30") }
        // A second active tenancy for room 10 is rejected by the unique activeRoomId index.
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO tenancies (roomId, tenantId, moveInDate, status, activeRoomId, createdAt, updatedAt) VALUES (10, 23, '2026-05-01', 'ACTIVE', 10, 0, 0)")
        }
    }

    @Test
    fun emptyV1DatabaseMigratesWithoutInventingAProperty() {
        helper.createDatabase(dbName, 1).close()
        val db = helper.runMigrationsAndValidate(dbName, 2, true, Migration1To2)
        assertEquals(0L, db.long("SELECT COUNT(*) FROM properties"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM payments"))
        assertTrue(db.string("SELECT report FROM migration_reports")!!.contains("Validation: PASSED"))
    }

    @Test
    fun failedMigrationRollsBackAndLeavesV1DataIntact() {
        // Simulate an unexpected database state: a foreign `properties` table already exists, so
        // the migration's CREATE IF NOT EXISTS is skipped and copying into it fails mid-way.
        helper.createDatabase(dbName, 1).close()
        val path = ApplicationProvider.getApplicationContext<android.content.Context>().getDatabasePath(dbName).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TABLE properties (unexpected TEXT)")
        }
        createV1FixtureRows(path)

        assertThrows(Exception::class.java) { helper.runMigrationsAndValidate(dbName, 2, true, Migration1To2) }

        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, db.version)
            fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
            assertEquals(4L, count("rooms"))
            assertEquals(5L, count("tenants"))
            assertEquals(6L, count("payments"))
            db.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'legacy_v1_%' OR name = 'tenancies'", null)
                .use { assertEquals(0, it.count) }
        }
    }

    /** Re-inserts the fixture rows into a v1 file that was recreated empty. */
    private fun createV1FixtureRows(path: String) {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO rooms (id, name, monthlyRent) VALUES (10, 'Room A', 6000.0), (11, 'B', 8000.5), (12, 'C', 0.285), (13, 'Shop', 15000.0)")
            db.execSQL(
                "INSERT INTO tenants (id, name, phone, roomId, moveInDate) VALUES " +
                    "(20, 'Asha', '', 10, '2026-03-15'), (21, 'Bilal', '', 11, '2026-01-01'), (22, 'Chen', '', 11, '2026-02-01'), " +
                    "(23, 'Devi', '', NULL, '2026-01-01'), (24, 'Eshan', '', 13, 'x')",
            )
            db.execSQL(
                "INSERT INTO payments (id, tenantId, month, amount, paidOn, note) VALUES " +
                    "(30, 20, '2026-03', 6000.0, '2026-03-16', ''), (31, 20, '2026-04', 1.0, '2026-04-02', ''), " +
                    "(32, 20, '2026-02', 1.0, '2026-02-20', ''), (33, 21, '2026-01', 1.0, '2026-01-05', ''), " +
                    "(34, 23, '2026-01', 1.0, '2026-01-09', ''), (35, 24, '2026-03', 1.0, '2026-03-20', '')",
            )
        }
    }

    @Test
    fun migratedDatabaseOpensWithRoomAndDaos() = runTest {
        createV1Fixture()
        helper.runMigrationsAndValidate(dbName, 2, true, Migration1To2).close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val room = AppDatabase.configure(Room.databaseBuilder(context, AppDatabase::class.java, dbName))
            .allowMainThreadQueries().build()
        try {
            assertEquals(5, room.tenantDao().observeTenants().first().size)
            assertEquals(6, room.financeDao().observePayments().first().size)
            assertEquals(9, room.migrationDao().observeReview().first().size)
            helper.closeWhenFinished(room)
        } finally {
            room.close()
        }
    }
}
