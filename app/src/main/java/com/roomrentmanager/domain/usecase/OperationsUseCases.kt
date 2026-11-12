package com.roomrentmanager.domain.usecase

import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.model.Expense
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.MaintenanceRequest
import com.roomrentmanager.domain.model.MaintenanceStatus
import com.roomrentmanager.domain.repository.AuditRepository
import com.roomrentmanager.domain.repository.OperationsRepository
import com.roomrentmanager.domain.repository.PropertyRepository
import com.roomrentmanager.domain.repository.TenancyRepository
import com.roomrentmanager.domain.repository.TransactionRunner
import java.time.Clock
import java.time.LocalDate

object ExpenseCategories {
    val ALL = listOf(
        "Repairs", "Maintenance", "Electricity", "Water", "Property tax", "Society / association",
        "Insurance", "Cleaning", "Staff salary", "Legal / brokerage", "Furnishing", "Other",
    )
}

data class ExpenseInput(
    val propertyId: Long,
    /** Null when the expense is for the whole property. */
    val roomId: Long?,
    val category: String,
    val amountPaise: Long,
    val date: LocalDate,
    val description: String,
    val vendor: String? = null,
)

/**
 * Property and room expenses. Expenses can be corrected (every edit is audited with the old and new
 * amount) but are never deleted.
 */
class ManageExpensesUseCase(
    private val tx: TransactionRunner,
    private val properties: PropertyRepository,
    private val operations: OperationsRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    suspend fun save(id: Long?, input: ExpenseInput): Long = tx.inTransaction {
        val property = properties.getProperty(input.propertyId) ?: fail("Choose a property.")
        if (input.roomId != null) {
            val room = properties.getRoom(input.roomId) ?: fail("This room no longer exists.")
            if (room.propertyId != property.id) fail("'${room.name}' is not in '${property.name}'.")
        }
        val category = input.category.cleaned() ?: fail("Choose a category.")
        if (input.amountPaise !in 1..Money.MAX_PAISE) fail("Enter an amount greater than zero.")
        if (input.date.isAfter(LocalDate.now(clock))) fail("The expense date cannot be in the future.")
        val description = input.description.cleaned() ?: fail("Describe the expense.")
        val expense = Expense(id ?: 0, property.id, input.roomId, category, input.amountPaise, input.date, description, input.vendor.cleaned())
        if (id == null) {
            if (!property.isActive) fail("'${property.name}' is archived.")
            val newId = operations.insertExpense(expense)
            audit.log("EXPENSE_RECORDED", "EXPENSE", newId, "Expense ${Money.format(input.amountPaise)} ($category) for '${property.name}' on ${input.date}.")
            newId
        } else {
            val old = operations.getExpense(id) ?: fail("This expense no longer exists.")
            operations.updateExpense(expense)
            audit.log(
                "EXPENSE_CORRECTED", "EXPENSE", id,
                "Expense corrected: ${Money.format(old.amountPaise)} ${old.category} on ${old.date} -> " +
                    "${Money.format(input.amountPaise)} $category on ${input.date}.",
            )
            id
        }
    }
}

data class MaintenanceInput(
    val propertyId: Long,
    val roomId: Long?,
    val title: String,
    val description: String,
    val priority: MaintenancePriority,
    val status: MaintenanceStatus,
    val reportedDate: LocalDate,
    val completedDate: LocalDate?,
    val costPaise: Long?,
    /**
     * When the request becomes COMPLETED with a cost, also record that cost as a "Maintenance"
     * expense, so it appears in income-and-expense reports. Off by default to avoid double counting
     * when the bill is entered separately.
     */
    val recordCostAsExpense: Boolean = false,
)

/** Maintenance requests: create, update, complete. A new request is linked to the room's current tenancy. */
class ManageMaintenanceUseCase(
    private val tx: TransactionRunner,
    private val properties: PropertyRepository,
    private val tenancies: TenancyRepository,
    private val operations: OperationsRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    suspend fun save(id: Long?, input: MaintenanceInput): Long = tx.inTransaction {
        val today = LocalDate.now(clock)
        val property = properties.getProperty(input.propertyId) ?: fail("Choose a property.")
        if (input.roomId != null) {
            val room = properties.getRoom(input.roomId) ?: fail("This room no longer exists.")
            if (room.propertyId != property.id) fail("'${room.name}' is not in '${property.name}'.")
        }
        val title = input.title.cleaned() ?: fail("Enter a short title.")
        if (input.reportedDate.isAfter(today)) fail("The reported date cannot be in the future.")
        val completed = when (input.status) {
            MaintenanceStatus.COMPLETED -> input.completedDate ?: fail("Enter the completion date.")
            else -> null
        }
        if (completed != null && (completed.isBefore(input.reportedDate) || completed.isAfter(today))) {
            fail("The completion date must be between the reported date and today.")
        }
        if (input.costPaise != null && input.costPaise !in 0..Money.MAX_PAISE) fail("Enter a valid cost.")

        val becameCompleted: Boolean
        val requestId = if (id == null) {
            becameCompleted = input.status == MaintenanceStatus.COMPLETED
            val tenancyId = input.roomId?.let { tenancies.activeTenancyForRoom(it)?.id }
            val newId = operations.insertMaintenance(
                MaintenanceRequest(0, property.id, input.roomId, tenancyId, title, input.description.trim(), input.priority,
                    input.status, input.reportedDate, completed, input.costPaise),
            )
            audit.log("MAINTENANCE_CREATED", "MAINTENANCE", newId, "Request '$title' (${input.priority.name.lowercase()}) for '${property.name}'.")
            newId
        } else {
            val old = operations.getMaintenance(id) ?: fail("This request no longer exists.")
            becameCompleted = old.status != MaintenanceStatus.COMPLETED && input.status == MaintenanceStatus.COMPLETED
            val tenancyId = if (old.roomId == input.roomId) old.tenancyId else input.roomId?.let { tenancies.activeTenancyForRoom(it)?.id }
            operations.updateMaintenance(
                old.copy(propertyId = property.id, roomId = input.roomId, tenancyId = tenancyId, title = title, description = input.description.trim(),
                    priority = input.priority, status = input.status, reportedDate = input.reportedDate, completedDate = completed, costPaise = input.costPaise),
            )
            val change = if (old.status != input.status) " Status ${old.status.name.lowercase()} -> ${input.status.name.lowercase()}." else ""
            audit.log("MAINTENANCE_UPDATED", "MAINTENANCE", id, "Updated '$title'.$change")
            id
        }
        val cost = input.costPaise
        if (input.recordCostAsExpense && becameCompleted && cost != null && cost > 0) {
            val expenseId = operations.insertExpense(
                Expense(0, property.id, input.roomId, "Maintenance", cost, completed!!, "Maintenance: $title", null),
            )
            audit.log("EXPENSE_RECORDED", "EXPENSE", expenseId, "Maintenance cost ${Money.format(cost)} for '$title' recorded as an expense.")
        }
        requestId
    }
}
