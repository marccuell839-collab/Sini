package dz.sini.jarvis

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Experimental acoustic similarity filter, not secure speaker biometrics. */
object OwnerVoiceProfile {
    fun capture(context: Context, durationMs: Long = 2400): ByteArray {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min*3, 4096))
        require(audio.state == AudioRecord.STATE_INITIALIZED)
        val data = java.io.ByteArrayOutputStream()
        try {
            audio.startRecording()
            val samples = ShortArray(1024)
            val until = android.os.SystemClock.elapsedRealtime() + durationMs
            while (android.os.SystemClock.elapsedRealtime() < until) {
                val n = audio.read(samples, 0, samples.size)
                for (i in 0 until maxOf(0,n)) {
                    val v = samples[i].toInt()
                    data.write(v and 255); data.write((v shr 8) and 255)
                }
            }
        } finally {
            try { audio.stop() } catch (_: Exception) {}
            audio.release()
        }
        return data.toByteArray()
    }
    // Coarse log-frequency features; this is deliberately labelled non-security-grade.
    fun signature(pcm: ByteArray): DoubleArray {
        val samples = ShortArray(pcm.size / 2) { i ->
            ((pcm[2*i].toInt() and 255) or (pcm[2*i+1].toInt() shl 8)).toShort()
        }
        val length = 512
        val bands = DoubleArray(16)
        var frames = 0
        for (offset in 0 until samples.size-length step 256) {
            var energy = 0.0
            for (i in 0 until length) {
                val v = samples[offset+i].toDouble()/32768
                energy += v*v
            }
            if (energy/length < .00015) continue
            for (band in bands.indices) {
                val hz = 110.0 * 2.0.pow(band / 4.0)
                val cycles = 2*Math.PI*hz/16000
                var re=0.0; var im=0.0
                for (i in 0 until length step 4) {
                    val v = samples[offset+i].toDouble()/32768
                    re += v*cos(cycles*i); im += v*sin(cycles*i)
                }
                bands[band] += ln(1.0+re*re+im*im)
            }
            frames++
        }
        require(frames >= 8) { "لم نسمع كلاماً واضحاً، حاول مجدداً" }
        for (i in bands.indices) bands[i] /= frames
        val mean = bands.average()
        val norm = sqrt(bands.sumOf {(it-mean)*(it-mean)}).coerceAtLeast(.000001)
        return DoubleArray(bands.size) {(bands[it]-mean)/norm}
    }
    fun save(context: Context, profiles: List<DoubleArray>) {
        require(profiles.size >= 3)
        val json = JSONArray()
        profiles.forEach { p -> json.put(JSONArray().also { a -> p.forEach(a::put) }) }
        context.getSharedPreferences("dexter_config",Context.MODE_PRIVATE).edit()
            .putString("owner_voice_profiles", json.toString()).putBoolean("owner_voice_only",true).apply()
    }
    fun enrolled(context: Context): Boolean = context.getSharedPreferences("dexter_config",Context.MODE_PRIVATE)
        .contains("owner_voice_profiles")
    fun accepts(context: Context, pcm: ByteArray): Boolean {
        val prefs=context.getSharedPreferences("dexter_config",Context.MODE_PRIVATE)
        if (!prefs.getBoolean("owner_voice_only",false)) return true
        val raw=prefs.getString("owner_voice_profiles",null) ?: return false
        return try {
            val test=signature(pcm)
            val list=JSONArray(raw)
            val scores=(0 until list.length()).map { i ->
                val p=list.getJSONArray(i)
                (0 until minOf(test.size,p.length())).sumOf { j -> test[j]*p.getDouble(j) }
            }
            scores.count { it >= .90 } >= 2
        } catch (_: Exception) { false }
    }
}
