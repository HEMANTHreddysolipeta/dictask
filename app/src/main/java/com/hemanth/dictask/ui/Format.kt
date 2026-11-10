package com.hemanth.dictask.ui

import com.hemanth.dictask.data.Recurrence
import com.hemanth.dictask.data.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val dateYearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun timeLabel(t: LocalTime): String = timeFmt.format(t)

fun dayLabel(d: LocalDate, today: LocalDate = LocalDate.now()): String {
    val diff = ChronoUnit.DAYS.between(today, d)
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff == -1L -> "Yesterday"
        diff in 2..6 -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        d.year == today.year -> dateFmt.format(d)
        else -> dateYearFmt.format(d)
    }
}

/** "Today, 5:00 PM" / "Fri" / null when there is no date. */
fun dueLabel(t: Task): String? {
    val d = t.dueDate ?: return null
    val time = t.dueTime?.let { ", " + timeLabel(it) } ?: ""
    val rep = if (t.recurrence != Recurrence.NONE) "  ↻" else ""
    return dayLabel(d) + time + rep
}

fun remindLabel(m: Int?): String = when (m) {
    null -> "No reminder"
    0 -> "At the time"
    in 1..59 -> "$m min before"
    60 -> "1 hour before"
    in 61..1439 -> "${m / 60} hours before"
    1440 -> "1 day before"
    else -> "${m / 1440} days before"
}

enum class Section(val label: String) { OVERDUE("Overdue"), TODAY("Today"), TOMORROW("Tomorrow"), WEEK("Next 7 days"), LATER("Later"), NODATE("No date") }

fun sectionOf(t: Task, today: LocalDate = LocalDate.now()): Section {
    val d = t.dueDate ?: return Section.NODATE
    if (t.isOverdue()) return Section.OVERDUE
    val diff = ChronoUnit.DAYS.between(today, d)
    return when {
        diff <= 0L -> Section.TODAY
        diff == 1L -> Section.TOMORROW
        diff <= 7L -> Section.WEEK
        else -> Section.LATER
    }
}
