package com.jarvis.ai.core.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/** Arabic Orpheus WAV synthesis via Groq. Deepgram remains independent. */
class GroqTtsProvider(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
    private val cacheDir: File,
) : TtsProvider {
    override val id = "groq"
    override val label = "Groq Orpheus • عبدالله"
    @Volatile private var player: MediaPlayer? = null
    override suspend fun isAvailable() = !secureStore.openRouterKey.isNullOrBlank()
    override suspend fun voices() = listOf(
        VoiceOption("abdullah", "Abdullah", "ar-SA", "صوت عربي رجالي"),
        VoiceOption("fahad", "Fahad", "ar-SA", "صوت عربي رجالي"),
        VoiceOption("sultan", "Sultan", "ar-SA", "صوت عربي رجالي"),
        VoiceOption("lulwa", "Lulwa", "ar-SA", "صوت عربي نسائي"),
        VoiceOption("noura", "Noura", "ar-SA", "صوت عربي نسائي"),
        VoiceOption("aisha", "Aisha", "ar-SA", "صوت عربي نسائي")
    )
    override suspend fun speak(text: String, voice: String, pitch: String, rate: String, onAmplitude: ((Float) -> Unit)?) {
        val key = secureStore.openRouterKey ?: throw TtsException("مفتاح Groq غير موجود")
        for (part in text.chunked(180)) {
            val file = withContext(Dispatchers.IO) {
                val body = JSONObject().put("model", "canopylabs/orpheus-arabic-saudi")
                    .put("input", part).put("voice", if (voice in listOf("abdullah", "fahad", "sultan", "lulwa", "noura", "aisha")) voice else "abdullah")
                    .put("response_format", "wav")
                val request = Request.Builder().url("https://api.groq.com/openai/v1/audio/speech")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw TtsException("Groq TTS HTTP ${response.code}")
                    val bytes = response.body?.bytes() ?: throw TtsException("Empty Groq response")
                    File.createTempFile("groq-", ".wav", cacheDir).also { it.writeBytes(bytes) }
                }
            }
            try { playFile(file) } finally { file.delete() }
        }
    }
    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        stop()
        val mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.absolutePath)
            setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
            setOnErrorListener { _, what, extra ->
                if (cont.isActive) cont.resumeWith(Result.failure(TtsException("Groq playback $what/$extra")))
                true
            }
        }
        player = mp
        cont.invokeOnCancellation { stop() }
        runCatching { mp.prepare(); mp.start() }.onFailure {
            if (cont.isActive) cont.resumeWith(Result.failure(it))
        }
    }
    override fun stop() { player?.runCatching { stop(); release() }; player = null }
    override fun release() = stop()
}
