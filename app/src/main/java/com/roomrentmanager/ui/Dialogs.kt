package com.roomrentmanager.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.finance.BillingCycleCalculator
import com.roomrentmanager.domain.finance.ChargeStatus
import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.Payment
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.Property
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.RentalUnit
import com.roomrentmanager.domain.model.ReviewItem
import com.roomrentmanager.domain.model.Tenant
import com.roomrentmanager.domain.usecase.AgreementInput
import com.roomrentmanager.domain.usecase.PaymentInput
import com.roomrentmanager.domain.usecase.StartTenancyInput
import com.roomrentmanager.domain.usecase.TenancyView
import com.roomrentmanager.ui.components.DateInput
import com.roomrentmanager.ui.components.FormDialog
import com.roomrentmanager.ui.components.MoneyInput
import com.roomrentmanager.ui.components.Picker
import com.roomrentmanager.ui.components.TextInput
import com.roomrentmanager.ui.components.money
import com.roomrentmanager.ui.components.parseDateOrNull
import java.time.LocalDate

private fun optionalMoney(text: String): Long? = if (text.isBlank()) null else Money.parseRupees(text)
private fun optionalMoneyValid(text: String) = text.isBlank() || Money.parseRupees(text) != null

fun tenancyLabel(v: TenancyView) = "${v.tenant.name} · ${v.room.name}" + if (v.isActive) "" else " (ended)"

@Composable
fun PropertyDialog(property: Property?, vm: RentViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(property?.name ?: "") }
    var type by rememberSaveable { mutableStateOf(property?.type ?: PropertyType.RESIDENTIAL) }
    var address by rememberSaveable { mutableStateOf(property?.address ?: "") }
    var notes by rememberSaveable { mutableStateOf(property?.notes ?: "") }
    FormDialog(
        title = if (property == null) "Add property" else "Edit property",
        canSave = name.isNotBlank(),
        onDismiss = onDismiss,
        onSave = { vm.saveProperty(property?.id, name, type, address, notes, onDismiss) },
    ) {
        TextInput(name, { name = it }, "Property name")
        Picker("Type", type.label(), PropertyType.entries.map { it to it.label() }) { type = it }
        TextInput(address, { address = it }, "Address (optional)")
        TextInput(notes, { notes = it }, "Notes (optional)")
    }
}

fun PropertyType.label() = when (this) {
    PropertyType.RESIDENTIAL -> "Residential"
    PropertyType.COMMERCIAL -> "Commercial"
}

@Composable
fun RoomDialog(room: RentalUnit?, properties: List<Property>, defaultPropertyId: Long?, vm: RentViewModel, onDismiss: () -> Unit) {
    var propertyId by rememberSaveable { mutableStateOf(room?.propertyId ?: defaultPropertyId ?: properties.firstOrNull()?.id) }
    var name by rememberSaveable { mutableStateOf(room?.name ?: "") }
    var rent by rememberSaveable { mutableStateOf(room?.defaultRentPaise?.let(Money::toInputText) ?: "") }
    var notes by rememberSaveable { mutableStateOf(room?.notes ?: "") }
    FormDialog(
        title = if (room == null) "Add room / unit" else "Edit room / unit",
        canSave = name.isNotBlank() && propertyId != null && optionalMoneyValid(rent),
        onDismiss = onDismiss,
        onSave = { vm.saveRoom(room?.id, propertyId!!, name, optionalMoney(rent), notes, onDismiss) },
    ) {
        Picker("Property", properties.firstOrNull { it.id == propertyId }?.name ?: "Choose", properties.map { it.id to it.name }) { propertyId = it }
        TextInput(name, { name = it }, "Room, flat, shop or unit name")
        MoneyInput(rent, { rent = it }, "Usual monthly rent", required = false)
        Text("Used as the suggested rent for new tenancies. Changing it never alters existing agreements.", style = MaterialTheme.typography.bodySmall)
        TextInput(notes, { notes = it }, "Notes (optional)")
    }
}

