package com.hemanth.dictask.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hemanth.dictask.data.Priority
import com.hemanth.dictask.data.Recurrence
import com.hemanth.dictask.data.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val remindOptions = listOf<Int?>(null, 0, 5, 15, 30, 60, 1440)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(
    task: Task,
    knownLists: List<String>,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: (Task) -> Unit,
) {
    var title by remember { mutableStateOf(task.title) }
    var notes by remember { mutableStateOf(task.notes) }
    var date by remember { mutableStateOf(task.dueDate) }
    var time by remember { mutableStateOf(task.dueTime) }
    var priority by remember { mutableStateOf(task.priority) }
    var list by remember { mutableStateOf(task.listName.orEmpty()) }
    var rec by remember { mutableStateOf(task.recurrence) }
    var remind by remember { mutableStateOf(task.remindBefore) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (task.id == 0L) "New task" else "Edit task", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Task") })
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)

            Label("Due")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                FilterChip(date == today, { date = today }, { Text("Today") })
                FilterChip(date == today.plusDays(1), { date = today.plusDays(1) }, { Text("Tomorrow") })
                AssistChip({ pickDate = true }, { Text(date?.let { dayLabel(it) }?.takeIf { date != today && date != today.plusDays(1) } ?: "Pick date") })
                AssistChip({ pickTime = true }, { Text(time?.let { timeLabel(it) } ?: "Add time") })
                if (date != null || time != null) AssistChip({ date = null; time = null }, { Text("Clear") })
            }

            if (time != null) {
                Label("Reminder")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    remindOptions.forEach { m ->
                        FilterChip(remind == m, { remind = m }, {
                            Text(when (m) { null -> "None"; 0 -> "On time"; 60 -> "1 hr"; 1440 -> "1 day"; else -> "$m min" })
                        })
                    }
                }
            }

            Label("Repeat")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Recurrence.entries.forEach { r ->
                    FilterChip(rec == r, { rec = r }, { Text(if (r == Recurrence.NONE) "Never" else r.label.removePrefix("Every ").replaceFirstChar { it.uppercase() }) })
                }
            }

            Label("Priority")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.labels.forEachIndexed { i, l -> FilterChip(priority == i, { priority = i }, { Text(l) }) }
            }

            Label("List")
            OutlinedTextField(list, { list = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. Work, Shopping") }, singleLine = true)
            if (knownLists.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                knownLists.forEach { l -> FilterChip(list == l, { list = if (list == l) "" else l }, { Text(l) }) }
            }

            task.spokenText?.let {
                Text("You said: \"$it\"", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.id != 0L) TextButton({ onDelete(task) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text("Cancel") }
                Button(
                    enabled = title.isNotBlank(),
                    onClick = {
                        onSave(
                            task.copy(
                                title = title.trim(), notes = notes.trim(),
                                dueEpochDay = (date ?: if (time != null) LocalDate.now() else null)?.toEpochDay(),
                                dueMinute = time?.let { it.hour * 60 + it.minute },
                                priority = priority, listName = list.trim().ifEmpty { null },
                                recurrence = rec, remindBefore = if (time == null) null else remind,
                            )
                        )
                    },
                ) { Text("Save") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (pickDate) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(st) }
    }

    if (pickTime) {
        val init = time ?: LocalTime.now().plusHours(1).withMinute(0)
        val st = rememberTimePickerState(init.hour, init.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton({
                    time = LocalTime.of(st.hour, st.minute)
                    if (date == null) date = LocalDate.now()
                    if (task.dueTime == null && remind == null) remind = 0
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(st) },
        )
    }
}

@Composable
private fun Label(s: String) = Text(s, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
