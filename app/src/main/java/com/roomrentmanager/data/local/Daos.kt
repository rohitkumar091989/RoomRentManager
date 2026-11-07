package com.roomrentmanager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/*
 * Persistence queries only. Business rules live in domain use cases; multi-row writes are wrapped
 * in transactions by the repositories.
 */

@Dao
interface PropertyDao {
    @Query("SELECT * FROM properties ORDER BY name COLLATE NOCASE")
    fun observeProperties(): Flow<List<PropertyEntity>>

    @Query("SELECT * FROM properties WHERE id = :id")
    suspend fun getProperty(id: Long): PropertyEntity?

    @Insert
    suspend fun insertProperty(property: PropertyEntity): Long

    @Update
    suspend fun updateProperty(property: PropertyEntity)

    @Query("SELECT * FROM rooms ORDER BY name COLLATE NOCASE")
    fun observeRooms(): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE id = :id")
    suspend fun getRoom(id: Long): RoomEntity?

    @Query("SELECT COUNT(*) FROM rooms WHERE propertyId = :propertyId AND isActive = 1")
    suspend fun countActiveRooms(propertyId: Long): Int

    @Insert
    suspend fun insertRoom(room: RoomEntity): Long

    @Update
    suspend fun updateRoom(room: RoomEntity)
}

@Dao
interface TenantDao {
    @Query("SELECT * FROM tenants ORDER BY name COLLATE NOCASE")
    fun observeTenants(): Flow<List<TenantEntity>>

    @Query("SELECT * FROM tenants WHERE id = :id")
    suspend fun getTenant(id: Long): TenantEntity?

    @Insert
    suspend fun insertTenant(tenant: TenantEntity): Long

    @Update
    suspend fun updateTenant(tenant: TenantEntity)
}

@Dao
interface TenancyDao {
    @Query("SELECT * FROM tenancies ORDER BY moveInDate DESC, id DESC")
    fun observeTenancies(): Flow<List<TenancyEntity>>

    @Query("SELECT * FROM tenancies WHERE id = :id")
    suspend fun getTenancy(id: Long): TenancyEntity?

    @Query("SELECT * FROM tenancies")
    suspend fun allTenancies(): List<TenancyEntity>

    @Query("SELECT * FROM tenancies WHERE roomId = :roomId AND status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveTenancy(roomId: Long): TenancyEntity?

    @Query("SELECT COUNT(*) FROM tenancies WHERE tenantId = :tenantId AND status = 'ACTIVE'")
    suspend fun countActiveForTenant(tenantId: Long): Int

    @Query("SELECT * FROM tenancies WHERE tenantId = :tenantId ORDER BY moveInDate DESC")
    fun observeTenantHistory(tenantId: Long): Flow<List<TenancyEntity>>

    @Insert
    suspend fun insertTenancy(tenancy: TenancyEntity): Long

    @Update
    suspend fun updateTenancy(tenancy: TenancyEntity)

    @Query("SELECT * FROM rent_agreements ORDER BY tenancyId, effectiveFrom")
    fun observeAgreements(): Flow<List<RentAgreementEntity>>

    @Query("SELECT * FROM rent_agreements WHERE tenancyId = :tenancyId ORDER BY effectiveFrom")
    suspend fun agreementsFor(tenancyId: Long): List<RentAgreementEntity>

    @Insert
    suspend fun insertAgreement(agreement: RentAgreementEntity): Long

    /** Agreements are only ever closed (effectiveTo set); their terms are never edited. */
    @Query("UPDATE rent_agreements SET effectiveTo = :effectiveTo WHERE id = :id AND effectiveTo IS NULL")
    suspend fun closeAgreement(id: Long, effectiveTo: String): Int
}

@Dao
interface FinanceDao {
    @Query("SELECT * FROM rent_ledger ORDER BY dueDate, createdAt, id")
    fun observeLedger(): Flow<List<RentLedgerEntity>>

