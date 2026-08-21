package com.kristian.jarvis.claude

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for secrets - currently the Anthropic API key and an
 * optional web search key.
 *
 * The key is written to EncryptedSharedPreferences, whose master key lives in
 * the Android Keystore and never leaves it. Nothing here is ever logged, and
 * the key is only ever read to build the x-api-key header.
 */
class SecureKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // A rotated or corrupted master key can make the file unreadable.
            // Dropping it costs the user a re-entry; leaving it breaks the app.
            Log.w(TAG, "Encrypted store unreadable, recreating", t)
            runCatching { appContext.deleteSharedPreferences(FILE_NAME) }
            runCatching {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrNull()
        }
    }

    var apiKey: String?
        get() = prefs?.getString(KEY_ANTHROPIC, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.putString(KEY_ANTHROPIC, value?.trim().orEmpty())?.apply()
        }

    /** Optional; enables the web search tool when present. */
    var searchApiKey: String?
        get() = prefs?.getString(KEY_SEARCH, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.putString(KEY_SEARCH, value?.trim().orEmpty())?.apply()
        }

    val hasApiKey: Boolean get() = apiKey != null

    /** For display only - never render the whole key. */
    fun maskedApiKey(): String? {
        val key = apiKey ?: return null
        if (key.length <= 12) return "****"
        return "${key.take(8)}…${key.takeLast(4)}"
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val FILE_NAME = "jarvis_secrets"
        private const val KEY_ANTHROPIC = "anthropic_api_key"
        private const val KEY_SEARCH = "search_api_key"

        /** Cheap sanity check so an obvious paste error is caught before a 401. */
        fun looksLikeAnthropicKey(candidate: String): Boolean =
            candidate.trim().startsWith("sk-ant-") && candidate.trim().length > 20
    }
}
