package com.kristian.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kristian.jarvis.settings.JarvisPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the microphone is currently doing. */
enum class VoiceState { OFF, WAKE_LISTENING, COMMAND_LISTENING }

/**
 * Owns the microphone and decides who gets it.
 *
 * Android hands out one recognition session at a time, so the wake-word engine
 * and the command recogniser can never both be running. This is the only place
 * that transition is allowed to happen, which keeps "listening for Jarvis" and
 * "listening to you" from fighting over the mic.
 *
 * It also stands down while JARVIS is speaking, so it doesn't wake itself up.
 */
class VoiceController(
    context: Context,
    private val prefs: JarvisPrefs = JarvisPrefs(context)
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VoiceState.OFF)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Live transcript while a command is being spoken. */
    private val _partial = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partial.asStateFlow()

    /** 0f..1f mic level for the HUD. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** A finished spoken command. */
    var onCommand: ((String) -> Unit)? = null

    /** Notable events worth showing in the transcript. */
    var onNotice: ((String) -> Unit)? = null

    /** Fired the moment the wake word is heard, before capture starts. */
    var onWakeWord: (() -> Unit)? = null

    private var wakeWordArmed = false
    private var mutedForSpeech = false

    private val wakeEngine: WakeWordEngine = WakeWordEngines.create(
        context = appContext,
        type = runCatching { WakeWordEngineType.valueOf(prefs.wakeWordEngine) }
            .getOrDefault(WakeWordEngineType.SPEECH_RECOGNIZER),
        keyword = prefs.wakeWord
    ).apply {
        onDetected = { handleWakeWord() }
        onAmplitude = { if (_state.value == VoiceState.WAKE_LISTENING) _amplitude.value = it }
        onError = { message -> onNotice?.invoke(message) }
    }

    private val commandRecognizer = CommandRecognizer(appContext).apply {
        onReady = { _state.value = VoiceState.COMMAND_LISTENING }
        onPartial = { _partial.value = it }
        onAmplitude = { _amplitude.value = it }
        onFinal = { text ->
            _partial.value = ""
            _amplitude.value = 0f
            onCommand?.invoke(text)
            resumeWakeWord()
        }
        onNoSpeech = {
            _partial.value = ""
            _amplitude.value = 0f
            onNotice?.invoke("I didn't catch that, sir.")
            resumeWakeWord()
        }
        onError = { message ->
            _partial.value = ""
            _amplitude.value = 0f
            onNotice?.invoke(message)
            resumeWakeWord()
        }
    }

    /** The engine actually in use, for display in settings. */
    val wakeEngineName: String get() = wakeEngine.name

    /** Arms or disarms always-on wake-word listening. */
    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.wakeWordEnabled = enabled
        wakeWordArmed = enabled
        if (enabled) resumeWakeWord() else stopEverything()
    }

    fun isWakeWordEnabled(): Boolean = wakeWordArmed

    /** Start capturing a command right now (mic button, or after a wake word). */
    fun startCommandListening() {
        if (_state.value == VoiceState.COMMAND_LISTENING) return
        wakeEngine.stop()
        _state.value = VoiceState.COMMAND_LISTENING
        _partial.value = ""
        // Give the wake-word session a moment to release the mic before grabbing it.
        handler.postDelayed({ commandRecognizer.start() }, MIC_HANDOVER_MS)
    }

    /** Finish the current utterance early (second tap on the mic button). */
    fun stopCommandListening() {
        commandRecognizer.stop()
    }

    /**
     * Stand down while JARVIS is speaking, then pick standby back up. Without
     * this the wake-word engine hears the assistant's own voice.
     */
    fun setMutedForSpeech(muted: Boolean) {
        if (mutedForSpeech == muted) return
        mutedForSpeech = muted
        if (muted) {
            wakeEngine.stop()
            if (_state.value == VoiceState.WAKE_LISTENING) _state.value = VoiceState.OFF
        } else {
            resumeWakeWord()
        }
    }

    private fun handleWakeWord() {
        if (_state.value == VoiceState.COMMAND_LISTENING) return
        onWakeWord?.invoke()
        startCommandListening()
    }

    private fun resumeWakeWord() {
        _amplitude.value = 0f
        if (!wakeWordArmed || mutedForSpeech) {
            _state.value = VoiceState.OFF
            return
        }
        if (!wakeEngine.isAvailable()) {
            _state.value = VoiceState.OFF
            return
        }
        // Same handover delay: the command session needs to let go first.
        handler.postDelayed({
            if (wakeWordArmed && !mutedForSpeech && !commandRecognizer.isListening) {
                _state.value = VoiceState.WAKE_LISTENING
                wakeEngine.start()
            }
        }, MIC_HANDOVER_MS)
    }

    private fun stopEverything() {
        wakeEngine.stop()
        commandRecognizer.cancel()
        _state.value = VoiceState.OFF
        _partial.value = ""
        _amplitude.value = 0f
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { wakeEngine.release() }
        runCatching { commandRecognizer.release() }
        _state.value = VoiceState.OFF
        Log.d(TAG, "Voice controller released")
    }

    companion object {
        private const val TAG = "VoiceController"

        /** Android needs a beat to hand the mic between recognition sessions. */
        private const val MIC_HANDOVER_MS = 300L
    }
}
