package com.roomrentmanager.domain.finance

import java.time.LocalDate
import java.time.YearMonth

/** One billing period. Rent for the period is due on its first day ([dueDate] == [start]). */
data class BillingPeriod(val start: LocalDate, val end: LocalDate) {
    val dueDate: LocalDate get() = start
}

/** The parts of a rent agreement the calculator needs. Dates are inclusive. */
data class AgreementTerms(
    val agreementId: Long,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val rentPaise: Long,
    val anchorDay: Int,
)

/** A rent charge the ledger should contain. [sourceKey] makes posting it idempotent. */
data class PlannedCharge(
    val tenancyId: Long,
    val agreementId: Long,
    val period: BillingPeriod,
    val amountPaise: Long,
    val sourceKey: String,
)

/**
 * Pure anniversary-billing rules. Every input date is explicit, so this is fully unit-testable.
 *
 * Rules:
 * - Billing dates are the [anchorDay] of every month. If a month is shorter than the anchor day,
 *   the month's last day is used, and later months return to the anchor day
 *   (anchor 31: Jan 31, Feb 28/29, Mar 31, Apr 30, ...).
 * - A period runs from one billing date up to the day before the next. Rent is due in advance,
 *   on the period's first day.
 * - Agreements change only on billing dates. Mid-period changes are rejected rather than
 *   prorated (no proration policy has been approved yet), so a period is never split.
 * - The move-out date is the handover day and is not an occupied day: a period is charged only
 *   if it starts before the move-out date. A period that has started is charged in full.
 * - Only periods whose due date is on or before `asOf` are charged.
 */
object BillingCycleCalculator {

    fun billingDate(month: YearMonth, anchorDay: Int): LocalDate {
        require(anchorDay in 1..31) { "Anchor day must be 1-31, was $anchorDay" }
        return month.atDay(minOf(anchorDay, month.lengthOfMonth()))
    }

    fun isBillingDate(date: LocalDate, anchorDay: Int): Boolean =
        date == billingDate(YearMonth.from(date), anchorDay)

    /** The first billing date strictly after [date]. */
    fun nextBillingDate(date: LocalDate, anchorDay: Int): LocalDate {
        val sameMonth = billingDate(YearMonth.from(date), anchorDay)
        return if (sameMonth.isAfter(date)) sameMonth else billingDate(YearMonth.from(date).plusMonths(1), anchorDay)
    }

    /** The first billing date on or after [date]. */
    fun billingDateOnOrAfter(date: LocalDate, anchorDay: Int): LocalDate =
        if (isBillingDate(date, anchorDay)) date else nextBillingDate(date, anchorDay)

    fun periodStartingOn(start: LocalDate, anchorDay: Int): BillingPeriod {
        require(isBillingDate(start, anchorDay)) { "$start is not a billing date for anchor day $anchorDay" }
        return BillingPeriod(start, nextBillingDate(start, anchorDay).minusDays(1))
    }

    /**
     * Checks a tenancy's agreements. Returns a list of problems; empty means valid.
     * Agreements must share one anchor day, start on billing dates no earlier than move-in,
     * not overlap, and (when closed) end the day before a billing date.
     */
    fun validate(moveIn: LocalDate, moveOut: LocalDate?, agreements: List<AgreementTerms>): List<String> {
        val problems = mutableListOf<String>()
        if (moveOut != null && moveOut.isBefore(moveIn)) problems += "Move-out $moveOut is before move-in $moveIn"
        val sorted = agreements.sortedBy { it.effectiveFrom }
        val anchors = sorted.map { it.anchorDay }.distinct()
        if (anchors.size > 1) problems += "Agreements use different anchor days $anchors"
        sorted.forEachIndexed { i, a ->
            if (a.anchorDay !in 1..31) {
                problems += "Agreement ${a.agreementId}: anchor day ${a.anchorDay} is not 1-31"
                return@forEachIndexed
            }
            if (a.rentPaise <= 0) problems += "Agreement ${a.agreementId}: rent must be positive"
            if (a.effectiveFrom.isBefore(moveIn)) problems += "Agreement ${a.agreementId} starts before move-in"
            if (!isBillingDate(a.effectiveFrom, a.anchorDay)) {
                problems += "Agreement ${a.agreementId} starts on ${a.effectiveFrom}, which is not a billing date"
            }
            val to = a.effectiveTo
            if (to != null) {
                if (to.isBefore(a.effectiveFrom)) problems += "Agreement ${a.agreementId} ends before it starts"
                if (!isBillingDate(to.plusDays(1), a.anchorDay)) {
                    problems += "Agreement ${a.agreementId} ends on $to, which splits a billing period"
                }
            }
            val next = sorted.getOrNull(i + 1)
            if (next != null && (to == null || !to.isBefore(next.effectiveFrom))) {
                problems += "Agreements ${a.agreementId} and ${next.agreementId} overlap"
            }
        }
        return problems
    }

    /** Every rent charge due on or before [asOf]. Throws [IllegalArgumentException] for invalid input. */
    fun plannedCharges(
        tenancyId: Long,
        moveIn: LocalDate,
        moveOut: LocalDate?,
        agreements: List<AgreementTerms>,
        asOf: LocalDate,
    ): List<PlannedCharge> {
        val problems = validate(moveIn, moveOut, agreements)
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val charges = mutableListOf<PlannedCharge>()
        for (a in agreements.sortedBy { it.effectiveFrom }) {
            var start = a.effectiveFrom
            while (true) {
                if (start.isAfter(asOf)) break
                if (a.effectiveTo != null && start.isAfter(a.effectiveTo)) break
                if (moveOut != null && !start.isBefore(moveOut)) break
                val period = periodStartingOn(start, a.anchorDay)
                charges += PlannedCharge(
                    tenancyId = tenancyId,
                    agreementId = a.agreementId,
                    period = period,
                    amountPaise = a.rentPaise,
                    sourceKey = rentSourceKey(tenancyId, a.agreementId, period.start),
                )
                start = period.end.plusDays(1)
            }
        }
        return charges
    }

    fun rentSourceKey(tenancyId: Long, agreementId: Long, periodStart: LocalDate): String =
        "rent:$tenancyId:$agreementId:$periodStart"
}
