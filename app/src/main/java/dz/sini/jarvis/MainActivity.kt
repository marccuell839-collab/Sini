package dz.sini.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognizerIntent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val cyan = Color.rgb(0, 213, 255)
    private val bg = Color.rgb(2, 8, 17)
    private val light = Color.rgb(218, 238, 250)
    private val muted = Color.rgb(132, 158, 184)
    private val prefs by lazy { getSharedPreferences("dexter_config", MODE_PRIVATE) }
    private val engine by lazy { AgentEngine(this) }
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var reply: TextView
    private lateinit var bottom: LinearLayout
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var media: MediaPlayer? = null
    private var busy = false
    private var capture: AudioRecord? = null
    @Volatile private var captureRunning = false
    @Volatile private var voicePlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var pendingWake = false
    private var audioPaused = false
    private var capturingSpeech = false

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun panel(fill: Int = Color.rgb(7, 19, 33), edge: Int = Color.rgb(24, 71, 98), radius: Int = 22) =
        GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), edge) }
    private fun text(value: String, size: Float, color: Int = light) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
    }
    private fun add(v: LinearLayout, child: View, margin: Int = 0, height: Int = -2) {
        v.addView(child, LinearLayout.LayoutParams(-1, height).apply { topMargin = dp(margin) })
    }
    private fun action(label: String, fn: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; setTextColor(light); gravity = Gravity.CENTER
        background = panel(Color.rgb(7, 20, 35), Color.rgb(27, 80, 108), 50)
        setOnClickListener { fn() }
        contentDescription = label
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        tts = TextToSpeech(this, this)
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val settings = action("⚙") { showSettings() }
        header.addView(settings, LinearLayout.LayoutParams(dp(54), dp(54)))
        val heading = text("D · E · X · T · E · R", 21f)
            .apply { letterSpacing = .16f; setTypeface(null, Typeface.BOLD) }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(54), 1f))
        val reports = action("▤") { showReport() }
        header.addView(reports, LinearLayout.LayoutParams(dp(54), dp(54)))
        add(frame, header)
        add(frame, text("●  WAKE     │     ●  LISTENING     │     ●  AGENT READY", 11f, cyan), 20)
        orb = OrbView(this)
        add(frame, orb, 12, 0)
        val orbParams = orb.layoutParams as LinearLayout.LayoutParams
        orbParams.height = 0; orbParams.weight = 1f; orb.layoutParams = orbParams
        status = text("Ready", 27f)
        add(frame, status, 2)
        reply = text("مرحباً سيدي. أعطني المهمة، وسأحللها وأقترح الخطوات والأدوات قبل التنفيذ.", 16f)
            .apply {
                gravity = Gravity.CENTER
                background = panel()
                setPadding(dp(16), dp(16), dp(16), dp(16))
                maxLines = 5
            }
        add(frame, reply, 18)
        val mic = action("◉  ALWAYS LISTENING • PAUSE / RESUME") {
            if (DexterVoiceService.running) {
                stopService(Intent(this, DexterVoiceService::class.java))
                status.text = "⏸ تم إيقاف ديكستر"
            } else startBackgroundVoice()
        }
        add(frame, mic, 24, dp(60))
        setContentView(frame)
        startBackgroundVoice()
    }
    private fun startBackgroundVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 44)
            return
        }
        try {
            androidxPlaceholderStart()
            status.text = "● DEXTER يعمل في الخلفية"
        } catch (e: Exception) { status.text = "تعذر تشغيل الخدمة: ${e.message}" }
        if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this).setTitle("عينَا ديكستر")
                .setMessage("اسمح بالظهور فوق التطبيقات لإظهار عيني DEXTER أعلى الشاشة. يمكنك إلغاء الإذن من إعدادات Android.")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                }.setNegativeButton("لاحقاً", null).show()
        }
    }
    private fun androidxPlaceholderStart() {
        if (android.os.Build.VERSION.SDK_INT >= 26)
            startForegroundService(Intent(this, DexterVoiceService::class.java))
        else startService(Intent(this, DexterVoiceService::class.java))
    }
    private fun handleSpoken(phrase: String) {
        val normalized = phrase.lowercase(Locale.ROOT).trim()
        val wake = listOf("ديكستر", "دكستر", "dexter").firstOrNull { normalized.contains(it) }
        if (wake == null && !pendingWake) return
        val command = if (wake != null) normalized.substringAfter(wake).trim() else normalized
        pendingWake = command.isBlank()
        if (pendingWake) {
            status.text = "نعم سيدي، تفضل"
            speak("نعم سيدي")
            return
        }
        pendingWake = false
        if ((command.contains("افتح") || command.contains("شغل")) &&
            (command.contains("الكاميرا") || command.contains("كاميرا") || command.contains("camera"))) {
            try {
                val camera = Intent("android.media.action.STILL_IMAGE_CAMERA")
                startActivity(camera)
                status.text = "✓ الكاميرا"
            } catch (e: Exception) { status.text = "تعذر فتح الكاميرا: ${e.message}" }
            // Camera opens without restarting the microphone capture session.
            return
        }
        runAgent(command)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 44 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startBackgroundVoice()
    }
    private fun runAgent(query: String) {
        if (query.isBlank() || busy) return
        val useGroq = prefs.getString("provider", "groq") == "groq"
        val key = prefs.getString(if (useGroq) "groq" else "open", "") ?: ""
        val model = if (useGroq) "openai/gpt-oss-20b" else (prefs.getString("model", "openrouter/free") ?: "openrouter/free")
        if (key.isBlank()) { showSettings(); status.text = "أدخل مفتاح مزوّد الذكاء من الإعدادات"; return }
        busy = true; orb.active = true; status.text = "Thinking"
        reply.text = "⏳ يجري تحليل المهمة..."
        thread {
            try {
                val result = engine.execute(query, key, model) { phase ->
                    runOnUiThread { status.text = phase }
                }
                runOnUiThread {
                    reply.text = result.answer
                    status.text = "✓ Ready • ${result.proposals.size} tools"
                    if (result.proposals.isNotEmpty()) showProposals(result.proposals)
                    speak(result.answer.take(650))
                    val record = "المهمة: $query\n\n${result.stages.joinToString("\n")}\n\n${result.answer}"
                    prefs.edit().putString("report", record).apply()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Connection error"; reply.text = "تعذّر إكمال المهمة: ${e.message}" }
            } finally { runOnUiThread { orb.active = false; busy = false; Unit } }
        }
    }
    private fun showProposals(items: List<ToolProposal>) {
        val names = items.map { "${engine.label(it)}\n${it.explanation.take(105)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("اقتراحات التنفيذ — تحتاج موافقتك")
            .setItems(names) { _, selected ->
                val tool = items[selected]
                AlertDialog.Builder(this).setTitle(engine.label(tool))
                    .setMessage("التفاصيل:\n${tool.argument}\n\n${tool.explanation}")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("تنفيذ") { _, _ ->
                        try { engine.runApproved(tool); status.text = "✓ تم طلب العملية" }
                        catch (e: Exception) { reply.text = "تعذّر التنفيذ: ${e.message}" }
                    }.show()
            }.setNegativeButton("لاحقاً", null).show()
    }
    private fun showReport() {
        val saved = prefs.getString("report", "لم تنجز أي مهمة بعد") ?: ""
        AlertDialog.Builder(this).setTitle("آخر تقرير").setMessage(saved)
            .setPositiveButton("مشاركة") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, saved)
                }, "مشاركة التقرير"))
            }.setNegativeButton("رجوع", null).show()
    }
    private fun settingsField(hint: String, value: String, sensitive: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(light); setHintTextColor(muted)
        if (sensitive) inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true); textSize = 14f
    }
    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            setBackgroundColor(Color.rgb(11, 25, 40))
        }
        val groq = settingsField("Groq API Key • GPT-OSS + Orpheus", prefs.getString("groq", "") ?: "", true)
        val groqMode = CheckBox(this).apply {
            text = "استعمال Groq GPT-OSS 20B كمخ رئيسي"
            setTextColor(light); isChecked = prefs.getString("provider", "groq") == "groq"
        }
        val groqAudio = CheckBox(this).apply {
            text = "صوت Orpheus العربي (abdullah)"
            setTextColor(light); isChecked = prefs.getBoolean("groq_audio", true)
        }
        val api = settingsField("OpenRouter API Key (احتياطي)", prefs.getString("open", "") ?: "", true)
        val model = settingsField("OpenRouter Model", prefs.getString("model", "openrouter/free") ?: "openrouter/free")
        val deep = settingsField("Deepgram API key (optional)", prefs.getString("deep", "") ?: "", true)
        val voice = settingsField("Deepgram voice", prefs.getString("voice", "aura-2-thalia-en") ?: "aura-2-thalia-en")
        val enabled = CheckBox(this).apply {
            text = "تشغيل الصوت"; setTextColor(light); isChecked = prefs.getBoolean("speak", true)
        }
        for (v in listOf(groqMode, groq, groqAudio, api, model, deep, voice, enabled)) add(box, v, 5)
        val accessibility = Button(this).apply { text = "تفعيل التحكم باللمس والكتابة" }
        add(box, accessibility)
        accessibility.setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val pick = Button(this).apply { text = "تصفّح نماذج OpenRouter المجانية" }
        add(box, pick)
        pick.setOnClickListener { browseModels { selected -> model.setText(selected) } }
        val dialog = AlertDialog.Builder(this).setTitle("DEXTER • SETTINGS")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("حفظ") { _, _ ->
                prefs.edit().putString("provider", if (groqMode.isChecked) "groq" else "openrouter")
                    .putString("groq", groq.text.toString().trim())
                    .putBoolean("groq_audio", groqAudio.isChecked)
                    .putString("open", api.text.toString().trim())
                    .putString("model", model.text.toString().trim())
                    .putString("deep", deep.text.toString().trim())
                    .putString("voice", voice.text.toString().trim())
                    .putBoolean("speak", enabled.isChecked).apply()
                status.text = "Settings saved"
            }.setNegativeButton("إغلاق", null).create()
        dialog.show()
    }
    private fun browseModels(select: (String) -> Unit) {
        status.text = "Loading free models..."
        thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
                connection.connectTimeout = 18000; connection.readTimeout = 20000
                if (connection.responseCode !in 200..299) throw Exception("HTTP ${connection.responseCode}")
                val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
                val list = ArrayList<String>()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i); val id = item.optString("id")
                    val pricing = item.optJSONObject("pricing")
                    if (id.endsWith(":free") || (pricing?.optString("prompt")?.toDoubleOrNull() == 0.0 &&
                                pricing.optString("completion").toDoubleOrNull() == 0.0)) list.add(id)
                }
                list.sort()
                runOnUiThread {
                    status.text = "${list.size} free models"
                    if (list.isEmpty()) { Toast.makeText(this, "لا توجد نتائج", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                    AlertDialog.Builder(this).setTitle("نماذج مجانية • حسب OpenRouter")
                        .setItems(list.toTypedArray()) { _, which -> select(list[which]) }
                        .setNegativeButton("رجوع", null).show()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Models unavailable: ${e.message}" } }
            finally { connection?.disconnect() }
        }
    }
    private fun speak(words: String) {
        if (!prefs.getBoolean("speak", true)) return
        voicePlaying = true
        val groqKey = prefs.getString("groq", "") ?: ""
        if (prefs.getBoolean("groq_audio", true) && groqKey.isNotBlank()) {
            speakOrpheus(words, groqKey)
            return
        }
        val deep = prefs.getString("deep", "") ?: ""
        if (deep.isBlank()) { speakLocal(words); return }
        thread {
            var conn: HttpURLConnection? = null
            try {
                val voice = prefs.getString("voice", "aura-2-thalia-en") ?: "aura-2-thalia-en"
                conn = URL("https://api.deepgram.com/v1/speak?model=${java.net.URLEncoder.encode(voice, "UTF-8")}")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 40000
                conn.setRequestProperty("Authorization", "Token $deep")
                conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("text", words).toString().toByteArray()) }
                if (conn.responseCode !in 200..299) throw Exception("Deepgram HTTP ${conn.responseCode}")
                val audio = File(cacheDir, "dexter_tts.mp3")
                conn.inputStream.use { input -> audio.outputStream().use { input.copyTo(it) } }
                runOnUiThread {
                    media?.release()
                    media = MediaPlayer().apply {
                        setDataSource(audio.absolutePath); prepare()
                        setOnCompletionListener { it.release(); if (media === it) media = null; voicePlaying = false }
                        start()
                    }
                }
            } catch (_: Exception) { runOnUiThread { voicePlaying = false; speakLocal(words) } }
            finally { conn?.disconnect() }
        }
    }
    private fun speakOrpheus(words: String, apiKey: String) {
        thread {
            try {
                val sentence = words.replace(Regex("[\\r\\n]+"), " ").take(180)
                val conn = URL("https://api.groq.com/openai/v1/audio/speech").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 40000
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                    val payload = JSONObject().put("model", "canopylabs/orpheus-arabic-saudi")
                        .put("voice", "abdullah").put("response_format", "wav")
                        .put("input", sentence)
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) throw Exception("Groq TTS HTTP ${conn.responseCode}")
                    val file = File(cacheDir, "dexter_orpheus.wav")
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    runOnUiThread {
                        media?.release()
                        media = MediaPlayer().apply {
                            setDataSource(file.absolutePath); prepare()
                            setOnCompletionListener { it.release(); if (media === it) media = null }
                            start()
                        }
                    }
                } finally { conn.disconnect() }
            } catch (_: Exception) { runOnUiThread { speakLocal(words) } }
        }
    }
    private fun speakLocal(words: String) {
        if (ttsReady) {
            voicePlaying = true
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { voicePlaying = false }
                override fun onError(utteranceId: String?) { voicePlaying = false }
            })
            tts.language = Locale("ar")
            tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "dexter-response")
        } else voicePlaying = false
    }
    override fun onInit(status: Int) { ttsReady = status == TextToSpeech.SUCCESS }
    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        media?.release(); tts.stop(); tts.shutdown()
        super.onDestroy()
    }
}
