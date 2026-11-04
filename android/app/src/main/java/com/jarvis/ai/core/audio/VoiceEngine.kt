package com.jarvis.ai.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses a speech provider and owns audio focus.
 *
 * The cloud voice is used when it is configured and reachable; otherwise the
 * device engine speaks. A failed cloud call falls back rather than leaving
 * the assistant mute, and the caller is told which provider actually spoke.
 */
@Singleton
class VoiceEngine @Inject constructor(
    private val context: Context,
    http: OkHttpClient,
    private val secureStore: SecureStore,
) {

    private val groq = GroqTtsProvider(http, secureStore, context.cacheDir)
    private val system = SystemTtsProvider(context)
    private val google = GoogleGeminiTtsProvider(http, secureStore)
    private val deepgramBackup = DeepgramTtsProvider(http, secureStore, context.cacheDir)
    private val deepgramStream = DeepgramStreamingTts(http, secureStore)
    private val quotaPrefs = context.getSharedPreferences("lora_voice_quota", Context.MODE_PRIVATE)
    private val backupVoice = "aura-2-thalia-en"
    fun wantsEnglishReply(): Boolean = isGroqCoolingDown() && !secureStore.deepgramKey.isNullOrBlank()
    fun isGroqCoolingDown(): Boolean =
        System.currentTimeMillis() < quotaPrefs.getLong("groq_retry_at", 0L)
    private fun coolDown(seconds: Long) {
        quotaPrefs.edit().putLong("groq_retry_at",
            System.currentTimeMillis() + seconds.coerceIn(1L, 172800L) * 1000L).apply()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private val focusLock = Any()
    private var speaking = 0

    @Volatile private var active: TtsProvider? = null

    val providers: List<TtsProvider> get() = listOf(groq, system, google, deepgramStream, deepgramBackup)
    suspend fun availableProviders(): List<TtsProvider> = providers.filter { it.isAvailable() }
    fun setExpressivity(value: Int) = Unit

    data class SpokenBy(val providerId: String, val fellBack: Boolean)

    /**
     * Speak [text]. Returns which provider was used.
     *
     * @param preferredProviderId "relay", "system", or null to pick the best.
     */
    suspend fun speak(
        text: String,
        voice: String = "ar-DZ-IsmaelNeural",
        pitch: String = "-15Hz",
        rate: String = "-8%",
        preferredProviderId: String? = null,
        onAmplitude: ((Float) -> Unit)? = null,
    ): SpokenBy {
        if (text.isBlank()) return SpokenBy("none", false)

        requestFocus()
        try {
            if (preferredProviderId == google.id) {
                active = google
                if (!google.isAvailable()) throw TtsException("أضف مفتاح Google AI Studio من الإعدادات")
                google.speak(text, voice, pitch, rate, onAmplitude)
                return SpokenBy(google.id, false)
            }
            if (preferredProviderId == system.id) {
                active = system
                if (!system.isAvailable()) throw TtsException("لا يوجد محرك نطق عربي محلي مثبت. ثبّت صوتا عربيا في إعدادات الهاتف.")
                system.speak(text, voice, pitch, rate, onAmplitude)
                return SpokenBy(system.id, false)
            }
            if (isGroqCoolingDown()) {
                if (!deepgramBackup.isAvailable()) {
                    throw TtsException("انتهت حصة صوت Groq. أضف مفتاح Deepgram.")
                }
                active = deepgramStream
                val english = if (containsArabic(text))
                    "My Arabic voice is temporarily unavailable. I will speak English until it returns."
                else text
                speakDeepgram(english, onAmplitude)
                return SpokenBy("deepgram", true)
            }
            try {
                active = groq
                if (!groq.isAvailable()) throw TtsException("أضف مفتاح Groq لتفعيل صوت لورا")
                groq.speak(text, voice, pitch, rate, onAmplitude)
                return SpokenBy("groq", false)
            } catch (limit: GroqSpeechRateLimit) {
                coolDown(limit.retryAfterSeconds)
                if (!deepgramBackup.isAvailable()) throw limit
                active = deepgramStream
                val english = if (containsArabic(text))
                    "My Arabic voice reached its usage limit. I will use my English voice for now."
                else text
                speakDeepgram(english, onAmplitude)
                return SpokenBy("deepgram", true)
            }
        } finally {
            active = null
            abandonFocus()
        }
    }
    private suspend fun speakDeepgram(text: String, onAmplitude: ((Float) -> Unit)?) {
        try {
            deepgramStream.speak(text, backupVoice, "0Hz", "0%", onAmplitude)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Streaming may be unavailable for this key or network. Keep REST
            // as the last fallback so the assistant never becomes silent.
            active = deepgramBackup
            deepgramBackup.speak(text, backupVoice, "0Hz", "0%", onAmplitude)
        }
    }

    private fun containsArabic(text: String): Boolean = text.any { it.code in 0x0600..0x06FF }

    /** Stop whatever is speaking. Safe at any time. */
    fun stop() {
        active?.stop()
        active = null
        providers.forEach { it.stop() }
        releaseFocus()
    }

    fun release() {
        providers.forEach { it.release() }
        releaseFocus()
    }

    /**
     * Duck other audio while speaking, rather than fighting it. Music drops
     * in volume and comes back on its own once focus is abandoned.
     *
     * Reference-counted, because a reply is spoken sentence by sentence:
     * taking and dropping focus per sentence would make background music
     * surge between every one.
     */
    private fun requestFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            if (speaking++ > 0) return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            if (speaking > 0) speaking--
            if (speaking > 0) return
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    /** Drop focus outright, whatever the count — for stop(). */
    private fun releaseFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            speaking = 0
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }
}
