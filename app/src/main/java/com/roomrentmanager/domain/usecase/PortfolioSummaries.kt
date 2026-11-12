package com.roomrentmanager.domain.usecase

import com.roomrentmanager.domain.finance.BillingCycleCalculator
import com.roomrentmanager.domain.finance.FinanceCalculator
import com.roomrentmanager.domain.finance.TenancyBalance
import com.roomrentmanager.domain.model.MaintenanceRequest
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.Reminder
import com.roomrentmanager.domain.model.ReminderStatus
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.Property
import com.roomrentmanager.domain.model.RentAgreement
import com.roomrentmanager.domain.model.RentalUnit
import com.roomrentmanager.domain.model.ReviewStatus
import com.roomrentmanager.domain.model.Tenancy
import com.roomrentmanager.domain.model.TenancyStatus
import com.roomrentmanager.domain.model.Tenant
import java.time.LocalDate
import java.time.YearMonth

data class TenancyView(
    val tenancy: Tenancy,
    val tenant: Tenant,
    val room: RentalUnit,
    val property: Property?,
    val agreements: List<RentAgreement>,
    val balance: TenancyBalance,
    val depositHeldPaise: Long,
    /** Next rent due date on or after today, for active tenancies with an agreement. */
    val nextDue: LocalDate?,
) {
    val isActive: Boolean get() = tenancy.status == TenancyStatus.ACTIVE
    val needsAgreement: Boolean get() = isActive && agreements.isEmpty()

    fun agreementOn(date: LocalDate): RentAgreement? =
        agreements.lastOrNull { !it.effectiveFrom.isAfter(date) && (it.effectiveTo == null || !it.effectiveTo.isBefore(date)) }

    /** The agreement in force today, else the latest one. */
    fun currentAgreement(today: LocalDate): RentAgreement? = agreementOn(today) ?: agreements.lastOrNull()
}

data class RoomView(val room: RentalUnit, val property: Property?, val occupant: TenancyView?)

data class MonthFigures(
    val month: YearMonth,
    /** Charges due in the month, less any cancelled amounts. */
    val expectedPaise: Long,
    /** Money received in the month (cash basis), including advance payments. */
    val collectedPaise: Long,
    /** What is still unpaid on the charges due in the month. */
    val outstandingPaise: Long,
)

data class Dashboard(
    val figures: MonthFigures,
    val propertyCount: Int,
    val occupiedRooms: Int,
    val vacantRooms: Int,
    val totalOutstandingPaise: Long,
    val totalOverduePaise: Long,
    val totalCreditPaise: Long,
    /** Tenancies with something owed or due in the selected month, most owed first. */
    val tenancies: List<TenancyView>,
    val upcoming: List<TenancyView>,
    val recentPayments: List<Payment>,
    val pendingReviews: Int,
    val tenanciesNeedingAgreement: Int,
    /** Open or in-progress maintenance, most urgent first. */
    val openMaintenance: List<MaintenanceRequest> = emptyList(),
    /** Reminders raised and not dismissed, newest first. */
    val activeReminders: List<Reminder> = emptyList(),
    val monthExpensesPaise: Long = 0,
)

/** Pure read-model calculations over a [Portfolio] snapshot. */
object PortfolioSummaries {

    fun tenancyViews(p: Portfolio, today: LocalDate): List<TenancyView> {
        val tenants = p.tenants.associateBy { it.id }
        val rooms = p.rooms.associateBy { it.id }
        val properties = p.properties.associateBy { it.id }
        val agreementsByTenancy = p.agreements.groupBy { it.tenancyId }
        val depositsByTenancy = p.deposits.groupBy { it.tenancyId }
        return p.tenancies.mapNotNull { t ->
            val tenant = tenants[t.tenantId] ?: return@mapNotNull null
            val room = rooms[t.roomId] ?: return@mapNotNull null
            val agreements = agreementsByTenancy[t.id].orEmpty().sortedBy { it.effectiveFrom }
            TenancyView(
                tenancy = t,
                tenant = tenant,
                room = room,
                property = properties[room.propertyId],
                agreements = agreements,
                balance = FinanceCalculator.balance(t.id, p.ledger, p.payments, p.allocations),
                depositHeldPaise = FinanceCalculator.depositBalance(depositsByTenancy[t.id].orEmpty()),
                nextDue = nextDue(t, agreements, today),
            )
        }
    }

