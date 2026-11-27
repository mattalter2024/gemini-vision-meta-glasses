package com.geminivision.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.geminivision.app.data.VideoSourceMode
import com.geminivision.app.ui.GeminiVisionScreen
import com.geminivision.app.ui.GeminiVisionViewModel
import com.geminivision.app.ui.SettingsBottomSheet
import com.geminivision.app.ui.theme.GeminiVisionTheme
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

class MainActivity : ComponentActivity() {
    private val viewModel: GeminiVisionViewModel by viewModels()
    private val hasPhoneCameraPermission = mutableStateOf(false)

    private val metaCameraPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        result.fold(
            onSuccess = { status ->
                viewModel.glassesManager.onPermissionResult(
                    granted = status == PermissionStatus.Granted
                )
            },
            onFailure = { error, throwable ->
                viewModel.glassesManager.onPermissionResult(
                    granted = false,
                    errorMessage = throwable?.message ?: "Meta glasses permission error: $error"
                )
            }
        )
    }

    private val androidPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePhoneCameraPermissionFlag()
        viewModel.onAudioPermissionUpdated(hasAudioPermission())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleMetaIntent(intent)
        updatePhoneCameraPermissionFlag()
        requestBaseAndroidPermissions()

        setContent {
            GeminiVisionTheme {
                val state by viewModel.uiState.collectAsState()

                GeminiVisionScreen(
                    state = state,
                    hasPhoneCameraPermission = hasPhoneCameraPermission.value,
                    onSelectMode = viewModel::selectMode,
                    onOpenSettings = { viewModel.openSettings(true) },
                    onDismissBanner = viewModel::dismissBanner,
                    onConnectMetaGlasses = { viewModel.connectMetaGlasses(this) },
                    onRequestGlassesCameraPermission = {
                        metaCameraPermissionLauncher.launch(Permission.CAMERA)
                    },
                    onStartGlassesStream = viewModel::startGlassesStream,
                    onStopGlassesStream = viewModel::stopGlassesStream,
                    onOpenFirmwareUpdate = { viewModel.openGlassesFirmwareUpdate(this) },
                    onOpenGlassesAppUpdate = { viewModel.openGlassesAppUpdate(this) },
                    onSwitchToPhoneFallback = {
                        viewModel.saveSettings(
                            state.settings.copy(videoSource = VideoSourceMode.PHONE_CAMERA)
                        )
                        if (!hasPhoneCameraPermission.value) {
                            androidPermissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        }
                    },
                    onRequestPhoneCameraPermission = {
                        androidPermissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    },
                    onTogglePhoneCameraFacing = viewModel::togglePhoneCameraFacing,
                    onPhoneFrame = viewModel::onPhoneCameraFrame,
                    onQaDraftChange = viewModel::updateQaDraft,
                    onSubmitQa = viewModel::submitQaQuery,
                    onToggleQaVoice = {
                        ensureAudioPermissionThen { viewModel.toggleQaVoiceInput() }
                    },
                    onClearQa = viewModel::clearQaConversation,
                    onStopTts = viewModel::stopTts,
                    onToggleLiveSession = {
                        ensureAudioPermissionThen { viewModel.toggleLiveSession() }
                    },
                    onToggleLiveMute = viewModel::toggleLiveMute,
                    onToggleLiveVision = viewModel::toggleLiveVisionPaused,
                    onLiveNudgeChange = viewModel::updateLiveNudgeDraft,
                    onSendLiveNudge = viewModel::sendLiveTextNudge
                )

                if (state.showSettings) {
                    SettingsBottomSheet(
                        settings = state.settings,
                        glassesStatusSummary = state.glassesStatus.statusBadgeText,
                        isRegisteredWithMeta = state.glassesStatus.isRegistered,
                        onConnectMetaGlasses = { viewModel.connectMetaGlasses(this) },
                        onDisconnectMetaGlasses = { viewModel.disconnectMetaGlasses(this) },
                        onSave = viewModel::saveSettings,
                        onDismiss = { viewModel.openSettings(false) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePhoneCameraPermissionFlag()
        viewModel.onAppResumed(hasAudioPermission())
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppPaused()
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMetaIntent(intent)
    }

    private fun handleMetaIntent(intent: Intent?) {
        if (intent == null) return
        runCatching {
            Wearables.handleIntent(intent) { }
        }
    }

    private fun updatePhoneCameraPermissionFlag() {
        hasPhoneCameraPermission.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBaseAndroidPermissions() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isNotEmpty()) {
            androidPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun ensureAudioPermissionThen(action: () -> Unit) {
        val hasAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) {
            action()
        } else {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            androidPermissionsLauncher.launch(perms.toTypedArray())
        }
    }
}
