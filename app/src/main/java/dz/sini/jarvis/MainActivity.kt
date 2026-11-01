package dz.sini.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val bg = Color.rgb(5, 9, 19)
    private val cyan = Color.rgb(0, 222, 240)
    private val fg = Color.rgb(220, 240, 248)
    private val muted = Color.rgb(122, 155, 176)
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var prompt: EditText
    private lateinit var openKey: EditText
    private lateinit var deepKey: EditText
    private lateinit var model: EditText
    private lateinit var voice: EditText
    private lateinit var speakToggle: CheckBox
    private lateinit var launch: Button
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var player: MediaPlayer? = null
    @Volatile private var running = false
    private var lastReport = ""
    private val prefs by lazy { getSharedPreferences("dexter_config", MODE_PRIVATE) }
    private val speechCode = 301

    private fun shape(color: Int, stroke: Int = cyan, corner: Float = 24f) =
        GradientDrawable().apply { setColor(color); cornerRadius = corner; setStroke(1, stroke) }
    private fun txt(s: String, size: Float = 15f, color: Int = fg) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL }
    private fun field(hintText: String, value: String = "") =
        EditText(this).apply {
            hint = hintText; setText(value); setSingleLine(true)
            setTextColor(fg); setHintTextColor(muted); textSize = 14f
            background = shape(Color.rgb(15, 27, 43), Color.rgb(28, 62, 78), 16f)
            setPadding(22, 14, 22, 14)
        }
    private fun add(parent: LinearLayout, v: View, top: Int = 10) {
        val p = LinearLayout.LayoutParams(-1, -2); p.topMargin = top; parent.addView(v, p)
    }
    private fun button(label: String, fn: () -> Unit) =
        Button(this).apply {
            text = label; textSize = 14f; setTextColor(Color.BLACK)
            background = shape(cyan, cyan, 24f); setOnClickListener { fn() }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        tts = TextToSpeech(this, this)
        val outside = ScrollView(this).apply { fillViewport = true; setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 28)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        outside.addView(root)
        add(root, txt("◉  D E X T E R   L A B", 25f, cyan), 0)
        add(root, txt("APPNOVA  /  AUTONOMOUS RESEARCH SYSTEM", 11f, muted), 2)
        status = txt("● SYSTEM READY  •  وضع البحث", 13f, cyan)
        add(root, status, 19)
        val sphere = txt("◉", 104f, cyan).apply {
            gravity = Gravity.CENTER; background = shape(Color.rgb(7, 25, 39), Color.rgb(0, 126, 152), 250f)
        }
        add(root, sphere, 18)
        add(root, txt("المشكلة أو الفكرة البحثية", 16f))
        prompt = field("مثال: ابتكر طريقة لتوسيع استقبال الإنترنت في قبو").apply {
            setSingleLine(false); minLines = 3; gravity = Gravity.TOP
        }
        add(root, prompt, 8)
        launch = button("⚡ ابدأ تجربة بحثية") { startResearch() }
        add(root, launch)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mic = button("🎤 صوت") { listen() }
        val share = button("⇧ التقرير") { shareReport() }
        actions.addView(mic, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(share, LinearLayout.LayoutParams(0, -2, 1f))
        add(root, actions, 5)
        add(root, txt("سجل المختبر / LIVE EXPERIMENT", 14f, cyan), 20)
        transcript = txt("ابدأ بتحديد مشكلة، وسيعمل المختبر على تحليلها وإعداد فرضيات واختبارات مقترحة وتقرير.\n", 14f)
            .apply { setPadding(16, 14, 16, 14); background = shape(Color.rgb(11, 21, 36), Color.rgb(25, 57, 79)) }
        add(root, transcript, 8)
        add(root, txt("إعدادات الاتصال  /  API", 15f, cyan), 22)
        openKey = field("OpenRouter API key", prefs.getString("open", "") ?: "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        add(root, openKey)
        model = field("OpenRouter model", prefs.getString("model", "openrouter/free") ?: "openrouter/free")
        add(root, model)
        deepKey = field("Deepgram API key (اختياري)", prefs.getString("deep", "") ?: "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        add(root, deepKey)
        voice = field("Deepgram voice", prefs.getString("voice", "aura-2-thalia-en") ?: "aura-2-thalia-en")
        add(root, voice)
        speakToggle = CheckBox(this).apply {
            text = "تشغيل الرد الصوتي (Deepgram إن توفر، وإلا صوت الجهاز)"
            setTextColor(fg); isChecked = prefs.getBoolean("speak", true)
        }
        add(root, speakToggle)
        add(root, button("حفظ الإعدادات") { save(); status.text = "✓ تم حفظ الإعدادات على الهاتف" })
        add(root, txt("الأبحاث المقترحة ليست اكتشافات مثبتة. لا يجري التطبيق تجارب فيزيائية ولا ينفّذ كوداً عشوائياً.", 12f, muted))
        setContentView(outside)
        lastReport = prefs.getString("report", "") ?: ""
    }
    private fun save() {
        prefs.edit().putString("open", openKey.text.toString().trim())
            .putString("model", model.text.toString().trim())
            .putString("deep", deepKey.text.toString().trim())
            .putString("voice", voice.text.toString().trim())
            .putBoolean("speak", speakToggle.isChecked).apply()
    }
    private fun ui(stage: String, data: String? = null) = runOnUiThread {
        status.text = stage
        if (data != null) transcript.append("\n\n$data")
    }
    private fun startResearch() {
        val problem = prompt.text.toString().trim()
        val key = openKey.text.toString().trim()
        val chosen = model.text.toString().trim()
        if (problem.isEmpty() || key.isEmpty() || chosen.isEmpty()) {
            status.text = "أدخل المشكلة ومفتاح OpenRouter والنموذج"; return
        }
        if (running) return
        save(); running = true; launch.isEnabled = false
        transcript.text = "▸ $problem\n"
        thread {
            val output = StringBuilder("# DEXTER LAB — Research Report\n\n## Research problem\n$problem\n\n")
            val phases = listOf(
                "01 / ANALYSIS" to "حلل المشكلة بدقة: افصل المعطيات والافتراضات والقيود الفيزيائية والتقنية. لا تخترع حقائق.",
                "02 / HYPOTHESES" to "اقترح ثلاث فرضيات متميزة مع آلية عمل واضحة، شروط النجاح وأسباب الفشل وتقدير مستوى الثقة.",
                "03 / EXPERIMENT PLAN" to "صمم اختبارات آمنة وقابلة لإعادة الإنتاج باستخدام أدوات متاحة للهاتف. أعط خطوات وقياسات ومعايير نجاح واضحة. لا تدع أنك نفذت تجارب.",
                "04 / REVIEW & REPORT" to "انقد الأفكار السابقة وحدد ما يمكن إثباته وما يحتاج أجهزة أو أدلة خارجية، ثم قدم أفضل حل عملي والخطوات التالية."
            )
            var accumulated = ""
            try {
                for ((name, task) in phases) {
                    ui("◉ $name ...")
                    val context = accumulated.takeLast(10500)
                    val answer = queryModel(key, chosen, problem, task, context)
                    val section = "## $name\n$answer\n\n"
                    output.append(section)
                    accumulated += section
                    ui("✓ $name", "◆ $name\n$answer")
                }
                lastReport = output.toString()
                prefs.edit().putString("report", lastReport).apply()
                ui("✓ RESEARCH COMPLETE  /  4 OF 4")
                if (prefs.getBoolean("speak", true)) speak("اكتمل تحليل المشكلة. التقرير جاهز للمراجعة.")
            } catch (e: Exception) {
                lastReport = output.toString()
                ui("⚠ RESEARCH INTERRUPTED", "خطأ: ${e.message}\nيمكنك إعادة المحاولة. النتائج الجزئية محفوظة للجلسة.")
            } finally { running = false; runOnUiThread { launch.isEnabled = true } }
        }
    }
    private fun queryModel(key: String, selected: String, problem: String, task: String, context: String): String {
        val system = "You are DEXTER LAB, an evidence-conscious research engineer. Write in Arabic. Be original but rigorous, distinguish verified facts from hypotheses, never fabricate sources, experiment results or novelty claims. No autonomous physical actions. Prefer feasible designs and explicit falsifiable tests. Limit response to 450 words."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", "المشكلة: $problem\nالنتائج السابقة:\n$context\nالمهمة الحالية: $task"))
        val payload = JSONObject().put("model", selected).put("messages", messages).put("max_tokens", 950)
        val conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 25000; conn.readTimeout = 90000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Title", "Appnova Dexter Lab")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("OpenRouter HTTP $code: ${response.take(180)}")
            val content = JSONObject(response).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            if (content.isBlank()) throw IllegalStateException("النموذج لم يُرجع نصاً؛ جرّب نموذجاً آخر")
            return content
        } finally { conn.disconnect() }
    }
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "التعرف على الصوت غير متاح"; return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-DZ")
        }
        try { startActivityForResult(i, speechCode) } catch (e: Exception) { status.text = "الميكروفون غير متاح" }
    }
    @Deprecated("Legacy API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechCode && resultCode == RESULT_OK) {
            val value = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!value.isNullOrBlank()) prompt.setText(value)
        }
    }
    private fun speak(words: String) {
        val token = prefs.getString("deep", "") ?: ""
        if (token.isBlank()) { speakLocal(words); return }
        thread {
            try {
                val modelName = prefs.getString("voice", "aura-2-thalia-en") ?: "aura-2-thalia-en"
                val conn = URL("https://api.deepgram.com/v1/speak?model=${java.net.URLEncoder.encode(modelName, "UTF-8")}")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 45000
                    conn.setRequestProperty("Authorization", "Token $token")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(JSONObject().put("text", words).toString().toByteArray()) }
                    if (conn.responseCode !in 200..299) throw Exception("Deepgram HTTP ${conn.responseCode}")
                    val file = File(cacheDir, "dexter_voice.mp3")
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    runOnUiThread {
                        player?.release()
                        player = MediaPlayer().apply {
                            setDataSource(file.absolutePath); prepare()
                            setOnCompletionListener { it.release(); player = null }
                            start()
                        }
                    }
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                ui("⚠ Deepgram غير متاح؛ استعمال صوت الجهاز")
                runOnUiThread { speakLocal(words) }
            }
        }
    }
    private fun speakLocal(words: String) {
        if (!ttsReady) return
        tts.language = Locale("ar")
        tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "dexter")
    }
    private fun shareReport() {
        if (lastReport.isBlank()) { status.text = "لم يتم إنشاء تقرير بعد"; return }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, lastReport)
        }
        startActivity(Intent.createChooser(i, "مشاركة تقرير المختبر"))
    }
    override fun onInit(result: Int) { ttsReady = result == TextToSpeech.SUCCESS }
    override fun onDestroy() { player?.release(); tts.stop(); tts.shutdown(); super.onDestroy() }
}
