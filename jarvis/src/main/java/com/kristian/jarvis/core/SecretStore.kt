package com.kristian.jarvis.core

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API key encrypted with a key that lives in the Android Keystore, so the
 * secret is not sitting in plain text inside the app's preferences file.
 *
 * If a device's keystore misbehaves (it happens on a few OEM builds) we fall back to
 * storing the raw value — app-private storage is still not readable by other apps on an
 * unrooted phone.
 */
object SecretStore {

    private const val ALIAS = "jarvis_api_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun write(prefs: SharedPreferences, name: String, value: String) {
        val stored = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" + Base64.encodeToString(body, Base64.NO_WRAP)
        } catch (t: Throwable) {
            "raw:$value"
        }
        prefs.edit().putString(name, stored).apply()
    }

    fun read(prefs: SharedPreferences, name: String): String {
        val stored = prefs.getString(name, "").orEmpty()
        if (stored.isEmpty()) return ""
        if (stored.startsWith("raw:")) return stored.removePrefix("raw:")
        if (!stored.startsWith("v1:")) return stored
        return try {
            val parts = stored.split(":")
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val body = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
