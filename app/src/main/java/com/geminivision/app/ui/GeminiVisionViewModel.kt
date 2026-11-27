package com.geminivision.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geminivision.app.audio.LiveAudioEngine
import com.geminivision.app.audio.VoiceAssistHelper
import com.geminivision.app.data.AppSettings
import com.geminivision.app.data.SettingsRepository
import com.geminivision.app.data.VideoSourceMode
import com.geminivision.app.gemini.GeminiLiveClient
import com.geminivision.app.gemini.GeminiQaClient
import com.geminivision.app.gemini.LiveConnectionState
import com.geminivision.app.gemini.LiveServerEvent
import com.geminivision.app.glasses.GlassesUiStatus
import com.geminivision.app.glasses.MetaGlassesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

enum class AppMode(val label: String) {
    CAMERA_QA("Camera QA"),
    GEMINI_LIVE("Gemini Live")
}

data class QaMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val thumbnail: Bitmap? = null,
    val isError: Boolean = false
)

data class LiveTranscriptLine(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String
)

data class MainUiState(
    val mode: AppMode = AppMode.CAMERA_QA,
    val settings: AppSettings = AppSettings(),
    val glassesStatus: GlassesUiStatus = GlassesUiStatus(),
    val glassesPreviewBitmap: Bitmap? = null,
    val useFrontPhoneCamera: Boolean = false,
    // QA Mode
    val qaMessages: List<QaMessage> = emptyList(),
    val qaDraftPrompt: String = "",
    val isQaLoading: Boolean = false,
    val isHandsFreeVoiceArmed: Boolean = true,
    val isVoiceListening: Boolean = false,
    val isTtsSpeaking: Boolean = false,
    // Live Mode
    val liveConnectionState: LiveConnectionState = LiveConnectionState.DISCONNECTED,
    val isLiveMicMuted: Boolean = false,
    val isLiveVisionPaused: Boolean = false,
    val liveMicLevel: Float = 0f,
    val isGeminiSpeakingLive: Boolean = false,
    val audioRouteLabel: String = "Glasses / Phone Audio",
    val isGlassesAudioActive: Boolean = false,
    val liveTranscript: List<LiveTranscriptLine> = emptyList(),
    val liveNudgeDraft: String = "",
    // General
    val bannerMessage: String? = null,
    val showSettings: Boolean = false
)

class GeminiVisionViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = SettingsRepository(application)
    private val qaClient = GeminiQaClient()
    private val liveClient = GeminiLiveClient()
    private val audioEngine = LiveAudioEngine(application)
    private val voiceAssist = VoiceAssistHelper(application)
    val glassesManager = MetaGlassesManager()

    private val phoneFrameJpegRef = AtomicReference<ByteArray?>(null)
    private val phoneFrameBitmapRef = AtomicReference<Bitmap?>(null)
    private var lastQaInteractionId: String? = null
    @Volatile
    private var hasAudioPermission: Boolean = false
    @Volatile
    private var isAppForeground: Boolean = false

    private var liveVideoPumpJob: Job? = null

    private val _uiState = MutableStateFlow(
        settingsRepo.load().let { loaded ->
            MainUiState(
                settings = loaded,
                isHandsFreeVoiceArmed = loaded.autoListenOnStart
            )
        }
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            glassesManager.status.collect { gStatus ->
                _uiState.update { it.copy(glassesStatus = gStatus) }
            }
        }
        viewModelScope.launch {
            glassesManager.previewBitmap.collect { bmp ->
                _uiState.update { it.copy(glassesPreviewBitmap = bmp) }
            }
        }
        viewModelScope.launch {
            audioEngine.micLevel.collect { lvl ->
                _uiState.update { it.copy(liveMicLevel = lvl) }
            }
        }
        viewModelScope.launch {
            audioEngine.isGeminiSpeaking.collect { speaking ->
                _uiState.update { it.copy(isGeminiSpeakingLive = speaking) }
            }
        }
        viewModelScope.launch {
            audioEngine.audioRouteLabel.collect { route ->
                _uiState.update { it.copy(audioRouteLabel = route) }
            }
        }
        viewModelScope.launch {
            audioEngine.isGlassesAudioActive.collect { active ->
                _uiState.update { it.copy(isGlassesAudioActive = active) }
            }
        }
        viewModelScope.launch {
            voiceAssist.isListening.collect { listening ->
                _uiState.update { it.copy(isVoiceListening = listening) }
            }
        }
        viewModelScope.launch {
            voiceAssist.isSpeaking.collect { speaking ->
                _uiState.update { it.copy(isTtsSpeaking = speaking) }
            }
        }
    }

    fun selectMode(mode: AppMode) {
        if (_uiState.value.mode == mode) return
        if (mode == AppMode.CAMERA_QA) {
            if (_uiState.value.liveConnectionState != LiveConnectionState.DISCONNECTED) {
                stopLiveSession()
            }
            _uiState.update { it.copy(mode = mode, bannerMessage = null) }
            if (_uiState.value.isHandsFreeVoiceArmed) {
                armHandsFreeListeningIfReady()
            }
        } else {
            voiceAssist.stopListening()
            voiceAssist.stopSpeaking()
            _uiState.update { it.copy(mode = mode, bannerMessage = null) }
        }
    }

    fun openSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
        if (show) {
            voiceAssist.stopListening()
        } else if (_uiState.value.isHandsFreeVoiceArmed && _uiState.value.mode == AppMode.CAMERA_QA) {
            armHandsFreeListeningIfReady()
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun saveSettings(newSettings: AppSettings) {
        settingsRepo.save(newSettings)
        val previousSource = _uiState.value.settings.videoSource
        _uiState.update { it.copy(settings = newSettings) }
        if (previousSource != newSettings.videoSource) {
            if (newSettings.videoSource == VideoSourceMode.PHONE_CAMERA) {
                glassesManager.stopCameraStream()
            } else if (_uiState.value.glassesStatus.isRegistered &&
                _uiState.value.glassesStatus.cameraPermissionGranted
            ) {
                glassesManager.startCameraStream()
            }
        }
    }

    fun togglePhoneCameraFacing() {
        _uiState.update { it.copy(useFrontPhoneCamera = !it.useFrontPhoneCamera) }
    }

    fun onPhoneCameraFrame(jpegBytes: ByteArray, bitmap: Bitmap) {
        phoneFrameJpegRef.set(jpegBytes)
        phoneFrameBitmapRef.set(bitmap)
    }

    fun connectMetaGlasses(activity: Activity) {
        glassesManager.startRegistration(activity)
    }

    fun disconnectMetaGlasses(activity: Activity) {
        glassesManager.startUnregistration(activity)
    }

    fun openGlassesFirmwareUpdate(activity: Activity) {
        glassesManager.openFirmwareUpdate(activity)
    }

    fun openGlassesAppUpdate(activity: Activity) {
        glassesManager.openGlassesAppUpdate(activity)
    }

    fun onAppResumed(audioPermissionGranted: Boolean) {
        isAppForeground = true
        hasAudioPermission = audioPermissionGranted
        glassesManager.onAppResumed()
        if (_uiState.value.isHandsFreeVoiceArmed &&
            _uiState.value.mode == AppMode.CAMERA_QA &&
            !_uiState.value.showSettings
        ) {
            armHandsFreeListeningIfReady()
        }
    }

    fun onAppPaused() {
        isAppForeground = false
        voiceAssist.stopListening()
    }

    fun onAudioPermissionUpdated(granted: Boolean) {
        hasAudioPermission = granted
        if (granted && isAppForeground && _uiState.value.isHandsFreeVoiceArmed &&
            _uiState.value.mode == AppMode.CAMERA_QA && !_uiState.value.showSettings
        ) {
            armHandsFreeListeningIfReady()
        }
    }

    fun startGlassesStream() {
        glassesManager.startCameraStream()
    }

    fun stopGlassesStream() {
        glassesManager.stopCameraStream()
    }

    // region Camera QA Mode

    fun updateQaDraft(text: String) {
        _uiState.update { it.copy(qaDraftPrompt = text) }
    }

    fun clearQaConversation() {
        voiceAssist.stopSpeaking()
        lastQaInteractionId = null
        _uiState.update { it.copy(qaMessages = emptyList()) }
    }

    fun stopTts() {
        voiceAssist.stopSpeaking()
    }

    fun toggleQaVoiceInput() {
        val nextArmed = !_uiState.value.isHandsFreeVoiceArmed
        _uiState.update { it.copy(isHandsFreeVoiceArmed = nextArmed) }
        if (nextArmed) {
            armHandsFreeListeningIfReady()
        } else {
            voiceAssist.stopListening()
        }
    }

    private fun armHandsFreeListeningIfReady() {
        val state = _uiState.value
        if (!hasAudioPermission || !isAppForeground) return
        if (state.mode != AppMode.CAMERA_QA || state.showSettings || state.isQaLoading || state.isTtsSpeaking) return
        if (state.isVoiceListening) return

        voiceAssist.startListening(
            continuous = true,
            onPartial = { partial ->
                _uiState.update { it.copy(qaDraftPrompt = partial) }
            },
            onFinalResult = { spoken ->
                _uiState.update { it.copy(qaDraftPrompt = "") }
                submitQaQuery(spoken)
            },
            onError = { err ->
                _uiState.update { it.copy(bannerMessage = err) }
            }
        )
    }

    fun submitQaQuery(promptOverride: String? = null) {
        val state = _uiState.value
        val prompt = (promptOverride ?: state.qaDraftPrompt).trim()
        if (prompt.isEmpty() || state.isQaLoading) return

        if (state.settings.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    bannerMessage = "Add your Gemini API key in Settings to run camera queries.",
                    showSettings = true
                )
            }
            return
        }

        voiceAssist.stopSpeaking()

        viewModelScope.launch {
            val capturedFrame: Pair<ByteArray, Bitmap?>? = when (state.settings.videoSource) {
                VideoSourceMode.META_GLASSES -> glassesManager.capturePhotoOrLatestFrame()
                VideoSourceMode.PHONE_CAMERA -> phoneFrameJpegRef.get()?.let { it to phoneFrameBitmapRef.get() }
            }

            if (capturedFrame == null) {
                _uiState.update {
                    it.copy(
                        bannerMessage = if (state.settings.videoSource == VideoSourceMode.META_GLASSES) {
                            "No frame from Meta glasses yet. Start the glasses camera stream (or switch to Phone Camera in Settings)."
                        } else {
                            "Waiting for camera frame..."
                        }
                    )
                }
                return@launch
            }

            val userMsg = QaMessage(isUser = true, text = prompt, thumbnail = capturedFrame.second)
            _uiState.update {
                it.copy(
                    qaDraftPrompt = "",
                    isQaLoading = true,
                    bannerMessage = null,
                    qaMessages = it.qaMessages + userMsg
                )
            }

            val result = qaClient.ask(
                apiKey = state.settings.apiKey,
                model = state.settings.qaModel,
                prompt = prompt,
                jpegFrame = capturedFrame.first,
                previousInteractionId = lastQaInteractionId,
                systemInstruction = state.settings.systemPrompt
            )

            result.fold(
                onSuccess = { interaction ->
                    lastQaInteractionId = interaction.interactionId
                    _uiState.update {
                        it.copy(
                            isQaLoading = false,
                            qaMessages = it.qaMessages + QaMessage(isUser = false, text = interaction.text)
                        )
                    }
                    if (_uiState.value.settings.autoSpeakQa) {
                        voiceAssist.speak(interaction.text) {
                            if (_uiState.value.isHandsFreeVoiceArmed) {
                                viewModelScope.launch {
                                    delay(350L)
                                    armHandsFreeListeningIfReady()
                                }
                            }
                        }
                    } else if (_uiState.value.isHandsFreeVoiceArmed) {
                        delay(350L)
                        armHandsFreeListeningIfReady()
                    }
                },
                onFailure = { err ->
                    val message = err.message ?: "Failed to query Gemini."
                    _uiState.update {
                        it.copy(
                            isQaLoading = false,
                            qaMessages = it.qaMessages + QaMessage(
                                isUser = false,
                                text = message,
                                isError = true
                            )
                        )
                    }
                    if (_uiState.value.isHandsFreeVoiceArmed) {
                        delay(600L)
                        armHandsFreeListeningIfReady()
                    }
                }
            )
        }
    }

    // endregion

    // region Real-Time Gemini Live Mode

    fun toggleLiveSession() {
        if (_uiState.value.liveConnectionState == LiveConnectionState.DISCONNECTED) {
            startLiveSession()
        } else {
            stopLiveSession()
        }
    }

    fun startLiveSession() {
        val state = _uiState.value
        if (state.settings.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    bannerMessage = "Add your Gemini API key in Settings before starting Gemini Live.",
                    showSettings = true
                )
            }
            return
        }

        // Auto-start glasses camera stream if registered and permitted
        if (state.settings.videoSource == VideoSourceMode.META_GLASSES &&
            state.glassesStatus.isRegistered &&
            state.glassesStatus.cameraPermissionGranted &&
            !state.glassesStatus.isStreaming
        ) {
            glassesManager.startCameraStream()
        }

        _uiState.update {
            it.copy(
                bannerMessage = null,
                isLiveMicMuted = false,
                isLiveVisionPaused = false
            )
        }

        liveClient.connect(
            apiKey = state.settings.apiKey,
            model = state.settings.liveModel,
            voiceName = state.settings.liveVoice,
            systemInstruction = state.settings.systemPrompt,
            listener = object : GeminiLiveClient.Listener {
                override fun onConnectionStateChanged(state: LiveConnectionState) {
                    _uiState.update { it.copy(liveConnectionState = state) }
                    if (state == LiveConnectionState.CONNECTED) {
                        startLiveAudioAndVideoLoops()
                    } else if (state == LiveConnectionState.DISCONNECTED) {
                        stopLiveAudioAndVideoLoops()
                    }
                }

                override fun onServerEvent(event: LiveServerEvent) {
                    handleLiveServerEvent(event)
                }

                override fun onClosed(reason: String) {
                    stopLiveAudioAndVideoLoops()
                }

                override fun onError(message: String) {
                    stopLiveAudioAndVideoLoops()
                    _uiState.update { it.copy(bannerMessage = "Gemini Live: $message") }
                }
            }
        )
    }

    private fun startLiveAudioAndVideoLoops() {
        val preferGlassesBt = _uiState.value.settings.preferGlassesAudio
        val audioResult = audioEngine.start(preferGlassesBluetooth = preferGlassesBt) { pcm16k ->
            liveClient.sendAudioPcm16k(pcm16k)
        }
        audioResult.onFailure { err ->
            _uiState.update {
                it.copy(bannerMessage = "Audio error: ${err.message}. Check microphone permission.")
            }
        }

        liveVideoPumpJob?.cancel()
        liveVideoPumpJob = viewModelScope.launch {
            while (isActive && liveClient.isReady) {
                if (!_uiState.value.isLiveVisionPaused) {
                    val frameBytes = when (_uiState.value.settings.videoSource) {
                        VideoSourceMode.META_GLASSES -> glassesManager.getLatestJpegFrame()
                        VideoSourceMode.PHONE_CAMERA -> phoneFrameJpegRef.get()
                    }
                    if (frameBytes != null) {
                        liveClient.sendVideoFrameJpeg(frameBytes)
                    }
                }
                delay(1000L) // Gemini Live API recommends <= 1 FPS JPEG frames
            }
        }
    }

    private fun stopLiveAudioAndVideoLoops() {
        liveVideoPumpJob?.cancel()
        liveVideoPumpJob = null
        audioEngine.stop()
    }

    private fun handleLiveServerEvent(event: LiveServerEvent) {
        when (event) {
            is LiveServerEvent.SetupComplete -> Unit
            is LiveServerEvent.AudioChunk -> {
                audioEngine.enqueuePlaybackPcm24k(event.pcm24kBytes)
            }
            is LiveServerEvent.Interrupted -> {
                audioEngine.flushPlayback()
            }
            is LiveServerEvent.InputTranscript -> {
                appendLiveTranscript(isUser = true, delta = event.text)
            }
            is LiveServerEvent.OutputTranscript -> {
                appendLiveTranscript(isUser = false, delta = event.text)
            }
            is LiveServerEvent.ModelText -> {
                appendLiveTranscript(isUser = false, delta = event.text)
            }
            is LiveServerEvent.TurnComplete -> Unit
            is LiveServerEvent.Error -> {
                _uiState.update { it.copy(bannerMessage = event.message) }
            }
        }
    }

    private fun appendLiveTranscript(isUser: Boolean, delta: String) {
        if (delta.isBlank()) return
        _uiState.update { current ->
            val list = current.liveTranscript.toMutableList()
            val last = list.lastOrNull()
            if (last != null && last.isUser == isUser && last.text.length < 360) {
                val sep = if (last.text.endsWith(" ") || delta.startsWith(" ") || delta.firstOrNull() in listOf('.', ',', '!', '?', '\'')) {
                    ""
                } else {
                    " "
                }
                list[list.lastIndex] = last.copy(text = (last.text + sep + delta).trim())
            } else {
                list.add(LiveTranscriptLine(isUser = isUser, text = delta.trim()))
            }
            current.copy(liveTranscript = list.takeLast(24))
        }
    }

    fun toggleLiveMute() {
        val nextMuted = !_uiState.value.isLiveMicMuted
        audioEngine.setMuted(nextMuted)
        if (nextMuted) {
            liveClient.sendAudioStreamEnd()
        }
        _uiState.update { it.copy(isLiveMicMuted = nextMuted) }
    }

    fun toggleLiveVisionPaused() {
        _uiState.update { it.copy(isLiveVisionPaused = !it.isLiveVisionPaused) }
    }

    fun updateLiveNudgeDraft(text: String) {
        _uiState.update { it.copy(liveNudgeDraft = text) }
    }

    fun sendLiveTextNudge() {
        val text = _uiState.value.liveNudgeDraft.trim()
        if (text.isEmpty() || !liveClient.isReady) return
        if (liveClient.sendTextNudge(text)) {
            appendLiveTranscript(isUser = true, delta = text)
            _uiState.update { it.copy(liveNudgeDraft = "") }
        }
    }

    fun stopLiveSession() {
        stopLiveAudioAndVideoLoops()
        liveClient.disconnect()
        _uiState.update { it.copy(liveConnectionState = LiveConnectionState.DISCONNECTED) }
    }

    // endregion

    override fun onCleared() {
        super.onCleared()
        stopLiveSession()
        voiceAssist.release()
        glassesManager.release()
    }
}
