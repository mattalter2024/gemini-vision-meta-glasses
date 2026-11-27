package com.geminivision.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.geminivision.app.camera.PhoneCameraPreview
import com.geminivision.app.data.VideoSourceMode
import com.geminivision.app.gemini.LiveConnectionState
import com.geminivision.app.glasses.GlassesUiStatus
import com.geminivision.app.ui.theme.AlertCoral
import com.geminivision.app.ui.theme.GeminiBlue
import com.geminivision.app.ui.theme.GeminiCyan
import com.geminivision.app.ui.theme.GeminiViolet
import com.geminivision.app.ui.theme.GeminiVisionTheme
import com.geminivision.app.ui.theme.LiveEmerald
import com.geminivision.app.ui.theme.SurfaceObsidian
import com.meta.wearable.dat.core.types.RegistrationState

private val QUICK_PROMPTS = listOf(
    "What am I looking at?",
    "Read this text aloud",
    "Translate to English",
    "Describe any details"
)

@Composable
fun GeminiVisionScreen(
    state: MainUiState,
    hasPhoneCameraPermission: Boolean,
    onSelectMode: (AppMode) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissBanner: () -> Unit,
    onConnectMetaGlasses: () -> Unit,
    onRequestGlassesCameraPermission: () -> Unit,
    onStartGlassesStream: () -> Unit,
    onStopGlassesStream: () -> Unit,
    onOpenFirmwareUpdate: () -> Unit = {},
    onOpenGlassesAppUpdate: () -> Unit = {},
    onSwitchToPhoneFallback: () -> Unit,
    onRequestPhoneCameraPermission: () -> Unit,
    onTogglePhoneCameraFacing: () -> Unit,
    onPhoneFrame: (ByteArray, Bitmap) -> Unit,
    // QA callbacks
    onQaDraftChange: (String) -> Unit,
    onSubmitQa: (String?) -> Unit,
    onToggleQaVoice: () -> Unit,
    onClearQa: () -> Unit,
    onStopTts: () -> Unit,
    // Live callbacks
    onToggleLiveSession: () -> Unit,
    onToggleLiveMute: () -> Unit,
    onToggleLiveVision: () -> Unit,
    onLiveNudgeChange: (String) -> Unit,
    onSendLiveNudge: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
    ) {
        // 1. Background Viewfinder (Meta Glasses POV feed OR optional Phone Camera fallback)
        ViewfinderLayer(
            state = state,
            hasPhoneCameraPermission = hasPhoneCameraPermission,
            onConnectMetaGlasses = onConnectMetaGlasses,
            onRequestGlassesCameraPermission = onRequestGlassesCameraPermission,
            onStartGlassesStream = onStartGlassesStream,
            onOpenFirmwareUpdate = onOpenFirmwareUpdate,
            onOpenGlassesAppUpdate = onOpenGlassesAppUpdate,
            onSwitchToPhoneFallback = onSwitchToPhoneFallback,
            onRequestPhoneCameraPermission = onRequestPhoneCameraPermission,
            onPhoneFrame = onPhoneFrame
        )

        // Subtle vertical gradient scrims so floating controls stay legible over bright scenes
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
        )

        // 2. Foreground Companion UI
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar: Mode Switcher + Status Pills + Settings
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TopControlBar(
                    selectedMode = state.mode,
                    apiKeyMissing = state.settings.apiKey.isBlank(),
                    isPhoneCameraMode = state.settings.videoSource == VideoSourceMode.PHONE_CAMERA,
                    onSelectMode = onSelectMode,
                    onFlipPhoneCamera = onTogglePhoneCameraFacing,
                    onOpenSettings = onOpenSettings
                )

                StatusPillsRow(
                    state = state,
                    onStartGlassesStream = onStartGlassesStream,
                    onStopGlassesStream = onStopGlassesStream
                )

                AnimatedVisibility(visible = state.bannerMessage != null) {
                    state.bannerMessage?.let { msg ->
                        ErrorBanner(message = msg, onDismiss = onDismissBanner)
                    }
                }
            }

            // Bottom area: Mode-specific controls
            when (state.mode) {
                AppMode.CAMERA_QA -> CameraQaOverlay(
                    state = state,
                    onDraftChange = onQaDraftChange,
                    onSubmit = onSubmitQa,
                    onToggleVoice = onToggleQaVoice,
                    onClear = onClearQa,
                    onStopTts = onStopTts
                )

                AppMode.GEMINI_LIVE -> GeminiLiveOverlay(
                    state = state,
                    onToggleSession = onToggleLiveSession,
                    onToggleMute = onToggleLiveMute,
                    onToggleVision = onToggleLiveVision,
                    onNudgeChange = onLiveNudgeChange,
                    onSendNudge = onSendLiveNudge
                )
            }
        }
    }
}

