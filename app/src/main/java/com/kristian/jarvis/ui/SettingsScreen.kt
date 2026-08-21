package com.kristian.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kristian.jarvis.claude.Persona
import com.kristian.jarvis.claude.SecureKeyStore
import com.kristian.jarvis.core.JarvisEngine
import com.kristian.jarvis.settings.JarvisPrefs
import com.kristian.jarvis.ui.theme.JarvisPalette
import com.kristian.jarvis.ui.theme.JarvisTheme

/**
 * Everything configurable, in one scroll: who JARVIS is, how it sounds, what
 * it listens for, and which model answers.
 */
@Composable
fun SettingsScreen(
    prefs: JarvisPrefs,
    voices: List<JarvisEngine.VoiceOption>,
    maskedApiKey: String?,
    onBack: () -> Unit,
    onVoiceSelected: (String) -> Unit,
    onRateAndPitch: (Float, Float) -> Unit,
    onPreviewVoice: () -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onWakeWordEnabled: (Boolean) -> Unit,
    onClearConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    var persona by remember { mutableStateOf(prefs.personaPrompt) }
    var model by remember { mutableStateOf(prefs.model) }
    var wakeWord by remember { mutableStateOf(prefs.wakeWord) }
    var wakeEnabled by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var speechEnabled by remember { mutableStateOf(prefs.speechEnabled) }
    var webSearch by remember { mutableStateOf(prefs.webSearchEnabled) }
    var rate by remember { mutableFloatStateOf(prefs.speechRate) }
    var pitch by remember { mutableFloatStateOf(prefs.speechPitch) }
    var newKey by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisPalette.Void)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = JarvisPalette.Cyan
                )
            }
            Text(
                text = "S E T T I N G S",
                style = MaterialTheme.typography.titleMedium,
                color = JarvisPalette.TextPrimary
            )
        }

        Section("Voice") {
            SwitchRow(
                label = "Speak replies aloud",
                checked = speechEnabled,
                onChange = {
                    speechEnabled = it
                    prefs.speechEnabled = it
                }
            )

            if (voices.isEmpty()) {
                Hint("No voices reported yet - they appear once the speech engine is ready.")
            } else {
                Hint("British voices are listed first. Tap one to hear it.")
                voices.take(12).forEach { voice ->
                    SelectableRow(
                        label = voice.label,
                        sublabel = voice.name,
                        selected = voice.selected,
                        onClick = { onVoiceSelected(voice.name) }
                    )
                }
            }

            LabelledSlider(
                label = "Rate",
                value = rate,
                range = 0.6f..1.3f,
                onChange = { rate = it },
                onSettled = { onRateAndPitch(rate, pitch) }
            )
            LabelledSlider(
                label = "Pitch",
                value = pitch,
                range = 0.7f..1.2f,
                onChange = { pitch = it },
                onSettled = { onRateAndPitch(rate, pitch) }
            )
            TextButtonRow("Preview voice", onPreviewVoice)
        }

        Section("Wake word") {
            SwitchRow(
                label = "Listen for the wake word",
                checked = wakeEnabled,
                onChange = {
                    wakeEnabled = it
                    prefs.wakeWordEnabled = it
                    onWakeWordEnabled(it)
                }
            )
            Field(
                value = wakeWord,
                onChange = {
                    wakeWord = it
                    prefs.wakeWord = it
                },
                label = "Wake word"
            )
            Hint(
                "Continuous listening is the biggest battery cost in this app. Turning it off " +
                    "leaves the mic button and typing, and costs almost nothing."
            )
        }

        Section("Claude") {
            Field(
                value = model,
                onChange = {
                    model = it
                    prefs.model = it
                },
                label = "Model"
            )
            Hint(
                "claude-opus-5 is the default. claude-sonnet-5 replies faster and costs less; " +
                    "claude-haiku-4-5 is faster still."
            )
            SwitchRow(
                label = "Allow web search",
                checked = webSearch,
                onChange = {
                    webSearch = it
                    prefs.webSearchEnabled = it
                }
            )
            Hint("Runs on Anthropic's side and is billed to the same key, per search.")

            Field(
                value = newKey,
                onChange = { newKey = it },
                label = maskedApiKey?.let { "Replace API key (current: $it)" } ?: "API key",
                secret = true
            )
            if (newKey.isNotBlank()) {
                TextButtonRow(
                    label = if (SecureKeyStore.looksLikeAnthropicKey(newKey)) {
                        "Save key"
                    } else {
                        "That doesn't look like an sk-ant- key"
                    },
                    onClick = {
                        if (SecureKeyStore.looksLikeAnthropicKey(newKey)) {
                            onApiKeyChanged(newKey.trim())
                            newKey = ""
                        }
                    }
                )
            }
        }

        Section("Persona") {
            Hint("Blank uses the built-in JARVIS prompt. Anything here replaces it entirely.")
            Field(
                value = persona,
                onChange = {
                    persona = it
                    prefs.personaPrompt = it
                },
                label = "System prompt",
                singleLine = false
            )
            TextButtonRow("Load the built-in prompt to edit") {
                persona = Persona.DEFAULT_SYSTEM_PROMPT
                prefs.personaPrompt = persona
            }
            TextButtonRow("Reset to built-in") {
                persona = ""
                prefs.personaPrompt = ""
            }
        }

        Section("Conversation") {
            TextButtonRow("Clear conversation history", onClearConversation)
            Hint("Older turns are trimmed automatically; this wipes the lot.")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .background(JarvisPalette.Panel.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .border(1.dp, JarvisPalette.PanelEdge, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.Cyan
        )
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = JarvisPalette.TextSecondary
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = JarvisPalette.TextPrimary
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = JarvisPalette.CyanBright,
                checkedTrackColor = JarvisPalette.Cyan.copy(alpha = 0.35f),
                uncheckedThumbColor = JarvisPalette.TextSecondary,
                uncheckedTrackColor = JarvisPalette.Panel
            )
        )
    }
}

