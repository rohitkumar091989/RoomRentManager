package com.roomrentmanager.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.model.Expense
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.MaintenanceRequest
import com.roomrentmanager.domain.model.MaintenanceStatus
import com.roomrentmanager.domain.model.ReminderStatus
import com.roomrentmanager.domain.usecase.ExpenseCategories
import com.roomrentmanager.domain.usecase.ExpenseInput
import com.roomrentmanager.domain.usecase.MaintenanceInput
import com.roomrentmanager.domain.usecase.Period
import com.roomrentmanager.domain.usecase.Reports
import com.roomrentmanager.domain.usecase.ReminderSettings
import com.roomrentmanager.ui.components.DateInput
import com.roomrentmanager.ui.components.EmptyText
import com.roomrentmanager.ui.components.FormDialog
import com.roomrentmanager.ui.components.ListCard
import com.roomrentmanager.ui.components.MoneyInput
import com.roomrentmanager.ui.components.Picker
import com.roomrentmanager.ui.components.SectionHeader
import com.roomrentmanager.ui.components.TextInput
import com.roomrentmanager.ui.components.money
import com.roomrentmanager.ui.components.notAfter
import com.roomrentmanager.ui.components.parseDateOrNull
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val padding = PaddingValues(16.dp)
private val spacing = Arrangement.spacedBy(8.dp)
private val fabClearance = 72.dp