@Composable
private fun ViewfinderLayer(
    state: MainUiState,
    hasPhoneCameraPermission: Boolean,
    onConnectMetaGlasses: () -> Unit,
    onRequestGlassesCameraPermission: () -> Unit,
    onStartGlassesStream: () -> Unit,
    onOpenFirmwareUpdate: () -> Unit,
    onOpenGlassesAppUpdate: () -> Unit,
    onSwitchToPhoneFallback: () -> Unit,
    onRequestPhoneCameraPermission: () -> Unit,
    onPhoneFrame: (ByteArray, Bitmap) -> Unit
) {
    when (state.settings.videoSource) {
        VideoSourceMode.META_GLASSES -> {
            val bitmap = state.glassesPreviewBitmap
            if (bitmap != null && state.glassesStatus.isStreaming) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Meta Glasses POV Camera Feed",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MetaGlassesSetupCanvas(
                    status = state.glassesStatus,
                    onConnectMetaGlasses = onConnectMetaGlasses,
                    onRequestGlassesCameraPermission = onRequestGlassesCameraPermission,
                    onStartGlassesStream = onStartGlassesStream,
                    onOpenFirmwareUpdate = onOpenFirmwareUpdate,
                    onOpenGlassesAppUpdate = onOpenGlassesAppUpdate,
                    onSwitchToPhoneFallback = onSwitchToPhoneFallback
                )
            }
        }

        VideoSourceMode.PHONE_CAMERA -> {
            if (hasPhoneCameraPermission) {
                PhoneCameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    useFrontCamera = state.useFrontPhoneCamera,
                    onFrameAvailable = onPhoneFrame
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(28.dp)
                    ) {
                        Text(
                            text = "Phone Camera Fallback",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Grant Android camera permission to test with your phone camera.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onRequestPhoneCameraPermission) {
                            Text("Grant Phone Camera")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaGlassesSetupCanvas(
    status: GlassesUiStatus,
    onConnectMetaGlasses: () -> Unit,
    onRequestGlassesCameraPermission: () -> Unit,
    onStartGlassesStream: () -> Unit,
    onOpenFirmwareUpdate: () -> Unit,
    onOpenGlassesAppUpdate: () -> Unit,
    onSwitchToPhoneFallback: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF17223B), SurfaceObsidian)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xCC121826),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .border(1.dp, Color(0xFF28344E), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothAudio,
                    contentDescription = null,
                    tint = if (status.isEligibleDeviceReady) LiveEmerald else GeminiCyan,
                    modifier = Modifier.size(38.dp)
                )
                Text(
                    text = "Meta Glasses Companion",
                    style = MaterialTheme.typography.titleLarge
                )

                // Live DAT diagnostic pill so connection issues are immediately transparent
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF182235),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = status.diagnosticsLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF93C5FD),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Text(
                    text = when {
                        !status.isRegistered ->
                            "Link your Ray-Ban Meta or Oakley Meta glasses via the Meta AI app (with Developer Mode enabled) to stream POV camera frames and hear Gemini in your glasses."
                        !status.cameraPermissionGranted ->
                            "Glasses are linked! Grant camera access in Meta AI so Gemini can see through your glasses."
                        status.isWaitingForEligibleDevice ->
                            "Waiting for an eligible connected Meta glasses link. Make sure your glasses are unfolded, awake, and connected in the Meta AI app."
                        else ->
                            "Your Meta glasses are ready. Start the POV camera stream to let Gemini see what you see."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                status.lastError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelMedium,
                        color = AlertCoral
                    )
                }

                if (status.requiresFirmwareUpdate) {
                    Button(
                        onClick = onOpenFirmwareUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update Glasses Firmware in Meta AI")
                    }
                } else if (status.requiresGlassesAppUpdate) {
                    Button(
                        onClick = onOpenGlassesAppUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update Glasses App in Meta AI")
                    }
                } else {
                    when {
                        !status.isRegistered -> {
                            Button(
                                onClick = onConnectMetaGlasses,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Connect Meta Glasses")
                            }
                        }
                        !status.cameraPermissionGranted -> {
                            Button(
                                onClick = onRequestGlassesCameraPermission,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Allow Glasses Camera")
                            }
                        }
                        else -> {
                            Button(
                                onClick = onStartGlassesStream,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (status.isWaitingForEligibleDevice) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Retry Glasses Camera")
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Start Glasses Camera")
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onSwitchToPhoneFallback,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test with Phone Camera Instead")
                }
            }
        }
    }
}

