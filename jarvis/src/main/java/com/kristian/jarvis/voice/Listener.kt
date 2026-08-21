package com.kristian.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Wraps Android's speech recogniser.
 *
 * Every call into [SpeechRecognizer] has to happen on the main thread, so everything here
 * hops onto the main looper before touching it.
 */
class Listener(context: Context) {

    interface Callbacks {
        fun onReady() {}
        fun onLevel(rms: Float) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onEndOfSpeech() {}
        fun onFailure(code: Int, reason: String) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var callbacks: Callbacks? = null

    @Volatile
    var listening: Boolean = false
        private set

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(app)

    fun start(callbacks: Callbacks, preferOffline: Boolean = false) {
        onMain {
            this.callbacks = callbacks
            val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also {
                it.setRecognitionListener(bridge)
                recognizer = it
            }
            listening = true
            runCatching { engine.startListening(intent(preferOffline)) }
                .onFailure {
                    listening = false
                    callbacks.onFailure(-1, "The speech recogniser would not start.")
                }
        }
    }

    /** Stops recording but still delivers whatever was said. */
    fun stop() = onMain {
        runCatching { recognizer?.stopListening() }
    }

    /** Throws away the current utterance. */
    fun abort() = onMain {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    fun destroy() = onMain {
        listening = false
        callbacks = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun intent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_400L,
            )
            if (preferOffline && onDeviceAvailable()) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)

    private val bridge = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callbacks?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            callbacks?.onLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            callbacks?.onEndOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { callbacks?.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val heard = best(results)
            if (heard.isNullOrBlank()) {
                callbacks?.onFailure(SpeechRecognizer.ERROR_NO_MATCH, "I didn't catch that.")
            } else {
                callbacks?.onFinal(heard)
            }
        }

        override fun onError(error: Int) {
            listening = false
            callbacks?.onFailure(error, explain(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun best(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
    }

    private fun explain(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone had a problem."
        SpeechRecognizer.ERROR_CLIENT -> "The recogniser stopped unexpectedly."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone access has not been granted."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a network connection."
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy."
        SpeechRecognizer.ERROR_SERVER -> "The speech service refused the request."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
        else -> "Speech recognition failed."
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