/** Opens the system share sheet (SMS, WhatsApp, email, ...). Nothing is sent by the app itself. */
fun shareText(context: Context, text: String, subject: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun MoreScreen(onOpen: (MoreSection) -> Unit) {
    LazyColumn(contentPadding = padding, verticalArrangement = spacing) {
        items(MoreSection.entries) { s -> ListCard(title = s.label, subtitle = s.description, onClick = { onOpen(s) }) }
    }
}

// ---------------------------------------------------------------------------------------------
// Expenses

@Composable
fun ExpensesScreen(state: UiState, show: (DialogState) -> Unit) {
    val p = state.portfolio
    if (p.properties.none { it.isActive } && p.expenses.isEmpty()) {
        EmptyText("Add a property first, then record its expenses here.")
        return
    }
    var propertyId by rememberSaveable { mutableStateOf<Long?>(null) }
    val list = p.expenses.filter { propertyId == null || it.propertyId == propertyId }
    val thisMonth = list.filter { YearMonth.from(it.date) == YearMonth.from(state.today) }.sumOf { it.amountPaise }
    val rooms = p.rooms.associateBy { it.id }
    val properties = p.properties.associateBy { it.id }
    LazyColumn(contentPadding = padding, verticalArrangement = spacing) {
        item {
            Picker("Property", properties[propertyId]?.name ?: "All", listOf<Pair<Long?, String>>(null to "All") + p.properties.map { it.id to it.name }) { propertyId = it }
        }
        item { Text("This month: ${money(thisMonth)} · all time: ${money(list.sumOf { it.amountPaise })}", style = MaterialTheme.typography.bodyMedium) }
        if (list.isEmpty()) item { EmptyText("No expenses yet. Tap + to add one.") }
        items(list, key = { it.id }) { e ->
            val where = listOfNotNull(properties[e.propertyId]?.name, e.roomId?.let { rooms[it]?.name }).joinToString(" · ")
            ListCard(
                title = "${e.category} · ${money(e.amountPaise)}",
                subtitle = "${e.date} · $where",
                detail = listOfNotNull(e.description, e.vendor).joinToString(" · "),
                onClick = { show(DialogState.EditExpense(e.id)) },
            )
        }
        item { Spacer(Modifier.height(fabClearance)) }
    }
}

@Composable
fun ExpenseDialog(expense: Expense?, state: UiState, vm: RentViewModel, onDismiss: () -> Unit) {
    val p = state.portfolio
    val properties = p.properties.filter { it.isActive || it.id == expense?.propertyId }
    var propertyId by rememberSaveable { mutableStateOf(expense?.propertyId ?: properties.firstOrNull()?.id) }
    var roomId by rememberSaveable { mutableStateOf(expense?.roomId) }
    var category by rememberSaveable { mutableStateOf(expense?.category ?: ExpenseCategories.ALL.first()) }
    var amount by rememberSaveable { mutableStateOf(expense?.amountPaise?.let(Money::toInputText) ?: "") }
    var date by rememberSaveable { mutableStateOf((expense?.date ?: state.today).toString()) }
    var description by rememberSaveable { mutableStateOf(expense?.description ?: "") }
    var vendor by rememberSaveable { mutableStateOf(expense?.vendor ?: "") }
    val rooms = p.rooms.filter { it.propertyId == propertyId }
    val paise = Money.parseRupees(amount)
    val dateValue = parseDateOrNull(date)
    FormDialog(
        title = if (expense == null) "Add expense" else "Correct expense",
        canSave = propertyId != null && paise != null && paise > 0 && dateValue != null && description.isNotBlank(),
        onDismiss = onDismiss,
        onSave = { vm.saveExpense(expense?.id, ExpenseInput(propertyId!!, roomId, category, paise!!, dateValue!!, description, vendor), onDismiss) },
    ) {
        Picker("Property", properties.firstOrNull { it.id == propertyId }?.name ?: "Choose", properties.map { it.id to it.name }) {
            if (it != propertyId) roomId = null
            propertyId = it
        }
        Picker("For", rooms.firstOrNull { it.id == roomId }?.name ?: "Whole property",
            listOf<Pair<Long?, String>>(null to "Whole property") + rooms.map { it.id to it.name }) { roomId = it }
        Picker("Category", category, (ExpenseCategories.ALL + listOfNotNull(expense?.category)).distinct().map { it to it }) { category = it }
        MoneyInput(amount, { amount = it }, "Amount")
        DateInput(date, { date = it }, "Date paid", selectable = notAfter(state.today))
        TextInput(description, { description = it }, "Description")
        TextInput(vendor, { vendor = it }, "Paid to (optional)")
        if (expense != null) Text("Corrections are kept in the audit log. Expenses are never deleted.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------------------------
// Maintenance

fun MaintenanceStatus.label() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
fun MaintenancePriority.label() = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
fun MaintenanceScreen(state: UiState, show: (DialogState) -> Unit) {
    val p = state.portfolio
    if (p.properties.none { it.isActive } && p.maintenance.isEmpty()) {
        EmptyText("Add a property first, then track maintenance requests here.")
        return
    }
    val rooms = p.rooms.associateBy { it.id }
    val properties = p.properties.associateBy { it.id }
    val sorted = p.maintenance.sortedWith(compareBy<MaintenanceRequest> { !it.isOpen }.thenByDescending { it.priority.ordinal }.thenByDescending { it.reportedDate })
    LazyColumn(contentPadding = padding, verticalArrangement = spacing) {
        item {
            val open = p.maintenance.filter { it.isOpen }
            Text("${open.size} open · spent on completed jobs: ${money(p.maintenance.filter { it.status == MaintenanceStatus.COMPLETED }.sumOf { it.costPaise ?: 0 })}",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (sorted.isEmpty()) item { EmptyText("No maintenance requests. Tap + to add one.") }
        items(sorted, key = { it.id }) { m ->
            val where = listOfNotNull(properties[m.propertyId]?.name, m.roomId?.let { rooms[it]?.name }).joinToString(" · ")
            ListCard(
                title = m.title,
                subtitle = "${m.status.label()} · ${m.priority.label()} · $where",
                detail = listOfNotNull("Reported ${m.reportedDate}", m.completedDate?.let { "done $it" }, m.costPaise?.let { "cost ${money(it)}" }).joinToString(" · "),
                detailColor = if (m.isOpen && m.priority >= MaintenancePriority.HIGH) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                onClick = { show(DialogState.EditMaintenance(m.id)) },
            )
        }
        item { Spacer(Modifier.height(fabClearance)) }
    }
}

@Composable
fun MaintenanceDialog(request: MaintenanceRequest?, state: UiState, vm: RentViewModel, onDismiss: () -> Unit) {
    val p = state.portfolio
    val properties = p.properties.filter { it.isActive || it.id == request?.propertyId }
    var propertyId by rememberSaveable { mutableStateOf(request?.propertyId ?: properties.firstOrNull()?.id) }
    var roomId by rememberSaveable { mutableStateOf(request?.roomId) }
    var title by rememberSaveable { mutableStateOf(request?.title ?: "") }
    var description by rememberSaveable { mutableStateOf(request?.description ?: "") }
    var priority by rememberSaveable { mutableStateOf(request?.priority ?: MaintenancePriority.NORMAL) }
    var status by rememberSaveable { mutableStateOf(request?.status ?: MaintenanceStatus.OPEN) }
    var reported by rememberSaveable { mutableStateOf((request?.reportedDate ?: state.today).toString()) }
    var completed by rememberSaveable { mutableStateOf((request?.completedDate ?: state.today).toString()) }
    var cost by rememberSaveable { mutableStateOf(request?.costPaise?.let(Money::toInputText) ?: "") }
    var asExpense by rememberSaveable { mutableStateOf(false) }
    val rooms = p.rooms.filter { it.propertyId == propertyId }
    val reportedDate = parseDateOrNull(reported)
    val completedDate = parseDateOrNull(completed)
    val costPaise = if (cost.isBlank()) null else Money.parseRupees(cost)
    val wasCompleted = request?.status == MaintenanceStatus.COMPLETED
    FormDialog(
        title = if (request == null) "New maintenance request" else "Maintenance request",
        canSave = propertyId != null && title.isNotBlank() && reportedDate != null && (cost.isBlank() || costPaise != null) &&
            (status != MaintenanceStatus.COMPLETED || completedDate != null),
        onDismiss = onDismiss,
        onSave = {
            vm.saveMaintenance(
                request?.id,
                MaintenanceInput(propertyId!!, roomId, title, description, priority, status, reportedDate!!,
                    completedDate.takeIf { status == MaintenanceStatus.COMPLETED }, costPaise, asExpense),
                onDismiss,
            )
        },
    ) {
        Picker("Property", properties.firstOrNull { it.id == propertyId }?.name ?: "Choose", properties.map { it.id to it.name }) {
            if (it != propertyId) roomId = null
            propertyId = it
        }
        Picker("Unit", rooms.firstOrNull { it.id == roomId }?.name ?: "Common area / whole property",
            listOf<Pair<Long?, String>>(null to "Common area / whole property") + rooms.map { it.id to it.name }) { roomId = it }
        TextInput(title, { title = it }, "What needs fixing")
        TextInput(description, { description = it }, "Details (optional)")
        Picker("Priority", priority.label(), MaintenancePriority.entries.map { it to it.label() }) { priority = it }
        Picker("Status", status.label(), MaintenanceStatus.entries.map { it to it.label() }) { status = it }
        DateInput(reported, { reported = it }, "Reported on", selectable = notAfter(state.today))
        if (status == MaintenanceStatus.COMPLETED) {
            DateInput(completed, { completed = it }, "Completed on", selectable = { d ->
                !d.isAfter(state.today) && (reportedDate == null || !d.isBefore(reportedDate))
            })
        }
        MoneyInput(cost, { cost = it }, "Cost (optional)", required = false)
        if (status == MaintenanceStatus.COMPLETED && !wasCompleted && (costPaise ?: 0) > 0) {
            Row(Modifier.toggleable(value = asExpense, role = Role.Checkbox, onValueChange = { asExpense = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = asExpense, onCheckedChange = null)
                Text("Also record the cost as an expense", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reports

enum class ReportType(val label: String, val usesPeriod: Boolean, val usesTenancy: Boolean) {
    Collection("Rent collection", true, false),
    Outstanding("Outstanding rent", false, false),
    TenantStatement("Tenant statement", false, true),
    DepositStatement("Deposit statement", false, true),
    IncomeExpenses("Property income and expenses", true, false),
    Occupancy("Occupancy and vacancy", false, false),
}

enum class PeriodKind(val label: String) { Month("Month"), FinancialYear("Financial year (Apr-Mar)"), CalendarYear("Calendar year") }

private val monthLabel = DateTimeFormatter.ofPattern("MMM yyyy")

fun periodFor(kind: PeriodKind, anchor: YearMonth): Pair<Period, String> = when (kind) {
    PeriodKind.Month -> Period.month(anchor) to anchor.format(monthLabel)
    PeriodKind.CalendarYear -> Period.year(anchor.year) to anchor.year.toString()
    PeriodKind.FinancialYear -> {
        val start = if (anchor.monthValue >= 4) anchor.year else anchor.year - 1
        Period.financialYear(start) to "FY $start-${(start + 1) % 100}"
    }
}

/** A report as a plain table, rendered on screen and shared as text from the same rows. */
data class ReportTable(val title: String, val headers: List<String>, val rows: List<List<String>>, val notes: List<String> = emptyList()) {
    fun asText(): String = buildString {
        appendLine(title)
        appendLine(headers.joinToString(" | "))
        rows.forEach { appendLine(it.joinToString(" | ")) }
        notes.forEach { appendLine(it) }
    }
}

fun buildReport(type: ReportType, state: UiState, period: Period, periodLabel: String, tenancyId: Long?): ReportTable {
    val p = state.portfolio
    val views = state.tenancies
    return when (type) {
        ReportType.Collection -> {
            val rows = Reports.rentCollection(p, views, period)
            ReportTable(
                "Rent collection, $periodLabel", listOf("Month", "Due", "Received", "Unpaid"),
                rows.map { listOf(it.month.format(monthLabel), money(it.expectedPaise), money(it.collectedPaise), money(it.outstandingPaise)) } +
                    listOf(listOf("Total", money(rows.sumOf { it.expectedPaise }), money(rows.sumOf { it.collectedPaise }), money(rows.sumOf { it.outstandingPaise }))),
                listOf("Received counts money on the day it arrived, including advance payments. Deposits are not included."),
            )
        }
        ReportType.Outstanding -> {
            val rows = Reports.outstanding(views, state.today)
            ReportTable(
                "Outstanding rent on ${state.today}", listOf("Tenant", "Not yet due", "1-30 days", "31-60", "61-90", "90+", "Credit", "Net"),
                rows.map {
                    listOf(tenancyLabel(it.tenancy), money(it.notYetDuePaise), money(it.days1To30Paise), money(it.days31To60Paise),
                        money(it.days61To90Paise), money(it.over90Paise), money(it.creditPaise), money(it.netPaise))
                },
                listOf("Net = outstanding − credit. A negative net is advance credit held for the tenant."),
            )
        }
        ReportType.TenantStatement -> {
            val v = views.firstOrNull { it.tenancy.id == tenancyId }
            if (v == null) ReportTable("Tenant statement", emptyList(), emptyList(), listOf("Choose a tenancy."))
            else ReportTable(
                "Statement: ${tenancyLabel(v)}", listOf("Date", "Description", "Charge", "Paid / credit", "Balance"),
                Reports.tenantStatement(p, v.tenancy.id, ZoneId.systemDefault()).map {
                    listOf(it.date?.toString() ?: "", it.description, if (it.debitPaise > 0) money(it.debitPaise) else "",
                        if (it.creditPaise > 0) money(it.creditPaise) else "", money(it.balancePaise))
                },
                listOf("Closing balance ${money(v.balance.netPaise)} (positive = owed, negative = advance credit)."),
            )
        }
        ReportType.DepositStatement -> {
            val v = views.firstOrNull { it.tenancy.id == tenancyId }
            if (v == null) ReportTable("Deposit statement", emptyList(), emptyList(), listOf("Choose a tenancy."))
            else ReportTable(
                "Deposit: ${tenancyLabel(v)}", listOf("Date", "Type", "Amount", "Held"),
                Reports.depositStatement(p, v.tenancy.id).map { listOf(it.date.toString(), it.type.label() + (it.note?.let { n -> " ($n)" } ?: ""), money(it.signedPaise), money(it.heldPaise)) },
                listOf("Refundable balance ${money(v.depositHeldPaise)}. Deposits are kept separate from rent."),
            )
        }
        ReportType.IncomeExpenses -> {
            val rows = Reports.incomeAndExpenses(p, period)
            ReportTable(
                "Income and expenses, $periodLabel", listOf("Property", "Received", "Expenses", "Net"),
                rows.map { listOf(it.property.name, money(it.rentReceivedPaise), money(it.expensesPaise), money(it.netPaise)) } +
                    listOf(listOf("Total", money(rows.sumOf { it.rentReceivedPaise }), money(rows.sumOf { it.expensesPaise }), money(rows.sumOf { it.netPaise }))),
                rows.filter { it.expensesByCategory.isNotEmpty() }.map { r ->
                    "${r.property.name}: " + r.expensesByCategory.entries.joinToString(", ") { "${it.key} ${money(it.value)}" }
                } + "Cash basis: money received and spent in the period. Deposits are excluded.",
            )
        }
        ReportType.Occupancy -> {
            val rows = Reports.occupancy(p, views)
            ReportTable(
                "Occupancy on ${state.today}", listOf("Property", "Units", "Occupied", "Vacant", "Occupancy"),
                rows.map { listOf(it.property.name, it.activeUnits.toString(), it.occupied.toString(), it.vacant.toString(), it.occupancyPercent?.let { pct -> "$pct%" } ?: "-") },
                rows.filter { it.vacantUnits.isNotEmpty() }.map { r -> "Vacant in ${r.property.name}: " + r.vacantUnits.joinToString { it.name } },
            )
        }
    }
}

@Composable
fun ReportsScreen(state: UiState) {
    val context = LocalContext.current
    var type by rememberSaveable { mutableStateOf(ReportType.Collection) }
    var kind by rememberSaveable { mutableStateOf(PeriodKind.Month) }
    var anchor by rememberSaveable { mutableStateOf(YearMonth.from(state.today)) }
    var tenancyId by rememberSaveable { mutableStateOf(state.tenancies.firstOrNull()?.tenancy?.id) }
    val (period, periodLabel) = periodFor(kind, anchor)
    val report = buildReport(type, state, period, periodLabel, tenancyId)

    LazyColumn(contentPadding = padding, verticalArrangement = spacing) {
        item { Picker("Report", type.label, ReportType.entries.map { it to it.label }) { type = it } }
        if (type.usesPeriod) {
            item {
                Column(verticalArrangement = spacing) {
                    Picker("Period", kind.label, PeriodKind.entries.map { it to it.label }) { kind = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val step = if (kind == PeriodKind.Month) 1L else 12L
                        IconButton(onClick = { anchor = anchor.minusMonths(step) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous period") }
                        Text(periodLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        IconButton(onClick = { anchor = anchor.plusMonths(step) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next period") }
                    }
                }
            }
        }
        if (type.usesTenancy) {
            item {
                Picker("Tenancy", state.tenancies.firstOrNull { it.tenancy.id == tenancyId }?.let(::tenancyLabel) ?: "Choose",
                    state.tenancies.map { it.tenancy.id to tenancyLabel(it) }) { tenancyId = it }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(report.title)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { shareText(context, report.asText(), report.title) }, enabled = report.rows.isNotEmpty()) { Text("Share") }
            }
        }
        if (report.rows.isEmpty() && report.headers.isNotEmpty()) item { EmptyText("Nothing to show for this report.") }
        val wide = report.headers.size > MAX_TABLE_COLUMNS
        if (report.rows.isNotEmpty() && !wide) item { TableRow(report.headers, header = true) }
        itemsIndexed(report.rows) { _, row ->
            if (wide) StackedRow(report.headers, row) else TableRow(row, header = false)
            HorizontalDivider()
        }
        items(report.notes) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** More columns than this do not fit a phone screen; such rows are stacked instead. */
private const val MAX_TABLE_COLUMNS = 5

/** A wide report row as a block: the first cell as a title, then label/value pairs, two per line. */
@Composable
private fun StackedRow(headers: List<String>, cells: List<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(cells.first(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        headers.drop(1).zip(cells.drop(1)).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, value) ->
                    Row(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, softWrap = false)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One row of a report. The first column is wider; the rest are right-aligned amounts. */
@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    // Each row is read as one item ("Green Villa, ₹15,000.00, ...") rather than cell by cell.
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                modifier = Modifier.weight(if (i == 0) 1.6f else 1f),
                style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                fontWeight = if (header || cell == "Total") FontWeight.SemiBold else null,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reminders

fun notificationsPermitted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
fun RemindersScreen(state: UiState, vm: RentViewModel, openTenant: (Long) -> Unit) {
    val context = LocalContext.current
    val settings by vm.reminderSettings.collectAsState()
    var permitted by rememberSaveable { mutableStateOf(notificationsPermitted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    fun update(s: ReminderSettings) = vm.updateReminderSettings(s)
    val tenantOfTenancy = state.tenancies.associate { it.tenancy.id to it.tenant.id }

    LazyColumn(contentPadding = padding, verticalArrangement = spacing) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingSwitch("Reminders on", settings.enabled) { update(settings.copy(enabled = it)) }
                SettingSwitch("Overdue rent reminders", settings.overdue) { update(settings.copy(overdue = it)) }
                Picker("Remind before rent is due", daysLabel(settings.daysBeforeDue), (0..7).map { it to daysLabel(it) }) { update(settings.copy(daysBeforeDue = it)) }
                Picker("Deposit follow-up after move-out", "${settings.depositFollowUpDays} days", listOf(7, 14, 30, 60).map { it to "$it days" }) {
                    update(settings.copy(depositFollowUpDays = it))
                }
                Picker("Repeat open maintenance every", "${settings.maintenanceFollowUpDays} days", listOf(3, 7, 14, 30).map { it to "$it days" }) {
                    update(settings.copy(maintenanceFollowUpDays = it))
                }
                if (!permitted) {
                    Text("Notifications are off, so reminders only appear in the app.", style = MaterialTheme.typography.bodySmall)
                    if (Build.VERSION.SDK_INT >= 33) {
                        TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                    }
                }
                Text(
                    "Reminders are worked out on this phone once a day and whenever you open the app. They never add charges. " +
                        "To remind a tenant, open them and tap Remind to send a message from your phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val list = state.portfolio.reminders.sortedWith(compareBy<com.roomrentmanager.domain.model.Reminder> { it.status == ReminderStatus.DISMISSED }.thenByDescending { it.dueDate })
        item { SectionHeader("Recent reminders") }
        if (list.isEmpty()) item { EmptyText("No reminders yet.") }
        items(list.take(100), key = { it.id }) { r ->
            ListCard(
                title = r.message,
                subtitle = "${r.dueDate} · ${r.status.name.lowercase()}",
                onClick = if (r.entityType == "TENANCY") tenantOfTenancy[r.entityId]?.let { id -> { openTenant(id) } } else null,
            ) {
                if (r.status != ReminderStatus.DISMISSED) TextButton(onClick = { vm.dismissReminder(r.id) }) { Text("Dismiss") }
            }
        }
    }
}

private fun daysLabel(days: Int) = when (days) {
    0 -> "On the day"
    1 -> "1 day before"
    else -> "$days days before"
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Dashboard rows for reminders and open maintenance. */
@Composable
fun DashboardOperations(state: UiState, vm: RentViewModel, openTenant: (Long) -> Unit, openSection: (MoreSection) -> Unit) {
    val d = state.dashboard ?: return
    val tenantOfTenancy = state.tenancies.associate { it.tenancy.id to it.tenant.id }
    val context = LocalContext.current
    Column(verticalArrangement = spacing) {
        if (d.activeReminders.isNotEmpty() && vm.reminderSettings.value.enabled && !notificationsPermitted(context)) {
            com.roomrentmanager.ui.components.Banner("Notifications are off, so reminders only show here.", "Settings") { openSection(MoreSection.Reminders) }
        }
        if (d.activeReminders.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Reminders")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { openSection(MoreSection.Reminders) }) { Text("All") }
            }
            d.activeReminders.take(3).forEach { r ->
                ListCard(
                    title = r.message,
                    subtitle = r.dueDate.toString(),
                    onClick = if (r.entityType == "TENANCY") tenantOfTenancy[r.entityId]?.let { id -> { openTenant(id) } } else ({ openSection(MoreSection.Maintenance) }),
                ) { TextButton(onClick = { vm.dismissReminder(r.id) }) { Text("Dismiss") } }
            }
        }
        if (d.openMaintenance.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Open maintenance (${d.openMaintenance.size})")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { openSection(MoreSection.Maintenance) }) { Text("All") }
            }
            val rooms = state.portfolio.rooms.associateBy { it.id }
            d.openMaintenance.take(3).forEach { m ->
                ListCard(
                    title = m.title,
                    subtitle = "${m.priority.label()} · ${m.roomId?.let { rooms[it]?.name } ?: "Common area"} · since ${m.reportedDate}",
                    onClick = { openSection(MoreSection.Maintenance) },
                )
            }
        }
    }
}