@Composable
private fun TopControlBar(
    selectedMode: AppMode,
    apiKeyMissing: Boolean,
    isPhoneCameraMode: Boolean,
    onSelectMode: (AppMode) -> Unit,
    onFlipPhoneCamera: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Segmented Mode Pill
        Surface(
            shape = CircleShape,
            color = Color(0xCC111827),
            modifier = Modifier.border(1.dp, Color(0xFF2D3A56), CircleShape)
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                AppMode.entries.forEach { mode ->
                    val selected = mode == selectedMode
                    val pillColor = when {
                        selected && mode == AppMode.GEMINI_LIVE -> LiveEmerald.copy(alpha = 0.24f)
                        selected -> GeminiBlue.copy(alpha = 0.28f)
                        else -> Color.Transparent
                    }
                    val textColor = when {
                        selected && mode == AppMode.GEMINI_LIVE -> LiveEmerald
                        selected -> Color.White
                        else -> Color(0xFF94A3B8)
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(pillColor)
                            .clickable { onSelectMode(mode) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isPhoneCameraMode) {
                Surface(shape = CircleShape, color = Color(0xCC111827)) {
                    IconButton(onClick = onFlipPhoneCamera) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch phone camera",
                            tint = Color.White
                        )
                    }
                }
            }
            Box {
                Surface(shape = CircleShape, color = Color(0xCC111827)) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                }
                if (apiKeyMissing) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .align(Alignment.TopEnd)
                            .padding(1.dp)
                            .clip(CircleShape)
                            .background(AlertCoral)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPillsRow(
    state: MainUiState,
    onStartGlassesStream: () -> Unit,
    onStopGlassesStream: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isMeta = state.settings.videoSource == VideoSourceMode.META_GLASSES
        val dotColor = when {
            isMeta && state.glassesStatus.isStreaming -> LiveEmerald
            isMeta && state.glassesStatus.isRegistered -> GeminiCyan
            !isMeta -> GeminiViolet
            else -> Color(0xFF64748B)
        }
        val sourceText = if (isMeta) state.glassesStatus.statusBadgeText else "Phone Camera (Fallback)"

        Surface(
            shape = CircleShape,
            color = Color(0xB80F172A),
            modifier = Modifier
                .clickable(enabled = isMeta && state.glassesStatus.isRegistered && state.glassesStatus.cameraPermissionGranted) {
                    if (state.glassesStatus.isStreaming) onStopGlassesStream() else onStartGlassesStream()
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (state.mode == AppMode.GEMINI_LIVE || state.isGlassesAudioActive) {
            Surface(shape = CircleShape, color = Color(0xB80F172A)) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BluetoothAudio,
                        contentDescription = null,
                        tint = if (state.isGlassesAudioActive) LiveEmerald else Color(0xFF94A3B8),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = state.audioRouteLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xE63B141C),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AlertCoral.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFECACA),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color(0xFFFECACA))
            }
        }
    }
}

