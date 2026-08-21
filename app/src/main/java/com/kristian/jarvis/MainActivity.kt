package com.kristian.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kristian.jarvis.ui.AssistantState
import com.kristian.jarvis.ui.ChatMessage
import com.kristian.jarvis.ui.JarvisScreen
import com.kristian.jarvis.ui.theme.JarvisTheme

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
 * Temporary local wiring so the shell can be exercised on-device before the
 * speech, Claude and service layers exist. Replaced by the real ViewModel in
 * the orchestration step: sending echoes back, and the mic button just walks
 * through the states so each animation can be checked.
 */
@Composable
private fun JarvisShellDemo() {
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                ChatMessage.Role.SYSTEM,
                "Shell online. Voice, Claude and the background service are not wired up yet."
            )
        )
    }
    var input by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(AssistantState.IDLE) }

    JarvisScreen(
        state = state,
        messages = messages,
        inputText = input,
        onInputChange = { input = it },
        onSend = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                messages += ChatMessage(ChatMessage.Role.USER, text)
                messages += ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "Noted, sir. I'm not yet connected to anything that can act on that."
                )
                input = ""
            }
        },
        onMicTap = {
            state = AssistantState.entries[(state.ordinal + 1) % AssistantState.entries.size]
        },
        micEnabled = true
    )
}
