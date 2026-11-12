package com.roomrentmanager.domain

import com.roomrentmanager.domain.finance.AgreementTerms
import com.roomrentmanager.domain.finance.BillingCycleCalculator
import com.roomrentmanager.domain.finance.BillingCycleCalculator.billingDate
import com.roomrentmanager.domain.finance.BillingCycleCalculator.plannedCharges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BillingCycleCalculatorTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun dueDates(moveIn: String, months: Int): List<LocalDate> {
        val start = d(moveIn)
        val terms = AgreementTerms(1, start, null, 100_00, start.dayOfMonth)
        val asOf = start.plusMonths(months.toLong())
        return plannedCharges(1, start, null, listOf(terms), asOf).map { it.period.dueDate }
    }

    @Test
    fun moveInOnFirst() {
        assertEquals(
            listOf(d("2026-01-01"), d("2026-02-01"), d("2026-03-01")),
            dueDates("2026-01-01", 2),
        )
    }

    @Test
    fun moveInOnFifteenth() {
        assertEquals(listOf(d("2026-01-15"), d("2026-02-15")), dueDates("2026-01-15", 1))
    }

    @Test
    fun moveInOn28th() {
        assertEquals(listOf(d("2026-01-28"), d("2026-02-28"), d("2026-03-28")), dueDates("2026-01-28", 2))
    }

    @Test
    fun moveInOn30thClampsInFebruaryAndRestores() {
        val terms = AgreementTerms(1, d("2026-01-30"), null, 100_00, 30)
        val dates = plannedCharges(1, d("2026-01-30"), null, listOf(terms), d("2026-04-30")).map { it.period.start }
        assertEquals(listOf(d("2026-01-30"), d("2026-02-28"), d("2026-03-30"), d("2026-04-30")), dates)
    }

    @Test
    fun moveInOn31stClampsAndRestoresOriginalAnchor() {
        val terms = AgreementTerms(1, d("2026-01-31"), null, 100_00, 31)
        val dates = plannedCharges(1, d("2026-01-31"), null, listOf(terms), d("2026-05-31")).map { it.period.start }
        assertEquals(
            listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30"), d("2026-05-31")),
            dates,
        )
    }

    @Test
    fun leapYearFebruary() {
        assertEquals(d("2028-02-29"), billingDate(YearMonth.of(2028, 2), 31))
        assertEquals(d("2028-02-29"), billingDate(YearMonth.of(2028, 2), 29))
        assertEquals(d("2027-02-28"), billingDate(YearMonth.of(2027, 2), 29))
    }

    @Test
    fun periodsAreContiguousAndCoverEveryDay() {
        val terms = AgreementTerms(1, d("2026-01-31"), null, 100_00, 31)
        val periods = plannedCharges(1, d("2026-01-31"), null, listOf(terms), d("2027-01-31")).map { it.period }
        assertEquals(13, periods.size)
        periods.zipWithNext().forEach { (a, b) -> assertEquals(a.end.plusDays(1), b.start) }
        assertEquals(d("2026-02-27"), periods[0].end)
        assertEquals(d("2026-03-30"), periods[1].end)
    }

    @Test
    fun moveOutBeforeDueDateChargesNothingForThatPeriod() {
        val terms = AgreementTerms(1, d("2026-01-10"), null, 100_00, 10)
        val charges = plannedCharges(1, d("2026-01-10"), d("2026-03-05"), listOf(terms), d("2026-12-31"))
        assertEquals(listOf(d("2026-01-10"), d("2026-02-10")), charges.map { it.period.start })
    }

    @Test
    fun moveOutOnDueDateChargesNothingForThatPeriod() {
        val terms = AgreementTerms(1, d("2026-01-10"), null, 100_00, 10)
        val charges = plannedCharges(1, d("2026-01-10"), d("2026-03-10"), listOf(terms), d("2026-12-31"))
        assertEquals(listOf(d("2026-01-10"), d("2026-02-10")), charges.map { it.period.start })
    }

    @Test
    fun moveOutAfterDueDateChargesThatPeriodInFull() {
        val terms = AgreementTerms(1, d("2026-01-10"), null, 100_00, 10)
        val charges = plannedCharges(1, d("2026-01-10"), d("2026-03-11"), listOf(terms), d("2026-12-31"))
        assertEquals(listOf(d("2026-01-10"), d("2026-02-10"), d("2026-03-10")), charges.map { it.period.start })
        assertTrue(charges.all { it.amountPaise == 100_00L })
    }

    @Test
    fun onlyChargesDueOnOrBeforeAsOf() {
        val terms = AgreementTerms(1, d("2026-01-10"), null, 100_00, 10)
        assertEquals(2, plannedCharges(1, d("2026-01-10"), null, listOf(terms), d("2026-02-10")).size)
        assertEquals(1, plannedCharges(1, d("2026-01-10"), null, listOf(terms), d("2026-02-09")).size)
        assertEquals(0, plannedCharges(1, d("2026-01-10"), null, listOf(terms), d("2026-01-09")).size)
    }

    @Test
    fun agreementChangeAtCycleBoundaryKeepsOldRateForOldPeriods() {
        val first = AgreementTerms(1, d("2026-01-31"), d("2026-03-30"), 100_00, 31)
        val second = AgreementTerms(2, d("2026-03-31"), null, 120_00, 31)
        val charges = plannedCharges(7, d("2026-01-31"), null, listOf(second, first), d("2026-05-31"))
        assertEquals(
            listOf(
                Triple(d("2026-01-31"), 1L, 100_00L),
                Triple(d("2026-02-28"), 1L, 100_00L),
                Triple(d("2026-03-31"), 2L, 120_00L),
                Triple(d("2026-04-30"), 2L, 120_00L),
                Triple(d("2026-05-31"), 2L, 120_00L),
            ),
            charges.map { Triple(it.period.start, it.agreementId, it.amountPaise) },
        )
        assertEquals("rent:7:2:2026-03-31", charges[2].sourceKey)
        assertEquals(charges.size, charges.map { it.sourceKey }.toSet().size)
    }

    @Test
    fun midCycleAgreementChangeIsRejectedNotProrated() {
        val first = AgreementTerms(1, d("2026-01-10"), d("2026-02-19"), 100_00, 10)
        val second = AgreementTerms(2, d("2026-02-20"), null, 120_00, 10)
        val problems = BillingCycleCalculator.validate(d("2026-01-10"), null, listOf(first, second))
        assertTrue(problems.any { "splits a billing period" in it })
        assertTrue(problems.any { "not a billing date" in it })
        assertThrows(IllegalArgumentException::class.java) {
            plannedCharges(1, d("2026-01-10"), null, listOf(first, second), d("2026-06-01"))
        }
    }

    @Test
    fun overlappingAgreementsAreRejected() {
        val first = AgreementTerms(1, d("2026-01-10"), null, 100_00, 10)
        val second = AgreementTerms(2, d("2026-03-10"), null, 120_00, 10)
        assertTrue(BillingCycleCalculator.validate(d("2026-01-10"), null, listOf(first, second)).any { "overlap" in it })
    }

    @Test
    fun invalidInputsAreRejected() {
        val ok = AgreementTerms(1, d("2026-01-10"), null, 100_00, 10)
        assertTrue(BillingCycleCalculator.validate(d("2026-01-10"), d("2026-01-01"), listOf(ok)).isNotEmpty())
        assertTrue(BillingCycleCalculator.validate(d("2026-02-10"), null, listOf(ok)).any { "before move-in" in it })
        assertTrue(BillingCycleCalculator.validate(d("2026-01-10"), null, listOf(ok.copy(rentPaise = 0))).isNotEmpty())
        assertTrue(BillingCycleCalculator.validate(d("2026-01-10"), null, listOf(ok.copy(anchorDay = 32))).isNotEmpty())
        assertTrue(
            BillingCycleCalculator.validate(
                d("2026-01-10"), null, listOf(ok.copy(effectiveTo = d("2026-02-09")), ok.copy(agreementId = 2, effectiveFrom = d("2026-02-10"), anchorDay = 10, rentPaise = 1)),
            ).isEmpty(),
        )
    }

    @Test
    fun agreementStartingAfterMoveInChargesFromItsStart() {
        // Migrated tenancies: move-in is historical, agreement starts later; no charges before it.
        val terms = AgreementTerms(1, d("2026-11-05"), null, 100_00, 5)
        val charges = plannedCharges(1, d("2024-03-17"), null, listOf(terms), d("2026-12-05"))
        assertEquals(listOf(d("2026-11-05"), d("2026-12-05")), charges.map { it.period.start })
    }

    @Test
    fun billingDateHelpers() {
        assertTrue(BillingCycleCalculator.isBillingDate(d("2026-02-28"), 31))
        assertFalse(BillingCycleCalculator.isBillingDate(d("2026-02-27"), 31))
        assertEquals(d("2026-03-31"), BillingCycleCalculator.nextBillingDate(d("2026-02-28"), 31))
        assertEquals(d("2026-02-28"), BillingCycleCalculator.billingDateOnOrAfter(d("2026-02-01"), 31))
    }
}
