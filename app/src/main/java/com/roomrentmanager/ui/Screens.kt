package com.roomrentmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roomrentmanager.domain.model.PaymentStatus
import com.roomrentmanager.domain.model.ReviewEntityType
import com.roomrentmanager.domain.model.ReviewStatus
import com.roomrentmanager.domain.model.Tenant
import com.roomrentmanager.domain.usecase.TenancyView
import com.roomrentmanager.ui.components.Banner
import com.roomrentmanager.ui.components.ConfirmDialog
import com.roomrentmanager.ui.components.EmptyText
import com.roomrentmanager.ui.components.ListCard
import com.roomrentmanager.ui.components.SectionHeader
import com.roomrentmanager.ui.components.StatCard
import com.roomrentmanager.ui.components.money
import org.json.JSONObject
import java.time.format.DateTimeFormatter

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")
private val listPadding = PaddingValues(16.dp)
private val listSpacing = Arrangement.spacedBy(8.dp)

/** "Owes ₹x", "Credit ₹y" or "Settled" — never clamps a credit to zero. */
@Composable
private fun balanceText(v: TenancyView): Pair<String, androidx.compose.ui.graphics.Color> {
    val net = v.balance.netPaise
    return when {
        net > 0 -> "Owes ${money(net)}" to MaterialTheme.colorScheme.error
        net < 0 -> "Advance credit ${money(-net)}" to MaterialTheme.colorScheme.primary
        else -> "Settled" to MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun OverflowMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
    }
}

// ---------------------------------------------------------------------------------------------
// Dashboard

