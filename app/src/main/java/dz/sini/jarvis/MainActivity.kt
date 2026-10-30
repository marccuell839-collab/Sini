package dz.sini.jarvis

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var tts: TextToSpeech
    private var ready = false
    private val turns = JSONArray()
    private val prefs by lazy { getSharedPreferences("jarvis_private", MODE_PRIVATE) }
    private val speechRequest = 2401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 20)
            setBackgroundColor(Color.rgb(7, 13, 27))
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(197, 225, 255))
        }
        val title = label("◉  S I N I  ·  J A R V I S", 25f).apply { setTypeface(null, Typeface.BOLD) }
        root.addView(title)
        status = label("جاهز للإعداد", 14f)
        root.addView(status)
        keyInput = EditText(this).apply {
            hint = "OpenRouter API Key"
            setSingleLine(true)
            inputType = 129
            setText(prefs.getString("key", ""))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        root.addView(keyInput)
        modelInput = EditText(this).apply {
            hint = "Model ID (e.g. openrouter/free)"
            setSingleLine(true); setText(prefs.getString("model", "openrouter/free"))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        root.addView(modelInput)
        history = label("تحدث معي بالعربية أو الإنجليزية. اضغط الميكروفون أو أرسل رسالة.", 17f)
        val scroll = ScrollView(this).apply {
            addView(history); isFillViewport = true
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        messageInput = EditText(this).apply {
            hint = "اكتب رسالتك هنا..."
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        root.addView(messageInput)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
            this.text = text; setOnClickListener { onClick() }
        }
        buttons.addView(button("🎤 تحدث") { listen() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(button("➤ إرسال") {
            val query = messageInput.text.toString().trim()
            if (query.isNotEmpty()) { messageInput.setText(""); ask(query) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(button("■ صمت") { tts.stop() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        setContentView(root)
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "خدمة التعرف على الصوت غير متاحة على الهاتف"; return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تكلم الآن...")
        }
        try { startActivityForResult(intent, speechRequest) }
        catch (e: Exception) { status.text = "تعذر فتح الميكروفون: ${e.message}" }
    }

    @Deprecated("Legacy recognizer activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequest && resultCode == RESULT_OK) {
            val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!phrase.isNullOrBlank()) ask(phrase)
        }
    }

    private fun ask(query: String) {
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        if (key.isBlank() || model.isBlank()) { status.text = "أدخل مفتاح OpenRouter واسم النموذج"; return }
        prefs.edit().putString("key", key).putString("model", model).apply()
        history.append("\n\nأنت: $query")
        status.text = "جارفيس يفكر..."
        turns.put(JSONObject().put("role", "user").put("content", query))
        thread {
            try {
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
                    "You are JARVIS, a warm, quick, helpful conversational voice companion. Reply in the user's language, including Algerian Arabic when appropriate. Keep answers concise and natural. Do not pretend to execute actions you cannot perform. Prefix every answer with one emotion tag on its own line: [neutral], [happy], [sad], [excited], or [calm]. Use tags sparingly based on context."))
                synchronized(turns) {
                    for (i in maxOf(0, turns.length()-16) until turns.length()) messages.put(turns.getJSONObject(i))
                }
                val payload = JSONObject().put("model", model).put("messages", messages)
                    .put("max_tokens", 320)
                val conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw Exception("HTTP $code: ${body.take(250)}")
                val answer = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "")
                val clean = answer.replace(Regex("^\\[(neutral|happy|sad|excited|calm)]\\s*", RegexOption.IGNORE_CASE), "").trim()
                val emotion = Regex("^\\[(neutral|happy|sad|excited|calm)]", RegexOption.IGNORE_CASE).find(answer)
                    ?.groupValues?.get(1)?.lowercase() ?: "neutral"
                synchronized(turns) { turns.put(JSONObject().put("role", "assistant").put("content", clean)) }
                runOnUiThread {
                    history.append("\n\nJARVIS: $clean")
                    status.text = "النبرة: $emotion"
                    speak(clean, emotion)
                }
            } catch (e: Exception) {
                synchronized(turns) { if (turns.length() > 0) turns.remove(turns.length()-1) }
                runOnUiThread { status.text = "خطأ: ${e.message}" }
            }
        }
    }

    private fun speak(text: String, emotion: String) {
        if (!ready || text.isBlank()) return
        val (rate, pitch) = when (emotion) {
            "happy" -> 1.10f to 1.12f
            "excited" -> 1.17f to 1.15f
            "sad" -> .85f to .90f
            "calm" -> .90f to .94f
            else -> 1f to 1f
        }
        tts.setSpeechRate(rate); tts.setPitch(pitch)
        val locale = if (text.any { it in '\u0600'..'\u06ff' }) Locale("ar") else Locale.UK
        val support = tts.setLanguage(locale)
        if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.text = "ثبت صوتًا للغة المطلوبة من إعدادات تحويل النص إلى كلام"
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }

    override fun onInit(result: Int) { ready = result == TextToSpeech.SUCCESS }
    override fun onDestroy() { tts.stop(); tts.shutdown(); super.onDestroy() }
}
