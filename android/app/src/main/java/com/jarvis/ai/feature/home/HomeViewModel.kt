package com.jarvis.ai.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.core.agent.AgentRuntime
import com.jarvis.ai.core.agent.AssistantState
import com.jarvis.ai.core.agent.IntentRouter
import com.jarvis.ai.core.agent.Tool
import com.jarvis.ai.core.agent.ToolRegistry
import com.jarvis.ai.core.agent.canGoTo
import com.jarvis.ai.core.audio.AudioCapture
import com.jarvis.ai.core.audio.DeepgramTtsProvider
import com.jarvis.ai.core.audio.SpeechPipeline
import com.jarvis.ai.core.audio.VoiceEngine
import com.jarvis.ai.core.stt.SttEngine
import com.jarvis.ai.core.stt.SttEvent
import com.jarvis.ai.core.data.JarvisSettings
import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.data.SettingsStore
import com.jarvis.ai.core.net.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class HomeUiState(
    val state: AssistantState = AssistantState.IDLE,
    val transcript: String = "",
    val micOn: Boolean = false,
    val amplitude: Float = 0f,
    /** Set while a sensitive tool waits for explicit approval (spec s11). */
    val pendingConfirmation: PendingAction? = null,
    val secureStorageAvailable: Boolean = true,
    val hasApiKey: Boolean = false,
    /** What the agent is doing right now, in Arabic. */
    val activity: String? = null,
    val lastError: String? = null,
    val tokensThisSession: Int = 0,
    val conversationCoach: Boolean = false,
) {
    val canCancel: Boolean get() = state.isBusy
}

