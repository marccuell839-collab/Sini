package dz.sini.jarvis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ToolProposal(val id: String, val argument: String, val explanation: String)
data class AgentResult(val answer: String, val proposals: List<ToolProposal>, val stages: List<String>)

/** An explicit plan -> tools -> response agent. Device actions require approval in the UI. */
class AgentEngine(private val context: Context) {
    fun execute(prompt: String, apiKey: String, model: String, stage: (String) -> Unit): AgentResult {
        stage("01 • فهم الطلب وتحديد الهدف")
        val facts = gatherFacts()
        stage("02 • تحليل الأدوات وحدود الصلاحيات")
        val system = """
You are DEXTER, an Arabic-first Android device assistant and research partner.
Respond with only a valid JSON object:
{"reasoning_summary":"short user-facing plan, not private reasoning","answer":"helpful answer in user's language","tools":[{"id":"open_settings|open_url|web_search|open_app|share_text|copy_text","argument":"value","explanation":"why"}]}
Device snapshot: $facts
Tools are proposals, never claim execution. The user must approve each action.
Only propose relevant tools; never invent installed apps or unavailable device capabilities.
For open_app, argument is an Android package name or a visible app label. For web_search, argument is a search phrase.
Never provide fake research sources, fake test results, or claim physical experiments.
No shell, no invisible file access, no reading messages, no silent installs, no bypassing Android security.
Keep answer focused, useful, detailed when needed. Arabic Algerian speech is welcome.
""".trimIndent()
        stage("03 • استدعاء نموذج الذكاء الاصطناعي")
        val json = JSONObject().put("model", model)
            .put("temperature", 0.3).put("max_tokens", 1200)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val conn = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 90000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Title", "DEXTER by Appnova")
            conn.doOutput = true
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("OpenRouter HTTP $code: ${raw.take(200)}")
            val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            stage("04 • فحص الأوامر قبل عرضها")
            val parsed = parseJson(content)
            if (parsed == null) return AgentResult(content, emptyList(), listOf("الرد نصي؛ النموذج لم يرجع أوامر منظمة"))
            val tools = parsed.optJSONArray("tools") ?: JSONArray()
            val allowed = setOf("open_settings", "open_url", "web_search", "open_app", "share_text", "copy_text")
            val proposals = (0 until minOf(tools.length(), 5)).mapNotNull { i ->
                val t = tools.optJSONObject(i) ?: return@mapNotNull null
                val id = t.optString("id")
                val arg = t.optString("argument")
                if (id !in allowed || arg.isBlank() || arg.length > 1600) null
                else ToolProposal(id, arg, t.optString("explanation", id))
            }
            return AgentResult(parsed.optString("answer", content), proposals,
                listOf(parsed.optString("reasoning_summary", "تم تحليل المهمة")))
        } finally { conn.disconnect() }
    }
    private fun parseJson(text: String): JSONObject? = try {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        JSONObject(trimmed)
    } catch (_: Exception) {
        try {
            JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
        } catch (_: Exception) { null }
    }
    private fun gatherFacts(): String {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Android ${Build.VERSION.RELEASE}; device ${Build.MANUFACTURER} ${Build.MODEL}; battery $batteryPercent%; no privileged access"
    }
    fun label(t: ToolProposal): String = when (t.id) {
        "open_settings" -> "⚙ فتح إعدادات الهاتف"
        "open_url" -> "↗ فتح الرابط"
        "web_search" -> "⌕ بحث في المتصفح"
        "open_app" -> "▣ تشغيل تطبيق"
        "share_text" -> "⇧ مشاركة نص"
        "copy_text" -> "⧉ نسخ النص"
        else -> t.id
    }
    fun runApproved(t: ToolProposal) {
        when (t.id) {
            "open_settings" -> context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "open_url" -> {
                val u = Uri.parse(t.argument.trim())
                require(u.scheme == "https" || u.scheme == "http") { "يسمح فقط بروابط HTTP/HTTPS" }
                context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "web_search" -> {
                val uri = Uri.parse("https://www.google.com/search").buildUpon()
                    .appendQueryParameter("q", t.argument).build()
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "open_app" -> {
                val pm = context.packageManager
                val requested = t.argument.trim()
                var intent = pm.getLaunchIntentForPackage(requested)
                if (intent == null) {
                    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val matches = pm.queryIntentActivities(main, 0)
                    val match = matches.firstOrNull { it.loadLabel(pm).toString().equals(requested, true) }
                    if (match != null) intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                }
                require(intent != null) { "التطبيق غير موجود أو غير ظاهر للنظام" }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "share_text" -> {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t.argument)
                }
                context.startActivity(Intent.createChooser(i, "مشاركة من ديكستر").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "copy_text" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DEXTER", t.argument))
            }
        }
    }
}
