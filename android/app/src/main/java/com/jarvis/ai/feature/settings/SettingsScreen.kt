package com.jarvis.ai.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.ai.R
import com.jarvis.ai.core.agent.JarvisAccessibilityService
import com.jarvis.ai.ui.theme.BgPanel
import com.jarvis.ai.ui.theme.ErrorRed
import com.jarvis.ai.ui.theme.SuccessGreen
import com.jarvis.ai.ui.theme.TextDim
import com.jarvis.ai.ui.theme.WarnAmber
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val proactivePrefs = remember { context.getSharedPreferences("lora_proactive", android.content.Context.MODE_PRIVATE) }
    var proactiveEnabled by remember { mutableStateOf(proactivePrefs.getBoolean("enabled", false)) }
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val voicePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.enrollOwnerVoice()
    }

    LaunchedEffect(settings.voiceProvider) { viewModel.loadVoices() }
    LaunchedEffect(Unit) { viewModel.loadSttLanguages() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringRes(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("رجوع") }
        }

        ui.notice?.let { notice ->
            Section(title = "تنبيه") {
                Text(notice, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::dismissNotice) { Text("حسنًا") }
            }
        }

        if (!ui.secureStorageAvailable) {
            Section(title = "التخزين المشفّر") {
                Text(
                    "غير متاح على هذا الجهاز. لن يُحفَظ المفتاح ولا رمز الجهاز بين الجلسات.",
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ------------------------------------------------- AI provider
        Section(title = stringRes(R.string.settings_provider)) {
            var keyInput by rememberSaveable { mutableStateOf("") }

            Text(
                stringRes(R.string.settings_api_key_help),
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (ui.hasApiKey) {
                Text("مفتاح محفوظ ✓", color = SuccessGreen, style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(stringRes(R.string.settings_api_key)) },
                placeholder = { Text("gsk_...", color = TextDim) },
                // The key is never rendered back in clear text.
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.saveApiKey(keyInput)
                        keyInput = ""
                    },
                    enabled = keyInput.isNotBlank(),
                ) { Text("حفظ") }

                OutlinedButton(onClick = viewModel::testConnection, enabled = ui.hasApiKey && !ui.testing) {
                    if (ui.testing) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringRes(R.string.settings_test_connection))
                    }
                }

                if (ui.hasApiKey) {
                    TextButton(onClick = viewModel::clearApiKey) { Text("حذف", color = ErrorRed) }
                }
            }

            ui.testResult?.let { result ->
                Text(
                    result,
                    color = if (ui.testOk == true) SuccessGreen else ErrorRed,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ------------------------------------------------------- model
        Section(title = stringRes(R.string.settings_model)) {
            Text(settings.model, style = MaterialTheme.typography.bodyMedium)
            Text(
                "نماذج Groq القادرة على تنفيذ الأدوات. قد تختلف الإتاحة بحسب حسابك؛ اختبر الاتصال قبل اختيار النموذج.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::loadModels,
                    enabled = ui.hasApiKey && !ui.loadingModels,
                ) {
                    Text(if (ui.loadingModels) "جارٍ التحميل..." else "النماذج المدعومة")
                }
            }

            val choices = ui.toolCapableModels.map { it.id }.ifEmpty { viewModel.suggestedModels }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in choices.take(12)) {
                    FilterChip(
                        selected = settings.model == id,
                        onClick = { viewModel.setModel(id) },
                        label = { Text(id, style = MaterialTheme.typography.bodyMedium) },
                    )
                }
            }
            if (ui.toolCapableModels.isEmpty()) {
                Text(
                    "هذه نماذج Groq مقترحة؛ اضغط «النماذج المدعومة» للتحقق من النماذج المتاحة لمفتاحك.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // --------------------------------------- speech recognition
        Section(title = "محرّك الصوت") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.sttEngine == "deepgram",
                    onClick = { viewModel.setSttEngine("deepgram") },
                    label = { Text("ديبڨرام (مباشر)") },
                )
                FilterChip(
                    selected = settings.sttEngine == "off",
                    onClick = { viewModel.setSttEngine("off") },
                    label = { Text("كتابة فقط") },
                )
            }

            Text(
                "ديبڨرام يتعرّف على العربية مباشرة أثناء الكلام، ويكشف بداية ونهاية الجملة، " +
                    "ويسمح بمقاطعة لورا وهي تتحدّث. النطق يبقى بمحرّكاتنا لأن ديبڨرام " +
                    "لا يوفّر أي صوت عربي.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            var deepgramInput by rememberSaveable { mutableStateOf("") }

            Text(
                if (ui.backendPaired) {
                    "الجهاز مقترن بخادم، فالمفتاح يبقى عليه ولا ينزل إلى الهاتف."
                } else {
                    "بلا خادم: المفتاح يُحفَظ على هذا الهاتف مشفّرًا بـ Keystore. " +
                        "لا تشارك الـ APK بعد إدخاله."
                },
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!ui.backendPaired) {
                if (ui.hasDeepgramKey) {
                    Text("مفتاح ديبڨرام محفوظ ✓", color = SuccessGreen, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = deepgramInput,
                    onValueChange = { deepgramInput = it },
                    label = { Text("مفتاح ديبڨرام") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveDeepgramKey(deepgramInput)
                            deepgramInput = ""
                        },
                        enabled = deepgramInput.isNotBlank(),
                    ) { Text("حفظ") }
                    if (ui.hasDeepgramKey) {
                        TextButton(onClick = viewModel::clearDeepgramKey) { Text("حذف", color = ErrorRed) }
                    }
                }
            }

            if (ui.sttLanguages.isNotEmpty()) {
                Text("اللغة", style = MaterialTheme.typography.bodyLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (language in ui.sttLanguages) {
                        FilterChip(
                            selected = settings.sttLanguage == language.code,
                            onClick = { viewModel.setSttLanguage(language.code) },
                            label = {
                                Text(
                                    listOf(language.label, language.note)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" — "),
                                )
                            },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = viewModel::testRecognition,
                enabled = ui.backendPaired || ui.hasDeepgramKey,
            ) {
                Text("اختبار الاتصال بالتعرّف")
            }

            ui.sttCheck?.let { message ->
                Text(
                    message,
                    color = if (ui.sttCheckOk == true) SuccessGreen else ErrorRed,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Section(title = "لورا الاستباقية") {
            ToggleRow(
                label = "المساعدة التلقائية في المحادثات الفرنسية",
                checked = settings.automaticFrenchHelp,
                onChange = viewModel::setAutomaticFrenchHelp,
            )
            Text(
                "بعد تفعيل هذا الخيار مرة واحدة، وأثناء تشغيل ميكروفون لورا، تتعرف على مؤشرات التردد أو طلب المساعدة بالفرنسية وتقترح ردّاً بدون زر خاص. التسجيل لا يبدأ سراً. استعملها بعلم وموافقة الأشخاص الحاضرين، ويمكنك تعطيلها هنا.",
                color = TextDim,
                style = MaterialTheme.typography.bodySmall,
            )

            ToggleRow(
                label = "تفاعل لورا عندما يُذكر اسمها في ChatGPT",
                checked = proactiveEnabled,
                onChange = {
                    proactiveEnabled = it
                    proactivePrefs.edit().putBoolean("enabled", it).apply()
                },
            )
            Text(
                "تحتاج تفعيل خدمة إمكانية الوصول من إعدادات الهاتف. تفحص لورا النص الظاهر محلياً داخل ChatGPT فقط، ولا ترسل محتوى الشاشة لأي خادم. تتفاعل مرة كل عشر دقائق على الأكثر. يمكنك تعطيلها في أي لحظة.",
                color = TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ------------------------------------------------------- voice
        Section(title = stringRes(R.string.settings_voice)) {
            Text("النطق الأساسي: Groq Orpheus (عائشة). عند نفاد الحصة يتحول تلقائياً إلى Deepgram Thalia الإنجليزية، ثم يعود إلى عائشة بعد انتهاء مهلة Groq. يلزم مفتاح Deepgram محفوظ في قسم التعرف على الكلام.", color = TextDim, style = MaterialTheme.typography.bodyMedium)
            if (ui.voices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in ui.voices.take(12)) {
                        FilterChip(
                            selected = settings.voice == option.id,
                            onClick = { viewModel.setVoice(option.id) },
                            label = {
                                Text(
                                    listOf(option.name, option.localeName).filter { it.isNotBlank() }
                                        .joinToString(" — "),
                                )
                            },
                        )
                    }
                }
            }

            if (false) {
            SliderRow(
                label = stringRes(R.string.settings_pitch),
                value = settings.voicePitch.removeSuffix("Hz").toFloatOrNull() ?: -15f,
                range = -50f..50f,
                suffix = "Hz",
            ) { viewModel.setPitch("${it.roundToInt()}Hz") }

            SliderRow(
                label = stringRes(R.string.settings_speech_rate),
                value = settings.voiceRate.removeSuffix("%").toFloatOrNull() ?: -8f,
                range = -50f..100f,
                suffix = "%",
            ) { viewModel.setRate("${it.roundToInt()}%") }

            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::previewVoice) {
                    Text(if (ui.speaking) "إيقاف" else "سماع عيّنة")
                }
                AssistChip(
                    onClick = {},
                    label = { Text("الأنماط تُطبَّق بالنبرة فقط", style = MaterialTheme.typography.bodyMedium) },
                )
            }

            ToggleRow(
                label = stringRes(R.string.settings_auto_speak),
                checked = settings.autoSpeak,
                onChange = viewModel::setAutoSpeak,
            )
        }

        // -------------------------------------------------- automation
        Section(title = stringRes(R.string.settings_automation)) {
            ToggleRow(
                label = stringRes(R.string.settings_confirm_sensitive),
                checked = settings.confirmSensitiveActions,
                onChange = viewModel::setConfirmSensitive,
            )
            Text(
                "عند التشغيل، لا يُنفَّذ أي إجراء حسّاس قبل موافقتك الصريحة.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            SliderRow(
                label = "أقصى عدد لاستدعاءات الأدوات",
                value = settings.maxToolCalls.toFloat(),
                // Screen automation spends calls on looking before acting,
                // so the ceiling has to sit above what a UI chain needs.
                range = 1f..40f,
                suffix = "",
            ) { viewModel.setMaxToolCalls(it.roundToInt()) }

            SliderRow(
                label = "أقصى عدد لرموز الإخراج",
                value = settings.maxOutputTokens.toFloat(),
                range = 128f..4096f,
                suffix = "",
            ) { viewModel.setMaxOutputTokens(it.roundToInt()) }
        }

        // -------------------------------------------------- screen control
        Section(title = "التحكّم بالشاشة") {
            val context = androidx.compose.ui.platform.LocalContext.current
            // Read on each recomposition rather than cached: the user
            // leaves the app to toggle this and comes straight back.
            val enabled = JarvisAccessibilityService.isEnabled

            Text(
                if (enabled) "مفعّلة — يمكنني النقر والكتابة والتمرير على الشاشة" else "غير مفعّلة",
                color = if (enabled) SuccessGreen else WarnAmber,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "بدونها أستطيع فتح التطبيقات فقط، أمّا النقر داخلها والكتابة فيها " +
                    "فيحتاج هذا الإذن. التفعيل يدويّ من إعدادات النظام — لا يمكن للتطبيق تفعيله بنفسه.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!enabled) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        )
                    }
                }) { Text("افتح إعدادات إمكانية الوصول") }
            }
        }

        // Only an encrypted enrollment sample: do not imply speaker recognition
        // can authenticate sensitive commands without an anti-spoofing model.
        Section(title = "البصمة الصوتية للمالك — تجريبي") {
            Text(
                "تقدر تسجّل عينة صوتية من 3.5 ثوانٍ. تُشفّر بـ Android Keystore " +
                    "وتُحفظ داخل الهاتف فقط ولا تُرسل للسيرفر. هذه عينة تدريبية، " +
                    "وما زال التعرّف الآمن على المتحدث غير مفعّل؛ الأوامر الحساسة تحتاج تأكيدك.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (ui.ownerVoiceEnrolled) "عينة مشفّرة محفوظة ✓" else "ما كاين حتى تسجيل محفوظ",
                color = if (ui.ownerVoiceEnrolled) SuccessGreen else TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !ui.ownerVoiceRecording,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.enrollOwnerVoice()
                        } else {
                            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) {
                    Text(if (ui.ownerVoiceRecording) "جارٍ تسجيل صوتك..." else "سجّل بصمتي")
                }
                if (ui.ownerVoiceEnrolled) {
                    OutlinedButton(
                        enabled = !ui.ownerVoiceRecording,
                        onClick = viewModel::deleteOwnerVoice,
                    ) { Text("حذف العينة") }
                }
            }
        }

        // -------------------------------------------------- appearance
        Section(title = stringRes(R.string.settings_appearance)) {
            ToggleRow(
                label = stringRes(R.string.settings_reduced_motion),
                checked = settings.reducedMotion,
                onChange = viewModel::setReducedMotion,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgPanel),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text("${local.roundToInt()}$suffix", color = TextDim)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
        )
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