data class PendingAction(val description: String, val toolName: String, val args: JsonObject)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val secureStore: SecureStore,
    private val agent: AgentRuntime,
    private val registry: ToolRegistry,
    private val voice: VoiceEngine,
    private val capture: AudioCapture,
    private val stt: SttEngine,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        HomeUiState(
            secureStorageAvailable = secureStore.isAvailable,
            hasApiKey = !secureStore.openRouterKey.isNullOrBlank(),
        ),
    )
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    val settings: StateFlow<JarvisSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, JarvisSettings())

    /** Rolling context, trimmed so the whole history is never resent. */
    private val history = mutableListOf<ChatMessage>()

    private var runJob: Job? = null
    private var pipeline: SpeechPipeline? = null
    private var meterJob: Job? = null
    private var sttJob: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null
    private var lastCoachAt = 0L
    private var proactiveJob: Job? = null
    private var lastInteractionAt = android.os.SystemClock.elapsedRealtime()
    private var lastProactiveAt = 0L
    private var greetedThisSession = false
    private var utteranceFallbackJob: Job? = null
    private var unfinishedUtterance = ""
    private var receivedFinalForTurn = false
    // Explicit-session-only, modest cadence. Silence does NOT imply user is alone.
    private val proactiveIdleMs = 120_000L
    private val proactiveCooldownMs = 300_000L

    private fun noteInteraction() {
        lastInteractionAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun startProactiveCompanion() {
        proactiveJob?.cancel()
        greetedThisSession = false
        noteInteraction()
        proactiveJob = viewModelScope.launch {
            delay(700L)
            if (_ui.value.micOn && !greetedThisSession && settings.value.autoSpeak) {
                greetedThisSession = true
                speak("أهلا! أنا لورا. واش تحب نديرو اليوم؟")
                noteInteraction()
            }
            while (_ui.value.micOn) {
                delay(5_000L)
                val now = android.os.SystemClock.elapsedRealtime()
                if (!settings.value.autoSpeak ||
                    _ui.value.state.isBusy ||
                    _ui.value.pendingConfirmation != null ||
                    now - lastInteractionAt < proactiveIdleMs ||
                    now - lastProactiveAt < proactiveCooldownMs) continue
                lastProactiveAt = now
                noteInteraction()
                speak("أنا هنا إذا تحتاجني. تحب نعاونك في حاجة؟")
            }
        }
    }

    private fun stopProactiveCompanion() {
        proactiveJob?.cancel()
        proactiveJob = null
        greetedThisSession = false
    }

    /** Detect obvious requests for help or hesitation locally; do not infer feelings from arbitrary speech. */
    private fun frenchHelpNeeded(utterance: String): Boolean {
        val words = utterance.lowercase().trim()
        val triggers = listOf(
            "comment dire", "je ne sais pas comment", "je sais pas comment",
            "je cherche mes mots", "je ne trouve pas les mots",
            "comment on dit", "euh euh", "euh... euh",
            "كيفاش نقول", "كيف نقول", "ما نعرفش نهدر",
            "مفهمتش بالفرنسية", "مفهمتش بالفرونسي", "ساعديني بالفرنسية",
            "عاونيني بالفرنسية", "ترجملي للفرنسية", "ترجملي للفرونسي",
            "how do i say", "how to say in french",
        )
        if (triggers.any { words.contains(it) }) return true
        val hesitation = Regex("""(?iu)\b(euh|heu|hum)[,.! ?]+(euh|heu|hum)\b""")
        return hesitation.containsMatchIn(words)
    }

    private fun addressesLora(utterance: String): Boolean {
        val lowered = utterance.lowercase()
        return listOf("لورا", "نورا", "lora", "laura", "jarvis", "جارفيس")
            .any { lowered.contains(it) }
    }

    fun refreshKeyState() {
        _ui.value = _ui.value.copy(hasApiKey = !secureStore.openRouterKey.isNullOrBlank())
    }

    /**
     * Handle one instruction.
     *
     * A locally resolvable command runs with no model call at all. Anything
     * else goes to the model with the tool schemas attached.
     */
    fun submit(utterance: String) {
        if (utterance.isBlank()) return
        noteInteraction()
        runJob?.cancel()
        _ui.value = _ui.value.copy(transcript = "", lastError = null, activity = null)

        val local = IntentRouter.route(utterance)
        runJob = viewModelScope.launch {
            if (local != null) {
                runLocal(local)
            } else {
                runWithModel(utterance)
            }
        }
    }

    /** Deterministic path: one tool, no model, no tokens. */
    private suspend fun runLocal(intent: IntentRouter.Intent) {
        val executor = registry.find(intent.tool)
        if (executor == null) {
            // The router proposed a tool this device does not have.
            go(AssistantState.ERROR)
            _ui.value = _ui.value.copy(lastError = "الأداة '${intent.tool}' غير متاحة على هذا الجهاز")
            return
        }

        go(AssistantState.EXECUTING)
        val args = IntentRouter.argsToJson(intent.args)

        if (executor.tool.sensitive && settings.value.confirmSensitiveActions) {
            if (!askConfirmation(executor.tool, args)) {
                go(AssistantState.IDLE)
                return
            }
        }

        val result = runCatching { executor.execute(args) }.getOrElse { e ->
            if (e is CancellationException) throw e
            com.jarvis.ai.core.agent.ToolResult.failure("تعذّر التنفيذ: ${e.javaClass.simpleName}")
        }

        _ui.value = _ui.value.copy(transcript = result.summary)
        go(if (result.ok) AssistantState.SUCCESS else AssistantState.ERROR)
        if (result.ok && settings.value.autoSpeak) speak(result.summary)
    }

    /** Model path: the agent loop with tools and caps. */
    private suspend fun runWithModel(utterance: String) {
        val key = secureStore.openRouterKey
        if (key.isNullOrBlank()) {
            go(AssistantState.ERROR)
            _ui.value = _ui.value.copy(
                lastError = "أضف مفتاح Groq من الإعدادات أوّلًا",
                hasApiKey = false,
            )
            return
        }

        go(AssistantState.THINKING)
        _ui.value = _ui.value.copy(transcript = "")
        val current = settings.value

        // Speak sentence by sentence as the model writes, instead of
        // waiting for the whole reply and then the whole audio.
        val speech = if (current.autoSpeak) {
            stt.pauseUplink()
            SpeechPipeline(
                scope = viewModelScope,
                bufferUntilFinish = true,
                speak = { chunk ->
                    go(AssistantState.SPEAKING)
                    voice.speak(
                        text = chunk,
                        voice = current.voice,
                        pitch = current.voicePitch,
                        rate = current.voiceRate,
                        preferredProviderId = current.voiceProvider,
                    )
                },
                onError = { message -> _ui.value = _ui.value.copy(lastError = message) },
            )
        } else {
            null
        }
        pipeline = speech

        val outcome = agent.run(
            apiKey = key,
            model = current.model,
            instruction = utterance,
            registry = registry,
            replyLanguage = if (voice.wantsEnglishReply()) "en" else "ar",
            history = history.toList(),
            limits = AgentRuntime.Limits(
                maxToolCalls = current.maxToolCalls,
                maxOutputTokens = current.maxOutputTokens,
            ),
            confirm = { tool, args ->
                if (current.confirmSensitiveActions) askConfirmation(tool, args) else true
            },
            onEvent = { event ->
                if (event is AgentRuntime.Event.Delta) speech?.push(event.text)
                onAgentEvent(event)
            },
        )

        speech?.finish()
        speech?.join()
        pipeline = null
        stt.resumeUplink()

        _ui.value = _ui.value.copy(
            activity = null,
            tokensThisSession = _ui.value.tokensThisSession + outcome.tokensUsed,
        )

        when (outcome.stoppedBecause) {
            AgentRuntime.Stop.COMPLETED -> {
                history += ChatMessage.User(utterance)
                outcome.text?.let { history += ChatMessage.Assistant(it) }
                trimHistory()
                go(AssistantState.SUCCESS)
                // Already spoken by the pipeline while it streamed.
            }
            AgentRuntime.Stop.NEEDS_CONFIRMATION -> go(AssistantState.IDLE)
            else -> go(AssistantState.ERROR)
        }
    }

    private fun onAgentEvent(event: AgentRuntime.Event) {
        when (event) {
            is AgentRuntime.Event.Thinking ->
                _ui.value = _ui.value.copy(activity = null)

            is AgentRuntime.Event.ToolStarted -> {
                go(AssistantState.EXECUTING)
                _ui.value = _ui.value.copy(activity = event.summary)
            }

            is AgentRuntime.Event.ToolFinished ->
                _ui.value = _ui.value.copy(
                    activity = null,
                    transcript = _ui.value.transcript.appendLine(event.result.summary),
                )

            is AgentRuntime.Event.Spoke ->
                _ui.value = _ui.value.copy(transcript = event.text)

            is AgentRuntime.Event.Delta ->
                _ui.value = _ui.value.copy(
                    transcript = _ui.value.transcript + event.text,
                    activity = null,
                )

            is AgentRuntime.Event.Failed ->
                _ui.value = _ui.value.copy(lastError = event.message, activity = null)

            is AgentRuntime.Event.Finished, is AgentRuntime.Event.NeedsConfirmation -> Unit
        }
    }

    private fun String.appendLine(line: String): String =
        if (isBlank()) line else "$this\n$line"

    /** Suspend until the user answers the confirmation dialog. */
    private suspend fun askConfirmation(tool: Tool, args: JsonObject): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmation = deferred
        _ui.value = _ui.value.copy(
            pendingConfirmation = PendingAction(tool.description, tool.name, args),
        )
        return try {
            deferred.await()
        } finally {
            confirmation = null
            _ui.value = _ui.value.copy(pendingConfirmation = null)
        }
    }

    fun confirmPending() {
        confirmation?.complete(true)
    }

    fun rejectPending() {
        confirmation?.complete(false)
    }

    private suspend fun speak(text: String) {
        val current = settings.value
        go(AssistantState.SPEAKING)
        // Deaf while talking: otherwise the microphone hears the speaker
        // and JARVIS transcribes himself into an endless conversation.
        stt.pauseUplink()
        runCatching {
            voice.speak(
                text = text,
                voice = current.voice,
                pitch = current.voicePitch,
                rate = current.voiceRate,
                preferredProviderId = current.voiceProvider,
            )
        }.onFailure { e ->
            if (e is CancellationException) {
                // Cancelled playback still has to re-open the uplink, or
                // the microphone stays mute for the rest of the session.
                stt.resumeUplink()
                throw e
            }
            // A mute assistant is a degraded one, not a failed task: the
            // action already succeeded, so this is reported and no more.
            _ui.value = _ui.value.copy(lastError = e.message)
        }
        stt.resumeUplink()
        go(AssistantState.IDLE)
    }

    /** Keep the last few turns only — never resend the whole conversation. */
    private fun trimHistory() {
        val maxTurns = 8
        while (history.size > maxTurns) history.removeAt(0)
    }

    fun toggleMic(on: Boolean) {
        _ui.value = _ui.value.copy(micOn = on, lastError = null)
        if (on) {
            startListening()
            if (_ui.value.micOn) startProactiveCompanion()
        } else {
            stopProactiveCompanion()
            stopListening()
        }
    }

    /** Needs permission: report honestly instead of showing a dead orb. */
    fun onMicPermissionDenied() {
        _ui.value = _ui.value.copy(
            micOn = false,
            lastError = "إذن الميكروفون مرفوض، فلا يمكن الاستماع",
        )
        go(AssistantState.IDLE)
    }

    private fun startListening() {
        if (!capture.hasPermission()) {
            onMicPermissionDenied()
            return
        }
        go(AssistantState.LISTENING)

        // The orb reads the same frames the recogniser does: one recorder,
        // two consumers (see AudioCapture).
        meterJob?.cancel()
        meterJob = viewModelScope.launch {
            capture.frames.collect { frame ->
                _ui.value = _ui.value.copy(amplitude = frame.level)
            }
        }

        sttJob?.cancel()
        sttJob = viewModelScope.launch {
            stt.listen(if (settings.value.automaticFrenchHelp) "multi" else settings.value.sttLanguage).collect(::onSttEvent)
        }
    }

    private fun stopListening() {
        utteranceFallbackJob?.cancel()
        utteranceFallbackJob = null
        unfinishedUtterance = ""
        receivedFinalForTurn = false
        sttJob?.cancel()
        sttJob = null
        stt.stop()
        meterJob?.cancel()
        meterJob = null
        capture.stop()
        _ui.value = _ui.value.copy(amplitude = 0f)
        if (_ui.value.state == AssistantState.LISTENING) go(AssistantState.IDLE)
    }

    private fun onSttEvent(event: SttEvent) {
        when (event) {
            is SttEvent.Ready -> go(AssistantState.LISTENING)

            is SttEvent.SpeechStarted -> {
                utteranceFallbackJob?.cancel()
                unfinishedUtterance = ""
                receivedFinalForTurn = false
                noteInteraction()
                // Barge-in: the user talking over JARVIS stops JARVIS.
                voice.stop()
                go(AssistantState.LISTENING)
            }

            is SttEvent.Partial, is SttEvent.Segment -> {
                val text = when (event) {
                    is SttEvent.Partial -> event.text
                    is SttEvent.Segment -> event.text
                    else -> return
                }
                if (text.isNotBlank()) unfinishedUtterance = text
                _ui.value = _ui.value.copy(transcript = text, activity = "أسمعك...")
            }

            is SttEvent.Final -> {
                utteranceFallbackJob?.cancel()
                receivedFinalForTurn = true
                unfinishedUtterance = ""
                noteInteraction()
                _ui.value = _ui.value.copy(transcript = event.text, activity = null)
                handleRecognizedSpeech(event.text)
            }

            is SttEvent.UtteranceEnd -> {
                _ui.value = _ui.value.copy(activity = null)
                // Some streaming backends signal end-of-turn without a Final.
                // Wait briefly so a late Final has priority and avoid duplicate replies.
                if (!receivedFinalForTurn && unfinishedUtterance.isNotBlank() && _ui.value.micOn) {
                    val candidate = unfinishedUtterance
                    utteranceFallbackJob?.cancel()
                    utteranceFallbackJob = viewModelScope.launch {
                        delay(650L)
                        if (_ui.value.micOn && !receivedFinalForTurn) {
                            unfinishedUtterance = ""
                            receivedFinalForTurn = true
                            handleRecognizedSpeech(candidate)
                        }
                    }
                }
            }

            is SttEvent.Failed -> {
                _ui.value = _ui.value.copy(lastError = event.message, micOn = false, activity = null)
                stopListening()
                go(AssistantState.ERROR)
            }
        }
    }

    /** A completed turn is conversational even when optional French coaching is enabled. */
    private fun handleRecognizedSpeech(text: String) {
        if (text.isBlank() || !_ui.value.micOn) return
        if (settings.value.automaticFrenchHelp && frenchHelpNeeded(text)) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCoachAt >= 20_000L) {
                lastCoachAt = now
                submit(
                    "هذا كلامي في محادثة فرنسية بعلم الحاضرين: " + text +
                    ". ساعدني باختصار: اعطِ عبارة فرنسية طبيعية واحدة مناسبة للسياق، " +
                    "ومعناها بالعربية. لا تستخدم أدوات الهاتف."
                )
                return
            }
        }
        // The user deliberately opened the microphone session: normal turns
        // must not be discarded merely because they omit the wake word.
        submit(text)
    }

    /** Always permitted: an action the user cannot abort is a bug. */
    fun cancel() {
        stopProactiveCompanion()
        runJob?.cancel()
        runJob = null
        pipeline?.cancel()
        pipeline = null
        stt.resumeUplink()
        stopListening()
        confirmation?.complete(false)
        voice.stop()
        _ui.value = _ui.value.copy(micOn = false, conversationCoach = false, pendingConfirmation = null, activity = null)
        go(AssistantState.IDLE)
    }

    fun setConnected(connected: Boolean) {
        if (!connected) {
            go(AssistantState.DISCONNECTED)
        } else if (_ui.value.state == AssistantState.DISCONNECTED) {
            go(AssistantState.IDLE)
        }
    }

    fun pushAmplitude(rms: Float) {
        _ui.value = _ui.value.copy(amplitude = rms.coerceIn(0f, 1f))
    }

    override fun onCleared() {
        stopProactiveCompanion()
        stopListening()
        voice.stop()
        super.onCleared()
    }

    /** Apply a transition, ignoring one the machine does not allow. */
    private fun go(target: AssistantState) {
        val current = _ui.value.state
        if (current == target) return
        if (!current.canGoTo(target)) return
        _ui.value = _ui.value.copy(state = target)
    }
}
