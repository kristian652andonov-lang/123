package com.kristian.jarvis.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Wake-word detection built on Android's own recogniser, restarted in a loop.
 *
 * Free and keyless, which is the point - but it is the battery-hungry option,
 * because the recogniser is running whenever JARVIS is on standby. Swap in
 * [PorcupineWakeWordEngine] later if that matters.
 *
 * Everything here runs on the main thread: SpeechRecognizer requires it.
 */
class SpeechRecognizerWakeWordEngine(
    context: Context,
    private val keyword: String = "jarvis",
    /**
     * When true, listen exactly once and report back instead of restarting.
     * Used by [EnergyGatedWakeWordEngine], which only wants a single check
     * after it has heard something worth checking.
     */
    private val singleShot: Boolean = false
) : WakeWordEngine {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var consecutiveFailures = 0

    override val name: String = "Android SpeechRecognizer"

    override var onDetected: (() -> Unit)? = null
    override var onAmplitude: ((Float) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    /** Single-shot mode only: this listen is over and the mic is free. */
    var onFinished: (() -> Unit)? = null

    override fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext) && hasMicPermission()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun start() {
        if (running) return
        if (!hasMicPermission()) {
            onError?.invoke("Microphone permission is required for the wake word.")
            return
        }
        running = true
        consecutiveFailures = 0
        listenOnce()
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    override fun release() = stop()

    private fun listenOnce() {
        if (!running) return
        val engine = recognizer ?: createRecognizer().also { recognizer = it } ?: return
        runCatching { engine.startListening(buildIntent()) }
            .onFailure {
                Log.w(TAG, "startListening failed", it)
                scheduleRestart()
            }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val engine = try {
            // On-device recognition keeps the wake word off the network entirely.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not create recogniser", t)
            onError?.invoke("Speech recognition isn't available on this device.")
            running = false
            return null
        }
        engine.setRecognitionListener(listener)
        return engine
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        // Prefer offline so standby doesn't stream audio anywhere.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Short windows: we only need one word, and shorter windows restart faster.
        // Longer windows mean fewer restarts, and every restart re-opens the
        // microphone - which is what makes the privacy indicator blink.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
    }

    /** Ends a single-shot listen without scheduling anything further. */
    private fun finishSingleShot() {
        running = false
        recognizer?.let { runCatching { it.cancel() } }
        onFinished?.invoke()
    }

    /** Restart with a small backoff so a persistently failing engine can't spin. */
    private fun scheduleRestart(immediate: Boolean = false) {
        if (singleShot) {
            finishSingleShot()
            return
        }
        if (!running) return
        val delay = if (immediate) {
            RESTART_DELAY_MS
        } else {
            (RESTART_DELAY_MS * (1 shl consecutiveFailures.coerceAtMost(5))).coerceAtMost(MAX_BACKOFF_MS)
        }
        handler.postDelayed({ listenOnce() }, delay)
    }

    private fun handleHeard(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (!matchesKeyword(text)) return false
        onDetected?.invoke()
        return true
    }

    /**
     * Loose match: free-form recognition mangles a single shouted word often
     * enough that an exact compare would miss most real triggers.
     */
    private fun matchesKeyword(heard: String): Boolean {
        val normalised = heard.lowercase().replace(Regex("[^a-z ]"), " ")
        val words = normalised.split(' ').filter { it.isNotBlank() }
        return words.any { word ->
            word == keyword || NEAR_MISSES.contains(word) ||
                (word.length >= 5 && keyword.length >= 5 && levenshtein(word, keyword) <= 1)
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveFailures = 0
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            onAmplitude?.invoke(normaliseRms(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            // No speech and no match are the normal case on standby, not faults.
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (benign) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    onError?.invoke("Microphone permission is required for the wake word.")
                    running = false
                    if (singleShot) onFinished?.invoke()
                    return
                }
                Log.d(TAG, "Recogniser error $error (streak $consecutiveFailures)")
            }
            // A busy or errored recogniser is often unrecoverable; rebuild it.
            if (!benign) {
                recognizer?.let { runCatching { it.destroy() } }
                recognizer = null
            }
            scheduleRestart(immediate = benign)
        }

        override fun onResults(results: Bundle?) {
            val heard = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (handleHeard(heard)) return
            scheduleRestart(immediate = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            // Catching it in a partial saves ~1s over waiting for the final result.
            handleHeard(heard)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val RESTART_DELAY_MS = 250L
        private const val MAX_BACKOFF_MS = 5000L

        /** What Android's recogniser tends to hear instead of "jarvis". */
        private val NEAR_MISSES = setOf(
            "jarvis", "javis", "jervis", "jarvi", "jarvus", "jarves",
            "charvis", "darvis", "jarvis's"
        )

        /** rmsdB is roughly -2..10; map it onto 0..1 for the HUD. */
        fun normaliseRms(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }
}
