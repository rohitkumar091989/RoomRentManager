package com.roomrentmanager.domain.finance

import com.roomrentmanager.domain.model.DepositTransaction
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntry
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.PaymentAllocation
import java.time.LocalDate

/** A ledger entry that the tenant owes money on, with what has been paid or cancelled. */
data class ChargeStatus(val entry: LedgerEntry, val allocatedPaise: Long, val reversedPaise: Long) {
    val outstandingPaise: Long get() = entry.amountPaise - allocatedPaise - reversedPaise
    val isOpen: Boolean get() = outstandingPaise > 0
}

/**
 * A tenancy's rent position. Sign convention: [netPaise] > 0 means the tenant owes money;
 * [netPaise] < 0 means the landlord holds advance credit for the tenant. Never clamped.
 */
data class TenancyBalance(
    val charges: List<ChargeStatus>,
    /** Unpaid amount of all charges. */
    val outstandingPaise: Long,
    /** Unapplied payment value plus ledger credits, shown separately from what is owed. */
    val creditPaise: Long,
) {
    val netPaise: Long get() = outstandingPaise - creditPaise

    fun overduePaise(asOf: LocalDate): Long =
        charges.filter { it.isOpen && it.entry.dueDate != null && it.entry.dueDate.isBefore(asOf) }.sumOf { it.outstandingPaise }

    companion object {
        val EMPTY = TenancyBalance(emptyList(), 0, 0)
    }
}

/** How a payment is spread across a tenancy's open charges. */
sealed interface AllocationMode {
    /** Oldest due charge first, across all open charges. */
    data object OldestFirst : AllocationMode

    /** Oldest due first, but only across the charges the user selected. */
    data class Selected(val ledgerEntryIds: Set<Long>) : AllocationMode

    /** Keep the whole payment as advance credit. */
    data object KeepAsCredit : AllocationMode
}

/** Pure financial rules. All inputs are explicit; no database or clock access. */
object FinanceCalculator {

    private val CHARGE_TYPES = setOf(LedgerEntryType.RENT_CHARGE, LedgerEntryType.LATE_FEE, LedgerEntryType.ADJUSTMENT)

    fun isCharge(entry: LedgerEntry) = entry.type in CHARGE_TYPES && entry.amountPaise > 0

    /** Ledger credits that are not tied to a specific charge (CREDIT, negative ADJUSTMENT). */
    fun isFreeCredit(entry: LedgerEntry) = entry.amountPaise < 0 && entry.type != LedgerEntryType.REVERSAL

    /**
     * Balance for one tenancy. [payments] and [allocations] may include other tenancies' rows;
     * only rows belonging to this tenancy's payments/ledger are used.
     */
    fun balance(
        tenancyId: Long,
        ledger: List<LedgerEntry>,
        payments: List<Payment>,
        allocations: List<PaymentAllocation>,
    ): TenancyBalance {
        val entries = ledger.filter { it.tenancyId == tenancyId }
        val counted = payments.filter { it.counts && it.tenancyId == tenancyId }
        val entryIds = entries.map { it.id }.toSet()
        val tenancyAllocations = allocations.filter { it.ledgerEntryId in entryIds }
        val allocatedByEntry = tenancyAllocations.groupBy { it.ledgerEntryId }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        val reversedByEntry = entries.filter { it.type == LedgerEntryType.REVERSAL && it.reversesEntryId != null }
            .groupBy { it.reversesEntryId!! }.mapValues { (_, v) -> -v.sumOf { it.amountPaise } }

        val charges = entries.filter(::isCharge)
            .map { ChargeStatus(it, allocatedByEntry[it.id] ?: 0, reversedByEntry[it.id] ?: 0) }
            .sortedWith(compareBy<ChargeStatus, LocalDate?>(nullsLast()) { it.entry.dueDate }.thenBy { it.entry.id })

        val countedIds = counted.map { it.id }.toSet()
        val allocatedFromCounted = tenancyAllocations.filter { it.paymentId in countedIds }.sumOf { it.amountPaise }
        val unappliedPayments = counted.sumOf { it.amountPaise } - allocatedFromCounted
        val freeCredits = -entries.filter(::isFreeCredit).sumOf { it.amountPaise }

        return TenancyBalance(
            charges = charges,
            outstandingPaise = charges.sumOf { it.outstandingPaise },
            creditPaise = unappliedPayments + freeCredits,
        )
    }

    /** Value of a payment not yet allocated to charges. */
    fun unallocatedPaise(payment: Payment, allocations: List<PaymentAllocation>): Long =
        payment.amountPaise - allocations.filter { it.paymentId == payment.id }.sumOf { it.amountPaise }

    /**
     * Splits [availablePaise] over open charges. Returns ledgerEntryId -> amount (positive).
     * Never exceeds a charge's outstanding amount or the available amount. Whatever is left
     * stays as credit.
     */
    fun allocate(availablePaise: Long, openCharges: List<ChargeStatus>, mode: AllocationMode): Map<Long, Long> {
        require(availablePaise >= 0) { "Available amount cannot be negative" }
        val targets = when (mode) {
            AllocationMode.KeepAsCredit -> return emptyMap()
            AllocationMode.OldestFirst -> openCharges
            is AllocationMode.Selected -> {
                val known = openCharges.map { it.entry.id }.toSet()
                val unknown = mode.ledgerEntryIds - known
                require(unknown.isEmpty()) { "Charges $unknown are not open for this tenancy" }
                openCharges.filter { it.entry.id in mode.ledgerEntryIds }
            }
        }.filter { it.isOpen }
            .sortedWith(compareBy<ChargeStatus, LocalDate?>(nullsLast()) { it.entry.dueDate }.thenBy { it.entry.id })

        var remaining = availablePaise
        val result = linkedMapOf<Long, Long>()
        for (charge in targets) {
            if (remaining == 0L) break
            val amount = minOf(remaining, charge.outstandingPaise)
            result[charge.entry.id] = amount
            remaining -= amount
        }
        return result
    }

    /** Refundable deposit held: received - refunds - deductions + adjustments (signed). */
    fun depositBalance(transactions: List<DepositTransaction>): Long = transactions.sumOf {
        when (it.type) {
            DepositTransactionType.RECEIVED -> it.amountPaise
            DepositTransactionType.REFUND, DepositTransactionType.DEDUCTION -> -it.amountPaise
            DepositTransactionType.ADJUSTMENT -> it.amountPaise
        }
    }
}