    private fun nextDue(t: Tenancy, agreements: List<RentAgreement>, today: LocalDate): LocalDate? {
        if (t.status != TenancyStatus.ACTIVE || agreements.isEmpty()) return null
        val start = agreements.first().effectiveFrom
        return if (start.isAfter(today)) start else BillingCycleCalculator.billingDateOnOrAfter(today, agreements.first().anchorDay)
    }

    fun roomViews(p: Portfolio, views: List<TenancyView>): List<RoomView> {
        val active = views.filter { it.isActive }.associateBy { it.room.id }
        val properties = p.properties.associateBy { it.id }
        return p.rooms.map { RoomView(it, properties[it.propertyId], active[it.id]) }
    }

    fun monthFigures(p: Portfolio, views: List<TenancyView>, month: YearMonth): MonthFigures {
        val charges = views.flatMap { it.balance.charges }.filter { it.entry.dueDate != null && YearMonth.from(it.entry.dueDate) == month }
        val collected = p.payments.filter { it.counts }.filter { it.date?.let(YearMonth::from) == month }.sumOf { it.amountPaise }
        return MonthFigures(
            month = month,
            expectedPaise = charges.sumOf { it.entry.amountPaise - it.reversedPaise },
            collectedPaise = collected,
            outstandingPaise = charges.sumOf { it.outstandingPaise },
        )
    }

    fun dashboard(p: Portfolio, views: List<TenancyView>, month: YearMonth, today: LocalDate): Dashboard {
        val activeRooms = p.rooms.filter { it.isActive }
        val occupied = views.filter { it.isActive }.map { it.room.id }.toSet()
        val dueInMonth = views.filter { v ->
            v.balance.outstandingPaise > 0 ||
                v.balance.charges.any { it.entry.dueDate != null && YearMonth.from(it.entry.dueDate) == month }
        }.sortedByDescending { it.balance.netPaise }
        return Dashboard(
            figures = monthFigures(p, views, month),
            propertyCount = p.properties.count { it.isActive },
            occupiedRooms = activeRooms.count { it.id in occupied },
            vacantRooms = activeRooms.count { it.id !in occupied },
            totalOutstandingPaise = views.sumOf { it.balance.outstandingPaise },
            totalOverduePaise = views.sumOf { it.balance.overduePaise(today) },
            totalCreditPaise = views.sumOf { it.balance.creditPaise },
            tenancies = dueInMonth,
            upcoming = views.filter { it.nextDue != null && !it.nextDue.isAfter(today.plusDays(7)) }.sortedBy { it.nextDue },
            recentPayments = p.payments.filter { it.tenancyId != null }.take(5),
            pendingReviews = p.reviews.count { it.status == ReviewStatus.PENDING },
            tenanciesNeedingAgreement = views.count { it.needsAgreement },
            openMaintenance = p.maintenance.filter { it.isOpen }
                .sortedWith(compareByDescending<MaintenanceRequest> { it.priority.ordinal }.thenBy { it.reportedDate }),
            activeReminders = p.reminders.filter { it.status != ReminderStatus.DISMISSED && !it.dueDate.isBefore(today.minusDays(30)) }
                .sortedWith(compareByDescending<Reminder> { it.dueDate }.thenByDescending { it.id }),
            monthExpensesPaise = p.expenses.filter { YearMonth.from(it.date) == month }.sumOf { it.amountPaise },
        )
    }
}
