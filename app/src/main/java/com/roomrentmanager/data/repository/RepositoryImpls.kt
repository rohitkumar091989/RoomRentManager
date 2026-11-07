package com.roomrentmanager.data.repository

import androidx.room.withTransaction
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.data.local.AuditLogEntity
import com.roomrentmanager.domain.model.DepositTransaction
import com.roomrentmanager.domain.model.LedgerEntry
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.PaymentAllocation
import com.roomrentmanager.domain.model.PaymentStatus
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.Property
import com.roomrentmanager.domain.model.RentAgreement
import com.roomrentmanager.domain.model.RentalUnit
import com.roomrentmanager.domain.model.ReviewItem
import com.roomrentmanager.domain.model.Tenancy
import com.roomrentmanager.domain.model.Tenant
import com.roomrentmanager.domain.repository.AuditRepository
import com.roomrentmanager.domain.repository.FinanceRepository
import com.roomrentmanager.domain.repository.PortfolioRepository
import com.roomrentmanager.domain.repository.PropertyRepository
import com.roomrentmanager.domain.repository.TenancyRepository
import com.roomrentmanager.domain.repository.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.LocalDate

class RoomTransactionRunner(private val db: AppDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }
}

class PropertyRepositoryImpl(db: AppDatabase, private val clock: Clock) : PropertyRepository {
    private val dao = db.propertyDao()
    private fun now() = clock.millis()

    override suspend fun getProperty(id: Long) = dao.getProperty(id)?.toDomain()
    override suspend fun insertProperty(property: Property) = dao.insertProperty(property.copy(id = 0).toEntity(now(), now()))
    override suspend fun updateProperty(property: Property) {
        val existing = checkNotNull(dao.getProperty(property.id)) { "Property ${property.id} not found" }
        dao.updateProperty(property.toEntity(existing.createdAt, now()))
    }
    override suspend fun getRoom(id: Long) = dao.getRoom(id)?.toDomain()
    override suspend fun countActiveRooms(propertyId: Long) = dao.countActiveRooms(propertyId)
    override suspend fun insertRoom(room: RentalUnit) = dao.insertRoom(room.copy(id = 0).toEntity(now(), now()))
    override suspend fun updateRoom(room: RentalUnit) {
        val existing = checkNotNull(dao.getRoom(room.id)) { "Room ${room.id} not found" }
        dao.updateRoom(room.toEntity(existing.createdAt, now()))
    }
}

class TenancyRepositoryImpl(db: AppDatabase, private val clock: Clock) : TenancyRepository {
    private val tenants = db.tenantDao()
    private val dao = db.tenancyDao()
    private fun now() = clock.millis()

    override suspend fun getTenant(id: Long) = tenants.getTenant(id)?.toDomain()
    override suspend fun insertTenant(tenant: Tenant) = tenants.insertTenant(tenant.copy(id = 0).toEntity(now(), now()))
    override suspend fun updateTenant(tenant: Tenant) {
        val existing = checkNotNull(tenants.getTenant(tenant.id)) { "Tenant ${tenant.id} not found" }
        tenants.updateTenant(tenant.toEntity(existing.createdAt, now()))
    }
    override suspend fun getTenancy(id: Long) = dao.getTenancy(id)?.toDomain()
    override suspend fun allTenancies() = dao.allTenancies().map { it.toDomain() }
    override suspend fun activeTenancyForRoom(roomId: Long) = dao.getActiveTenancy(roomId)?.toDomain()
    override suspend fun countActiveForTenant(tenantId: Long) = dao.countActiveForTenant(tenantId)
    override suspend fun insertTenancy(tenancy: Tenancy) = dao.insertTenancy(tenancy.copy(id = 0).toEntity(now(), now()))
    override suspend fun updateTenancy(tenancy: Tenancy) {
        val existing = checkNotNull(dao.getTenancy(tenancy.id)) { "Tenancy ${tenancy.id} not found" }
        dao.updateTenancy(tenancy.toEntity(existing.createdAt, now()))
    }
    override suspend fun agreementsFor(tenancyId: Long) = dao.agreementsFor(tenancyId).map { it.toDomain() }
    override suspend fun insertAgreement(agreement: RentAgreement) = dao.insertAgreement(agreement.copy(id = 0).toEntity(now()))
    override suspend fun closeAgreement(agreementId: Long, effectiveTo: LocalDate) {
        check(dao.closeAgreement(agreementId, effectiveTo.toString()) == 1) { "Agreement $agreementId is not open" }
    }
}

class FinanceRepositoryImpl(db: AppDatabase, private val clock: Clock) : FinanceRepository {
    private val dao = db.financeDao()
    private val deposits = db.depositDao()
    private val migration = db.migrationDao()
    private fun now() = clock.millis()

