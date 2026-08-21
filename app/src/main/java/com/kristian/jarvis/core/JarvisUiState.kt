package com.kristian.jarvis.core

import com.kristian.jarvis.ui.AssistantState
import com.kristian.jarvis.ui.ChatMessage

/** Everything the HUD needs to draw itself, in one immutable snapshot. */
data class JarvisUiState(
    val state: AssistantState = AssistantState.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    /** Live speech-to-text while a command is being spoken. */
    val partialTranscript: String = "",
    val amplitude: Float = 0f,
    /** "Walk me through it" mode. */
    val narrate: Boolean = false,
    val micAvailable: Boolean = false,
    val wakeWordArmed: Boolean = false,
    /** True from sending a request until the reply is finished. */
    val busy: Boolean = false
)
