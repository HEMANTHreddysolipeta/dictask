package com.hemanth.dictask.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.hemanth.dictask.R
import com.hemanth.dictask.data.Priority
import com.hemanth.dictask.data.Repo
import com.hemanth.dictask.data.Task
import com.hemanth.dictask.ui.MainActivity
import com.hemanth.dictask.ui.VoiceActivity
import com.hemanth.dictask.ui.dueLabel
import java.time.LocalDate

private val idKey = ActionParameters.Key<Long>("task_id")
private val red = ColorProvider(Color(0xFFD93025))
private val amber = ColorProvider(Color(0xFFE37400))

class TaskWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repo(context)
        val initial = repo.pending()
        // Watch the database: a running widget session keeps its first snapshot otherwise.
        provideContent {
            val tasks by repo.pendingFlow.collectAsState(initial)
            GlanceTheme { WidgetBody(tasks) }
        }
    }

    companion object {
        suspend fun refresh(ctx: Context) = runCatching { TaskWidget().updateAll(ctx) }.let { }
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[idKey]?.let { Repo(context).toggle(it) }
    }
}

@Composable
private fun WidgetBody(tasks: List<Task>) {
    val today = LocalDate.now().toEpochDay()
    val overdue = tasks.count { it.isOverdue() }
    val dueToday = tasks.count { it.dueEpochDay == today && !it.isOverdue() }
    val header = when {
        tasks.isEmpty() -> "All clear"
        overdue > 0 && dueToday > 0 -> "$dueToday today · $overdue overdue"
        overdue > 0 -> "$overdue overdue"
        dueToday > 0 -> "$dueToday due today"
        else -> "${tasks.size} to do"
    }

    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                header,
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
            )
            RoundButton(R.drawable.ic_add, "Add task", GlanceTheme.colors.secondaryContainer, GlanceTheme.colors.onSecondaryContainer,
                actionStartActivity<MainActivity>(actionParametersOf(MainActivity.ADD_PARAM to true)))
            Spacer(GlanceModifier.width(8.dp))
            RoundButton(R.drawable.ic_mic, "Dictate task", GlanceTheme.colors.primary, GlanceTheme.colors.onPrimary,
                actionStartActivity<VoiceActivity>())
        }
        if (tasks.isEmpty()) {
            Box(GlanceModifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Tap the mic and say what you need to do.",
                    style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize().padding(top = 4.dp)) {
                items(tasks, itemId = { it.id }) { t -> TaskRow(t) }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: Int, desc: String, bg: ColorProvider, fg: ColorProvider, action: androidx.glance.action.Action) {
    Box(
        GlanceModifier.size(36.dp).cornerRadius(18.dp).background(bg).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), desc, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(fg))
    }
}

@Composable
private fun TaskRow(t: Task) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<ToggleAction>(actionParametersOf(idKey to t.id)),
        )
        Column(
            GlanceModifier.defaultWeight().clickable(
                actionStartActivity<MainActivity>(actionParametersOf(MainActivity.TASK_PARAM to t.id))
            )
        ) {
            Text(
                (if (t.priority == Priority.HIGH) "! " else "") + t.title,
                style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
                maxLines = 2,
            )
            val due = dueLabel(t)
            val sub = listOfNotNull(due, t.listName).joinToString("  ·  ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = when {
                            t.isOverdue() -> red
                            t.dueEpochDay == LocalDate.now().toEpochDay() -> amber
                            else -> GlanceTheme.colors.onSurfaceVariant
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
