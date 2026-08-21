package com.kristian.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kristian.jarvis.ui.AssistantState
import com.kristian.jarvis.ui.ChatMessage
import com.kristian.jarvis.ui.JarvisScreen
import com.kristian.jarvis.ui.theme.JarvisTheme
import com.kristian.jarvis.voice.JarvisTts

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                JarvisShellDemo()
            }
        }
    }
}

/**
 * Temporary local wiring, replaced by the real ViewModel in the orchestration
 * step. It now runs the voice layer for real: anything you send is spoken back
 * by [JarvisTts], and the HUD follows the actual speaking state.
 */
@Composable
private fun JarvisShellDemo() {
    val context = LocalContext.current
    val tts = remember { JarvisTts(context) }

    DisposableEffect(Unit) {
        tts.init()
        onDispose { tts.shutdown() }
    }

    val speaking by tts.isSpeaking.collectAsState()
    val ttsError by tts.error.collectAsState()

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                ChatMessage.Role.SYSTEM,
                "Shell online. Voice output is live; speech input and Claude are not wired up yet."
            )
        )
    }
    var input by remember { mutableStateOf("") }

    // Surface a TTS problem in the transcript exactly once.
    DisposableEffect(ttsError) {
        ttsError?.let {
            messages += ChatMessage(ChatMessage.Role.SYSTEM, it)
            tts.clearError()
        }
        onDispose { }
    }

    JarvisScreen(
        state = if (speaking) AssistantState.SPEAKING else AssistantState.IDLE,
        messages = messages,
        inputText = input,
        onInputChange = { input = it },
        onSend = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                messages += ChatMessage(ChatMessage.Role.USER, text)
                val reply = "You said: $text. I'm not yet connected to Claude, sir, " +
                    "but my voice is working."
                messages += ChatMessage(ChatMessage.Role.ASSISTANT, reply)
                tts.speak(reply)
                input = ""
            }
        },
        onMicTap = {
            if (speaking) {
                tts.stop()
            } else {
                val line = "Voice check. Rate and pitch are set for something composed " +
                    "and unhurried. Speech input arrives in the next step."
                messages += ChatMessage(ChatMessage.Role.ASSISTANT, line)
                tts.speak(line)
            }
        },
        micEnabled = true
    )
}
