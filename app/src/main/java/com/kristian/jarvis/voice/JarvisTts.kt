package com.kristian.jarvis.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.kristian.jarvis.settings.JarvisPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything spoken by JARVIS goes through here.
 *
 * Picks a British English voice - offline and high quality where the device
 * offers one - and speaks at a rate and pitch tuned to sound composed. Text
 * can be pushed in progressively while Claude is still writing, via
 * [speakStreaming]; complete sentences start playing immediately.
 *
 * This deliberately uses the device's own synthesiser. It evokes the tone of
 * the character; it does not imitate any actor's performance.
 */
class JarvisTts(
    context: Context,
    private val prefs: JarvisPrefs = JarvisPrefs(context)
) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** Set to surface a human-readable problem (no voice data, engine missing). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val pendingUtterances = AtomicInteger(0)
    private val chunker = SpeechChunker()
    private var utteranceCounter = 0L

    /** Called when the last queued utterance finishes. */
    var onSpokenComplete: (() -> Unit)? = null

    fun init(onReady: (() -> Unit)? = null) {
        if (tts != null) {
            onReady?.invoke()
            return
        }
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configure()
                _isReady.value = true
                onReady?.invoke()
            } else {
                _error.value = "No text-to-speech engine is available on this device."
                Log.w(TAG, "TTS init failed with status $status")
            }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) = finishOne()

                @Deprecated("Superseded by onError(String, int)")
                override fun onError(utteranceId: String?) = finishOne()

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "Utterance $utteranceId failed with code $errorCode")
                    finishOne()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    pendingUtterances.set(0)
                    _isSpeaking.value = false
                }
            })
        }
    }

    private fun finishOne() {
        if (pendingUtterances.decrementAndGet() <= 0) {
            pendingUtterances.set(0)
            _isSpeaking.value = false
            onSpokenComplete?.invoke()
        }
    }

    private fun configure() {
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.UK)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
            _error.value = "British English voice data isn't installed; using the default voice. " +
                "Install it under Settings > Accessibility > Text-to-speech."
        }
        selectVoice(prefs.voiceName)
        engine.setSpeechRate(prefs.speechRate)
        engine.setPitch(prefs.speechPitch)
    }

    /**
     * British English voices installed on this device, best first - offline
     * and higher quality voices are preferred. Used to populate a picker.
     */
    fun availableVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        val voices = runCatching { engine.voices }.getOrNull() ?: return emptyList()
        return voices
            .filter { it.locale.language.equals("en", ignoreCase = true) }
            .sortedWith(
                compareByDescending<Voice> { it.locale.country.uppercase() in BRITISH_COUNTRIES }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )
    }

    /** Applies a voice by name; passing null picks the best British voice. */
    fun selectVoice(name: String?) {
        val engine = tts ?: return
        val voices = availableVoices()
        val chosen = name?.let { wanted -> voices.firstOrNull { it.name == wanted } }
            ?: voices.firstOrNull { it.locale.country.uppercase() in BRITISH_COUNTRIES }
            ?: voices.firstOrNull()
        if (chosen != null) {
            engine.voice = chosen
            prefs.voiceName = chosen.name
        }
    }

    fun setRateAndPitch(rate: Float, pitch: Float) {
        prefs.speechRate = rate
        prefs.speechPitch = pitch
        tts?.setSpeechRate(rate)
        tts?.setPitch(pitch)
    }

    /** Speaks [text] immediately, cutting off anything already playing. */
    fun speak(text: String) {
        chunker.flush()
        stop()
        enqueue(text.forSpeech(), flush = true)
    }

    /**
     * Feeds streamed text in. Complete sentences are spoken as they arrive;
     * call [endStream] when the response is finished to speak the remainder.
     */
    fun speakStreaming(delta: String) {
        chunker.offer(delta).forEach { enqueue(it.forSpeech(), flush = false) }
    }

    fun endStream() {
        chunker.flush()?.let { enqueue(it.forSpeech(), flush = false) }
    }

    private fun enqueue(text: String, flush: Boolean) {
        if (!prefs.speechEnabled) return
        val engine = tts ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val id = "jarvis-${utteranceCounter++}"
        pendingUtterances.incrementAndGet()
        _isSpeaking.value = true
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(clean, mode, Bundle(), id)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() rejected utterance $id")
            finishOne()
        }
    }

    /** Stops mid-sentence and clears the queue. */
    fun stop() {
        chunker.flush()
        tts?.stop()
        pendingUtterances.set(0)
        _isSpeaking.value = false
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        _isReady.value = false
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "JarvisTts"
        private val BRITISH_COUNTRIES = setOf("GB", "GBR", "UK")
    }
}
