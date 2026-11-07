package com.roomrentmanager.domain.usecase

import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.finance.BillingCycleCalculator
import com.roomrentmanager.domain.finance.FinanceCalculator
import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.finance.TenancyBalance
import com.roomrentmanager.domain.model.DepositTransaction
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntry
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.PaymentAllocation
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.PaymentStatus
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.ReviewEntityType
import com.roomrentmanager.domain.model.ReviewStatus
import com.roomrentmanager.domain.model.TenancyStatus
import com.roomrentmanager.domain.repository.AuditRepository
import com.roomrentmanager.domain.repository.FinanceRepository
import com.roomrentmanager.domain.repository.TenancyRepository
import com.roomrentmanager.domain.repository.TransactionRunner
import java.time.Clock
import java.time.LocalDate

/**
 * Posts rent charges from tenancy and agreement history. Idempotent: each charge carries a
 * deterministic sourceKey (unique in the database), so running it again never double-charges.
 */
class GenerateRentDueUseCase(
    private val tx: TransactionRunner,
    private val tenancies: TenancyRepository,
    private val finance: FinanceRepository,
) {
    data class Result(val created: Int, val problems: List<String>)

    /** Generates for every tenancy. A tenancy with invalid terms is skipped and reported. */
    suspend fun forAll(asOf: LocalDate): Result {
        var created = 0
        val problems = mutableListOf<String>()
        for (t in tenancies.allTenancies().filter { it.status != TenancyStatus.CANCELLED }) {
            try {
                created += forTenancy(t.id, asOf)
            } catch (e: IllegalArgumentException) {
                problems += "Tenancy ${t.id}: ${e.message}"
            }
        }
        return Result(created, problems)
    }

    suspend fun forTenancy(tenancyId: Long, asOf: LocalDate): Int = tx.inTransaction {
        val tenancy = tenancies.getTenancy(tenancyId) ?: return@inTransaction 0
        if (tenancy.status == TenancyStatus.CANCELLED) return@inTransaction 0
        val agreements = tenancies.agreementsFor(tenancyId)
        if (agreements.isEmpty()) return@inTransaction 0
        val planned = BillingCycleCalculator.plannedCharges(tenancyId, tenancy.moveIn, tenancy.moveOut, termsOf(agreements), asOf)
        val existing = finance.sourceKeysFor(tenancyId)
        val missing = planned.filter { it.sourceKey !in existing }
        missing.forEach {
            finance.insertLedgerEntry(
                LedgerEntry(
                    id = 0, tenancyId = tenancyId, agreementId = it.agreementId, type = LedgerEntryType.RENT_CHARGE,
                    periodStart = it.period.start, periodEnd = it.period.end, dueDate = it.period.dueDate,
                    amountPaise = it.amountPaise, description = "Rent ${it.period.start} to ${it.period.end}",
                    sourceKey = it.sourceKey, reversesEntryId = null, createdAt = 0,
                ),
            )
        }
        missing.size
    }
}

/** Current balance of one tenancy, from persisted rows. */
class CalculateTenantBalanceUseCase(private val finance: FinanceRepository) {
    suspend operator fun invoke(tenancyId: Long): TenancyBalance = FinanceCalculator.balance(
        tenancyId, finance.ledgerFor(tenancyId), finance.paymentsFor(tenancyId), finance.allocationsForTenancy(tenancyId),
    )

    fun fromPortfolio(tenancyId: Long, p: Portfolio): TenancyBalance =
        FinanceCalculator.balance(tenancyId, p.ledger, p.payments, p.allocations)
}

data class PaymentInput(
    val tenancyId: Long,
    val amountPaise: Long,
    val date: LocalDate,
    val method: PaymentMethod,
    val reference: String? = null,
    val note: String? = null,
    val allocation: AllocationMode = AllocationMode.OldestFirst,
)