    @Query("SELECT * FROM rent_ledger WHERE tenancyId = :tenancyId ORDER BY dueDate, createdAt, id")
    suspend fun ledgerFor(tenancyId: Long): List<RentLedgerEntity>

    @Query("SELECT * FROM rent_ledger WHERE id = :id")
    suspend fun getLedgerEntry(id: Long): RentLedgerEntity?

    @Query("SELECT sourceKey FROM rent_ledger WHERE tenancyId = :tenancyId AND sourceKey IS NOT NULL")
    suspend fun sourceKeysFor(tenancyId: Long): List<String>

    @Insert
    suspend fun insertLedgerEntry(entry: RentLedgerEntity): Long

    @Query("SELECT * FROM payments ORDER BY paymentDate DESC, id DESC")
    fun observePayments(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun getPayment(id: Long): PaymentEntity?

    @Query("SELECT * FROM payments WHERE tenancyId = :tenancyId")
    suspend fun paymentsFor(tenancyId: Long): List<PaymentEntity>

    @Insert
    suspend fun insertPayment(payment: PaymentEntity): Long

    @Update
    suspend fun updatePayment(payment: PaymentEntity)

    @Query("SELECT * FROM payment_allocations")
    fun observeAllocations(): Flow<List<PaymentAllocationEntity>>

    @Query("SELECT * FROM payment_allocations WHERE paymentId = :paymentId")
    suspend fun allocationsForPayment(paymentId: Long): List<PaymentAllocationEntity>

    @Query(
        """
        SELECT a.* FROM payment_allocations a JOIN rent_ledger l ON l.id = a.ledgerEntryId
        WHERE l.tenancyId = :tenancyId
        """
    )
    suspend fun allocationsForTenancy(tenancyId: Long): List<PaymentAllocationEntity>

    @Insert
    suspend fun insertAllocations(rows: List<PaymentAllocationEntity>)
}

@Dao
interface DepositDao {
    @Query("SELECT * FROM deposit_transactions ORDER BY transactionDate, id")
    fun observeDeposits(): Flow<List<DepositTransactionEntity>>

    @Query("SELECT * FROM deposit_transactions WHERE tenancyId = :tenancyId ORDER BY transactionDate, id")
    suspend fun depositsFor(tenancyId: Long): List<DepositTransactionEntity>

    @Insert
    suspend fun insertDeposit(row: DepositTransactionEntity): Long
}

/** Expenses, maintenance and reminders. Screens for these arrive in milestone 5. */
@Dao
interface OperationsDao {
    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, id DESC")
    fun observeExpenses(): Flow<List<ExpenseEntity>>

    @Insert
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Query("SELECT * FROM maintenance_requests ORDER BY reportedDate DESC, id DESC")
    fun observeMaintenance(): Flow<List<MaintenanceRequestEntity>>

    @Insert
    suspend fun insertMaintenance(request: MaintenanceRequestEntity): Long

    @Update
    suspend fun updateMaintenance(request: MaintenanceRequestEntity)

    @Query("SELECT * FROM app_notifications WHERE reminderKey = :key")
    suspend fun getNotification(key: String): AppNotificationEntity?

    @Insert
    suspend fun insertNotification(row: AppNotificationEntity): Long
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(row: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE entityType = :entityType AND entityId = :entityId ORDER BY createdAt, id")
    suspend fun forEntity(entityType: String, entityId: Long): List<AuditLogEntity>
}

@Dao
interface MigrationDao {
    @Query("SELECT * FROM migration_review ORDER BY status, id")
    fun observeReview(): Flow<List<MigrationReviewEntity>>

    @Query("SELECT * FROM migration_review WHERE id = :id")
    suspend fun getReview(id: Long): MigrationReviewEntity?

    @Update
    suspend fun updateReview(row: MigrationReviewEntity)

    @Query("SELECT * FROM migration_reports ORDER BY id DESC")
    fun observeReports(): Flow<List<MigrationReportEntity>>
}