@Composable
fun DashboardScreen(state: UiState, vm: RentViewModel, show: (DialogState) -> Unit, openTenant: (Long) -> Unit, openPayments: () -> Unit) {
    val d = state.dashboard ?: return
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (d.pendingReviews > 0) {
            item { Banner("${d.pendingReviews} records imported from the previous version need review.", "Review", openPayments) }
        }
        if (d.tenanciesNeedingAgreement > 0) {
            item { Banner("${d.tenanciesNeedingAgreement} active tenancies have no rent agreement, so no rent is being charged. Open the tenant to set rent.") }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                Text(d.figures.month.format(monthTitle), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::nextMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Due this month", d.figures.expectedPaise, Modifier.weight(1f))
                StatCard("Received", d.figures.collectedPaise, Modifier.weight(1f))
                StatCard("Unpaid", d.figures.outstandingPaise, Modifier.weight(1f))
            }
        }
        item {
            Text(
                "All tenants: outstanding ${money(d.totalOutstandingPaise)} (overdue ${money(d.totalOverduePaise)}) · advance credit ${money(d.totalCreditPaise)}\n" +
                    "${d.propertyCount} properties · ${d.occupiedRooms} occupied · ${d.vacantRooms} vacant",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (d.upcoming.isNotEmpty()) {
            item { SectionHeader("Rent due in the next 7 days") }
            items(d.upcoming, key = { "up${it.tenancy.id}" }) { v ->
                ListCard(title = v.tenant.name, subtitle = "${v.room.name} · due ${v.nextDue}",
                    detail = v.currentAgreement(state.today)?.let { "Rent ${money(it.rentPaise)}" }, onClick = { openTenant(v.tenant.id) })
            }
        }
        item { SectionHeader("Balances") }
        if (d.tenancies.isEmpty()) item { EmptyText("Nothing due this month. Add properties, rooms and tenants to get started.") }
        items(d.tenancies, key = { "bal${it.tenancy.id}" }) { v ->
            val (text, color) = balanceText(v)
            ListCard(
                title = v.tenant.name, subtitle = tenancyLabel(v).removePrefix("${v.tenant.name} · "),
                detail = text, detailColor = color, onClick = { openTenant(v.tenant.id) },
            ) {
                if (v.balance.outstandingPaise > 0) Button(onClick = { show(DialogState.RecordPayment(v.tenancy.id)) }) { Text("Collect") }
            }
        }
        if (d.recentPayments.isNotEmpty()) {
            item { SectionHeader("Recent payments") }
            items(d.recentPayments, key = { "pay${it.id}" }) { pay ->
                val who = state.tenancies.firstOrNull { it.tenancy.id == pay.tenancyId }?.tenant?.name ?: ""
                ListCard(title = "$who · ${money(pay.amountPaise)}", subtitle = "${pay.paymentDate} · ${pay.method.label()}",
                    detail = if (pay.status == PaymentStatus.REVERSED) "Reversed" else null)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Units: properties and their rooms

@Composable
fun UnitsScreen(state: UiState, vm: RentViewModel, show: (DialogState) -> Unit, openTenant: (Long) -> Unit) {
    val p = state.portfolio
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    if (p.properties.isEmpty()) {
        EmptyText("Add a property (a building or premises), then its rooms, flats or shops.")
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = listSpacing) {
        p.properties.sortedBy { !it.isActive }.forEach { property ->
            item(key = "prop${property.id}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionHeader(property.name + if (property.isActive) "" else " (archived)")
                        Text(listOfNotNull(property.type.label(), property.address).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    OverflowMenu(
                        buildList {
                            add("Edit property" to { show(DialogState.EditProperty(property.id)) })
                            if (property.isActive) {
                                add("Add room / unit" to { show(DialogState.EditRoom(null, property.id)) })
                                add("Archive property" to { confirm = "Archive '${property.name}'? Its history is kept." to { vm.setPropertyActive(property.id, false) } })
                            } else {
                                add("Restore property" to { vm.setPropertyActive(property.id, true) })
                            }
                        },
                    )
                }
            }
            val rooms = state.rooms.filter { it.room.propertyId == property.id }.sortedBy { !it.room.isActive }
            if (rooms.isEmpty()) item(key = "empty${property.id}") { Text("No rooms yet.", style = MaterialTheme.typography.bodySmall) }
            items(rooms, key = { "room${it.room.id}" }) { rv ->
                val occupant = rv.occupant
                val status = when {
                    !rv.room.isActive -> "Archived"
                    occupant != null -> "Occupied by ${occupant.tenant.name}"
                    else -> "Vacant"
                }
                val rent = occupant?.currentAgreement(state.today)?.rentPaise ?: rv.room.defaultRentPaise
                ListCard(
                    title = rv.room.name,
                    subtitle = status,
                    detail = rent?.let { "Rent ${money(it)} / month" + if (occupant == null) " (usual)" else "" },
                    onClick = { if (occupant != null) openTenant(occupant.tenant.id) else show(DialogState.EditRoom(rv.room.id, rv.room.propertyId)) },
                ) {
                    if (rv.room.isActive && occupant == null && property.isActive) {
                        TextButton(onClick = { show(DialogState.StartTenancy(rv.room.id, null)) }) { Text("Let") }
                    }
                    OverflowMenu(
                        buildList {
                            add("Edit" to { show(DialogState.EditRoom(rv.room.id, rv.room.propertyId)) })
                            if (rv.room.isActive) {
                                add("Archive" to { confirm = "Archive '${rv.room.name}'? Its history is kept." to { vm.setRoomActive(rv.room.id, false) } })
                            } else {
                                add("Restore" to { vm.setRoomActive(rv.room.id, true) })
                            }
                        },
                    )
                }
            }
        }
    }
    confirm?.let { (message, action) -> ConfirmDialog(message, "Archive", onDismiss = { confirm = null }, onConfirm = action) }
}

// ---------------------------------------------------------------------------------------------
// Tenants

@Composable
fun TenantsScreen(state: UiState, show: (DialogState) -> Unit, openTenant: (Long) -> Unit) {
    val tenants = state.portfolio.tenants.sortedBy { !it.isActive }
    if (tenants.isEmpty()) {
        EmptyText("No tenants yet. Tap + to add one.")
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = listSpacing) {
        items(tenants, key = { it.id }) { t ->
            val views = state.tenancies.filter { it.tenant.id == t.id }
            val active = views.filter { it.isActive }
            val net = views.sumOf { it.balance.netPaise }
            ListCard(
                title = t.name + if (t.isActive) "" else " (archived)",
                subtitle = if (active.isEmpty()) "No active tenancy" else active.joinToString { it.room.name },
                detail = listOfNotNull(
                    t.phone,
                    when {
                        net > 0 -> "Owes ${money(net)}"
                        net < 0 -> "Credit ${money(-net)}"
                        views.isNotEmpty() -> "Settled"
                        else -> null
                    },
                ).joinToString(" · "),
                onClick = { openTenant(t.id) },
            )
        }
    }
}

@Composable
fun TenantDetailScreen(state: UiState, tenant: Tenant, vm: RentViewModel, show: (DialogState) -> Unit) {
    val views = state.tenancies.filter { it.tenant.id == tenant.id }.sortedWith(compareBy({ !it.isActive }, { -it.tenancy.moveIn.toEpochDay() }))
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LazyColumn(contentPadding = listPadding, verticalArrangement = listSpacing) {
        item {
            Column {
                Text(listOfNotNull(tenant.phone, tenant.email, tenant.address).joinToString(" · ").ifEmpty { "No contact details" })
                if (!tenant.notes.isNullOrBlank()) Text(tenant.notes, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { show(DialogState.EditTenant(tenant.id)) }) { Text("Edit") }
                    if (tenant.isActive) {
                        TextButton(onClick = { show(DialogState.StartTenancy(null, tenant.id)) }) { Text("Start tenancy") }
                        TextButton(onClick = { confirm = "Archive ${tenant.name}? All tenancies and payments are kept." to { vm.setTenantActive(tenant.id, false) } }) { Text("Archive") }
                    } else {
                        TextButton(onClick = { vm.setTenantActive(tenant.id, true) }) { Text("Restore") }
                    }
                }
            }
        }
        if (views.isEmpty()) item { EmptyText("No tenancies yet.") }
        views.forEach { v -> tenancySection(v, state, vm, show) }
    }
    confirm?.let { (message, action) -> ConfirmDialog(message, "Archive", onDismiss = { confirm = null }, onConfirm = action) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tenancySection(v: TenancyView, state: UiState, vm: RentViewModel, show: (DialogState) -> Unit) {
    val id = v.tenancy.id
    item(key = "t$id") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("${v.room.name} · ${v.property?.name ?: ""}")
            Text(
                "Moved in ${v.tenancy.moveIn}" + (v.tenancy.moveOut?.let { " · moved out $it" } ?: "") + " · ${v.tenancy.status.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
            )
            val (text, color) = balanceText(v)
            Text(text, color = color, style = MaterialTheme.typography.titleMedium)
            Text(
                "Outstanding ${money(v.balance.outstandingPaise)} (overdue ${money(v.balance.overduePaise(state.today))}) · " +
                    "credit ${money(v.balance.creditPaise)} · deposit held ${money(v.depositHeldPaise)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (v.needsAgreement) Banner("No rent agreement yet, so no rent is being charged.", "Set rent") { show(DialogState.Agreement(id)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { show(DialogState.RecordPayment(id)) }) { Text("Payment") }
                OutlinedButton(onClick = { show(DialogState.Deposit(id)) }) { Text("Deposit") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.isActive) TextButton(onClick = { show(DialogState.Agreement(id)) }) { Text(if (v.agreements.isEmpty()) "Set rent" else "Change rent") }
                TextButton(onClick = { show(DialogState.Adjustment(id)) }) { Text("Adjust") }
                if (v.balance.creditPaise > 0 && v.balance.outstandingPaise > 0) TextButton(onClick = { vm.applyCredit(id) }) { Text("Apply credit") }
                if (v.isActive) TextButton(onClick = { show(DialogState.EndTenancy(id)) }) { Text("End") }
            }
        }
    }
    if (v.agreements.isNotEmpty()) {
        item(key = "ag$id") {
            Column {
                Text("Rent agreements", style = MaterialTheme.typography.labelLarge)
                v.agreements.forEach { a ->
                    Text("${money(a.rentPaise)} from ${a.effectiveFrom}" + (a.effectiveTo?.let { " to $it" } ?: " onward") + ", due on day ${a.anchorDay}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    val ledger = state.portfolio.ledger.filter { it.tenancyId == id }.sortedWith(compareByDescending<com.roomrentmanager.domain.model.LedgerEntry> { it.dueDate }.thenByDescending { it.id })
    if (ledger.isNotEmpty()) item(key = "lh$id") { Text("Ledger", style = MaterialTheme.typography.labelLarge) }
    items(ledger, key = { "l${it.id}" }) { e ->
        val charge = v.balance.charges.firstOrNull { it.entry.id == e.id }
        ListCard(
            title = "${e.description} · ${money(e.amountPaise)}",
            subtitle = listOfNotNull(e.dueDate?.let { "Due $it" }, e.type.name.lowercase().replace('_', ' ')).joinToString(" · "),
            detail = charge?.let {
                when {
                    it.isOpen -> "Unpaid ${money(it.outstandingPaise)}"
                    it.reversedPaise > 0 && it.allocatedPaise == 0L -> "Cancelled"
                    it.reversedPaise > 0 -> "Paid ${money(it.allocatedPaise)}, rest cancelled"
                    else -> "Paid"
                }
            },
            detailColor = if (charge?.isOpen == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ) {
            if (charge != null && charge.isOpen && charge.reversedPaise == 0L) {
                TextButton(onClick = { show(DialogState.CancelCharge(id, e.id)) }) { Text("Cancel") }
            }
        }
    }
    val payments = state.portfolio.payments.filter { it.tenancyId == id }
    if (payments.isNotEmpty()) item(key = "ph$id") { Text("Payments", style = MaterialTheme.typography.labelLarge) }
    items(payments, key = { "p${it.id}" }) { pay -> PaymentCard(pay, state, show) }
    val deposits = state.portfolio.deposits.filter { it.tenancyId == id }
    if (deposits.isNotEmpty()) {
        item(key = "d$id") {
            Column {
                Text("Deposit", style = MaterialTheme.typography.labelLarge)
                deposits.forEach { dt ->
                    Text("${dt.date} · ${dt.type.label()} ${money(dt.amountPaise)}" + (dt.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PaymentCard(pay: com.roomrentmanager.domain.model.Payment, state: UiState, show: (DialogState) -> Unit) {
    val reversed = pay.status == PaymentStatus.REVERSED
    val unallocated = com.roomrentmanager.domain.finance.FinanceCalculator.unallocatedPaise(pay, state.portfolio.allocations)
    val who = state.tenancies.firstOrNull { it.tenancy.id == pay.tenancyId }?.let(::tenancyLabel)
    ListCard(
        title = money(pay.amountPaise) + (who?.let { " · $it" } ?: ""),
        subtitle = listOfNotNull(pay.paymentDate, pay.method.label(), pay.reference).joinToString(" · "),
        detail = when {
            reversed -> "Reversed: ${pay.reversalReason}"
            unallocated > 0 -> "Unapplied credit ${money(unallocated)}" + (pay.note?.let { " · $it" } ?: "")
            else -> pay.note
        },
        detailColor = if (reversed) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
    ) {
        if (!reversed && pay.tenancyId != null) TextButton(onClick = { show(DialogState.ReversePayment(pay.id)) }) { Text("Reverse") }
    }
}

// ---------------------------------------------------------------------------------------------
// Payments and imported records

@Composable
fun PaymentsScreen(state: UiState, vm: RentViewModel, show: (DialogState) -> Unit) {
    val p = state.portfolio
    val pending = p.reviews.filter { it.status == ReviewStatus.PENDING }
    val linked = p.payments.filter { it.tenancyId != null }
    val history = p.reviews.filter { it.entityType == ReviewEntityType.PAYMENT && it.status == ReviewStatus.KEPT_AS_HISTORY }
    if (pending.isEmpty() && linked.isEmpty() && history.isEmpty()) {
        EmptyText("No payments recorded yet.")
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = listSpacing) {
        if (pending.isNotEmpty()) {
            item {
                Column {
                    SectionHeader("Imported records to review (${pending.size})")
                    Text(
                        "These came from the previous app version, which did not keep rent history. They are not counted in any balance until you decide.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(pending, key = { "rv${it.id}" }) { item ->
                val data = runCatching { JSONObject(item.legacyData) }.getOrNull()
                if (item.entityType == ReviewEntityType.PAYMENT) {
                    ListCard(
                        title = "${data?.optString("tenantName") ?: "Unknown"} · ₹${data?.optString("amount") ?: "?"}",
                        subtitle = "For ${data?.optString("month")}, received ${data?.optString("paidOn")}",
                        detail = item.reason,
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { vm.keepLegacyPayment(item.id) }) { Text("History") }
                            if (state.tenancies.isNotEmpty() && p.payments.any { it.id == item.entityId }) {
                                TextButton(onClick = { show(DialogState.ApplyLegacyPayment(item.id)) }) { Text("Credit") }
                            }
                        }
                    }
                } else {
                    val name = p.tenants.firstOrNull { it.id == item.entityId }?.name ?: "Tenant ${item.entityId}"
                    ListCard(title = name, subtitle = "Room ${data?.optString("legacyRoomName")}, moved in ${data?.optString("moveInDate")}", detail = item.reason) {
                        TextButton(onClick = { vm.markTenantReviewed(item.id) }) { Text("Done") }
                    }
                }
            }
        }
        if (linked.isNotEmpty()) item { SectionHeader("Payments") }
        items(linked, key = { "pay${it.id}" }) { pay -> PaymentCard(pay, state, show) }
        if (history.isNotEmpty()) {
            item { SectionHeader("Imported history (not counted in balances)") }
            items(history, key = { "h${it.id}" }) { item ->
                val data = runCatching { JSONObject(item.legacyData) }.getOrNull()
                ListCard(
                    title = "${data?.optString("tenantName")} · ₹${data?.optString("amount")}",
                    subtitle = "For ${data?.optString("month")}, received ${data?.optString("paidOn")}",
                    detail = data?.optString("note")?.takeIf { it.isNotBlank() },
                    modifier = Modifier.padding(0.dp),
                )
            }
        }
        item { Spacer(Modifier.height(72.dp)) } // keeps the last card clear of the add button
    }
}
