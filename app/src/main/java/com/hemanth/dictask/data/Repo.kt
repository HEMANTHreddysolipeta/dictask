package com.hemanth.dictask.data

import android.content.Context
import com.hemanth.dictask.reminders.Reminders
import com.hemanth.dictask.voice.Draft
import com.hemanth.dictask.voice.Kind
import com.hemanth.dictask.widget.TaskWidget
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Every write goes through here so reminders, the home-screen widget and cloud sync always stay in step. */
class Repo(context: Context) {
    private val ctx = context.applicationContext
    private val dao = AppDb.get(ctx).tasks()

    val all: Flow<List<Task>> = dao.all()
    suspend fun pending() = dao.pending()
    val pendingFlow: Flow<List<Task>> = dao.pendingFlow()
    suspend fun get(id: Long) = dao.get(id)

    /** Marks a local edit: newest-wins timestamp, and whether it is going to the cloud. */
    private fun stamp(t: Task) = t.copy(updatedAt = System.currentTimeMillis(), synced = t.synced || Sync.signedIn)

    private suspend fun insert(t: Task): Task {
        val s = stamp(t)
        val id = dao.insert(s)
        val saved = s.copy(id = id)
        Reminders.schedule(ctx, saved)
        Sync.push(saved)
        return saved
    }

    private suspend fun update(t: Task): Task {
        val s = stamp(t)
        dao.update(s)
        Sync.push(s)
        return s
    }

    private suspend fun remove(t: Task) {
        dao.delete(t)
        Reminders.cancel(ctx, t.id)
        Sync.remove(t.uid)
    }

    suspend fun add(t: Task): Long {
        val id = insert(t).id
        refresh()
        return id
    }

    suspend fun addAll(ts: List<Task>) {
        ts.forEach { insert(it) }
        refresh()
    }

    suspend fun save(t: Task) {
        Reminders.schedule(ctx, update(t))
        refresh()
    }

    suspend fun delete(t: Task) {
        remove(t)
        refresh()
    }

    /** Undo of a delete: same shared id, so the laptop gets it back too. */
    suspend fun restore(t: Task) {
        insert(t.copy(id = 0, synced = false))
        refresh()
    }

    /** Ticking a repeating task completes it and creates the next occurrence. */
    suspend fun setDone(t: Task, done: Boolean) {
        if (done == t.done) return
        val updated = update(t.copy(done = done, completedAt = if (done) System.currentTimeMillis() else null))
        Reminders.cancel(ctx, t.id)
        if (done && t.recurrence != Recurrence.NONE) {
            val base = t.dueDate ?: LocalDate.now()
            // A long-overdue repeating task jumps to its next occurrence that is still ahead.
            val nextDay = Recurrence.firstNotPast(t.recurrence, t.recurrence.next(base), t.dueTime)
            insert(t.copy(
                id = 0, uid = Task.newUid(), synced = false, done = false, completedAt = null,
                createdAt = System.currentTimeMillis(), dueEpochDay = nextDay.toEpochDay(),
            ))
        } else if (!done) {
            Reminders.schedule(ctx, updated)
        }
        refresh()
    }

    /** Applies what was dictated or typed: new tasks plus edits to existing ones. Returns a short summary. */
    suspend fun apply(drafts: List<Draft>, spoken: String, defaultRemind: Int): String {
        var added = 0; var changed = 0; var done = 0; var deleted = 0
        for (d in drafts) {
            when (d.kind) {
                Kind.ADD -> { insert(d.toTask(spoken, defaultRemind)); added++ }
                Kind.UPDATE -> dao.get(d.targetId ?: continue)?.let { Reminders.schedule(ctx, update(d.applyTo(it, defaultRemind))); changed++ }
                Kind.DONE -> dao.get(d.targetId ?: continue)?.let { setDone(it, true); done++ }
                Kind.DELETE -> dao.get(d.targetId ?: continue)?.let { remove(it); deleted++ }
            }
        }
        refresh()
        fun n(c: Int, one: String, many: String) = if (c == 1) "1 $one" else "$c $many"
        return buildList {
            if (added > 0) add("Added " + n(added, "task", "tasks"))
            if (changed > 0) add("Updated " + n(changed, "task", "tasks"))
            if (done > 0) add("Completed " + n(done, "task", "tasks"))
            if (deleted > 0) add("Deleted " + n(deleted, "task", "tasks"))
        }.joinToString(", ").ifEmpty { "Nothing changed" }
    }

    suspend fun toggle(id: Long) {
        dao.get(id)?.let { setDone(it, !it.done) }
    }

    suspend fun clearDone() {
        dao.doneTasks().forEach { Sync.remove(it.uid) }
        dao.clearDone()
        refresh()
    }

    suspend fun snooze(id: Long, minutes: Int) {
        val t = dao.get(id) ?: return
        Reminders.scheduleAt(ctx, t, System.currentTimeMillis() + minutes * 60_000L)
    }

    /**
     * Applies cloud changes (made on the laptop, or on this phone earlier) to the local copy.
     * Returns how many local tasks changed.
     */
    suspend fun mergeRemote(remote: List<Task>, removed: List<String>, complete: Boolean): Int {
        var n = 0
        for (r in remote) {
            val local = dao.byUid(r.uid)
            when {
                local == null -> { val id = dao.insert(r); Reminders.schedule(ctx, r.copy(id = id)); n++ }
                r.updatedAt > local.updatedAt -> {
                    val t = r.copy(id = local.id)
                    dao.update(t); Reminders.schedule(ctx, t); n++
                }
                r.updatedAt < local.updatedAt -> Sync.push(local) // this phone has the newer edit
                !local.synced -> dao.update(local.copy(synced = true))
            }
        }
        for (uid in removed) dao.byUid(uid)?.let { dao.delete(it); Reminders.cancel(ctx, it.id); n++ }
        if (complete) {
            val inCloud = remote.mapTo(HashSet()) { it.uid }
            for (t in dao.everything()) {
                if (t.uid in inCloud) continue
                if (t.synced) { dao.delete(t); Reminders.cancel(ctx, t.id); n++ } // deleted on the other device
                else { dao.update(t.copy(synced = true)); Sync.push(t) } // never uploaded yet
            }
        }
        if (n > 0) refresh()
        return n
    }

    private suspend fun refresh() = TaskWidget.refresh(ctx)
}
