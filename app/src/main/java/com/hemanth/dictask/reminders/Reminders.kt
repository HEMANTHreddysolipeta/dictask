package com.hemanth.dictask.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hemanth.dictask.R
import com.hemanth.dictask.data.Prefs
import com.hemanth.dictask.data.Repo
import com.hemanth.dictask.data.Task
import com.hemanth.dictask.ui.MainActivity
import com.hemanth.dictask.ui.VoiceActivity
import com.hemanth.dictask.ui.dueLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Reminders {
    const val CH_REMIND = "reminders"
    const val CH_DIGEST = "digest"
    const val EXTRA_ID = "task_id"
    const val ACTION_SHOW = "com.hemanth.dictask.SHOW"
    const val ACTION_DONE = "com.hemanth.dictask.DONE"
    const val ACTION_SNOOZE = "com.hemanth.dictask.SNOOZE"
    const val ACTION_DIGEST = "com.hemanth.dictask.DIGEST"
    private const val DIGEST_CODE = Int.MAX_VALUE - 1
    private const val DIGEST_NOTIF = Int.MAX_VALUE - 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DIGEST, "Morning summary", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun alarms(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun showIntent(ctx: Context, id: Long) = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_SHOW).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context, t: Task) {
        cancel(ctx, t.id)
        if (t.done) return
        val at = t.reminderAt() ?: return
        if (at <= System.currentTimeMillis()) return
        scheduleAt(ctx, t, at)
    }

    fun scheduleAt(ctx: Context, t: Task, at: Long) = setAlarm(ctx, at, showIntent(ctx, t.id))

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = alarms(ctx)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(ctx: Context, id: Long) {
        alarms(ctx).cancel(showIntent(ctx, id))
        NotificationManagerCompat.from(ctx).cancel(id.toInt())
    }

    suspend fun rescheduleAll(ctx: Context) {
        Repo(ctx).pending().forEach { schedule(ctx, it) }
        scheduleDigest(ctx)
    }

    fun scheduleDigest(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, DIGEST_CODE, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_DIGEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms(ctx).cancel(pi)
        val p = Prefs(ctx)
        if (!p.digestOn) return
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(p.digestMinute / 60, p.digestMinute % 60)
        if (!at.isAfter(now)) at = at.plusDays(1)
        setAlarm(ctx, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, code: Int) = PendingIntent.getActivity(
        ctx, code, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(ctx: Context, act: String, id: Long, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, ReminderReceiver::class.java).setAction(act).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun notifyTask(ctx: Context, t: Task) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(t.title)
            .setContentText(listOfNotNull(dueLabel(t), t.notes.takeIf { it.isNotBlank() }).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                listOfNotNull(dueLabel(t), t.notes.takeIf { it.isNotBlank() }).joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, t.id.toInt()))
            .addAction(0, "Done", action(ctx, ACTION_DONE, t.id, t.id.toInt() * 4 + 1))
            .addAction(0, "Snooze 15 min", action(ctx, ACTION_SNOOZE, t.id, t.id.toInt() * 4 + 2))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(t.id.toInt(), n)
    }

    suspend fun notifyDigest(ctx: Context) {
        if (!canNotify(ctx)) return
        val today = LocalDate.now().toEpochDay()
        val pending = Repo(ctx).pending()
        val overdue = pending.filter { it.isOverdue() }
        val dueToday = pending.filter { it.dueEpochDay == today && !it.isOverdue() }
        if (overdue.isEmpty() && dueToday.isEmpty()) return
        val title = buildList {
            if (dueToday.isNotEmpty()) add("${dueToday.size} today")
            if (overdue.isNotEmpty()) add("${overdue.size} overdue")
        }.joinToString(", ")
        val lines = (overdue + dueToday).take(7)
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it.title + (dueLabel(it)?.let { l -> "  ·  $l" } ?: "")) }
        val dictate = PendingIntent.getActivity(
            ctx, DIGEST_NOTIF, Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("Your tasks: $title")
            .setContentText(lines.joinToString { it.title })
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, DIGEST_NOTIF))
            .addAction(0, "Add by voice", dictate)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(DIGEST_NOTIF, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1)
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = Repo(ctx)
                when (intent.action) {
                    Reminders.ACTION_SHOW -> repo.get(id)?.takeIf { !it.done }?.let { Reminders.notifyTask(ctx, it) }
                    Reminders.ACTION_DONE -> {
                        repo.get(id)?.let { repo.setDone(it, true) }
                        NotificationManagerCompat.from(ctx).cancel(id.toInt())
                    }
                    Reminders.ACTION_SNOOZE -> {
                        repo.snooze(id, 15)
                        NotificationManagerCompat.from(ctx).cancel(id.toInt())
                    }
                    Reminders.ACTION_DIGEST -> {
                        Reminders.notifyDigest(ctx)
                        Reminders.scheduleDigest(ctx)
                    }
                }
            } finally {
                pr.finish()
            }
        }
    }
}

/** Alarms are wiped on reboot, app update and clock changes, so put them all back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Reminders.rescheduleAll(ctx)
                com.hemanth.dictask.widget.TaskWidget.refresh(ctx)
            } finally {
                pr.finish()
            }
        }
    }
}
