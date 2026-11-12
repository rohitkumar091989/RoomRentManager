package com.roomrentmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roomrentmanager.domain.finance.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun money(paise: Long): String = Money.format(paise)

fun parseDateOrNull(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListCard(
    title: String,
    subtitle: String? = null,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
            }
            trailing()
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth()) { content() }
    else Card(modifier.fillMaxWidth()) { content() }
}

@Composable
fun StatCard(label: String, paise: Long, modifier: Modifier = Modifier) {
    // Read as one item by screen readers: "Due this month, ₹10,000.00".
    Card(modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(money(paise), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Banner(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
fun EmptyText(text: String) {
    Text(text, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
}

/** A form in a dialog. The content scrolls so long forms work on small screens. */
@Composable
fun FormDialog(
    title: String,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveLabel: String = "Save",
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text(saveLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Rupee input; shows an error for anything [Money.parseRupees] rejects. */
@Composable
fun MoneyInput(value: String, onChange: (String) -> Unit, label: String, required: Boolean = true) {
    val invalid = (value.isNotBlank() || required) && Money.parseRupees(value) == null
    TextInput(value, onChange, "$label (₹)", isError = value.isNotEmpty() && invalid, keyboard = KeyboardType.Decimal)
}

/**
 * A date field: type YYYY-MM-DD or tap the calendar button to pick. [selectable] limits which days
 * the picker offers (for example nothing after today); typed dates are still checked by the use
 * cases, so the restriction is a convenience, not the rule. With [optional], a blank field is valid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    optional: Boolean = false,
    selectable: ((LocalDate) -> Boolean)? = null,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val parsed = parseDateOrNull(value)
    OutlinedTextField(
        value, onChange,
        label = { Text("$label (YYYY-MM-DD)") },
        singleLine = true,
        isError = !(optional && value.isBlank()) && parsed == null,
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.DateRange, contentDescription = "Choose $label from calendar") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (!picking) return

    val state = rememberDatePickerState(
        initialSelectedDateMillis = parsed?.takeIf { selectable?.invoke(it) != false }?.toUtcMillis(),
        initialDisplayedMonthMillis = (parsed ?: LocalDate.now()).withDayOfMonth(1).toUtcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = selectable?.invoke(utcTimeMillis.toUtcDate()) != false
        },
    )
    DatePickerDialog(
        onDismissRequest = { picking = false },
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onChange(it.toUtcDate().toString()) }
                    picking = false
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
    ) {
        DatePicker(state = state, title = { Text(label, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) })
    }
}

/** Material's date picker works in UTC midnight milliseconds. */
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** For date fields that cannot be in the future. */
fun notAfter(day: LocalDate): (LocalDate) -> Boolean = { !it.isAfter(day) }

@Composable
fun <T> Picker(label: String, selected: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
