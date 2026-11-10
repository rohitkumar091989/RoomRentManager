package com.roomrentmanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roomrentmanager.ui.components.ConfirmDialog
import com.roomrentmanager.ui.components.SectionHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Called by the host activity to turn the app lock on (after one successful unlock) or off. */
typealias AppLockChanger = (enabled: Boolean, onResult: (error: String?) -> Unit) -> Unit

private val shownDate = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

@Composable
fun SettingsScreen(state: UiState, vm: RentViewModel, onAppLockChange: AppLockChanger?) {
    val busy by vm.busy.collectAsState()
    val canUndo by vm.canUndoRestore.collectAsState()
    val lockOn by vm.appLockEnabled.collectAsState()
    var lockMessage by remember { mutableStateOf<String?>(null) }
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    val today = state.today

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::backupTo) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportCsvTo) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::inspectBackup) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Working" }) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Backup")
                Text(
                    "Your records are stored only on this phone. If the phone is lost, reset or replaced without a backup, they are gone. " +
                        "Save a backup regularly to Google Drive, email or a computer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { backupLauncher.launch("room-rent-manager-backup-$today.json") }, enabled = !busy) { Text("Back up now") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, enabled = !busy) {
                    Text("Restore from a backup")
                }
                if (canUndo) TextButton(onClick = { confirmUndo = true }, enabled = !busy) { Text("Undo last restore") }
                Text(
                    "Restoring replaces everything on this phone. The file is checked completely first, and your current data is kept so the restore can be undone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Export")
                Text("A ZIP of spreadsheet (CSV) files for your records or an accountant. Use a backup, not an export, to move to a new phone.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { exportLauncher.launch("room-rent-manager-export-$today.zip") }, enabled = !busy) { Text("Export as CSV") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("App lock")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Require fingerprint, face or screen lock", modifier = Modifier.weight(1f))
                    Switch(
                        checked = lockOn,
                        enabled = onAppLockChange != null,
                        onCheckedChange = { want -> onAppLockChange?.invoke(want) { lockMessage = it } },
                    )
                }
                lockMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Locks the app when it opens and after 30 seconds in the background, and hides it in the recent-apps view. " +
                        "It protects against someone using your unlocked phone; it is not a separate account.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Privacy")
                Text(
                    "• No account, no analytics, no ads. The app sends nothing over the internet.\n" +
                        "• Android's automatic cloud backup is turned off for this app, so your tenants' details are never uploaded anywhere you did not choose. Use Back up now instead. (When you set up a new phone by copying directly from this one, Android may bring the app's data across.)\n" +
                        "• Backup and export files contain tenant names, phone numbers and payments. Store and share them carefully.\n" +
                        "• Reminders are worked out on this phone. Messages to tenants are sent by you, from your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SectionHeader("Display")
                Text("Money is shown in Indian rupees (₹) with Indian digit grouping. Dates are shown and entered as YYYY-MM-DD.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (confirmUndo) {
        ConfirmDialog(
            "Go back to the data you had before the last restore? What is on the phone now will be replaced (and can be undone again).",
            "Undo restore", onDismiss = { confirmUndo = false }, onConfirm = vm::undoLastRestore,
        )
    }
}

private val TABLE_LABELS = linkedMapOf(
    "properties" to "properties", "rooms" to "rooms/units", "tenants" to "tenants", "tenancies" to "tenancies",
    "payments" to "payments", "rent_ledger" to "ledger entries", "deposit_transactions" to "deposit entries",
    "expenses" to "expenses", "maintenance_requests" to "maintenance requests",
)

private fun summary(counts: Map<String, Int>) = TABLE_LABELS.entries.joinToString(", ") { (table, label) -> "${counts[table] ?: 0} $label" }

/** Shown whenever a validated backup is waiting for confirmation. */
@Composable
fun RestoreConfirmDialog(vm: RentViewModel) {
    val preview by vm.restorePreview.collectAsState()
    val p = preview ?: return
    var understood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = vm::cancelRestore,
        title = { Text("Replace all data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val made = Instant.ofEpochMilli(p.contents.exportedAtMillis).atZone(ZoneId.systemDefault()).format(shownDate)
                Text("The backup is complete and undamaged.", style = MaterialTheme.typography.bodyMedium)
                Text("Backup from $made:\n${summary(p.contents.counts)}", style = MaterialTheme.typography.bodySmall)
                Text("On this phone now:\n${summary(p.currentCounts)}", style = MaterialTheme.typography.bodySmall)
                // The whole row is the touch target, and is read as one checkbox by screen readers.
                Row(Modifier.toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Text("I understand everything on this phone will be replaced by the backup.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::confirmRestore, enabled = understood) { Text("Restore") } },
        dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
    )
}