@Composable
fun TenantDialog(tenant: Tenant?, vm: RentViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(tenant?.name ?: "") }
    var phone by rememberSaveable { mutableStateOf(tenant?.phone ?: "") }
    var email by rememberSaveable { mutableStateOf(tenant?.email ?: "") }
    var address by rememberSaveable { mutableStateOf(tenant?.address ?: "") }
    var notes by rememberSaveable { mutableStateOf(tenant?.notes ?: "") }
    FormDialog(
        title = if (tenant == null) "Add tenant" else "Edit tenant",
        canSave = name.isNotBlank(),
        onDismiss = onDismiss,
        onSave = { vm.saveTenant(tenant?.id, name, phone, email, address, notes, onDismiss) },
    ) {
        TextInput(name, { name = it }, "Name (person or business)")
        TextInput(phone, { phone = it }, "Phone (optional)", keyboard = KeyboardType.Phone)
        TextInput(email, { email = it }, "Email (optional)", keyboard = KeyboardType.Email)
        TextInput(address, { address = it }, "Address (optional)")
        TextInput(notes, { notes = it }, "Notes (optional)")
    }
}

@Composable
fun StartTenancyDialog(state: UiState, presetRoomId: Long?, presetTenantId: Long?, vm: RentViewModel, onDismiss: () -> Unit) {
    val vacantRooms = state.rooms.filter { it.room.isActive && it.occupant == null && it.property?.isActive != false }
    val tenants = state.portfolio.tenants.filter { it.isActive }
    var roomId by rememberSaveable { mutableStateOf(presetRoomId ?: vacantRooms.firstOrNull()?.room?.id) }
    var tenantId by rememberSaveable { mutableStateOf(presetTenantId ?: tenants.firstOrNull()?.id) }
    var moveIn by rememberSaveable { mutableStateOf(state.today.toString()) }
    var rentFrom by rememberSaveable { mutableStateOf("") }
    var rentDay by rememberSaveable { mutableStateOf("") }
    val defaultRent = state.rooms.firstOrNull { it.room.id == roomId }?.room?.defaultRentPaise
    var rent by rememberSaveable(roomId) { mutableStateOf(defaultRent?.let(Money::toInputText) ?: "") }
    var deposit by rememberSaveable { mutableStateOf("") }
    var lateFee by rememberSaveable { mutableStateOf("") }
    var graceDays by rememberSaveable { mutableStateOf("") }

    val moveInDate = parseDateOrNull(moveIn)
    val rentStart = if (rentFrom.isBlank()) moveInDate else parseDateOrNull(rentFrom)
    val day = if (rentDay.isBlank()) rentStart?.dayOfMonth else rentDay.toIntOrNull()
    val rentPaise = Money.parseRupees(rent)
    val valid = roomId != null && tenantId != null && moveInDate != null && rentStart != null && day != null &&
        rentPaise != null && rentPaise > 0 && optionalMoneyValid(deposit) && optionalMoneyValid(lateFee) &&
        (graceDays.isBlank() || graceDays.toIntOrNull() != null)

    FormDialog(
        title = "Start tenancy",
        canSave = valid,
        onDismiss = onDismiss,
        saveLabel = "Start",
        onSave = {
            val input = StartTenancyInput(
                roomId = roomId!!, tenantId = tenantId!!, moveIn = moveInDate!!,
                agreement = AgreementInput(rentStart!!, rentPaise!!, day!!, optionalMoney(lateFee) ?: 0, graceDays.toIntOrNull() ?: 0),
                depositPaise = optionalMoney(deposit)?.takeIf { it > 0 },
            )
            vm.startTenancy(input, onDismiss)
        },
    ) {
        if (vacantRooms.isEmpty()) Text("There are no vacant rooms. Add a room or end a tenancy first.")
        if (tenants.isEmpty()) Text("Add a tenant first.")
        Picker("Room", vacantRooms.firstOrNull { it.room.id == roomId }?.room?.name ?: "Choose",
            vacantRooms.map { it.room.id to "${it.room.name} · ${it.property?.name ?: ""}" }) { roomId = it }
        Picker("Tenant", tenants.firstOrNull { it.id == tenantId }?.name ?: "Choose", tenants.map { it.id to it.name }) { tenantId = it }
        DateInput(moveIn, { moveIn = it }, "Move-in date")
        MoneyInput(rent, { rent = it }, "Monthly rent")
        TextInput(rentFrom, { rentFrom = it }, "Rent starts on (blank = move-in date)",
            isError = rentFrom.isNotBlank() && parseDateOrNull(rentFrom) == null)
        TextInput(rentDay, { rentDay = it }, "Rent due day of month (blank = ${rentStart?.dayOfMonth ?: "start day"})",
            isError = rentDay.isNotBlank() && rentDay.toIntOrNull() !in 1..31, keyboard = KeyboardType.Number)
        Text("Rent is charged in advance on this day each month. In shorter months the last day is used.", style = MaterialTheme.typography.bodySmall)
        MoneyInput(deposit, { deposit = it }, "Security deposit received (optional)", required = false)
        MoneyInput(lateFee, { lateFee = it }, "Late fee (optional, recorded on the agreement)", required = false)
        TextInput(graceDays, { graceDays = it }, "Grace days (optional)", keyboard = KeyboardType.Number)
    }
}