@Composable
private fun CameraQaOverlay(
    state: MainUiState,
    onDraftChange: (String) -> Unit,
    onSubmit: (String?) -> Unit,
    onToggleVoice: () -> Unit,
    onClear: () -> Unit,
    onStopTts: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.qaMessages.size) {
        if (state.qaMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.qaMessages.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Conversation feed
        if (state.qaMessages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isTtsSpeaking) {
                    AssistChip(
                        onClick = onStopTts,
                        label = { Text("Stop Speaking") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0xCC1E293B),
                            labelColor = GeminiCyan,
                            leadingIconContentColor = GeminiCyan
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                }
                AssistChip(
                    onClick = onClear,
                    label = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color(0xCC1E293B),
                        labelColor = Color(0xFFCBD5E1),
                        leadingIconContentColor = Color(0xFFCBD5E1)
                    )
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(state.qaMessages, key = { it.id }) { msg ->
                    QaBubble(msg)
                }
            }
        }

        // Quick action chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QUICK_PROMPTS) { prompt ->
                AssistChip(
                    onClick = { onSubmit(prompt) },
                    label = { Text(prompt) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color(0xCC141B2D),
                        labelColor = Color(0xFFE2E8F0)
                    )
                )
            }
        }

        // Prompt bar with prominent Voice Mic + Text + Send
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xE6111827),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2A3652), RoundedCornerShape(24.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleVoice,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.isVoiceListening) AlertCoral else GeminiBlue.copy(alpha = 0.22f)
                        )
                ) {
                    Icon(
                        imageVector = if (state.isVoiceListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "Ask with voice",
                        tint = if (state.isVoiceListening) Color.White else GeminiBlue
                    )
                }

                Spacer(Modifier.width(6.dp))

                OutlinedTextField(
                    value = state.qaDraftPrompt,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (state.isVoiceListening) "Listening..." else "Ask Gemini about your view...",
                            maxLines = 1
                        )
                    },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                if (state.isQaLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = GeminiCyan
                    )
                } else {
                    IconButton(
                        onClick = { onSubmit(null) },
                        enabled = state.qaDraftPrompt.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send query",
                            tint = if (state.qaDraftPrompt.isNotBlank()) GeminiBlue else Color(0xFF475569)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QaBubble(msg: QaMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = when {
                msg.isError -> Color(0xE63B141C)
                msg.isUser -> Color(0xE61D3557)
                else -> Color(0xE6141926)
            },
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .border(
                    1.dp,
                    when {
                        msg.isError -> AlertCoral.copy(alpha = 0.5f)
                        msg.isUser -> GeminiBlue.copy(alpha = 0.4f)
                        else -> Color(0xFF28344E)
                    },
                    RoundedCornerShape(18.dp)
                )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                msg.thumbnail?.let { thumb ->
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = "Captured glasses frame",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (msg.isError) Color(0xFFFECACA) else Color(0xFFF1F5F9)
                )
            }
        }
    }
}

