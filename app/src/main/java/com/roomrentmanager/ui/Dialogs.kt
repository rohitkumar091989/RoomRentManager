package com.roomrentmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roomrentmanager.data.Payment
import com.roomrentmanager.data.RentalRoom
import com.roomrentmanager.data.Tenant
import com.roomrentmanager.data.TenantRow
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun RoomDialog(room: RentalRoom?, onDismiss: () -> Unit, onSave: (RentalRoom) -> Unit) {
    var name by remember { mutableStateOf(room?.name ?: "") }
    var rent by remember { mutableStateOf(room?.monthlyRent?.let(::plainNumber) ?: "") }
    val rentValue = rent.toDoubleOrNull()
    val valid = name.isNotBlank() && rentValue != null && rentValue >= 0

    FormDialog(
        title = if (room == null) "Add room" else "Edit room",
        canSave = valid,
        onDismiss = onDismiss,
        onSave = { onSave(RentalRoom(id = room?.id ?: 0, name = name.trim(), monthlyRent = rentValue!!)) },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Room name / number") }, singleLine = true)
        MoneyField(rent, { rent = it }, "Monthly rent")
    }
}

@Composable
fun TenantDialog(
    tenant: Tenant?,
    rooms: List<RentalRoom>,
    onDismiss: () -> Unit,
    onSave: (Tenant) -> Unit,
) {
    var name by remember { mutableStateOf(tenant?.name ?: "") }
    var phone by remember { mutableStateOf(tenant?.phone ?: "") }
    var roomId by remember { mutableStateOf(tenant?.roomId) }
    var moveIn by remember { mutableStateOf(tenant?.moveInDate ?: LocalDate.now().toString()) }
    val dateValid = isIsoDate(moveIn)

    FormDialog(
        title = if (tenant == null) "Add tenant" else "Edit tenant",
        canSave = name.isNotBlank() && dateValid,
        onDismiss = onDismiss,
        onSave = {
            onSave(
                Tenant(
                    id = tenant?.id ?: 0,
                    name = name.trim(),
                    phone = phone.trim(),
                    roomId = roomId,
                    moveInDate = moveIn.trim(),
                )
            )
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(
            phone, { phone = it },
            label = { Text("Phone") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        Picker(
            label = "Room",
            selected = rooms.firstOrNull { it.id == roomId }?.name ?: "No room",
            options = listOf<Pair<Long?, String>>(null to "No room") + rooms.map { it.id to it.name },
            onSelect = { roomId = it },
        )
        OutlinedTextField(
            moveIn, { moveIn = it },
            label = { Text("Move-in date (YYYY-MM-DD)") },
            singleLine = true,
            isError = !dateValid,
        )
    }
}

@Composable
fun PaymentDialog(
    tenants: List<TenantRow>,
    initialTenantId: Long?,
    initialAmount: Double?,
    initialMonth: YearMonth,
    onDismiss: () -> Unit,
    onSave: (Payment) -> Unit,
) {
    var tenantId by remember { mutableStateOf(initialTenantId ?: tenants.firstOrNull()?.tenant?.id) }
    var amount by remember { mutableStateOf(initialAmount?.let(::plainNumber) ?: "") }
    var month by remember { mutableStateOf(initialMonth.toString()) }
    var paidOn by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }

    val amountValue = amount.toDoubleOrNull()
    val monthValid = runCatching { YearMonth.parse(month.trim()) }.isSuccess
    val paidOnValid = isIsoDate(paidOn)

    // Pre-fill the amount with the tenant's rent when picking a tenant from scratch.
    fun selectTenant(id: Long?) {
        tenantId = id
        if (amount.isBlank()) {
            tenants.firstOrNull { it.tenant.id == id }?.monthlyRent?.let { amount = plainNumber(it) }
        }
    }

    FormDialog(
        title = "Record payment",
        canSave = tenantId != null && amountValue != null && amountValue > 0 && monthValid && paidOnValid,
        onDismiss = onDismiss,
        onSave = {
            onSave(
                Payment(
                    tenantId = tenantId!!,
                    month = YearMonth.parse(month.trim()).toString(),
                    amount = amountValue!!,
                    paidOn = paidOn.trim(),
                    note = note.trim(),
                )
            )
        },
    ) {
        if (tenants.isEmpty()) {
            Text("Add a tenant before recording payments.")
            return@FormDialog
        }
        Picker(
            label = "Tenant",
            selected = tenants.firstOrNull { it.tenant.id == tenantId }?.tenant?.name ?: "Choose tenant",
            options = tenants.map { it.tenant.id to it.tenant.name },
            onSelect = { selectTenant(it) },
        )
        MoneyField(amount, { amount = it }, "Amount")
        OutlinedTextField(
            month, { month = it },
            label = { Text("Rent month (YYYY-MM)") },
            singleLine = true,
            isError = !monthValid,
        )
        OutlinedTextField(
            paidOn, { paidOn = it },
            label = { Text("Received on (YYYY-MM-DD)") },
            singleLine = true,
            isError = !paidOnValid,
        )
        OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
    }
}

@Composable
private fun FormDialog(
    title: String,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        isError = value.isNotEmpty() && value.toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun <T> Picker(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

private fun isIsoDate(text: String): Boolean = runCatching { LocalDate.parse(text.trim()) }.isSuccess

/** 1500.0 -> "1500", 1500.5 -> "1500.5" */
private fun plainNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
