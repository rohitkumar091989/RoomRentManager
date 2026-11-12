package com.roomrentmanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.roomrentmanager.data.local.migrations.Migration1To2

@Database(
    entities = [
        PropertyEntity::class,
        RoomEntity::class,
        TenantEntity::class,
        TenancyEntity::class,
        RentAgreementEntity::class,
        RentLedgerEntity::class,
        PaymentEntity::class,
        PaymentAllocationEntity::class,
        DepositTransactionEntity::class,
        ExpenseEntity::class,
        MaintenanceRequestEntity::class,
        AppNotificationEntity::class,
        AuditLogEntity::class,
        MigrationReviewEntity::class,
        MigrationReportEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun propertyDao(): PropertyDao
    abstract fun tenantDao(): TenantDao
    abstract fun tenancyDao(): TenancyDao
    abstract fun financeDao(): FinanceDao
    abstract fun depositDao(): DepositDao
    abstract fun operationsDao(): OperationsDao
    abstract fun auditDao(): AuditDao
    abstract fun migrationDao(): MigrationDao

    companion object {
        /** Same file name as v1 so existing installs are migrated in place. */
        const val NAME = "room_rent_manager.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: configure(Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME))
                    .build().also { instance = it }
            }

        /**
         * Shared by the app and tests. Deliberately no fallbackToDestructiveMigration(): a failed
         * migration must leave the user's data untouched rather than wipe it.
         */
        fun configure(builder: Builder<AppDatabase>): Builder<AppDatabase> =
            builder.addMigrations(Migration1To2).addCallback(TriggerCallback)

        private object TriggerCallback : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = DatabaseTriggers.create(db)
        }
    }
}
