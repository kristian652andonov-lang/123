package com.kristian.jarvis.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kristian.jarvis.core.JarvisEngine

private data class Power(
    val label: String,
    val explanation: String,
    val permission: String,
)

private val POWERS = listOf(
    Power("Microphone", "Hearing you at all.", Manifest.permission.RECORD_AUDIO),
    Power("Contacts", "Calling and texting people by name.", Manifest.permission.READ_CONTACTS),
    Power("Phone", "Dialling without you pressing call.", Manifest.permission.CALL_PHONE),
    Power("Messages", "Sending a text without you pressing send.", Manifest.permission.SEND_SMS),
    Power("Location", "Answering anything about where you are.", Manifest.permission.ACCESS_FINE_LOCATION),
)

@Composable
fun SettingsScreen(
    engine: JarvisEngine,
    isGranted: (String) -> Boolean,
    onRequestPermission: (String) -> Unit,
    onWakeWordChanged: (Boolean) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by engine.prefs.state.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Hud.Void)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Hud.Cyan)
            }
            Text(
                text = "CONFIGURATION",
                style = HudLabel,
                color = Hud.Cyan,
                fontSize = 13.sp,
            )
        }

        Section("Connection")
        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = { key -> engine.prefs.update { it.copy(apiKey = key.trim()) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text("Anthropic API key", color = Hud.Muted) },
            singleLine = true,
            visualTransformation = if (showKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Text(
                    text = if (showKey) "hide" else "show",
                    color = Hud.Cyan,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { showKey = !showKey }
                        .padding(horizontal = 12.dp),
                )
            },
            shape = RoundedCornerShape(12.dp),
            colors = fieldColours(),
        )
        Hint(
            "Create one at console.anthropic.com, under API keys. It is stored encrypted on " +
                "this phone and sent only to Anthropic. Usage is billed to your own account.",
        )

        Section("Model")
        ChipRow(
            options = listOf(
                "claude-opus-5" to "Opus 5",
                "claude-sonnet-5" to "Sonnet 5",
                "claude-haiku-4-5" to "Haiku 4.5",
            ),
            selected = settings.model,
            onSelect = { model -> engine.prefs.update { it.copy(model = model) } },
        )
        Hint("Opus is the sharpest. Haiku is the cheapest and quickest to answer.")

        if (!settings.model.startsWith("claude-haiku")) {
            Section("Thinking effort")
            ChipRow(
                options = listOf("low" to "Low", "medium" to "Medium", "high" to "High"),
                selected = settings.effort,
                onSelect = { effort -> engine.prefs.update { it.copy(effort = effort) } },
            )
            Hint("Low keeps replies snappy. High is better at anything that needs real thought.")
        }

        Section("Manner")
        OutlinedTextField(
            value = settings.address,
            onValueChange = { name -> engine.prefs.update { it.copy(address = name) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text("What Jarvis calls you", color = Hud.Muted) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = fieldColours(),
        )

        Toggle("Speak replies out loud", settings.speakReplies) { on ->
            engine.prefs.update { it.copy(speakReplies = on) }
        }
        Toggle("Search the web when needed", settings.webSearch) { on ->
            engine.prefs.update { it.copy(webSearch = on) }
        }

        Section("Voice")
        SliderRow(
            label = "Speed",
            value = settings.speechRate,
            range = 0.6f..1.6f,
            onChange = { value ->
                engine.prefs.update { it.copy(speechRate = value) }
                engine.refreshVoice()
            },
        )
        SliderRow(
            label = "Pitch",
            value = settings.speechPitch,
            range = 0.6f..1.4f,
            onChange = { value ->
                engine.prefs.update { it.copy(speechPitch = value) }
                engine.refreshVoice()
            },
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Pill("Test the voice") {
                engine.refreshVoice()
                engine.say("All systems nominal, ${settings.address.ifBlank { "sir" }}.")
            }
        }

        Section("Hands free")
        Toggle(
            label = "Listen for \"Jarvis\" all the time",
            checked = settings.wakeWordEnabled,
            onChange = { on ->
                engine.prefs.update { it.copy(wakeWordEnabled = on) }
                onWakeWordChanged(on)
            },
        )
        Hint(
            "Keeps the microphone open in the background and costs battery. It needs the " +
                "microphone permission, and after a restart on Android 12 or newer you have to " +
                "open Jarvis once to start it again.",
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Pill("Allow appearing over other apps") { onOpenOverlaySettings() }
        }
        Hint(
            "Without that, Android blocks Jarvis from coming to the screen when you call him " +
                "while another app is open, and anything he opens for you fails quietly.",
        )

        Section("What Jarvis may do unaided")
        Toggle("Place calls without asking", settings.allowCalls) { on ->
            engine.prefs.update { it.copy(allowCalls = on) }
        }
        Toggle("Send texts without asking", settings.allowMessages) { on ->
            engine.prefs.update { it.copy(allowMessages = on) }
        }
        Hint("With these off, Jarvis still opens the dialer or the message ready to go — you press the button.")

        Section("Permissions")
        POWERS.forEach { power ->
            val granted = isGranted(power.permission)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !granted) { onRequestPermission(power.permission) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(power.label, color = Hud.Text, fontSize = 15.sp)
                    Text(power.explanation, color = Hud.Muted, fontSize = 12.sp)
                }
                Text(
                    text = if (granted) "granted" else "grant",
                    color = if (granted) Hud.Muted else Hud.Cyan,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Section("What Jarvis remembers")
        if (settings.memory.isEmpty()) {
            Hint("Nothing yet. Tell him something worth keeping and he will store it himself.")
        } else {
            settings.memory.forEach { (key, value) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(key, color = Hud.Cyan, fontSize = 14.sp)
                        Text(value, color = Hud.Text, fontSize = 14.sp)
                    }
                    IconButton(onClick = { engine.prefs.forget(key) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Forget", tint = Hud.Muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "Jarvis runs on Claude. Every request leaves this phone for Anthropic's API.",
            color = Hud.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title.uppercase(),
        style = HudLabel,
        color = Hud.CyanDim,
        modifier = Modifier.padding(start = 20.dp, top = 26.dp, bottom = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = Hud.Muted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Hud.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Hud.Void,
                checkedTrackColor = Hud.Cyan,
                uncheckedThumbColor = Hud.Muted,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = Hud.CyanDim,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text("$label  ${String.format(Locale.UK, "%.2f", value)}", color = Hud.Muted, fontSize = 13.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Hud.Cyan,
                activeTrackColor = Hud.Cyan,
                inactiveTrackColor = Hud.CyanDim,
            ),
        )
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .border(
                        1.dp,
                        if (active) Hud.Cyan else Hud.CyanDim,
                        RoundedCornerShape(20.dp),
                    )
                    .background(
                        if (active) Hud.Cyan.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(20.dp),
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    color = if (active) Hud.CyanGlow else Hud.Muted,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, Hud.CyanDim, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = Hud.Cyan, fontSize = 14.sp)
    }
}

@Composable
private fun fieldColours() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Hud.Cyan,
    unfocusedBorderColor = Hud.CyanDim,
    focusedTextColor = Hud.Text,
    unfocusedTextColor = Hud.Text,
    cursorColor = Hud.Cyan,
    focusedLabelColor = Hud.Cyan,
    focusedContainerColor = Hud.Deep,
    unfocusedContainerColor = Hud.Deep,
)
