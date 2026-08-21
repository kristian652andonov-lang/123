package com.kristian.jarvis.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * One-shot speech capture for an actual command, once the wake word (or the
 * mic button) has armed it.
 *
 * Unlike the wake-word engine this uses the full online recogniser where
 * available - accuracy matters more than battery for a single utterance - and
 * reports partial text as you speak so the HUD can show it live.
 */
class CommandRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Live text as it is being recognised. */
    var onPartial: ((String) -> Unit)? = null

    /** The finished command. Fired at most once per [start]. */
    var onFinal: ((String) -> Unit)? = null

    /** Heard nothing usable. The caller decides whether to say so. */
    var onNoSpeech: (() -> Unit)? = null

    /** A real problem, phrased for a human. */
    var onError: ((String) -> Unit)? = null

    var onAmplitude: ((Float) -> Unit)? = null

    /** Fired when the mic is actually open, so the HUD flips at the right moment. */
    var onReady: (() -> Unit)? = null

    val isListening: Boolean get() = listening

    fun start() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke("Microphone permission is required to listen.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError?.invoke("No speech recognition service is installed on this device.")
            return
        }

        val engine = recognizer ?: runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrNull()?.also {
            it.setRecognitionListener(listener)
            recognizer = it
        } ?: run {
            onError?.invoke("Speech recognition could not be started.")
            return
        }

        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Long enough to think mid-sentence, short enough not to hang.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1600L
            )
        }
        runCatching { engine.startListening(intent) }.onFailure {
            Log.w(TAG, "startListening failed", it)
            listening = false
            onError?.invoke("Speech recognition could not be started.")
        }
    }

    /** Stop capturing but still deliver whatever was heard so far. */
    fun stop() {
        if (!listening) return
        runCatching { recognizer?.stopListening() }
    }

    /** Abandon the utterance entirely. */
    fun cancel() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onReady?.invoke()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            onAmplitude?.invoke(SpeechRecognizerWakeWordEngine.normaliseRms(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onNoSpeech?.invoke()

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    onError?.invoke("Speech recognition needs a network connection right now.")

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onError?.invoke("Microphone permission is required to listen.")

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    onError?.invoke("The recogniser is busy; try again in a moment.")

                else -> onError?.invoke("Speech recognition failed (code $error).")
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrEmpty()) onNoSpeech?.invoke() else onFinal?.invoke(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { onPartial?.invoke(it) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "CommandRecognizer"
    }
}
