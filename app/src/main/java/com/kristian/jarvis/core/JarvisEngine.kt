package com.kristian.jarvis.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kristian.jarvis.claude.AnthropicClient
import com.kristian.jarvis.claude.ClaudeEvent
import com.kristian.jarvis.claude.Conversation
import com.kristian.jarvis.claude.Persona
import com.kristian.jarvis.claude.SecureKeyStore
import com.kristian.jarvis.claude.TurnResult
import com.kristian.jarvis.settings.JarvisPrefs
import com.kristian.jarvis.tools.ToolRegistry
import com.kristian.jarvis.ui.AssistantState
import com.kristian.jarvis.ui.ChatMessage
import com.kristian.jarvis.voice.JarvisTts
import com.kristian.jarvis.voice.VoiceController
import com.kristian.jarvis.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The brain stem: wires ears, Claude and voice into one loop.
 *
 * Deliberately not a ViewModel. The always-on service needs to own exactly
 * this object, and the UI needs to observe it - so it is a plain class with a
 * scope handed in, and both can hold it.
 */
class JarvisEngine(
    context: Context,
    private val scope: CoroutineScope
) {

    private val appContext = context.applicationContext
    private val prefs = JarvisPrefs(appContext)
    private val keys = SecureKeyStore(appContext)

    private val tts = JarvisTts(appContext, prefs)
    private val voice = VoiceController(appContext, prefs)
    private val claude = AnthropicClient(keys, prefs)
    private val conversation = Conversation()
    private val tools = ToolRegistry(appContext, prefs)

    private val _uiState = MutableStateFlow(JarvisUiState(narrate = prefs.narrateMode))
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private var turnJob: Job? = null

    /** Index of the assistant line currently being streamed into, if any. */
    private var liveReplyId: Long? = null
    private var liveThinkingId: Long? = null

    private var started = false

    /** Settings are read and written straight through; nothing is cached here. */
    val settings: JarvisPrefs get() = prefs

    /** True when a stored key exists, for the settings screen. */
    val maskedApiKey: String? get() = keys.maskedApiKey()

    fun start(micGranted: Boolean) {
        if (!started) {
            started = true
            tts.init()
            wireVoice()
            observeSpeech()
            say(
                ChatMessage.Role.SYSTEM,
                if (micGranted) {
                    "Standing by. Say \"Jarvis\", tap the mic, or type."
                } else {
                    "Standing by. Microphone is off, so type to me for now."
                }
            )
        }
        setMicAvailable(micGranted)
    }

    fun setMicAvailable(granted: Boolean) {
        _uiState.value = _uiState.value.copy(micAvailable = granted)
        voice.setWakeWordEnabled(granted && prefs.wakeWordEnabled)
        _uiState.value = _uiState.value.copy(wakeWordArmed = voice.isWakeWordEnabled())
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        voice.setWakeWordEnabled(enabled && _uiState.value.micAvailable)
        _uiState.value = _uiState.value.copy(wakeWordArmed = voice.isWakeWordEnabled())
    }

    fun setNarrate(enabled: Boolean) {
        prefs.narrateMode = enabled
        _uiState.value = _uiState.value.copy(narrate = enabled)
    }

    /** Mic button: stop speech, finish an utterance, or start listening. */
    fun onMicTap() {
        when {
            tts.isSpeaking.value -> stopSpeaking()
            voice.state.value == VoiceState.COMMAND_LISTENING -> voice.stopCommandListening()
            else -> voice.startCommandListening()
        }
    }

    fun stopSpeaking() {
        tts.stop()
    }

    /** Abandon the current request. */
    fun cancelTurn() {
        turnJob?.cancel()
        turnJob = null
        tts.stop()
        liveReplyId = null
        liveThinkingId = null
        setState(AssistantState.IDLE)
        _uiState.value = _uiState.value.copy(busy = false)
    }

    fun clearConversation() {
        cancelTurn()
        conversation.clear()
        _uiState.value = _uiState.value.copy(messages = emptyList())
        say(ChatMessage.Role.SYSTEM, "Conversation cleared, sir.")
    }

    /** Send a command - typed or spoken - and run the whole turn. */
    fun submit(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        if (!keys.hasApiKey) {
            say(ChatMessage.Role.SYSTEM, "No API key is stored. Add one in settings, sir.")
            return
        }
        if (!isOnline()) {
            append(ChatMessage(ChatMessage.Role.USER, text))
            say(
                ChatMessage.Role.SYSTEM,
                "There's no network connection, so I can't reach Claude. I'll be here when there is."
            )
            setState(AssistantState.ERROR)
            return
        }

        applyNarratePhrases(text)
        append(ChatMessage(ChatMessage.Role.USER, text))
        conversation.addUserText(text)

        turnJob?.cancel()
        turnJob = scope.launch { runTurn() }
    }

    /** Spoken phrases can flip narrate mode without touching settings. */
    private fun applyNarratePhrases(text: String) {
        when {
            Persona.mentionsNarrateOff(text) && _uiState.value.narrate -> setNarrate(false)
            Persona.mentionsNarrateOn(text) && !_uiState.value.narrate -> setNarrate(true)
        }
    }

    /**
     * The turn loop: request, and if Claude asked for tools, run them and go
     * back round with the results until it has nothing left to call.
     *
     * A hard iteration cap stops a tool that keeps provoking another call from
     * looping forever on someone's phone battery.
     */
    private suspend fun runTurn() {
        setState(AssistantState.THINKING)
        _uiState.value = _uiState.value.copy(busy = true)
        liveReplyId = null
        liveThinkingId = null

        val narrate = _uiState.value.narrate
        val base = prefs.personaPrompt.ifBlank { Persona.DEFAULT_SYSTEM_PROMPT }
        val system = Persona.systemPrompt(base, narrate)

        var iterations = 0
        while (iterations++ < MAX_TOOL_ITERATIONS) {
            val result = claude.stream(
                messages = conversation.snapshot(),
                systemPrompt = system,
                tools = tools.definitions(),
                narrate = narrate
            ) { event ->
                // Stream callbacks arrive on a network thread.
                scope.launch(Dispatchers.Main) {
                    when (event) {
                        is ClaudeEvent.TextDelta -> onTextDelta(event.text)
                        is ClaudeEvent.ThinkingDelta -> onThinkingDelta(event.text)
                        is ClaudeEvent.ToolUse -> Log.d(TAG, "Tool requested: ${event.name}")
                    }
                }
            }

            when (result) {
                is TurnResult.Failed -> {
                    withContext(Dispatchers.Main) {
                        tts.stop()
                        say(ChatMessage.Role.SYSTEM, result.message)
                        setState(AssistantState.ERROR)
                        _uiState.value = _uiState.value.copy(busy = false)
                    }
                    return
                }

                is TurnResult.Completed -> {
                    withContext(Dispatchers.Main) {
                        conversation.addAssistantContent(result.content)
                        result.refusalExplanation?.let {
                            say(ChatMessage.Role.SYSTEM, "Declined: $it")
                        }
                    }

                    if (result.toolUses.isNotEmpty()) {
                        runTools(result.toolUses)
                        // Speak what was said before the tool ran, then continue.
                        withContext(Dispatchers.Main) {
                            tts.endStream()
                            liveReplyId?.let { id -> updateMessage(id) { it.copy(partial = false) } }
                            liveReplyId = null
                            setState(AssistantState.THINKING)
                        }
                        continue
                    }

                    // A server-side tool paused the turn; resend to let it finish.
                    if (result.stopReason == "pause_turn") continue

                    withContext(Dispatchers.Main) { finishStreamedReply() }
                    break
                }
            }
        }

        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(busy = false)
            if (_uiState.value.state == AssistantState.THINKING) {
                finishStreamedReply()
            }
        }
    }

    /** Runs every tool call from one assistant turn and returns the results together. */
    private suspend fun runTools(calls: List<ClaudeEvent.ToolUse>) {
        val results = mutableListOf<org.json.JSONObject>()
        for (call in calls) {
            val (result, notice) = tools.execute(call.id, call.name, call.input)
            results += result
            notice?.let { withContext(Dispatchers.Main) { say(ChatMessage.Role.SYSTEM, it) } }
        }
        // All results go back in one message - splitting them teaches Claude to
        // stop calling tools in parallel.
        withContext(Dispatchers.Main) { conversation.addToolResults(results) }
    }

    private fun onTextDelta(delta: String) {
        val id = liveReplyId
        if (id == null) {
            val message = ChatMessage(ChatMessage.Role.ASSISTANT, delta, partial = true)
            liveReplyId = message.id
            append(message)
        } else {
            updateMessage(id) { it.copy(text = it.text + delta) }
        }
        // Speak sentences as they complete rather than waiting for the end.
        tts.speakStreaming(delta)
        if (_uiState.value.state == AssistantState.THINKING && tts.isSpeaking.value) {
            setState(AssistantState.SPEAKING)
        }
    }

    private fun onThinkingDelta(delta: String) {
        val id = liveThinkingId
        if (id == null) {
            val message = ChatMessage(ChatMessage.Role.SYSTEM, "Reasoning: $delta", partial = true)
            liveThinkingId = message.id
            append(message)
        } else {
            updateMessage(id) { it.copy(text = it.text + delta) }
        }
    }

    private fun finishStreamedReply() {
        liveReplyId?.let { id -> updateMessage(id) { it.copy(partial = false) } }
        liveThinkingId?.let { id -> updateMessage(id) { it.copy(partial = false) } }
        liveReplyId = null
        liveThinkingId = null
        tts.endStream()
        setState(if (tts.isSpeaking.value) AssistantState.SPEAKING else AssistantState.IDLE)
    }

    private fun wireVoice() {
        voice.onWakeWord = {
            // Barge-in: talking over JARVIS stops it talking.
            tts.stop()
        }
        voice.onCommand = { command -> submit(command) }
        voice.onNotice = { notice -> say(ChatMessage.Role.SYSTEM, notice) }

        scope.launch {
            voice.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    wakeWordArmed = voice.isWakeWordEnabled()
                )
                if (state == VoiceState.COMMAND_LISTENING) {
                    setState(AssistantState.LISTENING)
                } else if (_uiState.value.state == AssistantState.LISTENING) {
                    setState(if (_uiState.value.busy) AssistantState.THINKING else AssistantState.IDLE)
                }
            }
        }
        scope.launch {
            voice.partialTranscript.collect { partial ->
                _uiState.value = _uiState.value.copy(partialTranscript = partial)
            }
        }
        scope.launch {
            voice.amplitude.collect { level ->
                _uiState.value = _uiState.value.copy(amplitude = level)
            }
        }
    }

    private fun observeSpeech() {
        scope.launch {
            tts.isSpeaking.collect { speaking ->
                // Stand the wake word down while speaking so it can't hear itself.
                voice.setMutedForSpeech(speaking)
                when {
                    speaking -> setState(AssistantState.SPEAKING)
                    _uiState.value.state == AssistantState.SPEAKING -> setState(AssistantState.IDLE)
                }
            }
        }
        scope.launch {
            tts.error.collect { error ->
                error?.let {
                    say(ChatMessage.Role.SYSTEM, it)
                    tts.clearError()
                }
            }
        }
    }

    private fun setState(state: AssistantState) {
        _uiState.value = _uiState.value.copy(state = state)
    }

    private fun say(role: ChatMessage.Role, text: String) {
        append(ChatMessage(role, text))
    }

    private fun append(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + message)
    }

    private fun updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map { if (it.id == id) transform(it) else it }
        )
    }

    /** British-first voice list for the settings picker. */
    fun voiceOptions(): List<VoiceOption> = tts.availableVoices().map { voice ->
        VoiceOption(
            name = voice.name,
            label = buildString {
                append(voice.locale.displayName)
                append(if (voice.isNetworkConnectionRequired) " · online" else " · on-device")
            },
            selected = voice.name == prefs.voiceName
        )
    }

    fun applyVoice(name: String?) {
        tts.selectVoice(name)
        previewVoice()
    }

    fun applyRateAndPitch(rate: Float, pitch: Float) {
        tts.setRateAndPitch(rate, pitch)
    }

    fun previewVoice() {
        tts.speak("Voice check, sir. This is how I shall sound.")
    }

    fun updateApiKey(key: String) {
        keys.apiKey = key
        say(ChatMessage.Role.SYSTEM, "API key updated.")
    }

    private fun isOnline(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun release() {
        turnJob?.cancel()
        voice.release()
        tts.shutdown()
        started = false
    }

    /** One installed voice, as shown in settings. */
    data class VoiceOption(val name: String, val label: String, val selected: Boolean)

    companion object {
        private const val TAG = "JarvisEngine"

        /** Ceiling on tool round-trips within a single turn. */
        private const val MAX_TOOL_ITERATIONS = 6
    }
}
