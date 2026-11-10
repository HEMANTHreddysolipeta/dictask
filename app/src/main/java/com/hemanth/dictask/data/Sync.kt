package com.hemanth.dictask.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Keeps this phone's tasks and the cloud copy (shared with the laptop app) in step.
 * Room stays the source the UI, widget and reminders read; the cloud is a mirror both devices write to.
 * Conflicts: the copy with the later [Task.updatedAt] wins.
 */
object Sync {
    private const val TAG = "DictaskSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var listener: ListenerRegistration? = null

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val email: String? get() = auth.currentUser?.email
    val signedIn: Boolean get() = auth.currentUser != null

    private fun col() = auth.currentUser?.let {
        FirebaseFirestore.getInstance().collection("users").document(it.uid).collection("tasks")
    }

    // ---- outgoing ----

    /** Queued by Firestore when offline, sent when the network is back. */
    fun push(t: Task) {
        val c = col() ?: return
        c.document(t.uid).set(toDoc(t)).addOnFailureListener { Log.w(TAG, "push failed", it) }
    }

    fun remove(uid: String) {
        col()?.document(uid)?.delete()?.addOnFailureListener { Log.w(TAG, "delete failed", it) }
    }

    private fun toDoc(t: Task) = hashMapOf<String, Any?>(
        "title" to t.title, "notes" to t.notes, "dueEpochDay" to t.dueEpochDay, "dueMinute" to t.dueMinute,
        "priority" to t.priority, "listName" to t.listName, "recurrence" to t.recurrence.name,
        "remindBefore" to t.remindBefore, "done" to t.done, "completedAt" to t.completedAt,
        "createdAt" to t.createdAt, "spokenText" to t.spokenText, "updatedAt" to t.updatedAt,
    )

    private fun fromDoc(d: DocumentSnapshot) = Task(
        uid = d.id,
        title = d.getString("title").orEmpty(),
        notes = d.getString("notes").orEmpty(),
        dueEpochDay = d.getLong("dueEpochDay"),
        dueMinute = d.getLong("dueMinute")?.toInt(),
        priority = d.getLong("priority")?.toInt() ?: Priority.NONE,
        listName = d.getString("listName"),
        recurrence = runCatching { Recurrence.valueOf(d.getString("recurrence") ?: "NONE") }.getOrDefault(Recurrence.NONE),
        remindBefore = d.getLong("remindBefore")?.toInt(),
        done = d.getBoolean("done") ?: false,
        completedAt = d.getLong("completedAt"),
        createdAt = d.getLong("createdAt") ?: System.currentTimeMillis(),
        spokenText = d.getString("spokenText"),
        updatedAt = d.getLong("updatedAt") ?: 0,
        synced = true,
    )

    // ---- incoming ----

    /** Live updates while the app's process is alive. Safe to call repeatedly. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        schedulePeriodic(app)
        if (listener != null) return
        val c = col() ?: return
        listener = c.addSnapshotListener { snap, err ->
            if (err != null || snap == null) { Log.w(TAG, "listen", err); return@addSnapshotListener }
            val removed = snap.documentChanges.filter { it.type == DocumentChange.Type.REMOVED }.map { it.document.id }
            val docs = snap.documents.map(::fromDoc)
            val complete = !snap.metadata.isFromCache
            scope.launch { merge(app, docs, removed, complete) }
        }
    }

    fun stop() {
        listener?.remove(); listener = null
    }

    /** One full fetch from the server (periodic background sync, app open, before dictation). */
    suspend fun pullOnce(ctx: Context): Boolean {
        val c = col() ?: return false
        return runCatching {
            // Unsent local edits must reach the server first, or a fresh task would look "deleted elsewhere".
            val flushed = withTimeoutOrNull(10_000) { FirebaseFirestore.getInstance().waitForPendingWrites().await() } != null
            val snap = c.get(Source.SERVER).await()
            merge(ctx.applicationContext, snap.documents.map(::fromDoc), emptyList(), complete = flushed)
            true
        }.getOrElse { Log.w(TAG, "pull", it); false }
    }

    /**
     * @param complete the docs are everything in the cloud (not a partial cache view), so local tasks
     * missing from it were either deleted elsewhere (already synced) or never uploaded (upload now).
     */
    private suspend fun merge(ctx: Context, remote: List<Task>, removed: List<String>, complete: Boolean) = lock.withLock {
        val repo = Repo(ctx)
        val changed = repo.mergeRemote(remote, removed, complete)
        if (changed > 0) Log.i(TAG, "merged $changed change(s) from cloud")
    }

    suspend fun signIn(ctx: Context, email: String, password: String, create: Boolean) {
        if (create) auth.createUserWithEmailAndPassword(email.trim(), password).await()
        else auth.signInWithEmailAndPassword(email.trim(), password).await()
        start(ctx)
        pullOnce(ctx)
    }

    suspend fun signOut(ctx: Context) {
        stop()
        auth.signOut()
        // Tasks stay on the phone; if another account signs in later they are uploaded there, not deleted.
        AppDb.get(ctx).tasks().markAllUnsynced()
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    private const val WORK = "dictask-sync"

    private fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Picks up tasks added on the laptop while the phone app is closed, so the widget and reminders catch up. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = if (Sync.pullOnce(applicationContext)) Result.success() else Result.retry()
}
