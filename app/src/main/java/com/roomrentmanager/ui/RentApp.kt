package com.roomrentmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.Serializable

enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Home),
    Units("Units", Icons.AutoMirrored.Filled.List),
    Tenants("Tenants", Icons.Filled.Person),
    Payments("Payments", Icons.Filled.DateRange),
}

/** The dialog currently open. Serializable so it survives rotation along with the form's fields. */
sealed interface DialogState : Serializable {
    data class EditProperty(val propertyId: Long?) : DialogState
    data class EditRoom(val roomId: Long?, val propertyId: Long?) : DialogState
    data class EditTenant(val tenantId: Long?) : DialogState
    data class StartTenancy(val roomId: Long?, val tenantId: Long?) : DialogState
    data class Agreement(val tenancyId: Long) : DialogState
    data class EndTenancy(val tenancyId: Long) : DialogState
    data class RecordPayment(val tenancyId: Long?) : DialogState
    data class ReversePayment(val paymentId: Long) : DialogState
    data class Deposit(val tenancyId: Long) : DialogState
    data class Adjustment(val tenancyId: Long) : DialogState
    data class CancelCharge(val tenancyId: Long, val ledgerEntryId: Long) : DialogState
    data class ApplyLegacyPayment(val reviewId: Long) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RentApp(vm: RentViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    var dialog by rememberSaveable { mutableStateOf<DialogState?>(null) }
    var openTenantId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = openTenantId != null) { openTenantId = null }

    val show: (DialogState) -> Unit = { dialog = it }
    val detailTenant = openTenantId?.let { id -> state.portfolio.tenants.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detailTenant?.name ?: tab.label) },
                navigationIcon = {
                    if (detailTenant != null) {
                        IconButton(onClick = { openTenantId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = {
                    if (detailTenant == null && tab == Tab.Units) {
                        TextButton(onClick = { show(DialogState.EditProperty(null)) }) { Text("Add property") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && detailTenant == null,
                        onClick = { tab = t; openTenantId = null },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            val action: DialogState? = when {
                detailTenant != null -> null
                tab == Tab.Units -> if (state.portfolio.properties.any { it.isActive }) DialogState.EditRoom(null, null) else DialogState.EditProperty(null)
                tab == Tab.Tenants -> DialogState.EditTenant(null)
                tab == Tab.Payments -> DialogState.RecordPayment(null)
                else -> null
            }
            if (action != null) FloatingActionButton(onClick = { show(action) }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> Unit
                detailTenant != null -> TenantDetailScreen(state, detailTenant, vm, show)
                tab == Tab.Dashboard -> DashboardScreen(state, vm, show, openTenant = { openTenantId = it }, openPayments = { tab = Tab.Payments })
                tab == Tab.Units -> UnitsScreen(state, vm, show, openTenant = { openTenantId = it })
                tab == Tab.Tenants -> TenantsScreen(state, show, openTenant = { openTenantId = it })
                tab == Tab.Payments -> PaymentsScreen(state, vm, show)
            }
        }
    }

    DialogHost(dialog, state, vm, onDismiss = { dialog = null })
}

@Composable
private fun DialogHost(dialog: DialogState?, state: UiState, vm: RentViewModel, onDismiss: () -> Unit) {
    if (!state.loaded) return
    val p = state.portfolio
    fun view(id: Long) = state.tenancies.firstOrNull { it.tenancy.id == id }
    when (dialog) {
        null -> Unit
        is DialogState.EditProperty -> PropertyDialog(p.properties.firstOrNull { it.id == dialog.propertyId }, vm, onDismiss)
        is DialogState.EditRoom -> RoomDialog(
            p.rooms.firstOrNull { it.id == dialog.roomId },
            p.properties.filter { it.isActive || it.id == dialog.propertyId },
            dialog.propertyId, vm, onDismiss,
        )
        is DialogState.EditTenant -> TenantDialog(p.tenants.firstOrNull { it.id == dialog.tenantId }, vm, onDismiss)
        is DialogState.StartTenancy -> StartTenancyDialog(state, dialog.roomId, dialog.tenantId, vm, onDismiss)
        is DialogState.Agreement -> view(dialog.tenancyId)?.let { AgreementDialog(it, state.today, vm, onDismiss) }
        is DialogState.EndTenancy -> view(dialog.tenancyId)?.let { EndTenancyDialog(it, state.today, vm, onDismiss) }
        is DialogState.RecordPayment -> PaymentDialog(state, dialog.tenancyId, vm, onDismiss)
        is DialogState.ReversePayment -> p.payments.firstOrNull { it.id == dialog.paymentId }?.let { ReversePaymentDialog(it, vm, onDismiss) }
        is DialogState.Deposit -> view(dialog.tenancyId)?.let { DepositDialog(it, state.today, vm, onDismiss) }
        is DialogState.Adjustment -> view(dialog.tenancyId)?.let { AdjustmentDialog(it, state.today, vm, onDismiss) }
        is DialogState.CancelCharge -> view(dialog.tenancyId)?.balance?.charges?.firstOrNull { it.entry.id == dialog.ledgerEntryId }
            ?.let { CancelChargeDialog(it, vm, onDismiss) }
        is DialogState.ApplyLegacyPayment -> p.reviews.firstOrNull { it.id == dialog.reviewId }
            ?.let { ApplyLegacyPaymentDialog(it, state.tenancies, vm, onDismiss) }
    }
}
