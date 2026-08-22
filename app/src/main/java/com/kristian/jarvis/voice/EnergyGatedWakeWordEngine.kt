package com.kristian.jarvis.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Wake-word detection that doesn't make the microphone flicker.
 *
 * The obvious approach - restarting Android's recogniser in a loop - re-opens
 * the mic every couple of seconds. The privacy indicator blinks constantly, the
 * recognition service is doing full speech-to-text on silence, and it costs
 * battery for nothing.
 *
 * Instead this holds one continuous AudioRecord and does the cheapest possible
 * thing with it: root-mean-square level, compared against a noise floor that
 * follows the room. The recogniser is only started when something is actually
 * loud enough to be speech, and is stopped again immediately afterwards. In a
 * quiet room it essentially never runs. The mic session stays open the whole
 * time it is armed, so the indicator is steady rather than blinking.
 *
 * The recogniser needs exclusive access to the mic, so monitoring stops for the
 * moment it runs and resumes afterwards.
 */
class EnergyGatedWakeWordEngine(
    context: Context,
    private val keyword: String = "jarvis"
) : WakeWordEngine {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** Does the actual keyword check once the gate trips. */
    private val burstRecognizer =
        SpeechRecognizerWakeWordEngine(appContext, keyword, singleShot = true)

    @Volatile
    private var monitoring = false

    @Volatile
    private var recognizerRunning = false

    private var recorder: AudioRecord? = null
    private var monitorThread: Thread? = null

    /** Rolling estimate of the room's background level. */
    private var noiseFloor = 0.0

    override val name: String = "Level-gated listening"

    override var onDetected: (() -> Unit)? = null
    override var onAmplitude: ((Float) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override fun isAvailable(): Boolean =
        hasMicPermission() && burstRecognizer.isAvailable()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun start() {
        if (monitoring) return
        if (!hasMicPermission()) {
            onError?.invoke("Microphone permission is required for the wake word.")
            return
        }
        // The burst recogniser reports detections and problems as its own.
        burstRecognizer.onDetected = {
            stopBurst()
            onDetected?.invoke()
        }
        burstRecognizer.onError = { message -> onError?.invoke(message) }
        // One listen, then the mic comes straight back to monitoring.
        burstRecognizer.onFinished = { stopBurst() }

        monitoring = true
        startMonitor()
    }

    private fun startMonitor() {
        if (!monitoring || recognizerRunning) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onError?.invoke("This device won't open the microphone for level monitoring.")
            monitoring = false
            return
        }
        val bufferSize = maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)

        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not create AudioRecord", t)
            null
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            onError?.invoke("The microphone is in use by something else.")
            monitoring = false
            return
        }

        recorder = record
        runCatching { record.startRecording() }.onFailure {
            Log.w(TAG, "startRecording failed", it)
            runCatching { record.release() }
            recorder = null
            monitoring = false
            onError?.invoke("The microphone could not be opened.")
            return
        }

        monitorThread = thread(name = "jarvis-mic-monitor", isDaemon = true) {
            val buffer = ShortArray(FRAME_SAMPLES)
            var loudFrames = 0
            noiseFloor = 0.0

            while (monitoring && !recognizerRunning) {
                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (t: Throwable) {
                    Log.w(TAG, "Mic read failed", t)
                    break
                }
                if (read <= 0) continue

                val level = rms(buffer, read)

                // Track the quiet background so the gate works in a silent room
                // and in a noisy one without a hardcoded threshold.
                noiseFloor = if (noiseFloor == 0.0) {
                    level
                } else {
                    NOISE_FLOOR_DECAY * noiseFloor + (1 - NOISE_FLOOR_DECAY) * minOf(level, noiseFloor * 1.5)
                }

                val normalised = (level / MAX_AMPLITUDE).toFloat().coerceIn(0f, 1f)
                handler.post { onAmplitude?.invoke(normalised) }

                val threshold = maxOf(noiseFloor * TRIGGER_OVER_FLOOR, MIN_TRIGGER_LEVEL)
                if (level > threshold) {
                    loudFrames++
                    if (loudFrames >= FRAMES_TO_TRIGGER) {
                        handler.post { runBurst() }
                        break
                    }
                } else {
                    loudFrames = 0
                }
            }
        }
    }

    /** Hands the mic to the recogniser for one short listen. */
    private fun runBurst() {
        if (!monitoring || recognizerRunning) return
        recognizerRunning = true
        releaseRecorder()

        burstRecognizer.start()
        // The recogniser gives up on its own; this is the backstop that returns
        // the mic to monitoring if it doesn't.
        handler.postDelayed({ if (recognizerRunning) stopBurst() }, BURST_TIMEOUT_MS)
    }

    private fun stopBurst() {
        if (!recognizerRunning) return
        recognizerRunning = false
        handler.removeCallbacksAndMessages(null)
        burstRecognizer.stop()
        handler.postDelayed({ startMonitor() }, MIC_HANDOVER_MS)
    }

    private fun releaseRecorder() {
        val record = recorder ?: return
        recorder = null
        runCatching { record.stop() }
        runCatching { record.release() }
        monitorThread = null
    }

    override fun stop() {
        monitoring = false
        recognizerRunning = false
        handler.removeCallbacksAndMessages(null)
        burstRecognizer.stop()
        releaseRecorder()
    }

    override fun release() {
        stop()
        burstRecognizer.release()
    }

    private fun rms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length)
    }

    companion object {
        private const val TAG = "EnergyGate"

        private const val SAMPLE_RATE = 16000
        /** 32 ms at 16 kHz - long enough to be a stable level, short enough to react. */
        private const val FRAME_SAMPLES = 512

        private const val MAX_AMPLITUDE = 32768.0

        /** How fast the noise floor follows the room; nearer 1 is slower. */
        private const val NOISE_FLOOR_DECAY = 0.995

        /** Trip when the level is this many times above the background. */
        private const val TRIGGER_OVER_FLOOR = 3.0

        /** Absolute floor, so near-silence can't trip the gate on its own noise. */
        private const val MIN_TRIGGER_LEVEL = 900.0

        /** ~100 ms of sustained sound, so a click or a tap doesn't trip it. */
        private const val FRAMES_TO_TRIGGER = 3

        /** Give the recogniser this long before taking the mic back. */
        private const val BURST_TIMEOUT_MS = 4000L

        /** Android needs a beat to hand the mic between users. */
        private const val MIC_HANDOVER_MS = 250L
    }
}
