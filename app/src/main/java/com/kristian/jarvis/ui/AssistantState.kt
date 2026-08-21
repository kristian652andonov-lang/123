package com.kristian.jarvis.ui

import androidx.compose.ui.graphics.Color
import com.kristian.jarvis.ui.theme.JarvisPalette

/**
 * What JARVIS is doing right now. The HUD animation, the status readout and
 * the mic button all key off this single value so they can never disagree.
 */
enum class AssistantState(val label: String, val accent: Color) {
    /** Idle, but the wake word is being listened for in the background. */
    IDLE("STANDBY", JarvisPalette.CyanDim),

    /** Wake word heard (or mic tapped) — actively recording a command. */
    LISTENING("LISTENING", JarvisPalette.CyanBright),

    /** Command sent, waiting on Claude. */
    THINKING("PROCESSING", JarvisPalette.Amber),

    /** Speaking a response aloud. */
    SPEAKING("SPEAKING", JarvisPalette.Cyan),

    /** Something went wrong; details live in the transcript. */
    ERROR("FAULT", JarvisPalette.Danger)
}
