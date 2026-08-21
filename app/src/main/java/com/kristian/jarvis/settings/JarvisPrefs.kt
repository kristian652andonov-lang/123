package com.kristian.jarvis.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Ordinary (non-secret) settings. The Anthropic API key deliberately does not
 * live here - it gets its own encrypted store in the Claude step.
 */
class JarvisPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    /** Name of the TTS voice the user picked, or null for "best available". */
    var voiceName: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    /** Speech rate multiplier; below 1.0 reads unhurried. */
    var speechRate: Float
        get() = prefs.getFloat(KEY_RATE, DEFAULT_RATE)
        set(value) = prefs.edit().putFloat(KEY_RATE, value).apply()

    /** Pitch multiplier; slightly below 1.0 sits lower and calmer. */
    var speechPitch: Float
        get() = prefs.getFloat(KEY_PITCH, DEFAULT_PITCH)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value).apply()

    /** Whether spoken output is enabled at all. */
    var speechEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEECH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEECH_ENABLED, value).apply()

    companion object {
        private const val KEY_VOICE = "tts_voice_name"
        private const val KEY_RATE = "tts_rate"
        private const val KEY_PITCH = "tts_pitch"
        private const val KEY_SPEECH_ENABLED = "speech_enabled"

        /** Composed and unhurried, without dragging. */
        const val DEFAULT_RATE = 0.92f
        const val DEFAULT_PITCH = 0.95f
    }
}
