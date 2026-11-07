package com.roomrentmanager.domain.model

import java.time.LocalDate

/*
 * Domain models used by use cases and the UI. Room entities never leave the data layer.
 * Money is paise (Long). Dates that are always validated on input are LocalDate; payment dates
 * stay String because imported v1 payments may carry a date that never parsed.
 */

data class Property(
    val id: Long,
    val name: String,
    val type: PropertyType,
    val address: String?,
    val notes: String?,
    val isActive: Boolean,
)

data class RentalUnit(
    val id: Long,
    val propertyId: Long,
    val name: String,
    val defaultRentPaise: Long?,
    val isActive: Boolean,
    val notes: String?,
)

data class Tenant(
    val id: Long,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String?,
    val notes: String?,
    val isActive: Boolean,
)

data class Tenancy(
    val id: Long,
    val roomId: Long,
    val tenantId: Long,
    val moveIn: LocalDate,
    val moveOut: LocalDate?,
    val status: TenancyStatus,
)

data class RentAgreement(
    val id: Long,
    val tenancyId: Long,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val rentPaise: Long,
    val anchorDay: Int,
    val lateFeePaise: Long,
    val graceDays: Int,
    val notes: String?,
)

data class LedgerEntry(
    val id: Long,
    val tenancyId: Long,
    val agreementId: Long?,
    val type: LedgerEntryType,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val dueDate: LocalDate?,
    val amountPaise: Long,
    val description: String,
    val sourceKey: String?,
    val reversesEntryId: Long?,
    val createdAt: Long,
)

data class Payment(
    val id: Long,
    val tenancyId: Long?,
    val amountPaise: Long,
    val paymentDate: String,
    val method: PaymentMethod,
    val reference: String?,
    val note: String?,
    val status: PaymentStatus,
    val reversalReason: String?,
) {
    val date: LocalDate? get() = runCatching { LocalDate.parse(paymentDate) }.getOrNull()

    /** Counts towards a tenancy's balance: posted and linked. Unreconciled imports do not. */
    val counts: Boolean get() = status == PaymentStatus.POSTED && tenancyId != null
}

data class PaymentAllocation(
    val id: Long,
    val paymentId: Long,
    val ledgerEntryId: Long,
    val amountPaise: Long,
    val reversesAllocationId: Long?,
)

data class DepositTransaction(
    val id: Long,
    val tenancyId: Long,
    val type: DepositTransactionType,
    val amountPaise: Long,
    val date: LocalDate,
    val note: String?,
)

data class ReviewItem(
    val id: Long,
    val entityType: ReviewEntityType,
    val entityId: Long,
    val reason: String,
    /** Original v1 values as JSON text. */
    val legacyData: String,
    val suggestedTenancyId: Long?,
    val status: ReviewStatus,
)

data class AuditEvent(
    val id: Long,
    val eventType: String,
    val entityType: String,
    val entityId: Long?,
    val description: String,
    val createdAt: Long,
)

/** Everything the screens need, loaded together so derived figures are always consistent. */
data class Portfolio(
    val properties: List<Property> = emptyList(),
    val rooms: List<RentalUnit> = emptyList(),
    val tenants: List<Tenant> = emptyList(),
    val tenancies: List<Tenancy> = emptyList(),
    val agreements: List<RentAgreement> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val allocations: List<PaymentAllocation> = emptyList(),
    val deposits: List<DepositTransaction> = emptyList(),
    val reviews: List<ReviewItem> = emptyList(),
)

/** A rule was broken by user input. The message is shown to the user as-is. */
class ValidationException(message: String) : Exception(message)