/** Records money received and allocates it, atomically. Any unallocated value is advance credit. */
class RecordPaymentUseCase(
    private val tx: TransactionRunner,
    private val tenancies: TenancyRepository,
    private val finance: FinanceRepository,
    private val allocate: AllocatePaymentUseCase,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: PaymentInput): Long = tx.inTransaction {
        if (input.amountPaise !in 1..Money.MAX_PAISE) fail("Enter an amount greater than zero.")
        if (input.date.isAfter(LocalDate.now(clock))) fail("The payment date cannot be in the future.")
        val tenancy = tenancies.getTenancy(input.tenancyId) ?: fail("Choose a tenancy.")
        if (tenancy.status == TenancyStatus.CANCELLED) fail("This tenancy was cancelled.")
        val id = finance.insertPayment(
            Payment(0, tenancy.id, input.amountPaise, input.date.toString(), input.method, input.reference.cleaned(), input.note.cleaned(), PaymentStatus.POSTED, null),
        )
        val allocated = allocate.allocateWithinTransaction(id, input.allocation)
        audit.log(
            "PAYMENT_RECORDED", "PAYMENT", id,
            "Received ${Money.format(input.amountPaise)} on ${input.date} by ${input.method.name.lowercase()} for tenancy ${tenancy.id}; " +
                "allocated ${Money.format(allocated)}, credit ${Money.format(input.amountPaise - allocated)}.",
        )
        id
    }
}

/** Allocates the unallocated part of a posted payment to open charges of its tenancy. */
class AllocatePaymentUseCase(
    private val tx: TransactionRunner,
    private val finance: FinanceRepository,
    private val audit: AuditRepository,
) {
    /** Returns the amount allocated. */
    suspend operator fun invoke(paymentId: Long, mode: AllocationMode): Long = tx.inTransaction {
        val allocated = allocateWithinTransaction(paymentId, mode)
        if (allocated > 0) audit.log("PAYMENT_ALLOCATED", "PAYMENT", paymentId, "Allocated ${Money.format(allocated)} to open charges.")
        allocated
    }

    /** Applies all of a tenancy's unallocated payments to its open charges, oldest first. */
    suspend fun applyCredit(tenancyId: Long): Long = tx.inTransaction {
        var total = 0L
        val payments = finance.paymentsFor(tenancyId).filter { it.counts }.sortedWith(compareBy({ it.paymentDate }, { it.id }))
        for (p in payments) total += allocateWithinTransaction(p.id, AllocationMode.OldestFirst)
        if (total > 0) audit.log("CREDIT_APPLIED", "TENANCY", tenancyId, "Applied ${Money.format(total)} of advance credit to open charges.")
        total
    }

    /** Must be called inside a transaction. */
    internal suspend fun allocateWithinTransaction(paymentId: Long, mode: AllocationMode): Long {
        val payment = finance.getPayment(paymentId) ?: fail("This payment no longer exists.")
        if (payment.status != PaymentStatus.POSTED) fail("A reversed payment cannot be allocated.")
        val tenancyId = payment.tenancyId ?: fail("Link this payment to a tenancy first.")
        val available = FinanceCalculator.unallocatedPaise(payment, finance.allocationsForPayment(paymentId))
        if (available <= 0) return 0
        val balance = FinanceCalculator.balance(
            tenancyId, finance.ledgerFor(tenancyId), finance.paymentsFor(tenancyId), finance.allocationsForTenancy(tenancyId),
        )
        val split = try {
            FinanceCalculator.allocate(available, balance.charges.filter { it.isOpen }, mode)
        } catch (e: IllegalArgumentException) {
            fail("Some selected charges are no longer open. Refresh and try again.")
        }
        if (split.isEmpty()) return 0
        finance.insertAllocations(split.map { (entryId, amount) -> PaymentAllocation(0, paymentId, entryId, amount, null) })
        return split.values.sum()
    }
}

/**
 * Reverses a posted payment: marks it REVERSED and adds a contra-allocation for each of its
 * allocations, restoring the charges' outstanding amounts. Nothing is deleted. Idempotent.
 */
