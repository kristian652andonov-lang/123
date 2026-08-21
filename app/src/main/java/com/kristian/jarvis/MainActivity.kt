package com.kristian.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kristian.jarvis.claude.SecureKeyStore
import com.kristian.jarvis.ui.ApiKeyScreen
import com.kristian.jarvis.ui.AssistantState
import com.kristian.jarvis.ui.ChatMessage
import com.kristian.jarvis.ui.JarvisScreen
import com.kristian.jarvis.ui.rememberJarvisPermissions
import com.kristian.jarvis.ui.theme.JarvisTheme
import com.kristian.jarvis.voice.JarvisTts
import com.kristian.jarvis.voice.VoiceController
import com.kristian.jarvis.voice.VoiceState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                JarvisRoot()
            }
        }
    }
}

/** Key entry stands in front of everything until a key is stored. */
@Composable
private fun JarvisRoot() {
    val context = LocalContext.current
    val keys = remember { SecureKeyStore(context) }
    var hasKey by remember { mutableStateOf(keys.hasApiKey) }

    if (hasKey) {
        JarvisShellDemo()
    } else {
        ApiKeyScreen(
            onSave = { key ->
                keys.apiKey = key
                hasKey = keys.hasApiKey
            }
        )
    }
}

/**
 * Still the interim wiring - Claude arrives in the next step - but voice now
 * works end to end: say "Jarvis", speak a command, watch it transcribe live,
 * and hear it read back.
 */
@Composable
private fun JarvisShellDemo() {
    val context = LocalContext.current
    val permissions = rememberJarvisPermissions()

    val tts = remember { JarvisTts(context) }
    val voice = remember { VoiceController(context) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                ChatMessage.Role.SYSTEM,
                "Voice online. Say \"Jarvis\" then speak, or type below. Claude isn't connected yet."
            )
        )
    }
    var input by remember { mutableStateOf("") }

    val speaking by tts.isSpeaking.collectAsState()
    val voiceState by voice.state.collectAsState()
    val partial by voice.partialTranscript.collectAsState()
    val amplitude by voice.amplitude.collectAsState()
    val ttsError by tts.error.collectAsState()

    // Ask for the microphone (and notifications on 13+) on first launch.
    LaunchedEffect(Unit) { permissions.request() }

    DisposableEffect(Unit) {
        tts.init()
        voice.onNotice = { messages += ChatMessage(ChatMessage.Role.SYSTEM, it) }
        voice.onWakeWord = { messages += ChatMessage(ChatMessage.Role.SYSTEM, "Wake word heard.") }
        voice.onCommand = { command ->
            messages += ChatMessage(ChatMessage.Role.USER, command)
            val reply = "I heard: $command. Claude isn't wired in yet, sir."
            messages += ChatMessage(ChatMessage.Role.ASSISTANT, reply)
            tts.speak(reply)
        }
        onDispose {
            voice.release()
            tts.shutdown()
        }
    }

    // Arm the wake word as soon as the microphone is granted.
    LaunchedEffect(permissions.microphone, permissions.resolved) {
        if (!permissions.resolved) return@LaunchedEffect
        voice.setWakeWordEnabled(permissions.microphone)
        if (!permissions.microphone) {
            messages += ChatMessage(
                ChatMessage.Role.SYSTEM,
                "Microphone permission denied - wake word and voice input are off. " +
                    "You can still type."
            )
        }
    }

    // Never let the wake-word engine hear JARVIS talking to itself.
    LaunchedEffect(speaking) { voice.setMutedForSpeech(speaking) }

    DisposableEffect(ttsError) {
        ttsError?.let {
            messages += ChatMessage(ChatMessage.Role.SYSTEM, it)
            tts.clearError()
        }
        onDispose { }
    }

    val state = when {
        speaking -> AssistantState.SPEAKING
        voiceState == VoiceState.COMMAND_LISTENING -> AssistantState.LISTENING
        else -> AssistantState.IDLE
    }

    // Show the live transcript as a provisional last line while it's being spoken.
    val shown = if (partial.isNotBlank()) {
        messages + ChatMessage(
            role = ChatMessage.Role.USER,
            text = partial,
            id = ChatMessage.LIVE_ID,
            partial = true
        )
    } else {
        messages
    }

    JarvisScreen(
        state = state,
        messages = shown,
        inputText = input,
        onInputChange = { input = it },
        onSend = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                messages += ChatMessage(ChatMessage.Role.USER, text)
                val reply = "Noted: $text. I'll have an answer once Claude is connected, sir."
                messages += ChatMessage(ChatMessage.Role.ASSISTANT, reply)
                tts.speak(reply)
                input = ""
            }
        },
        onMicTap = {
            when {
                speaking -> tts.stop()
                voiceState == VoiceState.COMMAND_LISTENING -> voice.stopCommandListening()
                !permissions.microphone -> permissions.request()
                else -> voice.startCommandListening()
            }
        },
        micEnabled = permissions.microphone,
        amplitude = amplitude
    )
}
