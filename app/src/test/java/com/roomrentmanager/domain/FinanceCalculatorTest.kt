package com.roomrentmanager.domain

import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.finance.FinanceCalculator
import com.roomrentmanager.domain.model.DepositTransaction
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntry
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.PaymentAllocation
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FinanceCalculatorTest {

    private fun charge(id: Long, due: String, amount: Long, type: LedgerEntryType = LedgerEntryType.RENT_CHARGE) =
        LedgerEntry(id, 1, null, type, null, null, LocalDate.parse(due), amount, "c$id", null, null, 0)

    private fun entry(id: Long, type: LedgerEntryType, amount: Long, reverses: Long? = null) =
        LedgerEntry(id, 1, null, type, null, null, null, amount, "e$id", null, reverses, 0)

    private fun payment(id: Long, amount: Long, status: PaymentStatus = PaymentStatus.POSTED, tenancyId: Long? = 1) =
        Payment(id, tenancyId, amount, "2026-01-01", PaymentMethod.CASH, null, null, status, null)

    private fun alloc(id: Long, payment: Long, entry: Long, amount: Long, reverses: Long? = null) =
        PaymentAllocation(id, payment, entry, amount, reverses)

    private val jan = charge(1, "2026-01-05", 1000_00)
    private val feb = charge(2, "2026-02-05", 1000_00)
    private val mar = charge(3, "2026-03-05", 1000_00)

    @Test
    fun fullPaymentSettlesCharge() {
        val b = FinanceCalculator.balance(1, listOf(jan), listOf(payment(1, 1000_00)), listOf(alloc(1, 1, 1, 1000_00)))
        assertEquals(0L, b.outstandingPaise)
        assertEquals(0L, b.creditPaise)
        assertEquals(0L, b.netPaise)
        assertTrue(b.charges.none { it.isOpen })
    }

    @Test
    fun partialPaymentLeavesRemainderOutstanding() {
        val b = FinanceCalculator.balance(1, listOf(jan), listOf(payment(1, 400_00)), listOf(alloc(1, 1, 1, 400_00)))
        assertEquals(600_00L, b.outstandingPaise)
        assertEquals(600_00L, b.netPaise)
    }

    @Test
    fun oldestFirstAcrossMultipleCharges() {
        val open = FinanceCalculator.balance(1, listOf(mar, jan, feb), emptyList(), emptyList()).charges
        val split = FinanceCalculator.allocate(1500_00, open, AllocationMode.OldestFirst)
        assertEquals(mapOf(1L to 1000_00L, 2L to 500_00L), split)
        assertEquals(listOf(1L, 2L), split.keys.toList())
    }

    @Test
    fun selectedChargesOnly() {
        val open = FinanceCalculator.balance(1, listOf(jan, feb, mar), emptyList(), emptyList()).charges
        val split = FinanceCalculator.allocate(5000_00, open, AllocationMode.Selected(setOf(3L)))
        assertEquals(mapOf(3L to 1000_00L), split)
        assertThrows(IllegalArgumentException::class.java) { FinanceCalculator.allocate(1, open, AllocationMode.Selected(setOf(99L))) }
    }

    @Test
    fun advancePaymentIsCreditNotClamped() {
        val b = FinanceCalculator.balance(1, emptyList(), listOf(payment(1, 2500_00)), emptyList())
        assertEquals(0L, b.outstandingPaise)
        assertEquals(2500_00L, b.creditPaise)
        assertEquals(-2500_00L, b.netPaise)
        assertEquals(emptyMap<Long, Long>(), FinanceCalculator.allocate(2500_00, b.charges, AllocationMode.KeepAsCredit))
    }

    @Test
    fun overpaymentAllocatesOutstandingAndKeepsRestAsCredit() {
        val open = FinanceCalculator.balance(1, listOf(jan), emptyList(), emptyList()).charges
        val split = FinanceCalculator.allocate(1200_00, open, AllocationMode.OldestFirst)
        assertEquals(mapOf(1L to 1000_00L), split)
        val b = FinanceCalculator.balance(1, listOf(jan), listOf(payment(1, 1200_00)), listOf(alloc(1, 1, 1, 1000_00)))
        assertEquals(0L, b.outstandingPaise)
        assertEquals(200_00L, b.creditPaise)
        assertEquals(-200_00L, b.netPaise)
    }

    @Test
    fun allocationNeverExceedsAvailableOrOutstanding() {
        val partlyPaid = FinanceCalculator.balance(1, listOf(jan), listOf(payment(1, 300_00)), listOf(alloc(1, 1, 1, 300_00))).charges
        assertEquals(mapOf(1L to 700_00L), FinanceCalculator.allocate(10_000_00, partlyPaid, AllocationMode.OldestFirst))
        assertEquals(mapOf(1L to 1L), FinanceCalculator.allocate(1, partlyPaid, AllocationMode.OldestFirst))
        assertEquals(emptyMap<Long, Long>(), FinanceCalculator.allocate(0, partlyPaid, AllocationMode.OldestFirst))
        assertThrows(IllegalArgumentException::class.java) { FinanceCalculator.allocate(-1, partlyPaid, AllocationMode.OldestFirst) }
    }

    @Test
    fun reversedPaymentRestoresOutstandingAndReallocationWorks() {
        val allocations = listOf(alloc(1, 1, 1, 1000_00), alloc(2, 1, 1, -1000_00, reverses = 1))
        val reversed = FinanceCalculator.balance(1, listOf(jan), listOf(payment(1, 1000_00, PaymentStatus.REVERSED)), allocations)
        assertEquals(1000_00L, reversed.outstandingPaise)
        assertEquals(0L, reversed.creditPaise)
        // A new payment can then be allocated to the restored charge.
        val again = FinanceCalculator.allocate(1000_00, reversed.charges, AllocationMode.OldestFirst)
        assertEquals(mapOf(1L to 1000_00L), again)
    }

    @Test
    fun unlinkedOrReversedPaymentsDoNotCount() {
        val b = FinanceCalculator.balance(1, listOf(jan), listOf(payment(1, 500_00, tenancyId = null), payment(2, 700_00, PaymentStatus.REVERSED)), emptyList())
        assertEquals(1000_00L, b.netPaise)
        assertEquals(0L, b.creditPaise)
    }

    @Test
    fun ledgerCreditsAndReversals() {
        val ledger = listOf(jan, feb, entry(10, LedgerEntryType.CREDIT, -300_00), entry(11, LedgerEntryType.REVERSAL, -1000_00, reverses = 2), entry(12, LedgerEntryType.ADJUSTMENT, 150_00))
        val b = FinanceCalculator.balance(1, ledger, emptyList(), emptyList())
        assertEquals(1000_00L + 150_00L, b.outstandingPaise) // feb cancelled by its reversal
        assertEquals(300_00L, b.creditPaise)
        assertEquals(ledger.sumOf { it.amountPaise }, b.netPaise)
    }

    @Test
    fun overdueOnlyCountsChargesPastDue() {
        val b = FinanceCalculator.balance(1, listOf(jan, feb), emptyList(), emptyList())
        assertEquals(0L, b.overduePaise(LocalDate.parse("2026-01-05")))
        assertEquals(1000_00L, b.overduePaise(LocalDate.parse("2026-01-06")))
        assertEquals(2000_00L, b.overduePaise(LocalDate.parse("2026-03-01")))
    }

    @Test
    fun otherTenanciesAreIgnored() {
        val other = jan.copy(id = 9, tenancyId = 2)
        val b = FinanceCalculator.balance(1, listOf(jan, other), listOf(payment(5, 100_00, tenancyId = 2)), emptyList())
        assertEquals(1000_00L, b.netPaise)
    }

    @Test
    fun depositBalanceIsSeparateAndSigned() {
        fun d(type: DepositTransactionType, amount: Long) = DepositTransaction(0, 1, type, amount, LocalDate.parse("2026-01-01"), null)
        assertEquals(10000_00L, FinanceCalculator.depositBalance(listOf(d(DepositTransactionType.RECEIVED, 10000_00))))
        assertEquals(
            10000_00L - 1500_00 - 8500_00,
            FinanceCalculator.depositBalance(
                listOf(d(DepositTransactionType.RECEIVED, 10000_00), d(DepositTransactionType.DEDUCTION, 1500_00), d(DepositTransactionType.REFUND, 8500_00)),
            ),
        )
        assertEquals(9000_00L, FinanceCalculator.depositBalance(listOf(d(DepositTransactionType.RECEIVED, 10000_00), d(DepositTransactionType.ADJUSTMENT, -1000_00))))
    }
}