class ReversePaymentUseCase(
    private val tx: TransactionRunner,
    private val finance: FinanceRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    /** Returns false if the payment was already reversed. */
    suspend operator fun invoke(paymentId: Long, reason: String): Boolean = tx.inTransaction {
        val cleanReason = reason.cleaned() ?: fail("Enter a reason for reversing this payment.")
        val payment = finance.getPayment(paymentId) ?: fail("This payment no longer exists.")
        if (payment.status == PaymentStatus.REVERSED) return@inTransaction false
        val allocations = finance.allocationsForPayment(paymentId)
        val alreadyReversed = allocations.mapNotNull { it.reversesAllocationId }.toSet()
        val contra = allocations.filter { it.amountPaise > 0 && it.id !in alreadyReversed }
            .map { PaymentAllocation(0, paymentId, it.ledgerEntryId, -it.amountPaise, it.id) }
        if (contra.isNotEmpty()) finance.insertAllocations(contra)
        finance.markReversed(paymentId, cleanReason, clock.millis())
        audit.log("PAYMENT_REVERSED", "PAYMENT", paymentId, "Reversed payment of ${Money.format(payment.amountPaise)}: $cleanReason")
        true
    }
}

/** Deposit receipts, deductions and refunds. Kept separate from rent; never applied to rent automatically. */
class RecordDepositUseCase(
    private val tx: TransactionRunner,
    private val tenancies: TenancyRepository,
    private val finance: FinanceRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(tenancyId: Long, type: DepositTransactionType, amountPaise: Long, date: LocalDate, note: String?): Long = tx.inTransaction {
        if (amountPaise == 0L || kotlin.math.abs(amountPaise) > Money.MAX_PAISE) fail("Enter a valid amount.")
        if (type != DepositTransactionType.ADJUSTMENT && amountPaise < 0) fail("Enter a positive amount.")
        if (date.isAfter(LocalDate.now(clock))) fail("The date cannot be in the future.")
        tenancies.getTenancy(tenancyId) ?: fail("Choose a tenancy.")
        if (type == DepositTransactionType.DEDUCTION && note.cleaned() == null) fail("Describe what the deduction is for.")
        val held = FinanceCalculator.depositBalance(finance.depositsFor(tenancyId))
        val reduces = when (type) {
            DepositTransactionType.REFUND, DepositTransactionType.DEDUCTION -> amountPaise
            DepositTransactionType.ADJUSTMENT -> -amountPaise
            DepositTransactionType.RECEIVED -> -amountPaise
        }
        if (reduces > held) fail("Only ${Money.format(held)} of deposit is held.")
        val id = finance.insertDeposit(DepositTransaction(0, tenancyId, type, amountPaise, date, note.cleaned()))
        audit.log("DEPOSIT_${type.name}", "TENANCY", tenancyId, "Deposit ${type.name.lowercase()} ${Money.format(amountPaise)} on $date.")
        id
    }
}

