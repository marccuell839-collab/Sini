package dz.sini.jarvis

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.sqrt

class DexterVoiceService : Service(), android.speech.tts.TextToSpeech.OnInitListener {
    companion object {
        @Volatile var running = false
        @Volatile var audioLevel = 0f
        @Volatile var visualMode = "idle"
        private const val CHANNEL = "dexter_listening"
        private const val NOTICE = 9204
        private const val STOP = "dz.appnova.dexter.STOP"
    }
    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var speaking = false
    private var tts: android.speech.tts.TextToSpeech? = null
    private var windows: WindowManager? = null
    private var eyes: View? = null
    private val prefs by lazy { getSharedPreferences("dexter_config", MODE_PRIVATE) }
    override fun onBind(intent: Intent?) = null
    override fun onInit(status: Int) { }
    override fun onCreate() {
        super.onCreate()
        tts = android.speech.tts.TextToSpeech(this, this)
        val channel = NotificationChannel(CHANNEL, "DEXTER • Voice", android.app.NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val halt = PendingIntent.getService(this, 1, Intent(this, DexterVoiceService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DEXTER • أسمعك")
            .setContentText("قل ديكستر ثم الأمر • اضغط للإعدادات")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "إيقاف", halt).build())
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(NOTICE, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTICE, notification)
        running = true
        showEyes()
        if (!recording) startCapture()
        return START_STICKY
    }
    private fun showEyes() {
        if (!Settings.canDrawOverlays(this) || eyes != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = DexterEyesOverlayView(this)
        val p = WindowManager.LayoutParams(
            (94 * resources.displayMetrics.density).toInt(),
            (35 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = (42 * resources.displayMetrics.density).toInt()
        try { wm.addView(view, p); windows = wm; eyes = view } catch (_: Exception) { }
    }
    private fun startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { stopSelf(); return }
        try {
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min*4, 6400))
            if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); stopSelf(); return }
            recorder = audio
            audio.startRecording()
            recording = true
            thread(name="DexterBackgroundMicrophone") {
                val block = ShortArray(1600)
                val utterance = ByteArrayOutputStream()
                var start = 0L; var last = 0L; var voiceBlocks = 0
                try {
                    while (recording) {
                        val count = audio.read(block,0,block.size)
                        if (count <= 0) continue
                        if (speaking) { audioLevel = 0f; visualMode = "speaking"; utterance.reset(); start=0; voiceBlocks=0; continue }
                        var power = 0.0
                        for (i in 0 until count) { val v=block[i].toDouble()/32768;power+=v*v }
                        val level=sqrt(power/count).toFloat()
                        audioLevel = audioLevel*.72f + (level*10f).coerceIn(0f,1f)*.28f
                        visualMode = if(level>.024f) "listening" else "idle"
                        val now=SystemClock.elapsedRealtime()
                        if (sqrt(power/count)>0.024) {
                            last=now;voiceBlocks++
                            if (start==0L && voiceBlocks>=2) {start=now; utterance.reset()}
                        } else if (start==0L) voiceBlocks=0
                        if (start!=0L) {
                            for (i in 0 until count) { val v=block[i].toInt();utterance.write(v and 255);utterance.write((v shr 8) and 255) }
                            if (now-last>1150 || now-start>17000) {
                                val pcm=utterance.toByteArray();utterance.reset();start=0;voiceBlocks=0
                                if (pcm.size>7000) recognize(pcm)
                            }
                        }
                    }
                } finally { try {audio.stop()} catch (_:Exception){};audio.release() }
            }
        } catch (_: Exception) { stopSelf() }
    }
    private fun recognize(pcm: ByteArray) {
        val key=prefs.getString("groq","") ?: ""
        if(key.isBlank()) return
        thread(name="DexterSpeechToText") {
            try {
                val wav=ByteArrayOutputStream()
                fun intLE(n:Int){for(i in 0..3)wav.write((n shr (8*i)) and 255)}
                fun shortLE(n:Int){wav.write(n and 255);wav.write((n shr 8) and 255)}
                wav.write("RIFF".toByteArray());intLE(pcm.size+36)
                wav.write("WAVEfmt ".toByteArray());intLE(16);shortLE(1);shortLE(1)
                intLE(16000);intLE(32000);shortLE(2);shortLE(16)
                wav.write("data".toByteArray());intLE(pcm.size);wav.write(pcm)
                val boundary="DexterBoundary9467"
                val body=ByteArrayOutputStream()
                fun part(key:String,v:String){body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$v\r\n".toByteArray())}
                part("model","whisper-large-v3-turbo");part("language","ar")
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                body.write(wav.toByteArray());body.write("\r\n--$boundary--\r\n".toByteArray())
                val c=URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
                try {
                    c.requestMethod="POST";c.connectTimeout=18000;c.readTimeout=40000
                    c.setRequestProperty("Authorization","Bearer $key")
                    c.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
                    c.doOutput=true;c.outputStream.use {it.write(body.toByteArray())}
                    if(c.responseCode !in 200..299)return@thread
                    val phrase=JSONObject(c.inputStream.bufferedReader().use{it.readText()}).optString("text").lowercase()
                    val wake=listOf("ديكستر","دكستر","dexter").firstOrNull{phrase.contains(it)}?:return@thread
                    val command=phrase.substringAfter(wake).trim()
                    visualMode="thinking"
                    if(command.isBlank()) {say("نعم سيدي");return@thread}
                    if ((command.contains("افتح") || command.contains("شغل")) &&
                        (command.contains("كاميرا") || command.contains("الكاميرا") || command.contains("camera"))) {
                        val launch=Intent("android.media.action.STILL_IMAGE_CAMERA")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {startActivity(launch);say("تم سيدي")}catch (_:Exception){say("لم أستطع فتح الكاميرا")}
                    } else {
                        try {
                            val result=AgentEngine(this@DexterVoiceService).execute(command,key,"openai/gpt-oss-20b"){}
                            say(result.answer.take(320))
                        } catch (_:Exception){say("تعذر الاتصال بالمخ سيدي")}
                    }
                } finally {c.disconnect()}
            } catch (_:Exception){}
        }
    }
    private fun say(message:String) {
        speaking=true
        visualMode="speaking"
        val audio=tts
        if(audio==null) { speaking=false;return }
        audio.language=java.util.Locale("ar")
        audio.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){
            override fun onStart(id:String?){}
            override fun onDone(id:String?){speaking=false;visualMode="idle"}
            override fun onError(id:String?){speaking=false;visualMode="idle"}
        })
        audio.speak(message,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"dexter-bg")
    }
    override fun onDestroy() {
        recording=false;running=false;audioLevel=0f;visualMode="idle"
        eyes?.let {try{windows?.removeView(it)}catch (_:Exception){}}
        eyes=null
        tts?.stop();tts?.shutdown()
        super.onDestroy()
    }
}
