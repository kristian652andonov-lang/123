package com.kristian.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The voice. Text is queued a sentence at a time as it streams in, so Jarvis starts
 * talking while Claude is still writing.
 */
class Speaker(context: Context) {

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)

    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = AtomicInteger(0)
    private var counter = 0L

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()

    fun start(rate: Float, pitch: Float) {
        if (engine != null) {
            configure(rate, pitch)
            return
        }
        engine = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) return@TextToSpeech
            engine?.let { tts ->
                tts.language = preferredLocale(tts)
                pickVoice(tts)
                tts.setOnUtteranceProgressListener(progress)
                configure(rate, pitch)
            }
        }
    }

    fun configure(rate: Float, pitch: Float) {
        engine?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        engine?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    /** Queues one chunk. Chunks are spoken in the order they are handed over. */
    fun say(text: String) {
        val body = clean(text)
        if (body.isEmpty()) return
        val tts = engine ?: return
        if (!ready) return

        if (pending.getAndIncrement() == 0) {
            audio?.requestAudioFocus(focusRequest)
            _speaking.value = true
        }
        counter++
        tts.speak(body, TextToSpeech.QUEUE_ADD, null, "jarvis-$counter")
    }

    fun stop() {
        engine?.stop()
        pending.set(0)
        _speaking.value = false
        audio?.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = finished()

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = finished()

        override fun onError(utteranceId: String?, errorCode: Int) = finished()

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()

        private fun finished() {
            if (pending.decrementAndGet() <= 0) {
                pending.set(0)
                _speaking.value = false
                audio?.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    private fun preferredLocale(tts: TextToSpeech): Locale {
        val british = Locale.UK
        val supported = tts.isLanguageAvailable(british)
        return if (supported == TextToSpeech.LANG_AVAILABLE ||
            supported == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            supported == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        ) {
            british
        } else {
            Locale.getDefault()
        }
    }

    /** Prefer a high quality, locally installed British voice — it is the whole illusion. */
    private fun pickVoice(tts: TextToSpeech) {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull() ?: return
        val best = voices
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .maxWithOrNull(
                compareBy<Voice>(
                    { if (it.locale.country == "GB") 1 else 0 },
                    { it.quality },
                ),
            )
        if (best != null) runCatching { tts.voice = best }
    }

    /** Strips anything a synthesiser would read out as noise. */
    private fun clean(text: String): String = text
        .replace(Regex("""```[\s\S]*?```"""), " ")
        .replace(Regex("""[*_#`>|]"""), "")
        .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
