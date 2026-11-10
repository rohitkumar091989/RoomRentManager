package com.roomrentmanager.domain.usecase

import com.roomrentmanager.domain.finance.FinanceCalculator
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.PaymentStatus
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.Property
import com.roomrentmanager.domain.model.RentalUnit
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** An inclusive date range. */
data class Period(val from: LocalDate, val to: LocalDate) {
    init {
        require(!to.isBefore(from)) { "Period ends before it starts" }
    }

    operator fun contains(date: LocalDate?): Boolean = date != null && !date.isBefore(from) && !date.isAfter(to)

    val months: List<YearMonth>
        get() = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.toList()

    companion object {
        fun month(month: YearMonth) = Period(month.atDay(1), month.atEndOfMonth())
        fun year(year: Int) = Period(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

        /** Indian financial year starting 1 April of [startYear]. */
        fun financialYear(startYear: Int) = Period(LocalDate.of(startYear, 4, 1), LocalDate.of(startYear + 1, 3, 31))
    }
}

data class OutstandingRow(
    val tenancy: TenancyView,
    /** Unpaid amounts by how many days past their due date. */
    val notYetDuePaise: Long,
    val days1To30Paise: Long,
    val days31To60Paise: Long,
    val days61To90Paise: Long,
    val over90Paise: Long,
) {
    val outstandingPaise: Long get() = tenancy.balance.outstandingPaise
    val creditPaise: Long get() = tenancy.balance.creditPaise
    val netPaise: Long get() = tenancy.balance.netPaise
}

data class StatementLine(
    val date: LocalDate?,
    val description: String,
    /** Increases what the tenant owes. */
    val debitPaise: Long,
    /** Decreases what the tenant owes. */
    val creditPaise: Long,
    /** Running balance after this line: positive = owed, negative = advance credit. */
    val balancePaise: Long,
)

data class DepositLine(val date: LocalDate, val type: DepositTransactionType, val signedPaise: Long, val note: String?, val heldPaise: Long)

data class PropertyIncomeRow(
    val property: Property,
    /** Rent and other tenant payments received in the period (cash basis, excludes deposits). */
    val rentReceivedPaise: Long,
    val expensesPaise: Long,
    val expensesByCategory: Map<String, Long>,
) {
    val netPaise: Long get() = rentReceivedPaise - expensesPaise
}

data class OccupancyRow(val property: Property, val activeUnits: Int, val occupied: Int, val vacantUnits: List<RentalUnit>) {
    val vacant: Int get() = vacantUnits.size

    /** Whole-number percentage, or null when the property has no active units. */
    val occupancyPercent: Int? get() = if (activeUnits == 0) null else occupied * 100 / activeUnits
}

/** Pure report calculations over a [Portfolio] snapshot and its [TenancyView]s. */
object Reports {

    fun rentCollection(p: Portfolio, views: List<TenancyView>, period: Period): List<MonthFigures> =
        period.months.map { PortfolioSummaries.monthFigures(p, views, it) }

    fun outstanding(views: List<TenancyView>, today: LocalDate): List<OutstandingRow> =
        views.filter { it.balance.outstandingPaise > 0 || it.balance.creditPaise > 0 }.map { v ->
            val buckets = LongArray(5)
            v.balance.charges.filter { it.isOpen }.forEach { c ->
                val days = c.entry.dueDate?.let { ChronoUnit.DAYS.between(it, today) } ?: 0
                val i = when {
                    days <= 0 -> 0
                    days <= 30 -> 1
                    days <= 60 -> 2
                    days <= 90 -> 3
                    else -> 4
                }
                buckets[i] += c.outstandingPaise
            }
            OutstandingRow(v, buckets[0], buckets[1], buckets[2], buckets[3], buckets[4])
        }.sortedByDescending { it.netPaise }

    /**
     * Chronological statement for one tenancy: charges, credits, payments and payment reversals,
     * with a running balance. The final balance always equals the tenancy's net balance.
     */
    fun tenantStatement(p: Portfolio, tenancyId: Long, zone: ZoneId): List<StatementLine> {
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        data class Event(val date: LocalDate?, val order: Int, val id: Long, val description: String, val signed: Long)

        val events = mutableListOf<Event>()
        p.ledger.filter { it.tenancyId == tenancyId }.forEach {
            events += Event(it.dueDate ?: day(it.createdAt), 0, it.id, it.description, it.amountPaise)
        }
        p.payments.filter { it.tenancyId == tenancyId }.forEach { pay ->
            events += Event(pay.date, 1, pay.id, "Payment received (${pay.method.name.lowercase().replace('_', ' ')})", -pay.amountPaise)
            if (pay.status == PaymentStatus.REVERSED) {
                events += Event(pay.reversedAt?.let(::day) ?: pay.date, 2, pay.id, "Payment reversed: ${pay.reversalReason ?: ""}".trim(), pay.amountPaise)
            }
        }
        var balance = 0L
        return events.sortedWith(compareBy<Event, LocalDate?>(nullsFirst()) { it.date }.thenBy { it.order }.thenBy { it.id }).map {
            balance += it.signed
            StatementLine(it.date, it.description, maxOf(it.signed, 0), maxOf(-it.signed, 0), balance)
        }
    }

    fun depositStatement(p: Portfolio, tenancyId: Long): List<DepositLine> {
        var held = 0L
        return p.deposits.filter { it.tenancyId == tenancyId }.sortedWith(compareBy({ it.date }, { it.id })).map {
            val signed = FinanceCalculator.depositBalance(listOf(it))
            held += signed
            DepositLine(it.date, it.type, signed, it.note, held)
        }
    }

    /** Per-property cash received versus expenses. Properties with no activity and archived are omitted. */
    fun incomeAndExpenses(p: Portfolio, period: Period): List<PropertyIncomeRow> {
        val propertyOfRoom = p.rooms.associate { it.id to it.propertyId }
        val propertyOfTenancy = p.tenancies.associate { it.id to propertyOfRoom[it.roomId] }
        val received = p.payments.filter { it.counts && it.date in period }
            .groupBy { propertyOfTenancy[it.tenancyId] }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        val expenses = p.expenses.filter { it.date in period }.groupBy { it.propertyId }
        return p.properties.map { prop ->
            val list = expenses[prop.id].orEmpty()
            PropertyIncomeRow(
                property = prop,
                rentReceivedPaise = received[prop.id] ?: 0,
                expensesPaise = list.sumOf { it.amountPaise },
                expensesByCategory = list.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }.toSortedMap(),
            )
        }.filter { it.property.isActive || it.rentReceivedPaise != 0L || it.expensesPaise != 0L }
    }

    fun occupancy(p: Portfolio, views: List<TenancyView>): List<OccupancyRow> {
        val occupied = views.filter { it.isActive }.map { it.room.id }.toSet()
        return p.properties.filter { it.isActive }.map { prop ->
            val units = p.rooms.filter { it.propertyId == prop.id && it.isActive }
            OccupancyRow(prop, units.size, units.count { it.id in occupied }, units.filter { it.id !in occupied })
        }
    }
}
