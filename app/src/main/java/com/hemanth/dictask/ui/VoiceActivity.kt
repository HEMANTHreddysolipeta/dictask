package com.hemanth.dictask.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hemanth.dictask.R
import com.hemanth.dictask.data.Prefs
import com.hemanth.dictask.data.Repo
import com.hemanth.dictask.data.Task
import com.hemanth.dictask.voice.Draft
import com.hemanth.dictask.voice.Kind
import com.hemanth.dictask.voice.Groq
import com.hemanth.dictask.voice.Recorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed interface VoiceState {
    data object NeedPermission : VoiceState
    data object Recording : VoiceState
    data class Working(val step: String, val transcript: String? = null) : VoiceState
    data class Review(val transcript: String, val drafts: List<Draft>, val targets: Map<Long, Task>) : VoiceState
    data class Failed(val message: String, val transcript: String? = null) : VoiceState
}

/**
 * Pops up over whatever is on screen (widget, Quick Settings tile, app shortcut, share sheet),
 * records straight away, and turns the words into tasks.
 */
class VoiceActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var repo: Repo
    private val recorder by lazy { Recorder(this) }
    private var state by mutableStateOf<VoiceState>(VoiceState.Working("Starting…"))
    private var level by mutableFloatStateOf(0f)
    private var elapsed by mutableLongStateOf(0L)
    private var loop: Job? = null
    private var lastAudio: File? = null

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else state = VoiceState.NeedPermission
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = Repo(this)
        setContent { AppTheme { VoiceSheet() } }

        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        when {
            !shared.isNullOrBlank() -> understand(shared)
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording()
            else -> micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving mid-recording (home button, screen off) shouldn't keep the mic open.
        if (state == VoiceState.Recording) { loop?.cancel(); recorder.stopQuietly(); finish() }
    }

    private fun startRecording() {
        runCatching { recorder.start() }.onFailure {
            state = VoiceState.Failed("Couldn't start the microphone: ${it.message}")
            return
        }
        state = VoiceState.Recording
        heardSpeech = false
        loop = lifecycleScope.launch {
            var quietSince = System.currentTimeMillis()
            // Learn the room's background level first, so a whisper in a quiet room still counts as speech
            // while a noisy street doesn't keep the recording open forever.
            val floorSamples = mutableListOf<Int>()
            var speechLevel = 900
            var keepLevel = 500
            var loudRun = 0
            var maxSeen = 0
            while (isActive) {
                delay(100)
                val amp = recorder.level()
                elapsed = recorder.elapsedMs()
                if (amp > maxSeen) maxSeen = amp
                // Show quiet voices on the meter too (square-root scale).
                level = kotlin.math.sqrt((amp / 20000f).coerceIn(0f, 1f))
                val now = System.currentTimeMillis()
                if (elapsed < 600) {
                    if (amp > 0) floorSamples += amp
                    if (floorSamples.isNotEmpty()) {
                        val floor = floorSamples.sorted()[floorSamples.size / 2]
                        speechLevel = (floor * 2.5f).toInt().coerceIn(250, 2500)
                        // Once talking, a softer level still counts (whispers trail off between words).
                        keepLevel = (floor * 1.5f).toInt().coerceIn(150, 1200)
                    }
                    continue
                }
                // Speech starts only after ~0.3 s of sound, so a tap or bump on the phone doesn't count.
                loudRun = if (amp > speechLevel) loudRun + 1 else 0
                if (loudRun >= 3) heardSpeech = true
                if (amp > (if (heardSpeech) keepLevel else speechLevel)) quietSince = now
                if (prefs.autoStop) {
                    if (heardSpeech && elapsed > 3000 && now - quietSince > 2500) break
                    // Only give up when the mic picked up essentially nothing; anything louder gets sent.
                    if (!heardSpeech && elapsed > 10_000) {
                        if (maxSeen < 150) {
                            // Pure silence: don't send it, Whisper would make words up.
                            recorder.stopQuietly()
                            state = VoiceState.Failed("Didn't hear anything. Tap Record again, or hold the phone a bit closer.")
                            return@launch
                        }
                        break
                    }
                }
                if (elapsed > 120_000) break
            }
            if (isActive) finishRecording()
        }
    }

    private var heardSpeech = false

    private fun finishRecording() {
        loop?.cancel()
        val f = recorder.stop()
        if (f == null) { state = VoiceState.Failed("Didn't catch anything. Try again a little closer to the mic."); return }
        lastAudio = f
        transcribe(f)
    }

    private fun transcribe(f: File) {
        state = VoiceState.Working("Transcribing…")
        lifecycleScope.launch {
            val vocab = prefs.vocab.split(',', '\n') + repo.pending().mapNotNull { it.listName }
            val text = runCatching { Groq.transcribe(f, prefs.groqKey, prefs.language, vocab) }
                .getOrElse { state = VoiceState.Failed(it.message ?: "Transcription failed"); return@launch }
            if (text.isBlank()) { state = VoiceState.Failed("Didn't catch any words. Try again a little louder or closer."); return@launch }
            Log.d("Dictask", "heard: $text")
            understand(text)
        }
    }

    private fun understand(text: String) {
        state = VoiceState.Working("Finding your tasks…", text)
        lifecycleScope.launch {
            val open = repo.pending()
            val drafts = runCatching { Groq.parse(text, prefs.groqKey, open) }.getOrElse {
                // Words are safe even if the parser failed: offer to save them as-is.
                state = VoiceState.Failed(it.message ?: "Couldn't read tasks from that", text); return@launch
            }
            // Edits and deletions always get a look first, even with "Save without review" on.
            if (drafts.isNotEmpty() && prefs.skipReview && drafts.all { it.kind == Kind.ADD }) save(text, drafts)
            else state = VoiceState.Review(text, drafts, open.associateBy { it.id })
        }
    }

    private fun save(transcript: String, drafts: List<Draft>) {
        lifecycleScope.launch {
            val summary = repo.apply(drafts, transcript, prefs.defaultRemind)
            lastAudio?.delete()
            val msg = if (drafts.size == 1 && drafts[0].kind == Kind.ADD) "Added: ${drafts[0].title}" else summary
            Toast.makeText(this@VoiceActivity, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun saveRaw(text: String) = save(text, listOf(
        Draft(text.trim().replaceFirstChar { it.uppercase() }.take(200), "", null, null, null, null, null, null)
    ))

    private fun close() { loop?.cancel(); recorder.stopQuietly(); lastAudio?.delete(); finish() }

    @Composable
    private fun VoiceSheet() {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, null) { if (state !is VoiceState.Review) close() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier.navigationBarsPadding().imePadding().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (val s = state) {
                        VoiceState.NeedPermission -> {
                            Text("Dictask needs the microphone to hear your tasks.", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(::close) { Text("Cancel") }
                                Button({ micPerm.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                            }
                        }
                        VoiceState.Recording -> RecordingUi()
                        is VoiceState.Working -> {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(s.step, style = MaterialTheme.typography.titleMedium)
                            s.transcript?.let { Transcript(it) }
                        }
                        is VoiceState.Review -> ReviewUi(s)
                        is VoiceState.Failed -> {
                            Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                            s.transcript?.let { Transcript(it) }
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(::close) { Text("Cancel") }
                                if (s.transcript != null) OutlinedButton({ saveRaw(s.transcript) }) { Text("Save as is") }
                                else lastAudio?.let { f -> OutlinedButton({ transcribe(f) }) { Text("Retry") } }
                                Button({ lastAudio?.delete(); lastAudio = null; startRecording() }) { Text("Record again") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RecordingUi() {
        val scale by animateFloatAsState(1f + level * 0.6f, label = "level")
        Text("Listening…", style = MaterialTheme.typography.titleLarge)
        Text(
            "Add tasks, or change one: \"move gym to 7am\"",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(88.dp).scale(scale).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
            Box(
                Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { finishRecording() },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_stop), "Stop", Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
        Spacer(Modifier.height(12.dp))
        Text("%d:%02d".format(elapsed / 60000, (elapsed / 1000) % 60), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(::close) { Text("Cancel") }
            Button(::finishRecording) { Text("Done") }
        }
    }

    @Composable
    private fun ReviewUi(s: VoiceState.Review) {
        var drafts by remember(s) { mutableStateOf(s.drafts) }
        var keep by remember(s) { mutableStateOf(s.drafts.map { true }) }
        val allAdds = drafts.all { it.kind == Kind.ADD }
        Text(
            when {
                drafts.isEmpty() -> "No task found in that"
                allAdds -> if (drafts.size == 1) "1 task" else "${drafts.size} tasks"
                else -> if (drafts.size == 1) "1 change" else "${drafts.size} changes"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Transcript(s.transcript)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            itemsIndexed(drafts) { i, d ->
                val target = d.targetId?.let { s.targets[it] }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(keep[i], { v -> keep = keep.toMutableList().also { it[i] = v } })
                    Column(Modifier.weight(1f)) {
                        if (!allAdds) KindBadge(d.kind)
                        when (d.kind) {
                            Kind.ADD, Kind.UPDATE -> {
                                // Wraps instead of scrolling sideways, so the cursor can be placed anywhere.
                                OutlinedTextField(
                                    d.title.ifBlank { target?.title.orEmpty() },
                                    { v -> drafts = drafts.toMutableList().also { it[i] = d.copy(title = v) } },
                                    Modifier.fillMaxWidth(), maxLines = 4,
                                )
                                val after = if (d.kind == Kind.ADD) d.toTask("", prefs.defaultRemind)
                                    else target?.let { d.applyTo(it, prefs.defaultRemind) }
                                if (after != null) {
                                    val meta = listOfNotNull(
                                        dueLabel(after),
                                        after.remindBefore?.let { "🔔 " + remindLabel(it) },
                                        after.listName?.let { "# $it" },
                                        if (after.priority >= 2) com.hemanth.dictask.data.Priority.labels[after.priority] + " priority" else null,
                                        d.notes.takeIf { it.isNotBlank() },
                                    ).joinToString("  ·  ")
                                    val was = if (d.kind == Kind.UPDATE && target != null && dueLabel(target) != dueLabel(after))
                                        "was ${dueLabel(target) ?: "no date"}  →  " else ""
                                    if (meta.isNotEmpty() || was.isNotEmpty()) Text(was + meta.ifEmpty { "no date" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                                }
                            }
                            Kind.DONE, Kind.DELETE -> Text(
                                target?.title.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                textDecoration = if (d.kind == Kind.DELETE) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(::close) { Text("Cancel") }
            OutlinedButton({ lastAudio?.delete(); lastAudio = null; startRecording() }) { Text("Again") }
            val chosen = drafts.filterIndexed { i, d -> keep[i] && (d.kind != Kind.ADD || d.title.isNotBlank()) }
            if (drafts.isEmpty()) Button({ saveRaw(s.transcript) }) { Text("Save words") }
            else Button({ save(s.transcript, chosen) }, enabled = chosen.isNotEmpty()) {
                Text(
                    when {
                        !allAdds -> "Apply"
                        chosen.size == 1 -> "Add task"
                        else -> "Add ${chosen.size}"
                    }
                )
            }
        }
    }

    @Composable
    private fun KindBadge(k: Kind) {
        val (label, color) = when (k) {
            Kind.ADD -> "New" to MaterialTheme.colorScheme.primary
            Kind.UPDATE -> "Change" to Color(0xFFE37400)
            Kind.DONE -> "Mark done" to Color(0xFF1E8E3E)
            Kind.DELETE -> "Delete" to MaterialTheme.colorScheme.error
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
    }

    @Composable
    private fun Transcript(t: String) {
        Text("\"$t\"", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Quick Settings tile: pull down the shade, tap, speak. */
class DictateTile : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