/** Compensating ledger entries: extra charges, credits, and cancelling the unpaid part of a charge. */
class PostLedgerAdjustmentUseCase(
    private val tx: TransactionRunner,
    private val tenancies: TenancyRepository,
    private val finance: FinanceRepository,
    private val audit: AuditRepository,
) {
    /** [amountPaise] > 0 adds a charge (ADJUSTMENT or LATE_FEE); use [addCredit] for credits. */
    suspend fun addCharge(tenancyId: Long, type: LedgerEntryType, amountPaise: Long, dueDate: LocalDate, description: String): Long = tx.inTransaction {
        if (type != LedgerEntryType.ADJUSTMENT && type != LedgerEntryType.LATE_FEE) fail("Unsupported charge type.")
        if (amountPaise !in 1..Money.MAX_PAISE) fail("Enter an amount greater than zero.")
        val text = description.cleaned() ?: fail("Describe the charge.")
        tenancies.getTenancy(tenancyId) ?: fail("Choose a tenancy.")
        val id = finance.insertLedgerEntry(LedgerEntry(0, tenancyId, null, type, null, null, dueDate, amountPaise, text, null, null, 0))
        audit.log("LEDGER_${type.name}", "TENANCY", tenancyId, "Charged ${Money.format(amountPaise)}: $text")
        id
    }

    suspend fun addCredit(tenancyId: Long, amountPaise: Long, description: String): Long = tx.inTransaction {
        if (amountPaise !in 1..Money.MAX_PAISE) fail("Enter an amount greater than zero.")
        val text = description.cleaned() ?: fail("Describe the credit.")
        tenancies.getTenancy(tenancyId) ?: fail("Choose a tenancy.")
        val id = finance.insertLedgerEntry(LedgerEntry(0, tenancyId, null, LedgerEntryType.CREDIT, null, null, null, -amountPaise, text, null, null, 0))
        audit.log("LEDGER_CREDIT", "TENANCY", tenancyId, "Credited ${Money.format(amountPaise)}: $text")
        id
    }

    /** Cancels the unpaid remainder of a charge with a REVERSAL entry (the original stays). */
    suspend fun cancelCharge(ledgerEntryId: Long, reason: String): Long = tx.inTransaction {
        val text = reason.cleaned() ?: fail("Enter a reason.")
        val entry = finance.getLedgerEntry(ledgerEntryId) ?: fail("This charge no longer exists.")
        val status = FinanceCalculator.balance(
            entry.tenancyId, finance.ledgerFor(entry.tenancyId), finance.paymentsFor(entry.tenancyId), finance.allocationsForTenancy(entry.tenancyId),
        ).charges.firstOrNull { it.entry.id == ledgerEntryId } ?: fail("Only charges can be cancelled.")
        if (!status.isOpen) fail("Nothing is outstanding on this charge.")
        if (status.reversedPaise > 0) fail("This charge was already cancelled.")
        val id = finance.insertLedgerEntry(
            LedgerEntry(0, entry.tenancyId, entry.agreementId, LedgerEntryType.REVERSAL, entry.periodStart, entry.periodEnd, entry.dueDate,
                -status.outstandingPaise, "Cancelled: $text", null, entry.id, 0),
        )
        audit.log("LEDGER_REVERSAL", "TENANCY", entry.tenancyId, "Cancelled ${Money.format(status.outstandingPaise)} of '${entry.description}': $text")
        id
    }
}

/** Decisions on records imported from v1 that could not be reconciled automatically. */
class ResolveMigrationReviewUseCase(
    private val tx: TransactionRunner,
    private val tenancies: TenancyRepository,
    private val finance: FinanceRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    /** Links an imported payment to a tenancy. It then counts as that tenancy's advance credit. */
    suspend fun applyPaymentToTenancy(reviewId: Long, tenancyId: Long) = tx.inTransaction {
        val item = pending(reviewId, ReviewEntityType.PAYMENT)
        val payment = finance.getPayment(item.entityId) ?: fail("This imported amount was not valid and cannot be applied. Keep it as history.")
        tenancies.getTenancy(tenancyId) ?: fail("Choose a tenancy.")
        finance.linkPaymentToTenancy(payment.id, tenancyId)
        finance.updateReview(item.copy(status = ReviewStatus.APPLIED_TO_TENANCY, suggestedTenancyId = tenancyId), clock.millis())
        audit.log("LEGACY_PAYMENT_APPLIED", "PAYMENT", payment.id, "Imported payment of ${Money.format(payment.amountPaise)} linked to tenancy $tenancyId as credit.")
    }

    /** Keeps an imported payment as a historical record only; it never affects balances. */
    suspend fun keepPaymentAsHistory(reviewId: Long) = tx.inTransaction {
        val item = pending(reviewId, ReviewEntityType.PAYMENT)
        finance.updateReview(item.copy(status = ReviewStatus.KEPT_AS_HISTORY), clock.millis())
        audit.log("LEGACY_PAYMENT_KEPT", "PAYMENT", item.entityId, "Imported payment kept as history only.")
    }

    suspend fun markTenantReviewed(reviewId: Long) = tx.inTransaction {
        val item = pending(reviewId, ReviewEntityType.TENANT)
        finance.updateReview(item.copy(status = ReviewStatus.RESOLVED), clock.millis())
        audit.log("LEGACY_TENANT_REVIEWED", "TENANT", item.entityId, "Imported tenant reviewed.")
    }

    private suspend fun pending(reviewId: Long, type: ReviewEntityType) =
        finance.getReview(reviewId)?.takeIf { it.entityType == type && it.status == ReviewStatus.PENDING }
            ?: fail("This item has already been reviewed.")
}