@Composable
private fun GeminiLiveOverlay(
    state: MainUiState,
    onToggleSession: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleVision: () -> Unit,
    onNudgeChange: (String) -> Unit,
    onSendNudge: () -> Unit
) {
    val isConnected = state.liveConnectionState == LiveConnectionState.CONNECTED
    val isConnecting = state.liveConnectionState == LiveConnectionState.CONNECTING
    val pulse by animateFloatAsState(
        targetValue = if (state.isGeminiSpeakingLive) 1f else state.liveMicLevel.coerceIn(0.15f, 1f),
        label = "liveAudioPulse"
    )
    val listState = rememberLazyListState()
    LaunchedEffect(state.liveTranscript.size) {
        if (state.liveTranscript.isNotEmpty()) {
            listState.animateScrollToItem(state.liveTranscript.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Live captions from glasses mic + Gemini speech
        if (state.liveTranscript.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xCC0F172A),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 190.dp)
                    .border(1.dp, Color(0xFF26334D), RoundedCornerShape(18.dp))
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.liveTranscript, key = { it.id }) { line ->
                        Text(
                            text = "${if (line.isUser) "You" else "Gemini"}: ${line.text}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (line.isUser) Color(0xFF93C5FD) else Color(0xFFF8FAFC)
                        )
                    }
                }
            }
        }

        // Optional text nudge while live session is active
        if (isConnected) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xD9111827),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.liveNudgeDraft,
                        onValueChange = onNudgeChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Send a text nudge mid-call...") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                    IconButton(
                        onClick = onSendNudge,
                        enabled = state.liveNudgeDraft.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send nudge",
                            tint = if (state.liveNudgeDraft.isNotBlank()) LiveEmerald else Color(0xFF475569)
                        )
                    }
                }
            }
        }

        // Main Hands-Free Call Controls
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xE6101726),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (isConnected) LiveEmerald.copy(alpha = 0.55f) else Color(0xFF28344E),
                    shape = RoundedCornerShape(28.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = when {
                            state.isGeminiSpeakingLive -> GeminiCyan
                            isConnected -> LiveEmerald.copy(alpha = 0.5f + 0.5f * pulse)
                            else -> Color(0xFF64748B)
                        }
                    )
                    Text(
                        text = when {
                            isConnecting -> "Connecting to Gemini Live..."
                            state.isGeminiSpeakingLive -> "Gemini is speaking in your glasses"
                            isConnected && state.isLiveMicMuted -> "Live connected • Mic muted"
                            isConnected -> "Listening hands-free • 1 FPS POV active"
                            else -> "Tap Start for hands-free voice + glasses vision"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE2E8F0)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute Mic
                    IconButton(
                        onClick = onToggleMute,
                        enabled = isConnected,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.isLiveMicMuted) AlertCoral.copy(alpha = 0.25f) else Color(0xFF1E293B)
                            )
                    ) {
                        Icon(
                            imageVector = if (state.isLiveMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Toggle mute",
                            tint = if (state.isLiveMicMuted) AlertCoral else Color.White
                        )
                    }

                    // Primary Start / End Live Call Button
                    Button(
                        onClick = onToggleSession,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected || isConnecting) AlertCoral else LiveEmerald,
                            contentColor = Color(0xFF04131A)
                        )
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting", color = Color.White, fontWeight = FontWeight.SemiBold)
                        } else if (isConnected) {
                            Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("End Live", color = Color.White, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.Call, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Live", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Pause/Resume Vision Stream
                    IconButton(
                        onClick = onToggleVision,
                        enabled = isConnected,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.isLiveVisionPaused) AlertCoral.copy(alpha = 0.25f) else Color(0xFF1E293B)
                            )
                    ) {
                        Icon(
                            imageVector = if (state.isLiveVisionPaused) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            contentDescription = "Pause camera vision",
                            tint = if (state.isLiveVisionPaused) AlertCoral else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun CameraQaModePreview() {
    GeminiVisionTheme {
        GeminiVisionScreen(
            state = MainUiState(
                mode = AppMode.CAMERA_QA,
                glassesStatus = GlassesUiStatus(
                    registrationState = RegistrationState.REGISTERED,
                    deviceName = "Ray-Ban Meta",
                    batteryLevel = 84,
                    cameraPermissionGranted = true
                ),
                qaMessages = listOf(
                    QaMessage(isUser = true, text = "What coffee beans are these?"),
                    QaMessage(
                        isUser = false,
                        text = "That bag is a medium-roast Ethiopian single origin whole bean coffee."
                    )
                )
            ),
            hasPhoneCameraPermission = true,
            onSelectMode = {},
            onOpenSettings = {},
            onDismissBanner = {},
            onConnectMetaGlasses = {},
            onRequestGlassesCameraPermission = {},
            onStartGlassesStream = {},
            onStopGlassesStream = {},
            onSwitchToPhoneFallback = {},
            onRequestPhoneCameraPermission = {},
            onTogglePhoneCameraFacing = {},
            onPhoneFrame = { _, _ -> },
            onQaDraftChange = {},
            onSubmitQa = {},
            onToggleQaVoice = {},
            onClearQa = {},
            onStopTts = {},
            onToggleLiveSession = {},
            onToggleLiveMute = {},
            onToggleLiveVision = {},
            onLiveNudgeChange = {},
            onSendLiveNudge = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun GeminiLiveModePreview() {
    GeminiVisionTheme {
        GeminiVisionScreen(
            state = MainUiState(
                mode = AppMode.GEMINI_LIVE,
                liveConnectionState = LiveConnectionState.CONNECTED,
                isGlassesAudioActive = true,
                audioRouteLabel = "Ray-Ban Meta",
                isGeminiSpeakingLive = true,
                liveTranscript = listOf(
                    LiveTranscriptLine(isUser = true, text = "Can you read the parking sign ahead?"),
                    LiveTranscriptLine(isUser = false, text = "It says 2 hour parking from 8 AM to 6 PM, Monday through Friday.")
                )
            ),
            hasPhoneCameraPermission = true,
            onSelectMode = {},
            onOpenSettings = {},
            onDismissBanner = {},
            onConnectMetaGlasses = {},
            onRequestGlassesCameraPermission = {},
            onStartGlassesStream = {},
            onStopGlassesStream = {},
            onSwitchToPhoneFallback = {},
            onRequestPhoneCameraPermission = {},
            onTogglePhoneCameraFacing = {},
            onPhoneFrame = { _, _ -> },
            onQaDraftChange = {},
            onSubmitQa = {},
            onToggleQaVoice = {},
            onClearQa = {},
            onStopTts = {},
            onToggleLiveSession = {},
            onToggleLiveMute = {},
            onToggleLiveVision = {},
            onLiveNudgeChange = {},
            onSendLiveNudge = {}
        )
    }
}
