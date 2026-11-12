package com.roomrentmanager.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.RESTRICT
import androidx.room.Index
import androidx.room.PrimaryKey
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

/*
 * Schema v2 conventions:
 * - Dates are ISO `YYYY-MM-DD` strings; timestamps are UTC epoch milliseconds.
 * - Money is integer paise (`...Paise: Long`).
 * - Every foreign key into history uses RESTRICT: nothing financial is ever cascade-deleted.
 * - Ledger, allocation, deposit and audit rows are append-only (enforced by triggers, see
 *   DatabaseTriggers).
 */

@Entity(tableName = "properties")
data class PropertyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val propertyType: PropertyType,
    val address: String?,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "rooms",
    foreignKeys = [ForeignKey(entity = PropertyEntity::class, parentColumns = ["id"], childColumns = ["propertyId"], onDelete = RESTRICT)],
    indices = [Index("propertyId")],
)
data class RoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val propertyId: Long,
    val name: String,
    val defaultRentPaise: Long?,
    val isActive: Boolean,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "tenants")
data class TenantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String?,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * [activeRoomId] equals [roomId] while the tenancy is ACTIVE and is null otherwise. Its unique
 * index is how SQLite enforces "one active tenancy per room" (NULLs never collide). Room cannot
 * declare a partial index, so this column stands in for `UNIQUE(roomId) WHERE status = 'ACTIVE'`;
 * a trigger keeps the two columns consistent.
 */
@Entity(
    tableName = "tenancies",
    foreignKeys = [
        ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = RESTRICT),
        ForeignKey(entity = TenantEntity::class, parentColumns = ["id"], childColumns = ["tenantId"], onDelete = RESTRICT),
    ],
    indices = [Index("roomId"), Index("tenantId"), Index(value = ["activeRoomId"], unique = true)],
)
data class TenancyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    val tenantId: Long,
    val moveInDate: String,
    val moveOutDate: String?,
    val status: TenancyStatus,
    val activeRoomId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "rent_agreements",
    foreignKeys = [ForeignKey(entity = TenancyEntity::class, parentColumns = ["id"], childColumns = ["tenancyId"], onDelete = RESTRICT)],
    indices = [Index("tenancyId"), Index(value = ["tenancyId", "effectiveFrom"], unique = true)],
)
data class RentAgreementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tenancyId: Long,
    val effectiveFrom: String,
    /** Inclusive last day, or null while open-ended. */
    val effectiveTo: String?,
    val rentPaise: Long,
    val billingAnchorDay: Int,
    val billingMode: BillingMode,
    val lateFeePaise: Long,
    val graceDays: Int,
    val notes: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "rent_ledger",
    foreignKeys = [
        ForeignKey(entity = TenancyEntity::class, parentColumns = ["id"], childColumns = ["tenancyId"], onDelete = RESTRICT),
        ForeignKey(entity = RentAgreementEntity::class, parentColumns = ["id"], childColumns = ["agreementId"], onDelete = RESTRICT),
        ForeignKey(entity = RentLedgerEntity::class, parentColumns = ["id"], childColumns = ["reversesEntryId"], onDelete = RESTRICT),
    ],
    indices = [
        Index("tenancyId"),
        Index("agreementId"),
        Index("dueDate"),
        Index(value = ["sourceKey"], unique = true),
        Index(value = ["reversesEntryId"], unique = true),
    ],
)
data class RentLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tenancyId: Long,
    val agreementId: Long?,
    val entryType: LedgerEntryType,
    val periodStart: String?,
    val periodEnd: String?,
    val dueDate: String?,
    /** Signed: positive = owed by tenant, negative = credit. See [LedgerEntryType]. */
    val amountPaise: Long,
    val description: String,
    /** Deterministic idempotency key for generated entries, e.g. `rent:{tenancy}:{agreement}:{periodStart}`. */
    val sourceKey: String?,
    /** For REVERSAL entries: the entry being cancelled. */
    val reversesEntryId: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(entity = TenancyEntity::class, parentColumns = ["id"], childColumns = ["tenancyId"], onDelete = RESTRICT)],
    indices = [Index("tenancyId"), Index("paymentDate")],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null only for imported v1 payments awaiting reconciliation (see migration_review). */
    val tenancyId: Long?,
    val amountPaise: Long,
    val paymentDate: String,
    val paymentMethod: PaymentMethod,
    val reference: String?,
    val note: String?,
    val status: PaymentStatus,
    val reversalReason: String?,
    val reversedAt: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "payment_allocations",
    foreignKeys = [
        ForeignKey(entity = PaymentEntity::class, parentColumns = ["id"], childColumns = ["paymentId"], onDelete = RESTRICT),
        ForeignKey(entity = RentLedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerEntryId"], onDelete = RESTRICT),
        ForeignKey(entity = PaymentAllocationEntity::class, parentColumns = ["id"], childColumns = ["reversesAllocationId"], onDelete = RESTRICT),
    ],
    indices = [Index("paymentId"), Index("ledgerEntryId"), Index(value = ["reversesAllocationId"], unique = true)],
)
data class PaymentAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paymentId: Long,
    val ledgerEntryId: Long,
    /** Positive for an allocation; negative (and [reversesAllocationId] set) for its contra-entry. */
    val amountPaise: Long,
    val createdAt: Long,
    val reversesAllocationId: Long?,
)

