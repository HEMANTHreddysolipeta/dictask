package com.hemanth.dictask.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class Recurrence(val label: String) {
    NONE("Doesn't repeat"), DAILY("Every day"), WEEKDAYS("Every weekday"),
    WEEKLY("Every week"), MONTHLY("Every month"), YEARLY("Every year");

    fun next(from: LocalDate): LocalDate = when (this) {
        NONE -> from
        DAILY -> from.plusDays(1)
        WEEKDAYS -> {
            var d = from.plusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            d
        }
        WEEKLY -> from.plusWeeks(1)
        MONTHLY -> from.plusMonths(1)
        YEARLY -> from.plusYears(1)
    }

    companion object {
        /** Moves a repeating task's date forward until its due moment is not in the past. */
        fun firstNotPast(r: Recurrence, from: LocalDate, time: LocalTime?): LocalDate {
            if (r == NONE) return from
            val now = LocalDateTime.now()
            var d = from
            fun past() = if (time == null) d.isBefore(now.toLocalDate()) else !LocalDateTime.of(d, time).isAfter(now)
            while (past()) d = r.next(d)
            return d
        }

        fun parse(s: String?): Recurrence = when (s?.lowercase()) {
            "daily" -> DAILY; "weekdays" -> WEEKDAYS; "weekly" -> WEEKLY
            "monthly" -> MONTHLY; "yearly" -> YEARLY; else -> NONE
        }
    }
}

/** 0 = none, 1 = low, 2 = medium, 3 = high */
object Priority {
    const val NONE = 0
    const val LOW = 1
    const val MEDIUM = 2
    const val HIGH = 3
    val labels = listOf("None", "Low", "Medium", "High")
    fun parse(s: String?): Int = when (s?.lowercase()) {
        "high", "urgent" -> HIGH; "medium" -> MEDIUM; "low" -> LOW; else -> NONE
    }
}

@Entity(tableName = "tasks", indices = [Index(value = ["uid"], unique = true)])
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    /** LocalDate.toEpochDay(), null = no date */
    val dueEpochDay: Long? = null,
    /** minutes after midnight, null = all day */
    val dueMinute: Int? = null,
    val priority: Int = Priority.NONE,
    val listName: String? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    /** minutes before the due time to remind; null = no reminder (only used when a time is set) */
    val remindBefore: Int? = null,
    val done: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** set when the task was created by voice; keeps the original words */
    val spokenText: String? = null,
    /** Same id on every device; the cloud copy is users/{account}/tasks/{uid}. */
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
    /** Last edit, on any device. The newer copy wins when phone and laptop disagree. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /** Has been sent to the cloud at least once (so if it vanishes there, it was deleted elsewhere). */
    @ColumnInfo(defaultValue = "0") val synced: Boolean = false,
) {
    companion object {
        fun newUid(): String = java.util.UUID.randomUUID().toString().replace("-", "")
    }

    val dueDate: LocalDate? get() = dueEpochDay?.let(LocalDate::ofEpochDay)
    val dueTime: LocalTime? get() = dueMinute?.let { LocalTime.of(it / 60, it % 60) }

    /** Millis at which the reminder should fire, or null. */
    fun reminderAt(): Long? {
        val d = dueDate ?: return null
        val t = dueTime ?: return null
        val before = remindBefore ?: return null
        return LocalDateTime.of(d, t).minusMinutes(before.toLong())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun isOverdue(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (done) return false
        val d = dueDate ?: return false
        val t = dueTime
        return if (t == null) d.isBefore(now.toLocalDate()) else LocalDateTime.of(d, t).isBefore(now)
    }
}
