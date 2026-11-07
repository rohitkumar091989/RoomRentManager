package com.roomrentmanager.domain.repository

import com.roomrentmanager.domain.model.AuditEvent
import com.roomrentmanager.domain.model.DepositTransaction
import com.roomrentmanager.domain.model.LedgerEntry
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.PaymentAllocation
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.Property
import com.roomrentmanager.domain.model.RentAgreement
import com.roomrentmanager.domain.model.RentalUnit
import com.roomrentmanager.domain.model.ReviewItem
import com.roomrentmanager.domain.model.Tenancy
import com.roomrentmanager.domain.model.Tenant
import kotlinx.coroutines.flow.Flow

/*
 * Persistence contracts used by the use cases. Implementations map to and from Room entities.
 * Multi-row operations are wrapped in [TransactionRunner.inTransaction] by the use cases.
 * Inserted models ignore their `id` (use 0); the generated id is returned.
 */

interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

interface PropertyRepository {
    suspend fun getProperty(id: Long): Property?
    suspend fun insertProperty(property: Property): Long
    suspend fun updateProperty(property: Property)
    suspend fun getRoom(id: Long): RentalUnit?
    suspend fun countActiveRooms(propertyId: Long): Int
    suspend fun insertRoom(room: RentalUnit): Long
    suspend fun updateRoom(room: RentalUnit)
}

interface TenancyRepository {
    suspend fun getTenant(id: Long): Tenant?
    suspend fun insertTenant(tenant: Tenant): Long
    suspend fun updateTenant(tenant: Tenant)
    suspend fun getTenancy(id: Long): Tenancy?
    suspend fun allTenancies(): List<Tenancy>
    suspend fun activeTenancyForRoom(roomId: Long): Tenancy?
    suspend fun countActiveForTenant(tenantId: Long): Int
    suspend fun insertTenancy(tenancy: Tenancy): Long
    suspend fun updateTenancy(tenancy: Tenancy)
    suspend fun agreementsFor(tenancyId: Long): List<RentAgreement>
    suspend fun insertAgreement(agreement: RentAgreement): Long
    /** Sets the end date of an open agreement. Terms are never edited. */
    suspend fun closeAgreement(agreementId: Long, effectiveTo: java.time.LocalDate)
}

interface FinanceRepository {
    suspend fun ledgerFor(tenancyId: Long): List<LedgerEntry>
    suspend fun getLedgerEntry(id: Long): LedgerEntry?
    suspend fun sourceKeysFor(tenancyId: Long): Set<String>
    suspend fun insertLedgerEntry(entry: LedgerEntry): Long
    suspend fun getPayment(id: Long): Payment?
    suspend fun paymentsFor(tenancyId: Long): List<Payment>
    suspend fun insertPayment(payment: Payment): Long
    suspend fun markReversed(paymentId: Long, reason: String, at: Long)
    suspend fun linkPaymentToTenancy(paymentId: Long, tenancyId: Long)
    suspend fun allocationsForPayment(paymentId: Long): List<PaymentAllocation>
    suspend fun allocationsForTenancy(tenancyId: Long): List<PaymentAllocation>
    suspend fun insertAllocations(rows: List<PaymentAllocation>)
    suspend fun depositsFor(tenancyId: Long): List<DepositTransaction>
    suspend fun insertDeposit(row: DepositTransaction): Long
    suspend fun getReview(id: Long): ReviewItem?
    suspend fun updateReview(item: ReviewItem, resolvedAt: Long)
}

interface AuditRepository {
    suspend fun log(eventType: String, entityType: String, entityId: Long?, description: String)
    suspend fun forEntity(entityType: String, entityId: Long): List<AuditEvent>
}

/** Read side: everything the screens show, as one consistent, live snapshot. */
interface PortfolioRepository {
    fun observePortfolio(): Flow<Portfolio>
}
