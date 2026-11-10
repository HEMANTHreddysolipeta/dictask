package com.hemanth.dictask.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hemanth.dictask.R
import com.hemanth.dictask.data.Task
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Tab(val label: String) { TODAY("Today"), UPCOMING("Upcoming"), ALL("All"), DONE("Done") }

@Composable
fun App(act: MainActivity) {
    val vm: MainVm = viewModel()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(vm.prefs, onBack = { showSettings = false })
    } else {
        TasksScreen(vm, act, onSettings = { showSettings = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(vm: MainVm, act: MainActivity, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val all by vm.tasks.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.ALL) }
    var listFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Task?>(null) }
    var quick by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val quickFocus = remember { FocusRequester() }

    // Opened from the widget's "+" or a task tapped on the widget.
    LaunchedEffect(act.openAdd) {
        if (act.openAdd) { act.openAdd = false; runCatching { quickFocus.requestFocus() } }
    }
    LaunchedEffect(act.openTaskId, all) {
        val id = act.openTaskId ?: return@LaunchedEffect
        all?.firstOrNull { it.id == id }?.let { editing = it; act.openTaskId = null }
    }

    fun deleteWithUndo(t: Task) {
        vm.delete(t)
        scope.launch {
            val r = snack.showSnackbar("Deleted \"${t.title}\"", "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restore(t)
        }
    }

    fun complete(t: Task, d: Boolean) {
        vm.setDone(t, d)
        if (d) scope.launch {
            val r = snack.showSnackbar("Completed", "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.setDone(t.copy(done = true), false)
        }
    }

    val lists = all.orEmpty().mapNotNull { it.listName }.distinct().sorted()
    val today = LocalDate.now()
    val shown = all.orEmpty()
        .filter { listFilter == null || it.listName == listFilter }
        .filter {
            query.isBlank() || it.title.contains(query, true) || it.notes.contains(query, true) ||
                (it.listName?.contains(query, true) == true)
        }
        .filter {
            when (tab) {
                Tab.TODAY -> !it.done && it.dueDate != null && !it.dueDate!!.isAfter(today)
                Tab.UPCOMING -> !it.done && it.dueDate != null && it.dueDate!!.isAfter(today)
                Tab.ALL -> !it.done
                Tab.DONE -> it.done
            }
        }
        .let { l -> if (tab == Tab.DONE) l.sortedByDescending { it.completedAt ?: 0 } else l }

    Scaffold(
        topBar = {
            if (searching) {
                val f = remember { FocusRequester() }
                LaunchedEffect(Unit) { f.requestFocus() }
                TopAppBar(
                    navigationIcon = {
                        IconButton({ searching = false; query = "" }) { Icon(Icons.Default.ArrowBack, "Close search") }
                    },
                    title = {
                        TextField(
                            query, { query = it }, Modifier.fillMaxWidth().focusRequester(f),
                            placeholder = { Text("Search tasks") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Dictask", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { ctx.startActivity(Intent(ctx, VoiceActivity::class.java)) },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            ) { Icon(painterResource(R.drawable.ic_mic), "Dictate a task", Modifier.size(30.dp)) }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        quick, { quick = it },
                        Modifier.weight(1f).focusRequester(quickFocus),
                        placeholder = { Text("Add a task, e.g. pay rent Friday 10am") },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (quick.isNotBlank() && !adding) {
                                val text = quick; quick = ""; adding = true
                                vm.quickAdd(text) { msg -> adding = false; scope.launch { snack.showSnackbar(msg) } }
                            }
                        }),
                    )
                    Spacer(Modifier.width(4.dp))
                    if (adding) CircularProgressIndicator(Modifier.size(28.dp).padding(2.dp), strokeWidth = 3.dp)
                    else IconButton(
                        onClick = {
                            if (quick.isBlank()) { editing = Task(title = "") } else {
                                val text = quick; quick = ""; adding = true
                                vm.quickAdd(text) { msg -> adding = false; scope.launch { snack.showSnackbar(msg) } }
                            }
                        },
                    ) { Icon(if (quick.isBlank()) Icons.Default.Add else Icons.Default.Send, "Add") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Tab.entries) { t ->
                    val count = all.orEmpty().count {
                        when (t) {
                            Tab.TODAY -> !it.done && it.dueDate != null && !it.dueDate!!.isAfter(today)
                            Tab.UPCOMING -> !it.done && it.dueDate != null && it.dueDate!!.isAfter(today)
                            Tab.ALL -> !it.done
                            Tab.DONE -> false
                        }
                    }
                    FilterChip(tab == t, { tab = t }, { Text(if (count > 0) "${t.label} $count" else t.label) })
                }
                if (lists.isNotEmpty()) item { Spacer(Modifier.width(4.dp)) }
                items(lists) { l ->
                    FilterChip(listFilter == l, { listFilter = if (listFilter == l) null else l }, { Text("# $l") })
                }
            }
            when {
                all == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> EmptyState(tab, query.isNotBlank())
                else -> TaskList(
                    shown, tab,
                    onOpen = { editing = it },
                    onDone = ::complete,
                    onDelete = ::deleteWithUndo,
                    onClearDone = { vm.clearDone() },
                )
            }
        }
    }

    editing?.let { t ->
        EditSheet(
            task = t,
            knownLists = lists,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = { deleteWithUndo(it); editing = null },
        )
    }
}

@Composable
private fun EmptyState(tab: Tab, searching: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when {
                searching -> "Nothing matches"
                tab == Tab.DONE -> "Nothing completed yet"
                tab == Tab.TODAY -> "Nothing due today"
                tab == Tab.UPCOMING -> "Nothing scheduled"
                else -> "No tasks"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (!searching && tab != Tab.DONE) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap the mic and say something like\n\"Call the bank tomorrow at 11, and buy milk tonight\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>, tab: Tab,
    onOpen: (Task) -> Unit, onDone: (Task, Boolean) -> Unit, onDelete: (Task) -> Unit, onClearDone: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (tab == Tab.DONE) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClearDone) { Text("Clear completed") }
                }
            }
            items(tasks, key = { it.id }) { TaskRow(it, onOpen, onDone, onDelete) }
        } else {
            tasks.groupBy { sectionOf(it) }.toSortedMap().forEach { (sec, ts) ->
                item(key = "h-$sec") {
                    Text(
                        sec.label + "  ${ts.size}",
                        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (sec == Section.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                items(ts, key = { it.id }) { TaskRow(it, onOpen, onDone, onDelete) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(t: Task, onOpen: (Task) -> Unit, onDone: (Task, Boolean) -> Unit, onDelete: (Task) -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        when (it) {
            SwipeToDismissBoxValue.StartToEnd -> { onDone(t, !t.done); true }
            SwipeToDismissBoxValue.EndToStart -> { onDelete(t); true }
            else -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val bg by animateColorAsState(
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd -> Color(0xFF1E8E3E)
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    else -> Color.Transparent
                }, label = "swipe",
            )
            Box(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (dir == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (dir == SwipeToDismissBoxValue.StartToEnd) Icon(Icons.Default.Check, null, tint = Color.White)
                if (dir == SwipeToDismissBoxValue.EndToStart) Icon(Icons.Default.Delete, null, tint = Color.White)
            }
        },
    ) {
        Surface {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(t.done, { onDone(t, it) })
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val due = dueLabel(t)
                        if (due != null || t.listName != null || t.notes.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (due != null) Text(
                                    due + if (t.remindBefore != null && t.dueTime != null) "  🔔" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (t.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (t.listName != null) Text(
                                    (if (due != null) "  ·  " else "") + "# " + t.listName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (t.notes.isNotBlank()) Text(
                                t.notes, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (t.priority > 0) Box(Modifier.size(10.dp).clip(CircleShape).background(PriorityColors[t.priority]))
                }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}