    override suspend fun ledgerFor(tenancyId: Long) = dao.ledgerFor(tenancyId).map { it.toDomain() }
    override suspend fun getLedgerEntry(id: Long) = dao.getLedgerEntry(id)?.toDomain()
    override suspend fun sourceKeysFor(tenancyId: Long) = dao.sourceKeysFor(tenancyId).toSet()
    override suspend fun insertLedgerEntry(entry: LedgerEntry) = dao.insertLedgerEntry(entry.copy(id = 0).toEntity(now()))
    override suspend fun getPayment(id: Long) = dao.getPayment(id)?.toDomain()
    override suspend fun paymentsFor(tenancyId: Long) = dao.paymentsFor(tenancyId).map { it.toDomain() }
    override suspend fun insertPayment(payment: Payment) = dao.insertPayment(payment.copy(id = 0).toEntity(now()))
    override suspend fun markReversed(paymentId: Long, reason: String, at: Long) {
        val p = checkNotNull(dao.getPayment(paymentId)) { "Payment $paymentId not found" }
        dao.updatePayment(p.copy(status = PaymentStatus.REVERSED, reversalReason = reason, reversedAt = at))
    }
    override suspend fun linkPaymentToTenancy(paymentId: Long, tenancyId: Long) {
        val p = checkNotNull(dao.getPayment(paymentId)) { "Payment $paymentId not found" }
        check(p.tenancyId == null) { "Payment $paymentId is already linked" }
        dao.updatePayment(p.copy(tenancyId = tenancyId))
    }
    override suspend fun allocationsForPayment(paymentId: Long) = dao.allocationsForPayment(paymentId).map { it.toDomain() }
    override suspend fun allocationsForTenancy(tenancyId: Long) = dao.allocationsForTenancy(tenancyId).map { it.toDomain() }
    override suspend fun insertAllocations(rows: List<PaymentAllocation>) =
        dao.insertAllocations(rows.map { it.copy(id = 0).toEntity(now()) })
    override suspend fun depositsFor(tenancyId: Long) = deposits.depositsFor(tenancyId).map { it.toDomain() }
    override suspend fun insertDeposit(row: DepositTransaction) = deposits.insertDeposit(row.copy(id = 0).toEntity(now()))
    override suspend fun getReview(id: Long) = migration.getReview(id)?.toDomain()
    override suspend fun updateReview(item: ReviewItem, resolvedAt: Long) {
        val existing = checkNotNull(migration.getReview(item.id)) { "Review item ${item.id} not found" }
        migration.updateReview(existing.copy(status = item.status, suggestedTenancyId = item.suggestedTenancyId, resolvedAt = resolvedAt))
    }
}

class AuditRepositoryImpl(db: AppDatabase, private val clock: Clock) : AuditRepository {
    private val dao = db.auditDao()
    override suspend fun log(eventType: String, entityType: String, entityId: Long?, description: String) {
        dao.insert(AuditLogEntity(0, eventType, entityType, entityId, description, clock.millis()))
    }
    override suspend fun forEntity(entityType: String, entityId: Long) = dao.forEntity(entityType, entityId).map { it.toDomain() }
}

class PortfolioRepositoryImpl(private val db: AppDatabase) : PortfolioRepository {
    @Suppress("UNCHECKED_CAST")
    override fun observePortfolio(): Flow<Portfolio> {
        val flows: List<Flow<List<Any>>> = listOf(
            db.propertyDao().observeProperties(),
            db.propertyDao().observeRooms(),
            db.tenantDao().observeTenants(),
            db.tenancyDao().observeTenancies(),
            db.tenancyDao().observeAgreements(),
            db.financeDao().observeLedger(),
            db.financeDao().observePayments(),
            db.financeDao().observeAllocations(),
            db.depositDao().observeDeposits(),
            db.migrationDao().observeReview(),
        )
        return combine(flows) { a ->
            Portfolio(
                properties = (a[0] as List<com.roomrentmanager.data.local.PropertyEntity>).map { it.toDomain() },
                rooms = (a[1] as List<com.roomrentmanager.data.local.RoomEntity>).map { it.toDomain() },
                tenants = (a[2] as List<com.roomrentmanager.data.local.TenantEntity>).map { it.toDomain() },
                tenancies = (a[3] as List<com.roomrentmanager.data.local.TenancyEntity>).map { it.toDomain() },
                agreements = (a[4] as List<com.roomrentmanager.data.local.RentAgreementEntity>).map { it.toDomain() },
                ledger = (a[5] as List<com.roomrentmanager.data.local.RentLedgerEntity>).map { it.toDomain() },
                payments = (a[6] as List<com.roomrentmanager.data.local.PaymentEntity>).map { it.toDomain() },
                allocations = (a[7] as List<com.roomrentmanager.data.local.PaymentAllocationEntity>).map { it.toDomain() },
                deposits = (a[8] as List<com.roomrentmanager.data.local.DepositTransactionEntity>).map { it.toDomain() },
                reviews = (a[9] as List<com.roomrentmanager.data.local.MigrationReviewEntity>).map { it.toDomain() },
            )
        }
    }
}
