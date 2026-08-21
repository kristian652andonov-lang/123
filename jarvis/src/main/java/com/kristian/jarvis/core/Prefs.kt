package com.kristian.jarvis.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class Settings(
    val apiKey: String = "",
    val model: String = "claude-opus-5",
    val effort: String = "low",
    val address: String = "sir",
    val speakReplies: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val webSearch: Boolean = true,
    val allowCalls: Boolean = false,
    val allowMessages: Boolean = false,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 0.85f,
    val memory: Map<String, String> = emptyMap(),
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
}

/** Everything Jarvis remembers between launches. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        save(next)
        _state.value = next
    }

    fun remember(key: String, value: String) =
        update { it.copy(memory = it.memory + (key.trim() to value.trim())) }

    fun forget(key: String): Boolean {
        val hit = current.memory.keys.firstOrNull { it.equals(key.trim(), ignoreCase = true) }
            ?: return false
        update { it.copy(memory = it.memory - hit) }
        return true
    }

    private fun load(): Settings {
        val memory = runCatching {
            val raw = JSONObject(prefs.getString(KEY_MEMORY, "{}").orEmpty().ifBlank { "{}" })
            buildMap<String, String> {
                raw.keys().forEach { key -> put(key, raw.optString(key)) }
            }
        }.getOrDefault(emptyMap())
        val defaults = Settings()
        return Settings(
            apiKey = SecretStore.read(prefs, KEY_API),
            model = prefs.getString(KEY_MODEL, defaults.model) ?: defaults.model,
            effort = prefs.getString(KEY_EFFORT, defaults.effort) ?: defaults.effort,
            address = prefs.getString(KEY_ADDRESS, defaults.address) ?: defaults.address,
            speakReplies = prefs.getBoolean(KEY_SPEAK, defaults.speakReplies),
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE, defaults.wakeWordEnabled),
            webSearch = prefs.getBoolean(KEY_SEARCH, defaults.webSearch),
            allowCalls = prefs.getBoolean(KEY_CALLS, defaults.allowCalls),
            allowMessages = prefs.getBoolean(KEY_SMS, defaults.allowMessages),
            speechRate = prefs.getFloat(KEY_RATE, defaults.speechRate),
            speechPitch = prefs.getFloat(KEY_PITCH, defaults.speechPitch),
            memory = memory,
        )
    }

    private fun save(settings: Settings) {
        SecretStore.write(prefs, KEY_API, settings.apiKey)
        val memory = JSONObject()
        settings.memory.forEach { (key, value) -> memory.put(key, value) }
        prefs.edit()
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_EFFORT, settings.effort)
            .putString(KEY_ADDRESS, settings.address)
            .putBoolean(KEY_SPEAK, settings.speakReplies)
            .putBoolean(KEY_WAKE, settings.wakeWordEnabled)
            .putBoolean(KEY_SEARCH, settings.webSearch)
            .putBoolean(KEY_CALLS, settings.allowCalls)
            .putBoolean(KEY_SMS, settings.allowMessages)
            .putFloat(KEY_RATE, settings.speechRate)
            .putFloat(KEY_PITCH, settings.speechPitch)
            .putString(KEY_MEMORY, memory.toString())
            .apply()
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_EFFORT = "effort"
        const val KEY_ADDRESS = "address"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_WAKE = "wake_word"
        const val KEY_SEARCH = "web_search"
        const val KEY_CALLS = "allow_calls"
        const val KEY_SMS = "allow_messages"
        const val KEY_RATE = "speech_rate"
        const val KEY_PITCH = "speech_pitch"
        const val KEY_MEMORY = "memory"
    }
}
