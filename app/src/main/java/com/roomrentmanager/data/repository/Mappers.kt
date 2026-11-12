package com.roomrentmanager.data.repository

import com.roomrentmanager.data.local.AppNotificationEntity
import com.roomrentmanager.data.local.AuditLogEntity
import com.roomrentmanager.data.local.ExpenseEntity
import com.roomrentmanager.data.local.MaintenanceRequestEntity
import com.roomrentmanager.data.local.DepositTransactionEntity
import com.roomrentmanager.data.local.MigrationReviewEntity
import com.roomrentmanager.data.local.PaymentAllocationEntity
import com.roomrentmanager.data.local.PaymentEntity
import com.roomrentmanager.data.local.PropertyEntity
import com.roomrentmanager.data.local.RentAgreementEntity
import com.roomrentmanager.data.local.RentLedgerEntity
import com.roomrentmanager.data.local.RoomEntity
import com.roomrentmanager.data.local.TenancyEntity
import com.roomrentmanager.data.local.TenantEntity
import com.roomrentmanager.domain.model.AuditEvent
import com.roomrentmanager.domain.model.BillingMode
import com.roomrentmanager.domain.model.DepositTransaction
import com.roomrentmanager.domain.model.Expense
import com.roomrentmanager.domain.model.MaintenanceRequest
import com.roomrentmanager.domain.model.Reminder
import com.roomrentmanager.domain.model.LedgerEntry
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.PaymentAllocation
import com.roomrentmanager.domain.model.Property
import com.roomrentmanager.domain.model.RentAgreement
import com.roomrentmanager.domain.model.RentalUnit
import com.roomrentmanager.domain.model.ReviewItem
import com.roomrentmanager.domain.model.Tenancy
import com.roomrentmanager.domain.model.TenancyStatus
import com.roomrentmanager.domain.model.Tenant
import java.time.LocalDate

private fun String?.toDate(): LocalDate? = this?.let(LocalDate::parse)

internal fun PropertyEntity.toDomain() = Property(id, name, propertyType, address, notes, isActive)
internal fun Property.toEntity(createdAt: Long, now: Long) =
    PropertyEntity(id, name, type, address, notes, isActive, createdAt, now)

internal fun RoomEntity.toDomain() = RentalUnit(id, propertyId, name, defaultRentPaise, isActive, notes)
internal fun RentalUnit.toEntity(createdAt: Long, now: Long) =
    RoomEntity(id, propertyId, name, defaultRentPaise, isActive, notes, createdAt, now)

internal fun TenantEntity.toDomain() = Tenant(id, name, phone, email, address, notes, isActive)
internal fun Tenant.toEntity(createdAt: Long, now: Long) =
    TenantEntity(id, name, phone, email, address, notes, isActive, createdAt, now)

internal fun TenancyEntity.toDomain() =
    Tenancy(id, roomId, tenantId, LocalDate.parse(moveInDate), moveOutDate.toDate(), status)
internal fun Tenancy.toEntity(createdAt: Long, now: Long) = TenancyEntity(
    id = id, roomId = roomId, tenantId = tenantId, moveInDate = moveIn.toString(), moveOutDate = moveOut?.toString(),
    status = status, activeRoomId = if (status == TenancyStatus.ACTIVE) roomId else null,
    createdAt = createdAt, updatedAt = now,
)

internal fun RentAgreementEntity.toDomain() = RentAgreement(
    id, tenancyId, LocalDate.parse(effectiveFrom), effectiveTo.toDate(), rentPaise, billingAnchorDay, lateFeePaise, graceDays, notes,
)
internal fun RentAgreement.toEntity(now: Long) = RentAgreementEntity(
    id, tenancyId, effectiveFrom.toString(), effectiveTo?.toString(), rentPaise, anchorDay, BillingMode.ANNIVERSARY,
    lateFeePaise, graceDays, notes, now,
)

internal fun RentLedgerEntity.toDomain() = LedgerEntry(
    id, tenancyId, agreementId, entryType, periodStart.toDate(), periodEnd.toDate(), dueDate.toDate(),
    amountPaise, description, sourceKey, reversesEntryId, createdAt,
)
internal fun LedgerEntry.toEntity(now: Long) = RentLedgerEntity(
    id, tenancyId, agreementId, type, periodStart?.toString(), periodEnd?.toString(), dueDate?.toString(),
    amountPaise, description, sourceKey, reversesEntryId, now,
)

internal fun PaymentEntity.toDomain() =
    Payment(id, tenancyId, amountPaise, paymentDate, paymentMethod, reference, note, status, reversalReason, reversedAt)
internal fun Payment.toEntity(now: Long) =
    PaymentEntity(id, tenancyId, amountPaise, paymentDate, method, reference, note, status, reversalReason, null, now)

internal fun PaymentAllocationEntity.toDomain() = PaymentAllocation(id, paymentId, ledgerEntryId, amountPaise, reversesAllocationId)
internal fun PaymentAllocation.toEntity(now: Long) =
    PaymentAllocationEntity(id, paymentId, ledgerEntryId, amountPaise, now, reversesAllocationId)

internal fun DepositTransactionEntity.toDomain() =
    DepositTransaction(id, tenancyId, transactionType, amountPaise, LocalDate.parse(transactionDate), note)
internal fun DepositTransaction.toEntity(now: Long) =
    DepositTransactionEntity(id, tenancyId, type, amountPaise, date.toString(), note, now)

internal fun MigrationReviewEntity.toDomain() =
    ReviewItem(id, entityType, entityId, reason, legacyData, suggestedTenancyId, status)

internal fun AuditLogEntity.toDomain() = AuditEvent(id, eventType, entityType, entityId, description, createdAt)

internal fun ExpenseEntity.toDomain() =
    Expense(id, propertyId, roomId, category, amountPaise, LocalDate.parse(expenseDate), description, vendor)
internal fun Expense.toEntity(createdAt: Long) =
    ExpenseEntity(id, propertyId, roomId, category, amountPaise, date.toString(), description, vendor, createdAt)

internal fun MaintenanceRequestEntity.toDomain() = MaintenanceRequest(
    id, propertyId, roomId, tenancyId, title, description, priority, status,
    LocalDate.parse(reportedDate), completedDate.toDate(), costPaise,
)
internal fun MaintenanceRequest.toEntity(createdAt: Long, now: Long) = MaintenanceRequestEntity(
    id, propertyId, roomId, tenancyId, title, description, priority, status,
    reportedDate.toString(), completedDate?.toString(), costPaise, createdAt, now,
)

internal fun AppNotificationEntity.toDomain() =
    Reminder(id, reminderKey, kind, entityType, entityId, LocalDate.parse(dueDate), message, status)
