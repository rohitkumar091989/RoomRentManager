package com.roomrentmanager

import android.content.Context
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.data.repository.AuditRepositoryImpl
import com.roomrentmanager.data.repository.FinanceRepositoryImpl
import com.roomrentmanager.data.repository.PortfolioRepositoryImpl
import com.roomrentmanager.data.repository.PropertyRepositoryImpl
import com.roomrentmanager.data.repository.RoomTransactionRunner
import com.roomrentmanager.data.repository.TenancyRepositoryImpl
import com.roomrentmanager.domain.usecase.AllocatePaymentUseCase
import com.roomrentmanager.domain.usecase.GenerateRentDueUseCase
import com.roomrentmanager.domain.usecase.ManagePropertiesUseCase
import com.roomrentmanager.domain.usecase.ManageTenancyUseCase
import com.roomrentmanager.domain.usecase.PostLedgerAdjustmentUseCase
import com.roomrentmanager.domain.usecase.RecordDepositUseCase
import com.roomrentmanager.domain.usecase.RecordPaymentUseCase
import com.roomrentmanager.domain.usecase.ResolveMigrationReviewUseCase
import com.roomrentmanager.domain.usecase.ReversePaymentUseCase
import java.time.Clock

/** Manual dependency wiring. Small enough that a DI framework is not worth adding. */
class AppContainer(db: AppDatabase, val clock: Clock) {
    private val tx = RoomTransactionRunner(db)
    private val properties = PropertyRepositoryImpl(db, clock)
    private val tenancies = TenancyRepositoryImpl(db, clock)
    private val finance = FinanceRepositoryImpl(db, clock)
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

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(AppDatabase.get(context), Clock.systemDefaultZone()).also { instance = it }
        }

        /** Tests install a container backed by an in-memory database and a fixed clock. */
        @androidx.annotation.VisibleForTesting
        fun setForTests(container: AppContainer?) {
            instance = container
        }
    }
}