@Composable
fun AgreementDialog(view: TenancyView, today: LocalDate, vm: RentViewModel, onDismiss: () -> Unit) {
    val current = view.agreements.lastOrNull()
    val anchor = current?.anchorDay
    val lastCharged = view.balance.charges.filter { it.entry.type == LedgerEntryType.RENT_CHARGE }.mapNotNull { it.entry.periodStart }.maxOrNull()
    val suggested = if (anchor == null) {
        maxOf(today, view.tenancy.moveIn)
    } else {
        val candidate = BillingCycleCalculator.billingDateOnOrAfter(today, anchor)
        if (lastCharged != null && !candidate.isAfter(lastCharged)) BillingCycleCalculator.nextBillingDate(lastCharged, anchor) else candidate
    }
    var from by rememberSaveable { mutableStateOf(suggested.toString()) }
    var rent by rememberSaveable { mutableStateOf(current?.rentPaise?.let(Money::toInputText) ?: view.room.defaultRentPaise?.let(Money::toInputText) ?: "") }
    var rentDay by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    val fromDate = parseDateOrNull(from)
    val day = anchor ?: if (rentDay.isBlank()) fromDate?.dayOfMonth else rentDay.toIntOrNull()
    val rentPaise = Money.parseRupees(rent)

    FormDialog(
        title = if (current == null) "Set rent agreement" else "Change rent",
        canSave = fromDate != null && day != null && rentPaise != null && rentPaise > 0,
        onDismiss = onDismiss,
        onSave = {
            vm.addAgreement(view.tenancy.id, AgreementInput(fromDate!!, rentPaise!!, day!!, current?.lateFeePaise ?: 0, current?.graceDays ?: 0, notes), onDismiss)
        },
    ) {
        Text(tenancyLabel(view), style = MaterialTheme.typography.bodyMedium)
        if (current != null) {
            Text(
                "Current rent ${money(current.rentPaise)}, due on day $anchor. The new rent starts on a rent day; " +
                    "rent already charged is not changed.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("No rent is charged for any period before this date.", style = MaterialTheme.typography.bodySmall)
        }
        MoneyInput(rent, { rent = it }, if (current == null) "Monthly rent" else "New monthly rent")
        DateInput(from, { from = it }, "Effective from")
        if (anchor == null) {
            TextInput(rentDay, { rentDay = it }, "Rent due day of month (blank = ${fromDate?.dayOfMonth ?: "start day"})",
                isError = rentDay.isNotBlank() && rentDay.toIntOrNull() !in 1..31, keyboard = KeyboardType.Number)
        }
        TextInput(notes, { notes = it }, "Notes (optional)")
    }
}

@Composable
fun EndTenancyDialog(view: TenancyView, today: LocalDate, vm: RentViewModel, onDismiss: () -> Unit) {
    var moveOut by rememberSaveable { mutableStateOf(today.toString()) }
    val date = parseDateOrNull(moveOut)
    FormDialog(title = "End tenancy", canSave = date != null, onDismiss = onDismiss, saveLabel = "End tenancy",
        onSave = { vm.endTenancy(view.tenancy.id, date!!, onDismiss) }) {
        Text(tenancyLabel(view), style = MaterialTheme.typography.bodyMedium)
        DateInput(moveOut, { moveOut = it }, "Move-out (handover) date")
        Text(
            "Rent periods starting on or after this date are not charged; unpaid charges already posted for them are cancelled. " +
                "Payments, deposit and history are kept. Outstanding: ${money(view.balance.outstandingPaise)}; deposit held: ${money(view.depositHeldPaise)}.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private enum class AllocationChoice(val label: String) { OLDEST("Oldest due first"), SELECTED("Selected charges only"), CREDIT("Keep as advance credit") }

fun PaymentMethod.label() = when (this) {
    PaymentMethod.CASH -> "Cash"
    PaymentMethod.UPI -> "UPI"
    PaymentMethod.BANK_TRANSFER -> "Bank transfer"
    PaymentMethod.OTHER -> "Other"
}

@Composable
fun PaymentDialog(state: UiState, presetTenancyId: Long?, vm: RentViewModel, onDismiss: () -> Unit) {
    val options = state.tenancies.filter { it.isActive || it.balance.outstandingPaise > 0 }
    var tenancyId by rememberSaveable { mutableStateOf(presetTenancyId ?: options.firstOrNull()?.tenancy?.id) }
    val view = options.firstOrNull { it.tenancy.id == tenancyId }
    var amount by rememberSaveable(tenancyId) { mutableStateOf(view?.balance?.netPaise?.takeIf { it > 0 }?.let(Money::toInputText) ?: "") }
    var date by rememberSaveable { mutableStateOf(state.today.toString()) }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.CASH) }
    var reference by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var choice by rememberSaveable { mutableStateOf(AllocationChoice.OLDEST) }
    var selected by rememberSaveable(tenancyId) { mutableStateOf(listOf<Long>()) }
    val open: List<ChargeStatus> = view?.balance?.charges?.filter { it.isOpen }.orEmpty()
    val amountPaise = Money.parseRupees(amount)
    val dateValue = parseDateOrNull(date)

    FormDialog(
        title = "Record payment",
        canSave = view != null && amountPaise != null && amountPaise > 0 && dateValue != null &&
            (choice != AllocationChoice.SELECTED || selected.isNotEmpty()),
        onDismiss = onDismiss,
        onSave = {
            val mode = when (choice) {
                AllocationChoice.OLDEST -> AllocationMode.OldestFirst
                AllocationChoice.SELECTED -> AllocationMode.Selected(selected.toSet())
                AllocationChoice.CREDIT -> AllocationMode.KeepAsCredit
            }
            vm.recordPayment(PaymentInput(view!!.tenancy.id, amountPaise!!, dateValue!!, method, reference, note, mode), onDismiss)
        },
    ) {
        if (options.isEmpty()) Text("Start a tenancy before recording payments.")
        Picker("Tenancy", view?.let(::tenancyLabel) ?: "Choose", options.map { it.tenancy.id to tenancyLabel(it) }) { tenancyId = it }
        if (view != null) {
            Text("Outstanding ${money(view.balance.outstandingPaise)} · credit ${money(view.balance.creditPaise)}", style = MaterialTheme.typography.bodySmall)
        }
        MoneyInput(amount, { amount = it }, "Amount received")
        DateInput(date, { date = it }, "Received on")
        Picker("Method", method.label(), PaymentMethod.entries.map { it to it.label() }) { method = it }
        TextInput(reference, { reference = it }, "Reference / UPI ID (optional)")
        TextInput(note, { note = it }, "Note (optional)")
        Text("Apply to", style = MaterialTheme.typography.labelLarge)
        AllocationChoice.entries.forEach { c ->
            Row(Modifier.selectable(selected = choice == c, role = Role.RadioButton, onClick = { choice = c }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == c, onClick = null)
                Text(c.label)
            }
        }
        if (choice == AllocationChoice.SELECTED) {
            if (open.isEmpty()) Text("No open charges.")
            open.forEach { charge ->
                Row(
                    Modifier.toggleable(value = charge.entry.id in selected, role = Role.Checkbox, onValueChange = { on ->
                        selected = if (on) selected + charge.entry.id else selected - charge.entry.id
                    }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = charge.entry.id in selected, onCheckedChange = null)
                    Text("${charge.entry.dueDate ?: ""} ${charge.entry.description}: ${money(charge.outstandingPaise)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Anything not applied to charges is kept as advance credit.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ReversePaymentDialog(payment: Payment, vm: RentViewModel, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    FormDialog(title = "Reverse payment", canSave = reason.isNotBlank(), onDismiss = onDismiss, saveLabel = "Reverse",
        onSave = { vm.reversePayment(payment.id, reason, onDismiss) }) {
        Text("${money(payment.amountPaise)} received ${payment.paymentDate}. The payment stays on record marked as reversed, and the rent it paid becomes outstanding again.")
        TextInput(reason, { reason = it }, "Reason (e.g. cheque bounced, entered twice)")
    }
}

fun DepositTransactionType.label() = when (this) {
    DepositTransactionType.RECEIVED -> "Received"
    DepositTransactionType.REFUND -> "Refunded"
    DepositTransactionType.DEDUCTION -> "Deducted"
    DepositTransactionType.ADJUSTMENT -> "Adjustment"
}

@Composable
fun DepositDialog(view: TenancyView, today: LocalDate, vm: RentViewModel, onDismiss: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(if (view.depositHeldPaise > 0) DepositTransactionType.REFUND else DepositTransactionType.RECEIVED) }
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var note by rememberSaveable { mutableStateOf("") }
    val paise = Money.parseRupees(amount)
    val dateValue = parseDateOrNull(date)
    FormDialog(title = "Deposit", canSave = paise != null && paise > 0 && dateValue != null &&
        (type != DepositTransactionType.DEDUCTION || note.isNotBlank()), onDismiss = onDismiss,
        onSave = { vm.recordDeposit(view.tenancy.id, type, paise!!, dateValue!!, note, onDismiss) }) {
        Text("${tenancyLabel(view)} · held ${money(view.depositHeldPaise)}", style = MaterialTheme.typography.bodyMedium)
        Picker("Type", type.label(), listOf(DepositTransactionType.RECEIVED, DepositTransactionType.DEDUCTION, DepositTransactionType.REFUND).map { it to it.label() }) { type = it }
        MoneyInput(amount, { amount = it }, "Amount")
        DateInput(date, { date = it }, "Date")
        TextInput(note, { note = it }, if (type == DepositTransactionType.DEDUCTION) "What the deduction is for" else "Note (optional)")
        Text("Deposits are tracked separately and are never used for rent automatically.", style = MaterialTheme.typography.bodySmall)
    }
}

private enum class AdjustmentKind(val label: String) { CHARGE("Extra charge"), LATE_FEE("Late fee"), CREDIT("Credit to tenant") }

@Composable
fun AdjustmentDialog(view: TenancyView, today: LocalDate, vm: RentViewModel, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(AdjustmentKind.CHARGE) }
    var amount by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf(today.toString()) }
    val paise = Money.parseRupees(amount)
    val dueDate = parseDateOrNull(due)
    FormDialog(title = "Add adjustment", canSave = paise != null && paise > 0 && description.isNotBlank() && (kind == AdjustmentKind.CREDIT || dueDate != null),
        onDismiss = onDismiss,
        onSave = {
            when (kind) {
                AdjustmentKind.CHARGE -> vm.addCharge(view.tenancy.id, LedgerEntryType.ADJUSTMENT, paise!!, dueDate!!, description, onDismiss)
                AdjustmentKind.LATE_FEE -> vm.addCharge(view.tenancy.id, LedgerEntryType.LATE_FEE, paise!!, dueDate!!, description, onDismiss)
                AdjustmentKind.CREDIT -> vm.addCredit(view.tenancy.id, paise!!, description, onDismiss)
            }
        }) {
        Text(tenancyLabel(view), style = MaterialTheme.typography.bodyMedium)
        Picker("Kind", kind.label, AdjustmentKind.entries.map { it to it.label }) { kind = it }
        MoneyInput(amount, { amount = it }, "Amount")
        TextInput(description, { description = it }, "Description")
        if (kind != AdjustmentKind.CREDIT) DateInput(due, { due = it }, "Due date")
    }
}

@Composable
fun CancelChargeDialog(charge: ChargeStatus, vm: RentViewModel, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    FormDialog(title = "Cancel charge", canSave = reason.isNotBlank(), onDismiss = onDismiss, saveLabel = "Cancel charge",
        onSave = { vm.cancelCharge(charge.entry.id, reason, onDismiss) }) {
        Text("${charge.entry.description}: ${money(charge.outstandingPaise)} unpaid will be cancelled with a reversal entry. The original charge stays in the ledger.")
        TextInput(reason, { reason = it }, "Reason")
    }
}

@Composable
fun ApplyLegacyPaymentDialog(item: ReviewItem, tenancies: List<TenancyView>, vm: RentViewModel, onDismiss: () -> Unit) {
    var tenancyId by rememberSaveable { mutableStateOf(item.suggestedTenancyId ?: tenancies.firstOrNull()?.tenancy?.id) }
    FormDialog(title = "Count imported payment", canSave = tenancyId != null, onDismiss = onDismiss, saveLabel = "Apply",
        onSave = { vm.applyLegacyPayment(item.id, tenancyId!!); onDismiss() }) {
        Text("The payment will count as advance credit on the chosen tenancy. Use this only if it was not for rent before the agreement started.")
        Picker("Tenancy", tenancies.firstOrNull { it.tenancy.id == tenancyId }?.let(::tenancyLabel) ?: "Choose",
            tenancies.map { it.tenancy.id to tenancyLabel(it) }) { tenancyId = it }
    }
}
