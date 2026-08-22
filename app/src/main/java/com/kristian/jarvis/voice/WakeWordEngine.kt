package com.kristian.jarvis.voice

import android.content.Context
import android.util.Log

/**
 * A wake-word detector. Kept deliberately small so a different engine can be
 * dropped in without touching anything above it.
 *
 * Contract: [start] begins listening and calls [onDetected] once per wake
 * word, then keeps listening unless the owner calls [stop]. Only one engine
 * may hold the microphone at a time - the [VoiceController] enforces that.
 */
interface WakeWordEngine {

    /** Shown in settings and logs. */
    val name: String

    /** True if this engine can actually run on this device right now. */
    fun isAvailable(): Boolean

    fun start()

    fun stop()

    fun release()

    /** Fired on the main thread when the wake word is heard. */
    var onDetected: (() -> Unit)?

    /** 0f..1f mic level, for the HUD. May never be called by some engines. */
    var onAmplitude: ((Float) -> Unit)?

    /** Human-readable, non-fatal problems worth surfacing. */
    var onError: ((String) -> Unit)?
}

/** Which wake-word implementation to run. */
enum class WakeWordEngineType {
    /**
     * Default. Holds one microphone session and watches the audio level,
     * waking the recogniser only when it hears something. Steady mic
     * indicator, far less battery.
     */
    ENERGY_GATED,

    /**
     * Android's recogniser restarted in a loop. Simple and dependency-free,
     * but it re-opens the microphone every couple of seconds - which makes the
     * privacy indicator blink and costs real battery.
     */
    SPEECH_RECOGNIZER,

    /**
     * Dedicated offline hotword engine. Far better battery life and accuracy,
     * but needs the Porcupine SDK and an access key. See [PorcupineWakeWordEngine].
     */
    PORCUPINE
}

object WakeWordEngines {
    private const val TAG = "WakeWordEngines"

    /**
     * Builds the requested engine, falling back to the SpeechRecognizer one if
     * the requested engine isn't usable on this device.
     */
    fun create(context: Context, type: WakeWordEngineType, keyword: String): WakeWordEngine {
        val engine = when (type) {
            WakeWordEngineType.ENERGY_GATED -> EnergyGatedWakeWordEngine(context, keyword)
            WakeWordEngineType.SPEECH_RECOGNIZER -> SpeechRecognizerWakeWordEngine(context, keyword)
            WakeWordEngineType.PORCUPINE -> PorcupineWakeWordEngine(context, keyword)
        }
        if (engine.isAvailable()) return engine
        Log.w(TAG, "${engine.name} unavailable; falling back to SpeechRecognizer")
        engine.release()
        return SpeechRecognizerWakeWordEngine(context, keyword)
    }
}

/**
 * Drop-in slot for Picovoice Porcupine.
 *
 * Nothing is wired up: shipping it would mean bundling an SDK and asking for
 * an access key up front. To switch over later:
 *
 *  1. add `implementation("ai.picovoice:porcupine-android:3.0.2")`
 *  2. put your access key in the encrypted store (alongside the Anthropic key)
 *  3. build a `PorcupineManager` here with the built-in "jarvis" keyword,
 *     calling [onDetected] from its callback
 *  4. set [WakeWordEngineType.PORCUPINE] in settings
 *
 * The rest of the app needs no changes - it only ever sees [WakeWordEngine].
 */
class PorcupineWakeWordEngine(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val keyword: String
) : WakeWordEngine {

    override val name: String = "Porcupine (not installed)"

    override var onDetected: (() -> Unit)? = null
    override var onAmplitude: ((Float) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    /** False until the SDK and an access key are actually present. */
    override fun isAvailable(): Boolean = false

    override fun start() {
        onError?.invoke("Porcupine isn't installed in this build; using Android speech recognition.")
    }

    override fun stop() = Unit

    override fun release() = Unit
}
