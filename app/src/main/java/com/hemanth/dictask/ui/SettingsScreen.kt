package com.hemanth.dictask.ui

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import com.hemanth.dictask.data.Sync
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hemanth.dictask.data.Prefs
import com.hemanth.dictask.reminders.Reminders
import com.hemanth.dictask.widget.TaskWidgetReceiver
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(prefs.groqKey) }
    var autoStop by remember { mutableStateOf(prefs.autoStop) }
    var skipReview by remember { mutableStateOf(prefs.skipReview) }
    var lang by remember { mutableStateOf(prefs.language) }
    var digestOn by remember { mutableStateOf(prefs.digestOn) }
    var digestMin by remember { mutableIntStateOf(prefs.digestMinute) }
    var defRemind by remember { mutableIntStateOf(prefs.defaultRemind) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SyncSection()

            HorizontalDivider()
            Section("Voice (Whisper via Groq)")
            OutlinedTextField(
                key, { key = it; prefs.groqKey = it }, Modifier.fillMaxWidth(),
                label = { Text("Groq API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("Free key from console.groq.com. Audio is sent to Groq only while you dictate.") },
            )
            Text("Spoken language", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("en" to "English", "" to "Auto-detect", "hi" to "Hindi", "te" to "Telugu").forEach { (code, name) ->
                    FilterChip(lang == code, { lang = code; prefs.language = code }, { Text(name) })
                }
            }
            var vocab by remember { mutableStateOf(prefs.vocab) }
            OutlinedTextField(
                vocab, { vocab = it; prefs.vocab = it }, Modifier.fillMaxWidth(),
                label = { Text("Words Whisper should know") },
                placeholder = { Text("Adi, Anna, pitch deck, sales call flow") },
                supportingText = { Text("Names and work terms, separated by commas. Helps most when you speak softly.") },
                minLines = 2,
            )
            Toggle("Stop recording when I pause", "Ends after about 2 seconds of silence", autoStop) { autoStop = it; prefs.autoStop = it }
            Toggle("Save without review", "Add dictated tasks straight away", skipReview) { skipReview = it; prefs.skipReview = it }

            HorizontalDivider()
            Section("Reminders")
            Text("When a dictated task has a time but you didn't ask for a reminder", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "No reminder", 0 to "Remind on time", 10 to "10 min before", 30 to "30 min before").forEach { (m, l) ->
                    FilterChip(defRemind == m, { defRemind = m; prefs.defaultRemind = m }, { Text(l) })
                }
            }
            Toggle("Morning summary", "A notification with what's due today and overdue", digestOn) {
                digestOn = it; prefs.digestOn = it; Reminders.scheduleDigest(ctx)
            }
            if (digestOn) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6 * 60, 7 * 60, 8 * 60, 9 * 60, 10 * 60).forEach { m ->
                    FilterChip(digestMin == m, { digestMin = m; prefs.digestMinute = m; Reminders.scheduleDigest(ctx) },
                        { Text(timeLabel(LocalTime.of(m / 60, 0))) })
                }
            }
            if (Build.VERSION.SDK_INT >= 31 && !ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                OutlinedButton({
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Allow on-time reminders") }
            }

            HorizontalDivider()
            Section("Home screen")
            Text(
                "Long-press the home screen → Widgets → Dictask. You can also long-press the app icon for \"Dictate a task\", " +
                    "or add the Dictask tile to Quick Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (Build.VERSION.SDK_INT >= 26) {
                val awm = ctx.getSystemService(AppWidgetManager::class.java)
                if (awm.isRequestPinAppWidgetSupported) OutlinedButton({
                    awm.requestPinAppWidget(ComponentName(ctx, TaskWidgetReceiver::class.java), null, null)
                }) { Text("Add widget to home screen") }
            }
            Text("Dictask 0.1 · tasks are stored only on this phone", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Section(s: String) =
    Text(s, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, set)
    }
}

/** Same email + password on the phone and the laptop app = the same task list on both. */
@Composable
private fun SyncSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(Sync.email) }
    var typed by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun go(create: Boolean) {
        busy = true; msg = null
        scope.launch {
            runCatching { Sync.signIn(ctx, typed, pass, create) }
                .onSuccess { email = Sync.email; pass = "" }
                .onFailure { e ->
                    val m = e.message.orEmpty()
                    msg = when {
                        "password is invalid" in m || "credential" in m.lowercase() ->
                            "Wrong email or password. New here? Tap Create account."
                        "already in use" in m -> "That email already has an account. Tap Sign in."
                        "at least 6" in m || "WEAK_PASSWORD" in m -> "Use a password of at least 6 characters."
                        "network" in m.lowercase() -> "No internet. Try again when you're online."
                        else -> m.ifBlank { "Couldn't sign in." }
                    }
                }
            busy = false
        }
    }

    Section("Sync with laptop")
    if (email != null) {
        Text("Signed in as $email. Tasks sync with the Dictask desktop app signed in with the same account.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton({ scope.launch { Sync.signOut(ctx); email = null } }) { Text("Sign out") }
    } else {
        Text("Sign in here and in the laptop app with the same email and password. Your current tasks are kept and uploaded.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ go(false) }, enabled = !busy && typed.isNotBlank() && pass.isNotBlank()) { Text("Sign in") }
            OutlinedButton({ go(true) }, enabled = !busy && typed.isNotBlank() && pass.length >= 6) { Text("Create account") }
        }
    }
}