@Entity(
    tableName = "deposit_transactions",
    foreignKeys = [ForeignKey(entity = TenancyEntity::class, parentColumns = ["id"], childColumns = ["tenancyId"], onDelete = RESTRICT)],
    indices = [Index("tenancyId")],
)
data class DepositTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tenancyId: Long,
    val transactionType: DepositTransactionType,
    /** Positive for RECEIVED/REFUND/DEDUCTION; signed for ADJUSTMENT. */
    val amountPaise: Long,
    val transactionDate: String,
    val note: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(entity = PropertyEntity::class, parentColumns = ["id"], childColumns = ["propertyId"], onDelete = RESTRICT),
        ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = RESTRICT),
    ],
    indices = [Index("propertyId"), Index("roomId"), Index("expenseDate")],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val propertyId: Long,
    val roomId: Long?,
    val category: String,
    val amountPaise: Long,
    val expenseDate: String,
    val description: String,
    val vendor: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "maintenance_requests",
    foreignKeys = [
        ForeignKey(entity = PropertyEntity::class, parentColumns = ["id"], childColumns = ["propertyId"], onDelete = RESTRICT),
        ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = RESTRICT),
        ForeignKey(entity = TenancyEntity::class, parentColumns = ["id"], childColumns = ["tenancyId"], onDelete = RESTRICT),
    ],
    indices = [Index("propertyId"), Index("roomId"), Index("tenancyId"), Index("status")],
)
data class MaintenanceRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val propertyId: Long,
    val roomId: Long?,
    val tenancyId: Long?,
    val title: String,
    val description: String,
    val priority: MaintenancePriority,
    val status: MaintenanceStatus,
    val reportedDate: String,
    val completedDate: String?,
    val costPaise: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One row per reminder occurrence; [reminderKey] prevents duplicates (e.g. `due:{tenancy}:{date}`). */
@Entity(tableName = "app_notifications", indices = [Index(value = ["reminderKey"], unique = true), Index("dueDate")])
data class AppNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderKey: String,
    val kind: String,
    val entityType: String,
    val entityId: Long,
    val dueDate: String,
    val message: String,
    val status: ReminderStatus,
    val createdAt: Long,
)

@Entity(tableName = "audit_log", indices = [Index("entityType", "entityId"), Index("createdAt")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val entityType: String,
    val entityId: Long?,
    val description: String,
    val createdAt: Long,
)

/**
 * Records imported from v1 that need a human decision. [legacyData] is a JSON object with the
 * original v1 values (amount as text, month, date, note, tenant id/name, room id).
 */
@Entity(
    tableName = "migration_review",
    indices = [Index(value = ["entityType", "entityId"], unique = true), Index("status")],
)
data class MigrationReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: ReviewEntityType,
    val entityId: Long,
    val reason: String,
    val legacyData: String,
    /** A tenancy the record probably belongs to. Shown to the user; never applied automatically. */
    val suggestedTenancyId: Long?,
    val status: ReviewStatus,
    val resolvedAt: Long?,
    val createdAt: Long,
)

@Entity(tableName = "migration_reports")
data class MigrationReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromVersion: Int,
    val toVersion: Int,
    /** Human-readable summary: imported counts, totals, unmatched records and validation results. */
    val report: String,
    val createdAt: Long,
)
