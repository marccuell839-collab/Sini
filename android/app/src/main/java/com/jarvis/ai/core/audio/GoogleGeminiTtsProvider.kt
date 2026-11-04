package com.jarvis.ai.core.audio

import android.util.Base64
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini TTS via the official generateContent API.
 * The user supplies their own API key in encrypted app settings.
 * This provider does not embed keys and does not claim a native Algerian voice.
 */
class GoogleGeminiTtsProvider(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
) : TtsProvider {
    override val id = "google"
    override val label = "Google Gemini TTS"
    @Volatile private var currentCall: okhttp3.Call? = null
    @Volatile private var player: PcmPlayer? = null

    override suspend fun isAvailable(): Boolean = !secureStore.googleTtsKey.isNullOrBlank()

    override suspend fun voices() = listOf(
        VoiceOption("Leda", "Leda", "ar", "أنثوي"),
        VoiceOption("Aoede", "Aoede", "ar", "أنثوي"),
        VoiceOption("Kore", "Kore", "ar", "أنثوي"),
    )

    override suspend fun speak(
        text: String, voice: String, pitch: String, rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        val key = secureStore.googleTtsKey ?: throw TtsException("أضف مفتاح Google AI Studio من الإعدادات")
        val chosen = if (voice in setOf("Leda", "Aoede", "Kore")) voice else "Leda"
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text",
                    "Generate speech only, in a gentle, warm, youthful feminine voice. " +
                    "Speak the following text faithfully, with a natural Algerian Arabic accent where possible. " +
                    "Do not add any words. Text: " + text
                )
            ))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", chosen))
                ))
            )

        val data = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent")
                .header("x-goog-api-key", key)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = http.newCall(request)
            currentCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // Never log/echo the response body or the key.
                        throw TtsException("Google TTS HTTP ${response.code} — تحقق من المفتاح، الحصة وتفعيل Gemini API")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val parts = json.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                    var inline: JSONObject? = null
                    for (i in 0 until parts.length()) {
                        val current = parts.getJSONObject(i).optJSONObject("inlineData")
                        if (current != null && current.optString("mimeType").startsWith("audio/")) {
                            inline = current
                            break
                        }
                    }
                    val audio = inline ?: throw TtsException("Google لم يرجع أي بيانات صوتية")
                    val bytes = Base64.decode(audio.getString("data"), Base64.DEFAULT)
                    Pair(audio.optString("mimeType"), bytes)
                }
            } finally {
                if (currentCall === call) currentCall = null
            }
        }

        val mime = data.first.lowercase()
        val pcm = data.second
        val offset = if (mime.contains("wav") || (pcm.size >= 12 &&
            String(pcm, 0, 4, Charsets.US_ASCII) == "RIFF")) {
            findWavData(pcm)
        } else {
            if (!mime.contains("l16") && !mime.contains("pcm")) {
                throw TtsException("صيغة صوت Google غير مدعومة: ${data.first.take(50)}")
            }
            0
        }
        val audio = PcmPlayer(24_000)
        player = audio
        try {
            withContext(Dispatchers.IO) {
                audio.start(pitch, rate)
                var index = offset
                while (index < pcm.size) {
                    val count = minOf(8192, pcm.size - index)
                    val chunk = pcm.copyOfRange(index, index + count)
                    onAmplitude?.invoke(audio.write(chunk))
                    index += count
                }
            }
            audio.drain()
        } catch (e: CancellationException) {
            throw e
        } finally {
            audio.stop()
            if (player === audio) player = null
        }
    }

    private fun findWavData(wav: ByteArray): Int {
        if (wav.size < 44) throw TtsException("ملف WAV غير مكتمل")
        fun u32(pos: Int): Long =
            ((wav[pos].toLong() and 255) or
                ((wav[pos + 1].toLong() and 255) shl 8) or
                ((wav[pos + 2].toLong() and 255) shl 16) or
                ((wav[pos + 3].toLong() and 255) shl 24))
        var pos = 12
        while (pos + 8 <= wav.size) {
            val size = u32(pos + 4)
            if (size > wav.size.toLong() - (pos + 8)) break
            if (String(wav, pos, 4, Charsets.US_ASCII) == "data") return pos + 8
            pos += 8 + size.toInt() + (size.toInt() and 1)
        }
        throw TtsException("WAV بدون بيانات صوتية")
    }

    override fun stop() {
        currentCall?.cancel()
        currentCall = null
        player?.stop()
        player = null
    }

    override fun release() = stop()
}