@Composable
private fun SelectableRow(
    label: String,
    sublabel: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) JarvisPalette.Cyan.copy(alpha = 0.12f) else JarvisPalette.Void,
                RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) JarvisPalette.Cyan.copy(alpha = 0.6f) else JarvisPalette.PanelEdge,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) JarvisPalette.CyanBright else JarvisPalette.TextPrimary
        )
        Text(
            text = sublabel,
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.TextSecondary
        )
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onSettled: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label  ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelSmall,
            color = JarvisPalette.TextSecondary
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onSettled,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = JarvisPalette.CyanBright,
                activeTrackColor = JarvisPalette.Cyan,
                inactiveTrackColor = JarvisPalette.PanelEdge
            )
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = JarvisPalette.TextSecondary
            )
        },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 5,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = RoundedCornerShape(10.dp),
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisPalette.Cyan,
            unfocusedBorderColor = JarvisPalette.PanelEdge,
            focusedContainerColor = JarvisPalette.Void,
            unfocusedContainerColor = JarvisPalette.Void,
            cursorColor = JarvisPalette.Cyan,
            focusedTextColor = JarvisPalette.TextPrimary,
            unfocusedTextColor = JarvisPalette.TextPrimary
        )
    )
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = JarvisPalette.Cyan,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF04070D, widthDp = 400, heightDp = 900)
@Composable
private fun SettingsPreview() {
    JarvisTheme {
        SettingsScreen(
            prefs = JarvisPrefs(androidx.compose.ui.platform.LocalContext.current),
            voices = emptyList(),
            maskedApiKey = "sk-ant-a…9f2c",
            onBack = {},
            onVoiceSelected = {},
            onRateAndPitch = { _, _ -> },
            onPreviewVoice = {},
            onApiKeyChanged = {},
            onWakeWordEnabled = {},
            onClearConversation = {}
        )
    }
}
