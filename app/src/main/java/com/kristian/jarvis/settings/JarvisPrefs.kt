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

    /** Whether the always-on wake word is armed. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_ENABLED, value).apply()

    /** Which wake-word implementation to run; see WakeWordEngineType. */
    var wakeWordEngine: String
        get() = prefs.getString(KEY_WAKE_ENGINE, DEFAULT_WAKE_ENGINE) ?: DEFAULT_WAKE_ENGINE
        set(value) = prefs.edit().putString(KEY_WAKE_ENGINE, value).apply()

    /** The word that wakes it. Only used by the SpeechRecognizer engine. */
    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.lowercase().trim()).apply()

    /** Which service answers: ANTHROPIC or GEMINI. */
    var provider: String
        get() = prefs.getString(KEY_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    /**
     * Gemini model id; the default sits inside the free tier.
     *
     * Google retires models for new accounts fairly briskly, so a stored id
     * from an older build is migrated forward rather than left to fail on
     * every request.
     */
    var geminiModel: String
        get() {
            val stored = prefs.getString(KEY_GEMINI_MODEL, null) ?: return DEFAULT_GEMINI_MODEL
            if (RETIRED_GEMINI_PREFIXES.any { stored.startsWith(it) }) {
                geminiModel = DEFAULT_GEMINI_MODEL
                return DEFAULT_GEMINI_MODEL
            }
            return stored
        }
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    /** Claude model id used for every request. */
    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /**
     * How hard Claude works per request: low/medium/high/xhigh/max. Spoken
     * answers want speed, so this sits low by default and is raised
     * automatically in narrate mode.
     */
    var effort: String
        get() = prefs.getString(KEY_EFFORT, DEFAULT_EFFORT) ?: DEFAULT_EFFORT
        set(value) = prefs.edit().putString(KEY_EFFORT, value).apply()

    /** Ceiling on one reply. Deliberately modest: these get read aloud. */
    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    /** Sticky "walk me through it" mode. */
    var narrateMode: Boolean
        get() = prefs.getBoolean(KEY_NARRATE, false)
        set(value) = prefs.edit().putBoolean(KEY_NARRATE, value).apply()

    /**
     * Server-side web search. Off by default: it is billed to the same API key
     * per search, so it should be a deliberate choice.
     */
    var webSearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_SEARCH, false)
        set(value) = prefs.edit().putBoolean(KEY_WEB_SEARCH, value).apply()

    /** Persona override; blank means use the built-in prompt. */
    var personaPrompt: String
        get() = prefs.getString(KEY_PERSONA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONA, value).apply()

    companion object {
        private const val KEY_VOICE = "tts_voice_name"
        private const val KEY_RATE = "tts_rate"
        private const val KEY_PITCH = "tts_pitch"
        private const val KEY_SPEECH_ENABLED = "speech_enabled"
        private const val KEY_WAKE_ENABLED = "wake_enabled"
        private const val KEY_WAKE_ENGINE = "wake_engine"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_MODEL = "claude_model"
        private const val KEY_EFFORT = "claude_effort"
        private const val KEY_MAX_TOKENS = "claude_max_tokens"
        private const val KEY_NARRATE = "narrate_mode"
        private const val KEY_PERSONA = "persona_prompt"
        private const val KEY_WEB_SEARCH = "web_search_enabled"
        private const val KEY_PROVIDER = "llm_provider"
        private const val KEY_GEMINI_MODEL = "gemini_model"

        /** Composed and unhurried, without dragging. */
        const val DEFAULT_RATE = 0.92f
        const val DEFAULT_PITCH = 0.95f
        const val DEFAULT_WAKE_WORD = "jarvis"
        const val DEFAULT_WAKE_ENGINE = "ENERGY_GATED"
        const val DEFAULT_MODEL = "claude-opus-5"

        /** Free tier by default - no card, no credits. */
        const val DEFAULT_PROVIDER = "GEMINI"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.6-flash"

        /** Model families Google has closed to new accounts. */
        private val RETIRED_GEMINI_PREFIXES = listOf("gemini-1.", "gemini-2.")
        const val DEFAULT_EFFORT = "low"
        const val DEFAULT_MAX_TOKENS = 4096
    }
}
