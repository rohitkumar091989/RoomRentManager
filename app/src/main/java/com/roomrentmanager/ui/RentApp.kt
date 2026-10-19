package com.roomrentmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomrentmanager.data.PaymentRow
import com.roomrentmanager.data.RentalRoom
import com.roomrentmanager.data.TenantRow
import java.text.NumberFormat
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Home),
    Rooms("Rooms", Icons.Filled.List),
    Tenants("Tenants", Icons.Filled.Person),
    Payments("Payments", Icons.Filled.DateRange),
}

/** What the add/edit dialog is currently showing, if anything. */
private sealed interface DialogState {
    data class EditRoom(val room: RentalRoom?) : DialogState
    data class EditTenant(val row: TenantRow?) : DialogState
    data class AddPayment(val tenantId: Long?, val amount: Double?) : DialogState
    data class Confirm(val message: String, val onConfirm: () -> Unit) : DialogState
}

private val money: NumberFormat = NumberFormat.getCurrencyInstance()
fun formatMoney(amount: Double): String = money.format(amount)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RentApp(vm: RentViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    val rooms by vm.rooms.collectAsState()
    val tenants by vm.tenants.collectAsState()
    val payments by vm.payments.collectAsState()
    val summary by vm.summary.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.label) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab != Tab.Dashboard) {
                FloatingActionButton(onClick = {
                    dialog = when (tab) {
                        Tab.Rooms -> DialogState.EditRoom(null)
                        Tab.Tenants -> DialogState.EditTenant(null)
                        else -> DialogState.AddPayment(null, null)
                    }
                }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Dashboard -> DashboardScreen(
                    summary = summary,
                    onPrev = vm::previousMonth,
                    onNext = vm::nextMonth,
                    onCollect = { due -> dialog = DialogState.AddPayment(due.row.tenant.id, due.balance) },
                )
                Tab.Rooms -> RoomsScreen(
                    rooms = rooms,
                    tenants = tenants,
                    onEdit = { dialog = DialogState.EditRoom(it) },
                    onDelete = { room ->
                        dialog = DialogState.Confirm("Delete room \"${room.name}\"? Its tenants will be unassigned.") {
                            vm.deleteRoom(room)
                        }
                    },
                )
                Tab.Tenants -> TenantsScreen(
                    tenants = tenants,
                    onEdit = { dialog = DialogState.EditTenant(it) },
                    onDelete = { row ->
                        dialog = DialogState.Confirm("Delete tenant \"${row.tenant.name}\" and all their payments?") {
                            vm.deleteTenant(row.tenant)
                        }
                    },
                )
                Tab.Payments -> PaymentsScreen(
                    payments = payments,
                    onDelete = { row ->
                        dialog = DialogState.Confirm("Delete this payment of ${formatMoney(row.payment.amount)}?") {
                            vm.deletePayment(row.payment)
                        }
                    },
                )
            }
        }
    }

    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        is DialogState.EditRoom -> RoomDialog(d.room, onDismiss = close) { vm.saveRoom(it); close() }
        is DialogState.EditTenant -> TenantDialog(d.row?.tenant, rooms, onDismiss = close) { vm.saveTenant(it); close() }
        is DialogState.AddPayment -> PaymentDialog(
            tenants = tenants,
            initialTenantId = d.tenantId,
            initialAmount = d.amount,
            initialMonth = summary.month,
            onDismiss = close,
        ) { vm.addPayment(it); close() }
        is DialogState.Confirm -> AlertDialog(
            onDismissRequest = close,
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { d.onConfirm(); close() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
    }
}

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
private fun DashboardScreen(
    summary: MonthSummary,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCollect: (TenantDue) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                Text(
                    summary.month.format(monthTitle),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Expected", summary.expected, Modifier.weight(1f))
                StatCard("Collected", summary.collected, Modifier.weight(1f))
                StatCard("Pending", summary.pending, Modifier.weight(1f))
            }
        }
        if (summary.dues.isEmpty()) {
            item { EmptyText("No tenants with a room for this month. Add rooms and tenants first.") }
        }
        items(summary.dues, key = { it.row.tenant.id }) { due ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(due.row.tenant.name, fontWeight = FontWeight.SemiBold)
                        Text("${due.row.roomName} · rent ${formatMoney(due.due)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (due.balance == 0.0) "Paid" else "Paid ${formatMoney(due.paid)} · due ${formatMoney(due.balance)}",
                            color = if (due.balance == 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (due.balance > 0.0) {
                        Button(onClick = { onCollect(due) }) { Text("Collect") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, amount: Double, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(formatMoney(amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RoomsScreen(
    rooms: List<RentalRoom>,
    tenants: List<TenantRow>,
    onEdit: (RentalRoom) -> Unit,
    onDelete: (RentalRoom) -> Unit,
) {
    if (rooms.isEmpty()) {
        EmptyText("No rooms yet. Tap + to add one.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rooms, key = { it.id }) { room ->
            val occupants = tenants.filter { it.tenant.roomId == room.id }.joinToString { it.tenant.name }
            ListCard(
                title = room.name,
                subtitle = "Rent ${formatMoney(room.monthlyRent)} / month",
                detail = occupants.ifEmpty { "Vacant" },
                onClick = { onEdit(room) },
                onDelete = { onDelete(room) },
            )
        }
    }
}

@Composable
private fun TenantsScreen(
    tenants: List<TenantRow>,
    onEdit: (TenantRow) -> Unit,
    onDelete: (TenantRow) -> Unit,
) {
    if (tenants.isEmpty()) {
        EmptyText("No tenants yet. Tap + to add one.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tenants, key = { it.tenant.id }) { row ->
            ListCard(
                title = row.tenant.name,
                subtitle = row.roomName ?: "No room assigned",
                detail = listOf(row.tenant.phone, "Since ${row.tenant.moveInDate}").filter { it.isNotBlank() }.joinToString(" · "),
                onClick = { onEdit(row) },
                onDelete = { onDelete(row) },
            )
        }
    }
}

@Composable
private fun PaymentsScreen(payments: List<PaymentRow>, onDelete: (PaymentRow) -> Unit) {
    if (payments.isEmpty()) {
        EmptyText("No payments recorded yet.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(payments, key = { it.payment.id }) { row ->
            ListCard(
                title = "${row.tenantName} · ${formatMoney(row.payment.amount)}",
                subtitle = "For ${row.payment.month}, received ${row.payment.paidOn}",
                detail = row.payment.note,
                onClick = null,
                onDelete = { onDelete(row) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListCard(
    title: String,
    subtitle: String,
    detail: String,
    onClick: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            Spacer(Modifier.width(4.dp))
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
}
