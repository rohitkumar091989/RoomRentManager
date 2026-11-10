package com.roomrentmanager.domain.usecase

import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.Reminder
import com.roomrentmanager.domain.model.ReminderStatus
import com.roomrentmanager.domain.model.TenancyStatus
import com.roomrentmanager.domain.repository.OperationsRepository
import com.roomrentmanager.domain.repository.PortfolioRepository
import com.roomrentmanager.domain.repository.TransactionRunner
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ReminderSettings(
    val enabled: Boolean = true,
    /** Remind this many days before rent is due (0 = on the day). */
    val daysBeforeDue: Int = 2,
    val overdue: Boolean = true,
    /** After move-out, remind about a deposit still held once this many days have passed. */
    val depositFollowUpDays: Int = 14,
    /** Remind about open maintenance every this many days (urgent/high: from the day reported). */
    val maintenanceFollowUpDays: Int = 7,
) {
    init {
        require(daysBeforeDue in 0..15 && depositFollowUpDays in 1..90 && maintenanceFollowUpDays in 1..60)
    }
}

object ReminderKinds {
    const val RENT_DUE = "RENT_DUE"
    const val RENT_OVERDUE = "RENT_OVERDUE"
    const val DEPOSIT_FOLLOW_UP = "DEPOSIT_FOLLOW_UP"
    const val MAINTENANCE_FOLLOW_UP = "MAINTENANCE_FOLLOW_UP"
}

/**
 * Works out which reminders are due from persisted tenancy, agreement, ledger, deposit and
 * maintenance data. Each reminder has a deterministic key, so the same reminder is raised once:
 * - rent due:   `due:{tenancy}:{dueDate}`
 * - overdue:    `overdue:{tenancy}:{oldest unpaid due date}` (a new one once that charge is paid)
 * - deposit:    `deposit:{tenancy}`
 * - maintenance:`maint:{request}:{follow-up number}`
 * Planning never creates or changes charges.
 */
object ReminderPlanner {

    fun plan(p: Portfolio, views: List<TenancyView>, today: LocalDate, settings: ReminderSettings): List<Reminder> {
        if (!settings.enabled) return emptyList()
        val out = mutableListOf<Reminder>()
        fun add(key: String, kind: String, type: String, id: Long, due: LocalDate, message: String) {
            out += Reminder(0, key, kind, type, id, due, message, ReminderStatus.PENDING)
        }

        for (v in views) {
            val id = v.tenancy.id
            val next = v.nextDue
            val rent = next?.let { v.agreementOn(it)?.rentPaise }
            if (next != null && rent != null && !next.isAfter(today.plusDays(settings.daysBeforeDue.toLong()))) {
                val whenText = if (next == today) "today" else "on $next"
                add("due:$id:$next", ReminderKinds.RENT_DUE, "TENANCY", id, next,
                    "Rent ${Money.format(rent)} from ${v.tenant.name} (${v.room.name}) is due $whenText.")
            }
            if (settings.overdue) {
                val oldest = v.balance.charges.firstOrNull { it.isOpen && it.entry.dueDate?.isBefore(today) == true }
                if (oldest != null) {
                    add("overdue:$id:${oldest.entry.dueDate}", ReminderKinds.RENT_OVERDUE, "TENANCY", id, oldest.entry.dueDate!!,
                        "${v.tenant.name} (${v.room.name}) has ${Money.format(v.balance.overduePaise(today))} overdue since ${oldest.entry.dueDate}.")
                }
            }
            val moveOut = v.tenancy.moveOut
            if (v.tenancy.status == TenancyStatus.ENDED && moveOut != null && v.depositHeldPaise > 0) {
                val due = moveOut.plusDays(settings.depositFollowUpDays.toLong())
                if (!due.isAfter(today)) {
                    add("deposit:$id", ReminderKinds.DEPOSIT_FOLLOW_UP, "TENANCY", id, due,
                        "${Money.format(v.depositHeldPaise)} deposit is still held for ${v.tenant.name}, who moved out on $moveOut.")
                }
            }
        }

        val rooms = p.rooms.associateBy { it.id }
        for (m in p.maintenance.filter { it.isOpen }) {
            val days = ChronoUnit.DAYS.between(m.reportedDate, today)
            val urgent = m.priority == MaintenancePriority.URGENT || m.priority == MaintenancePriority.HIGH
            val n = days / settings.maintenanceFollowUpDays
            if (n >= 1 || urgent) {
                val where = m.roomId?.let { rooms[it]?.name } ?: p.properties.firstOrNull { it.id == m.propertyId }?.name ?: ""
                add("maint:${m.id}:$n", ReminderKinds.MAINTENANCE_FOLLOW_UP, "MAINTENANCE", m.id, today,
                    "Maintenance '${m.title}' ($where, ${m.priority.name.lowercase()}) is still ${m.status.name.lowercase().replace('_', ' ')} after $days days.")
            }
        }
        return out
    }

    /**
     * A message the landlord can send a tenant from their own phone (SMS, WhatsApp, ...).
     * The app sends nothing itself; there is no backend.
     */
    fun tenantMessage(v: TenancyView, today: LocalDate): String {
        val owed = v.balance.netPaise
        val next = v.nextDue
        val rent = next?.let { v.agreementOn(it)?.rentPaise }
        val place = listOfNotNull(v.room.name, v.property?.name).joinToString(", ")
        return buildString {
            append("Hello ${v.tenant.name}, ")
            when {
                owed > 0 && v.balance.overduePaise(today) > 0 ->
                    append("a reminder that ${Money.format(owed)} is outstanding for $place, including ${Money.format(v.balance.overduePaise(today))} overdue.")
                owed > 0 -> append("a reminder that ${Money.format(owed)} is due for $place.")
                rent != null -> append("a reminder that the rent of ${Money.format(rent)} for $place is due on $next.")
                else -> append("this is a note about $place.")
            }
            if (owed < 0) append(" You have an advance credit of ${Money.format(-owed)}.")
            append(" Thank you.")
        }
    }
}

/** Plans reminders and stores the new ones. Returns only reminders raised for the first time. */
class RunRemindersUseCase(
    private val tx: TransactionRunner,
    private val portfolio: PortfolioRepository,
    private val operations: OperationsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(settings: ReminderSettings): List<Reminder> {
        val today = LocalDate.now(clock)
        val p = portfolio.observePortfolio().first()
        val planned = ReminderPlanner.plan(p, PortfolioSummaries.tenancyViews(p, today), today, settings)
        return tx.inTransaction {
            val existing = operations.reminderKeys()
            planned.filter { it.key !in existing }.mapNotNull { r -> operations.insertReminder(r)?.let { r.copy(id = it) } }
        }
    }

    suspend fun setStatus(id: Long, status: ReminderStatus) = operations.setReminderStatus(id, status)
}
