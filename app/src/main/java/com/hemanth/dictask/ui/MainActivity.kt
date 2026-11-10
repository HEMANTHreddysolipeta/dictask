package com.hemanth.dictask.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.action.ActionParameters
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hemanth.dictask.data.Prefs
import com.hemanth.dictask.data.Repo
import com.hemanth.dictask.data.Task
import com.hemanth.dictask.reminders.Reminders
import com.hemanth.dictask.voice.Groq
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainVm(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)
    val prefs = Prefs(app)
    val tasks = repo.all.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(t: Task) = viewModelScope.launch { if (t.id == 0L) repo.add(t) else repo.save(t) }
    fun setDone(t: Task, d: Boolean) = viewModelScope.launch { repo.setDone(t, d) }
    fun delete(t: Task) = viewModelScope.launch { repo.delete(t) }
    fun restore(t: Task) = viewModelScope.launch { repo.restore(t) }
    fun clearDone() = viewModelScope.launch { repo.clearDone() }

    /** Typed quick-add goes through the same parser as dictation, so "pay rent friday" gets a date. */
    fun quickAdd(text: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val drafts = runCatching { Groq.parse(text, prefs.groqKey, repo.pending()) }.getOrNull()
        if (drafts.isNullOrEmpty()) {
            repo.add(Task(title = text.trim().replaceFirstChar { it.uppercase() }))
            onResult(if (drafts == null) "Added (offline, no date detected)" else "Added")
        } else {
            // Typed edits ("move gym to 7am") apply straight away; the snackbar says what changed.
            onResult(repo.apply(drafts, spoken = text, defaultRemind = prefs.defaultRemind))
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        val ADD_PARAM = ActionParameters.Key<Boolean>("open_add")
        val TASK_PARAM = ActionParameters.Key<Long>("open_task")
    }

    /** What the widget or a notification asked us to open. */
    var openAdd by mutableStateOf(false)
    var openTaskId by mutableStateOf<Long?>(null)

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Reminders.createChannels(this)
        Reminders.scheduleDigest(this)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        readIntent(intent)
        setContent { AppTheme { App(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(ADD_PARAM.name, false)) openAdd = true
        val id = i.getLongExtra(TASK_PARAM.name, -1)
        if (id > 0) openTaskId = id
        i.removeExtra(ADD_PARAM.name)
        i.removeExtra(TASK_PARAM.name)
    }
}
