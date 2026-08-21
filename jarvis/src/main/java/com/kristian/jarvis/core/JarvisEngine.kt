package com.kristian.jarvis.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kristian.jarvis.tools.ToolRunner
import com.kristian.jarvis.voice.Listener
import com.kristian.jarvis.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

enum class Who { USER, JARVIS, SYSTEM }

data class Line(val id: Long, val who: Who, val text: String)

/**
 * The single brain of the app: owns the microphone, the voice, the conversation and the
 * tool loop. It is a process-wide singleton so the on-screen UI and the wake-word service
 * are always talking to the same Jarvis rather than two of them.
 */
class JarvisEngine private constructor(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs = Prefs(app)
    private val tools = ToolRunner(app, prefs)
    private val claude = ClaudeClient(app, prefs, tools)
    private val speaker = Speaker(app)
    private val listener = Listener(app)

    private val _listening = MutableStateFlow(false)
    private val _thinking = MutableStateFlow(false)

    private val _transcript = MutableStateFlow<List<Line>>(emptyList())
    val transcript: StateFlow<List<Line>> = _transcript.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    val phase: StateFlow<Phase> =
        combine(_listening, speaker.speaking, _thinking) { listening, speaking, thinking ->
            when {
                listening -> Phase.LISTENING
                speaking -> Phase.SPEAKING
                thinking -> Phase.THINKING
                else -> Phase.IDLE
            }
        }.stateIn(scope, SharingStarted.Eagerly, Phase.IDLE)

    private var turnJob: Job? = null
    private var nextId = 0L

    init {
        val settings = prefs.current
        speaker.start(settings.speechRate, settings.speechPitch)
    }

    // ---------------------------------------------------------------- voice in

    fun canListen(): Boolean =
        listener.available() &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts a hands-free turn: stop talking, open the mic, send whatever is heard. */
    fun listen() {
        if (_listening.value) return
        if (!canListen()) {
            note("Microphone access is needed before Jarvis can listen.")
            return
        }
        speaker.stop()
        _partial.value = ""
        _listening.value = true
        _status.value = "listening"
        listener.start(micCallbacks)
    }

    fun stopListening() {
        if (!_listening.value) return
        listener.stop()
    }

    private val micCallbacks = object : Listener.Callbacks {
        override fun onLevel(rms: Float) {
            // The recogniser reports roughly -2..10 dB; map that onto 0..1.
            _level.value = ((rms + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onPartial(text: String) {
            _partial.value = text
        }

        override fun onFinal(text: String) {
            _listening.value = false
            _level.value = 0f
            _partial.value = ""
            submit(text)
        }

        override fun onEndOfSpeech() {
            _level.value = 0f
            _status.value = ""
        }

        override fun onFailure(code: Int, reason: String) {
            _listening.value = false
            _level.value = 0f
            _partial.value = ""
            _status.value = ""
            note(reason)
        }
    }

    // ---------------------------------------------------------------- a turn

    fun submit(text: String) {
        val request = text.trim()
        if (request.isEmpty()) return

        turnJob?.cancel()
        claude.cancel()
        speaker.stop()
        add(Who.USER, request)

        val replyId = add(Who.JARVIS, "")
        val reply = StringBuilder()
        val toSpeak = SentenceQueue()
        val speaking = prefs.current.speakReplies

        _thinking.value = true
        _status.value = "thinking"

        turnJob = scope.launch {
            try {
                claude.ask(request).collect { event ->
                    when (event) {
                        is ClaudeClient.Event.Text -> {
                            reply.append(event.delta)
                            replace(replyId, reply.toString())
                            _status.value = ""
                            if (speaking) {
                                toSpeak.offer(event.delta)?.let { speaker.say(it) }
                            }
                        }

                        is ClaudeClient.Event.Working -> _status.value = event.label

                        is ClaudeClient.Event.Finished -> {
                            if (speaking) toSpeak.drain()?.let { speaker.say(it) }
                            if (event.reply.isBlank() && reply.isBlank()) {
                                replace(replyId, "Done.")
                                if (speaking) speaker.say("Done.")
                            }
                            _status.value = ""
                        }

                        is ClaudeClient.Event.Failed -> {
                            if (reply.isBlank()) remove(replyId)
                            note(event.message)
                            _status.value = ""
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (reply.isBlank()) remove(replyId)
                throw cancelled
            } catch (t: Throwable) {
                // Nothing that goes wrong in a turn is worth taking the app down for.
                if (reply.isBlank()) remove(replyId)
                note("Something went wrong: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                _thinking.value = false
                if (_status.value == "thinking") _status.value = ""
            }
        }
    }

    fun interrupt() {
        turnJob?.cancel()
        turnJob = null
        claude.cancel()
        listener.abort()
        speaker.stop()
        _listening.value = false
        _thinking.value = false
        _level.value = 0f
        _partial.value = ""
        _status.value = ""
    }

    fun newConversation() {
        interrupt()
        claude.reset()
        _transcript.value = emptyList()
    }

    fun refreshVoice() {
        val settings = prefs.current
        speaker.start(settings.speechRate, settings.speechPitch)
    }

    fun say(text: String) = speaker.say(text)

    // ---------------------------------------------------------------- transcript

    private fun add(who: Who, text: String): Long {
        val id = ++nextId
        _transcript.value = _transcript.value + Line(id, who, text)
        return id
    }

    private fun replace(id: Long, text: String) {
        _transcript.value = _transcript.value.map {
            if (it.id == id) it.copy(text = text) else it
        }
    }

    private fun remove(id: Long) {
        _transcript.value = _transcript.value.filterNot { it.id == id }
    }

    private fun note(text: String) {
        add(Who.SYSTEM, text)
    }

    /**
     * Hands the synthesiser whole sentences instead of raw stream fragments — otherwise it
     * pauses in the middle of words and sounds like a broken robot rather than a butler.
     */
    private class SentenceQueue {
        private val buffer = StringBuilder()

        fun offer(delta: String): String? {
            buffer.append(delta)
            val cut = lastBreak() ?: return null
            val chunk = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            return chunk.ifBlank { null }
        }

        fun drain(): String? {
            val rest = buffer.toString().trim()
            buffer.setLength(0)
            return rest.ifBlank { null }
        }

        private fun lastBreak(): Int? {
            if (buffer.length < MIN_CHUNK) return null
            for (i in buffer.length - 1 downTo 1) {
                val c = buffer[i - 1]
                if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ';' || c == ':') {
                    // Avoid cutting inside "3.5" or "e.g."
                    val next = buffer[i]
                    if (c == '.' && next.isDigit()) continue
                    if (i >= MIN_CHUNK) return i
                }
            }
            return null
        }

        private companion object {
            const val MIN_CHUNK = 24
        }
    }

    companion object {
        @Volatile
        private var instance: JarvisEngine? = null

        fun get(context: Context): JarvisEngine =
            instance ?: synchronized(this) {
                instance ?: JarvisEngine(context).also { instance = it }
            }
    }
}
