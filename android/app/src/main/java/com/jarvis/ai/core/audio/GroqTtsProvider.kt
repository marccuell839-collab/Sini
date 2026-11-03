package com.jarvis.ai.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    override val label = "Groq Orpheus • عائشة"
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var pcmTrack: AudioTrack? = null
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
        for (part in splitForSpeech(text)) {
            val file = withContext(Dispatchers.IO) {
                val body = JSONObject().put("model", "canopylabs/orpheus-arabic-saudi")
                    .put("input", part.trimEnd().let { if (it.lastOrNull() in listOf('.', '!', '?', '؟')) it else "$it." }).put("voice", if (voice in listOf("abdullah", "fahad", "sultan", "lulwa", "noura", "aisha")) voice else "aisha")
                    .put("response_format", "wav")
                val request = Request.Builder().url("https://api.groq.com/openai/v1/audio/speech")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        // Groq Retry-After is seconds; the other reset header covers daily quotas.
                        val retrySeconds = response.header("retry-after")?.toDoubleOrNull()
                            ?.toLong()?.coerceIn(1, 172800)
                            ?: parseResetSeconds(response.header("x-ratelimit-reset-requests"))
                            ?: 60L
                        throw GroqSpeechRateLimit(retrySeconds)
                    }
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(300)
                        throw TtsException("فشل صوت عائشة (Groq HTTP ${response.code}): $detail")
                    }
                    val bytes = response.body?.bytes() ?: throw TtsException("Empty Groq response")
                    File.createTempFile("groq-", ".wav", cacheDir).also { it.writeBytes(bytes) }
                }
            }
            try { playWav(file); delay(180) } finally { file.delete() }
        }
    }
    private fun parseResetSeconds(value: String?): Long? {
        val match = Regex("""(?:(\\d+)h)?(?:(\\d+)m)?([0-9]+(?:\\.[0-9]+)?)s""")
            .matchEntire(value.orEmpty()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toDoubleOrNull()?.toLong() ?: 0L
        return (hours * 3600 + minutes * 60 + seconds).coerceIn(1, 172800)
    }

    /** Split on phrase or word boundaries to prevent cutting Arabic syllables. */
    private fun splitForSpeech(text: String): List<String> {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.length <= 180) return listOf(normalized)
        val result = mutableListOf<String>()
        var remaining = normalized
        while (remaining.length > 180) {
            val boundary = remaining.substring(0, 180).indexOfLast { it in "،؛.!؟? " }
            val cut = if (boundary >= 75) boundary + 1 else 180
            result.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }

    /** Orpheus returns RIFF WAV. Play PCM using its declared sample rate,
     * never as raw bytes at a guessed Android output sample rate. */
    private suspend fun playWav(file: File) = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw TtsException("Groq did not return a valid WAV file")
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var codec = 0
        var offset = -1
        var length = 0
        while (pos + 8 <= bytes.size) {
            val tag = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = u32(pos + 4).toLong() and 0xffffffffL
            if (size > bytes.size - (pos + 8)) break
            if (tag == "fmt " && size >= 16) {
                codec = u16(pos + 8)
                channels = u16(pos + 10)
                sampleRate = u32(pos + 12)
                bits = u16(pos + 22)
            }
            if (tag == "data") {
                offset = pos + 8
                length = size.toInt()
                break
            }
            pos += 8 + size.toInt() + (size.toInt() and 1)
        }
        if (codec != 1 || bits != 16 || channels !in 1..2 || sampleRate !in 8000..48000 ||
            offset < 0 || length <= 0
        ) {
            // Compressed/float WAV: let Android's container decoder try.
            playFile(file)
            return@withContext
        }
        val format = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, format, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw TtsException("Unsupported Groq WAV sample rate: $sampleRate")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(format).build())
            .setBufferSizeInBytes(maxOf(minBuffer, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        pcmTrack = track
        try {
            track.play()
            var written = 0
            while (written < length) {
                val count = track.write(bytes, offset + written, length - written, AudioTrack.WRITE_BLOCKING)
                if (count <= 0) throw TtsException("Audio output failed: $count")
                written += count
            }
            // Drain all samples before releasing the output device.
            val frames = length / (channels * 2)
            val deadline = android.os.SystemClock.elapsedRealtime() + length.toLong() * 1000 /
                (sampleRate * channels * 2) + 1200
            while (track.playbackHeadPosition.toLong() < frames &&
                android.os.SystemClock.elapsedRealtime() < deadline) {
                delay(20)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            if (pcmTrack === track) pcmTrack = null
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
    override fun stop() {
        pcmTrack?.runCatching { pause(); flush() }
        pcmTrack = null
        player?.runCatching { stop(); release() }
        player = null
    }
    override fun release() = stop()
}

/** Only rate limits should trigger Deepgram voice fallback. */
class GroqSpeechRateLimit(val retryAfterSeconds: Long) :
    TtsException("بلغ صوت Groq حد الطلبات؛ جرّب بعد $retryAfterSeconds ثانية")
