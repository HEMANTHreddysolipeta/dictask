package com.hemanth.dictask.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hemanth.dictask.data.Prefs
import com.hemanth.dictask.voice.Groq
import kotlinx.coroutines.launch

const val GROQ_KEYS_URL = "https://console.groq.com/keys"

/** Opens Groq's API-keys page in the browser. */
fun openGroqKeys(ctx: android.content.Context) =
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GROQ_KEYS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/**
 * First-run card: Dictask needs the person's own free Groq key for speech and task reading.
 * The key is checked against Groq before it's saved, so a typo shows up here, not at the first dictation.
 */
@Composable
fun GroqKeyCard(prefs: Prefs, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set up voice in 1 minute", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Dictask uses Groq's free AI to understand what you say. You need your own free key:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "1. Tap below and sign in to Groq (Google sign-in works, no card needed)\n" +
                    "2. Tap \"Create API Key\", give it any name, and copy it\n" +
                    "3. Come back and paste it here",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton({ openGroqKeys(ctx) }) { Text("Open console.groq.com/keys") }
            OutlinedTextField(
                key, { key = it.trim(); error = null }, Modifier.fillMaxWidth(),
                label = { Text("Paste your key (starts with gsk_)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        checking = true
                        scope.launch {
                            error = Groq.checkKey(key)
                            checking = false
                            if (error == null) { prefs.groqKey = key; onSaved() }
                        }
                    },
                    enabled = key.isNotBlank() && !checking,
                ) { Text("Check & save") }
                Spacer(Modifier.width(12.dp))
                if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }
            Text(
                "Your key stays on this phone. Audio is sent to Groq only while you dictate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}
