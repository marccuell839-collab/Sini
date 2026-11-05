package com.jarvis.ai.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat
import com.jarvis.ai.core.vision.ScreenShareService
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.ai.R
import com.jarvis.ai.core.agent.AssistantState
import com.jarvis.ai.ui.theme.BgPanel
import com.jarvis.ai.ui.theme.BgRaised
import com.jarvis.ai.ui.theme.BorderSubtle
import com.jarvis.ai.ui.theme.ErrorRed
import com.jarvis.ai.ui.theme.SuccessGreen
import com.jarvis.ai.ui.theme.TextDim
import com.jarvis.ai.ui.theme.WarnAmber
import com.jarvis.ai.ui.theme.colorFor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    var shareRequested by rememberSaveable { mutableStateOf(false) }
    var sharing by rememberSaveable { mutableStateOf(ScreenShareService.isSharing) }
    val shareConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        shareRequested = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            sharing = true
        } else {
            sharing = false
        }
    }
    val requestScreenShare = {
        shareRequested = true
        // Android 14+ displays both options: share one app or entire screen.
        shareConsent.launch(projectionManager.createScreenCaptureIntent())
    }
    val stopScreenShare = {
        context.startService(Intent(context, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
        sharing = false
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.toggleMic(true) else viewModel.onMicPermissionDenied()
    }

    val accent by animateColorAsState(
        targetValue = colorFor(ui.state),
        animationSpec = tween(420),
        label = "loraAccent",
    )

    val toggleListening = {
        when {
            ui.micOn -> viewModel.toggleMic(false)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> viewModel.toggleMic(true)
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // One immersive surface: settings only, with a voice-reactive orb at the
    // centre. Long-press the orb to cancel an active command.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(18.dp),
        ) {
            CircleIconButton(
                icon = Icons.Filled.Settings,
                description = "إعدادات لورا",
                size = 46.dp,
                onClick = onOpenSettings,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .aspectRatio(1f)
                    .combinedClickable(
                        onClick = toggleListening,
                        onLongClick = viewModel::cancel,
                        onClickLabel = if (ui.micOn) "إيقاف استماع لورا" else "بدء استماع لورا",
                        onLongClickLabel = "إلغاء المهمة الحالية",
                    ),
                contentAlignment = Alignment.Center,
            ) {
                JarvisOrb(
                    state = ui.state,
                    amplitude = ui.amplitude,
                    reducedMotion = settings.reducedMotion,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(22.dp))
            TextButton(onClick = {
                if (sharing && ScreenShareService.isSharing) stopScreenShare()
                else requestScreenShare()
            }) {
                Icon(Icons.Filled.Monitor, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (sharing && ScreenShareService.isSharing) "إيقاف مشاركة الشاشة"
                    else if (shareRequested) "اختَر الشاشة أو التطبيق..."
                    else "مشاركة الشاشة: كاملة أو تطبيق واحد",
                    color = Color.White,
                )
            }
            TextButton(
                onClick = viewModel::analyzeSharedScreen,
                enabled = sharing && ScreenShareService.isSharing,
            ) {
                Text("حللي الشاشة الآن", color = Color.White)
            }
            Text(
                "بموافقتك، ترسل لورا لقطة واحدة إلى OpenRouter عند الضغط على التحليل فقط.",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = ui.activity ?: stringResource(ui.state.statusRes),
                color = accent,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 18.dp),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    ui.lastError != null -> ui.lastError.orEmpty()
                    !ui.hasApiKey -> "أضيف مفتاح Groq من الإعدادات"
                    !ui.secureStorageAvailable -> "التخزين المشفر غير متاح"
                    ui.micOn -> "اضغط الدائرة لإيقاف الاستماع"
                    else -> "المس الدائرة للتحدث • اضغط مطولاً للإلغاء"
                },
                color = if (ui.lastError != null) ErrorRed else TextDim,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            AnimatedVisibility(
                visible = ui.transcript.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = ui.transcript,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    modifier = Modifier.padding(top = 24.dp, start = 22.dp, end = 22.dp),
                )
            }
        }
    }

    // Explicit confirmation remains mandatory for consequential actions.
    ui.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::rejectPending,
            title = { Text("تأكيد الإجراء") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.description)
                    Text("الأداة: ${pending.toolName}", color = TextDim)
                    if (pending.args.isNotEmpty()) {
                        Text("الوسائط: ${pending.args}", color = TextDim)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmPending) { Text("تنفيذ") } },
            dismissButton = { TextButton(onClick = viewModel::rejectPending) { Text("إلغاء") } },
            containerColor = BgPanel,
        )
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CircleIconButton(Icons.Filled.Settings, "الإعدادات", size = 42.dp, onClick = onOpenSettings)
        Spacer(Modifier.weight(1f))
        Text(
            text = "L O R A",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp,
            ),
            color = Color.White,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(42.dp)) // keeps the title optically centred
    }
}

// -------------------------------------------------------------- status strip

@Composable
private fun StatusStrip(
    state: AssistantState,
    hasApiKey: Boolean,
    model: String,
    accent: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            label = if (state == AssistantState.LISTENING) "ينصت" else "جاهز",
            color = if (state == AssistantState.DISCONNECTED) TextDim else accent,
        )
        Divider()
        StatusDot(
            label = if (hasApiKey) "متصل" else "بلا مفتاح",
            color = if (hasApiKey) SuccessGreen else WarnAmber,
        )
        Divider()
        StatusDot(
            label = model.substringAfterLast('/').substringBefore(':').take(14),
            color = SuccessGreen,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .width(1.dp)
            .height(12.dp)
            .background(BorderSubtle),
    )
}

@Composable
private fun StatusDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, color = TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- notices

@Composable
private fun NoticeCard(
    title: String,
    detail: String? = null,
    tone: Color,
    onClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tone.copy(alpha = 0.07f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.5.dp, tone, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = tone, style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = tone, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, color = TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// --------------------------------------------------------------- response

@Composable
private fun ResponseCard(
    text: String,
    state: AssistantState,
    amplitude: Float,
    reducedMotion: Boolean,
    accent: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(BgPanel, BgPanel.copy(alpha = 0.55f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        VoiceVisualizer(
            state = state,
            amplitude = amplitude,
            reducedMotion = reducedMotion,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
        )
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun ControlRow(
    micOn: Boolean,
    busy: Boolean,
    accent: Color,
    canSend: Boolean,
    onKeyboard: () -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onCancel: () -> Unit,
) {
    val micScale by animateFloatAsState(
        targetValue = if (micOn) 1.06f else 1f,
        animationSpec = tween(260),
        label = "micScale",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = Icons.Filled.Keyboard,
            description = "لوحة المفاتيح",
            size = 52.dp,
            onClick = if (canSend) onSend else onKeyboard,
            tint = if (canSend) accent else Color.White,
        )

        Box(
            modifier = Modifier
                .scale(micScale)
                .size(78.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = if (micOn) 0.95f else 0.75f),
                            accent.copy(alpha = 0.25f),
                        ),
                    ),
                )
                .border(1.5.dp, accent.copy(alpha = 0.8f), CircleShape)
                .clickable(onClick = onMic),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = stringResource(R.string.action_talk),
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }

        CircleIconButton(
            icon = Icons.Filled.Close,
            description = stringResource(R.string.action_cancel),
            size = 52.dp,
            onClick = onCancel,
            tint = if (busy) ErrorRed else TextDim,
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(BgRaised.copy(alpha = 0.7f))
            .border(1.dp, BorderSubtle, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}
