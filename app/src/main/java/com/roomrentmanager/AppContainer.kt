package com.roomrentmanager

import android.content.Context
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.data.repository.AuditRepositoryImpl
import com.roomrentmanager.data.repository.FinanceRepositoryImpl
import com.roomrentmanager.data.repository.OperationsRepositoryImpl
import com.roomrentmanager.data.repository.PortfolioRepositoryImpl
import com.roomrentmanager.data.repository.PropertyRepositoryImpl
import com.roomrentmanager.data.repository.RoomTransactionRunner
import com.roomrentmanager.data.repository.TenancyRepositoryImpl
import com.roomrentmanager.data.settings.InMemorySettingsRepository
import com.roomrentmanager.data.settings.SharedPrefsSettingsRepository
import com.roomrentmanager.domain.repository.SettingsRepository
import com.roomrentmanager.domain.usecase.AllocatePaymentUseCase
import com.roomrentmanager.domain.usecase.GenerateRentDueUseCase
import com.roomrentmanager.domain.usecase.ManageExpensesUseCase
import com.roomrentmanager.domain.usecase.ManageMaintenanceUseCase
import com.roomrentmanager.domain.usecase.ManagePropertiesUseCase
import com.roomrentmanager.domain.usecase.ManageTenancyUseCase
import com.roomrentmanager.domain.usecase.PostLedgerAdjustmentUseCase
import com.roomrentmanager.domain.usecase.RecordDepositUseCase
import com.roomrentmanager.domain.usecase.RecordPaymentUseCase
import com.roomrentmanager.domain.usecase.ResolveMigrationReviewUseCase
import com.roomrentmanager.domain.usecase.ReversePaymentUseCase
import com.roomrentmanager.domain.usecase.RunRemindersUseCase
import com.roomrentmanager.worker.ReminderScheduler
import com.roomrentmanager.data.backup.BackupManager
import com.roomrentmanager.data.backup.RestoreManager
import java.io.File
import java.time.Clock

/**
 * Manual dependency wiring. Small enough that a DI framework is not worth adding.
 *
 * @param requestReminderCheck asks the reminder worker to run soon (after data changes). A no-op
 *   in tests, which drive [runReminders] directly.
 */
class AppContainer(
    db: AppDatabase,
    val clock: Clock,
    val settings: SettingsRepository = InMemorySettingsRepository(),
    val requestReminderCheck: () -> Unit = {},
    /** Where the safety copy taken before a restore is kept (app-private storage). */
    safetyDir: File? = null,
    appVersion: String? = null,
) {
    private val tx = RoomTransactionRunner(db)
    private val properties = PropertyRepositoryImpl(db, clock)
    private val tenancies = TenancyRepositoryImpl(db, clock)
    private val finance = FinanceRepositoryImpl(db, clock)
    private val operations = OperationsRepositoryImpl(db, clock)
    private val audit = AuditRepositoryImpl(db, clock)

    val portfolio = PortfolioRepositoryImpl(db)
    val generateRent = GenerateRentDueUseCase(tx, tenancies, finance)
    val manageProperties = ManagePropertiesUseCase(tx, properties, tenancies, audit)
    val manageTenancy = ManageTenancyUseCase(tx, properties, tenancies, finance, audit, generateRent, clock)
    val allocatePayment = AllocatePaymentUseCase(tx, finance, audit)
    val recordPayment = RecordPaymentUseCase(tx, tenancies, finance, allocatePayment, audit, clock)
    val reversePayment = ReversePaymentUseCase(tx, finance, audit, clock)
    val recordDeposit = RecordDepositUseCase(tx, tenancies, finance, audit, clock)
    val adjustments = PostLedgerAdjustmentUseCase(tx, tenancies, finance, audit)
    val resolveReview = ResolveMigrationReviewUseCase(tx, tenancies, finance, audit, clock)
    val manageExpenses = ManageExpensesUseCase(tx, properties, operations, audit, clock)
    val manageMaintenance = ManageMaintenanceUseCase(tx, properties, tenancies, operations, audit, clock)
    val runReminders = RunRemindersUseCase(tx, portfolio, operations, clock)
    val backup = BackupManager(db, clock, audit, appVersion)
    val restore = RestoreManager(db, clock, backup, audit, safetyDir)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                AppContainer(
                    AppDatabase.get(app),
                    Clock.systemDefaultZone(),
                    SharedPrefsSettingsRepository(app),
                    requestReminderCheck = {
                        // Best effort: a scheduling problem must never fail the save that triggered it.
                        runCatching { ReminderScheduler.runSoon(app) }
                    },
                    safetyDir = File(app.filesDir, "backups"),
                    appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull(),
                )
            }.also { instance = it }
        }

        /** Tests install a container backed by an in-memory database and a fixed clock. */
        @androidx.annotation.VisibleForTesting
        fun setForTests(container: AppContainer?) {
            instance = container
        }
    }
}
