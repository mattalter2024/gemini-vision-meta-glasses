package com.geminivision.app.glasses

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.geminivision.app.camera.FrameCodec
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class GlassesUiStatus(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val sessionState: DeviceSessionState = DeviceSessionState.IDLE,
    val streamState: StreamState = StreamState.STOPPED,
    val deviceName: String? = null,
    val batteryLevel: Int? = null,
    val linkState: LinkState? = null,
    val cameraPermissionGranted: Boolean = false,
    val lastError: String? = null
) {
    val isRegistered: Boolean
        get() = registrationState == RegistrationState.REGISTERED

    val isStreaming: Boolean
        get() = streamState == StreamState.STREAMING || streamState == StreamState.STARTED

    val statusBadgeText: String
        get() = when {
            isStreaming -> buildString {
                append(deviceName ?: "Meta Glasses")
                batteryLevel?.takeIf { it in 0..100 }?.let { append(" • $it%") }
            }
            sessionState == DeviceSessionState.STARTING || streamState == StreamState.STARTING ->
                "Connecting glasses camera..."
            isRegistered && !deviceName.isNullOrBlank() -> buildString {
                append(deviceName)
                batteryLevel?.takeIf { it in 0..100 }?.let { append(" • $it%") }
            }
            isRegistered -> "Meta Glasses linked"
            registrationState == RegistrationState.REGISTERING -> "Registering with Meta AI..."
            registrationState == RegistrationState.AVAILABLE -> "Glasses ready to connect"
            else -> "Open Meta AI app to link glasses"
        }
}

/**
 * Manages the Meta Wearables Device Access Toolkit (DAT) lifecycle for
 * display-less Meta smart glasses (Ray-Ban Meta / Oakley Meta):
 * - Registration with Meta AI companion app
 * - Glasses camera permission checks
 * - DeviceSession & Camera Stream lifecycle (POV video frames -> JPEG + Bitmap)
 * - Still photo capture for high-detail Vision QA
 */
class MetaGlassesManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(GlassesUiStatus())
    val status: StateFlow<GlassesUiStatus> = _status.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val latestJpegRef = AtomicReference<ByteArray?>(null)
    private val latestBitmapRef = AtomicReference<Bitmap?>(null)

    @Volatile
    private var deviceSession: DeviceSession? = null
    @Volatile
    private var camera: Camera? = null

    private var sessionWatcherJob: Job? = null
    private var videoCollectJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var deviceMetaJob: Job? = null

    @Volatile
    private var lastFrameProcessMs: Long = 0L

    init {
        observeWearables()
    }

    private fun observeWearables() {
        scope.launch {
            runCatching {
                Wearables.registrationState.collectLatest { regState ->
                    _status.update { it.copy(registrationState = regState) }
                    if (regState == RegistrationState.REGISTERED) {
                        refreshCameraPermission()
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to observe Wearables.registrationState", err)
            }
        }

        scope.launch {
            runCatching {
                Wearables.devices.collectLatest { deviceIds ->
                    deviceMetaJob?.cancel()
                    val firstId = deviceIds.firstOrNull()
                    if (firstId == null) {
                        _status.update { it.copy(deviceName = null, batteryLevel = null, linkState = null) }
                    } else {
                        val metaFlow = Wearables.devicesMetadata[firstId]
                        if (metaFlow != null) {
                            deviceMetaJob = scope.launch {
                                metaFlow.collectLatest { device: Device ->
                                    _status.update {
                                        it.copy(
                                            deviceName = device.name.ifBlank { "Meta Glasses" },
                                            batteryLevel = device.batteryLevel,
                                            linkState = device.linkState
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to observe Wearables.devices", err)
            }
        }

        scope.launch {
            runCatching {
                Wearables.registrationErrorStream.collect { error ->
                    _status.update { it.copy(lastError = "Meta registration error: $error") }
                }
            }
        }
    }

    fun startRegistration(activity: Activity) {
        _status.update { it.copy(lastError = null) }
        runCatching {
            Wearables.startRegistration(activity)
        }.onFailure { err ->
            _status.update { it.copy(lastError = err.message ?: "Unable to launch Meta AI registration.") }
        }
    }

    fun startUnregistration(activity: Activity) {
        stopCameraStream()
        runCatching {
            Wearables.startUnregistration(activity)
        }.onFailure { err ->
            _status.update { it.copy(lastError = err.message ?: "Unable to unregister glasses.") }
        }
    }

    suspend fun refreshCameraPermission(): Boolean {
        val granted = runCatching {
            Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted
        }.getOrDefault(false)
        _status.update { it.copy(cameraPermissionGranted = granted) }
        return granted
    }

    fun onPermissionResult(granted: Boolean, errorMessage: String? = null) {
        _status.update {
            it.copy(
                cameraPermissionGranted = granted,
                lastError = errorMessage ?: if (!granted) "Glasses camera permission denied in Meta AI." else null
            )
        }
        if (granted) {
            startCameraStream()
        }
    }

    fun startCameraStream() {
        if (deviceSession != null && _status.value.isStreaming) return
        stopCameraStream()
        _status.update { it.copy(lastError = null) }

        val sessionResult = runCatching {
            Wearables.createSession(AutoDeviceSelector())
        }.getOrElse { err ->
            _status.update { it.copy(lastError = "Could not create glasses session: ${err.message}") }
            return
        }

        sessionResult.fold(
            onSuccess = { session ->
                deviceSession = session
                observeSessionAndStartCamera(session)
                session.start()
            },
            onFailure = { error, throwable ->
                _status.update {
                    it.copy(lastError = "Glasses session failed: ${throwable?.message ?: error}")
                }
            }
        )
    }

    private fun observeSessionAndStartCamera(session: DeviceSession) {
        sessionWatcherJob?.cancel()
        sessionWatcherJob = scope.launch {
            launch {
                session.errors.collect { err ->
                    _status.update { it.copy(lastError = "Glasses session error: $err") }
                }
            }
            launch {
                session.deviceInfo.collect { device ->
                    _status.update {
                        it.copy(
                            deviceName = device.name.ifBlank { it.deviceName ?: "Meta Glasses" },
                            batteryLevel = device.batteryLevel,
                            linkState = device.linkState
                        )
                    }
                }
            }
            session.state.collectLatest { state ->
                _status.update { it.copy(sessionState = state) }
                if (state == DeviceSessionState.STARTED && camera == null) {
                    attachAndStartCamera(session)
                } else if (state == DeviceSessionState.STOPPED) {
                    _status.update { it.copy(streamState = StreamState.STOPPED) }
                }
            }
        }
    }

    private fun attachAndStartCamera(session: DeviceSession) {
        val config = StreamConfiguration(
            videoQuality = VideoQuality.MEDIUM,
            frameRate = 24
        )
        session.addCamera(config).fold(
            onSuccess = { addedCamera ->
                camera = addedCamera
                val stream = addedCamera.stream

                streamStateJob?.cancel()
                streamStateJob = scope.launch {
                    stream.state.collectLatest { st ->
                        _status.update { it.copy(streamState = st) }
                    }
                }

                streamErrorJob?.cancel()
                streamErrorJob = scope.launch {
                    stream.errorStream.collect { streamErr ->
                        _status.update { it.copy(lastError = streamErr.description) }
                    }
                }

                videoCollectJob?.cancel()
                videoCollectJob = scope.launch(Dispatchers.Default) {
                    stream.videoStream.collect { frame ->
                        if (frame.isCodecConfig || frame.isCompressed) return@collect
                        // Throttle UI/JPEG decoding to ~6 FPS to stay cool & responsive on glasses + phone
                        val now = System.currentTimeMillis()
                        if (now - lastFrameProcessMs < 150L) return@collect
                        lastFrameProcessMs = now

                        val decoded = FrameCodec.i420BufferToJpegAndBitmap(
                            buffer = frame.buffer,
                            width = frame.width,
                            height = frame.height,
                            maxEdge = 768,
                            jpegQuality = 76
                        ) ?: return@collect

                        latestJpegRef.set(decoded.first)
                        latestBitmapRef.set(decoded.second)
                        _previewBitmap.value = decoded.second
                    }
                }

                stream.start().onFailure { err, throwable ->
                    _status.update {
                        it.copy(lastError = "Camera stream start failed: ${throwable?.message ?: err.description}")
                    }
                }
            },
            onFailure = { error, throwable ->
                _status.update {
                    it.copy(lastError = "Unable to attach glasses camera: ${throwable?.message ?: error}")
                }
            }
        )
    }

    fun getLatestJpegFrame(): ByteArray? = latestJpegRef.get()

    fun getLatestBitmap(): Bitmap? = latestBitmapRef.get()

    /**
     * Captures a sharp still photo from the Meta glasses if a stream is active,
     * falling back seamlessly to the latest decoded video frame.
     */
    suspend fun capturePhotoOrLatestFrame(): Pair<ByteArray, Bitmap?>? = withContext(Dispatchers.IO) {
        val activeStream = camera?.stream
        if (activeStream != null && _status.value.isStreaming) {
            val captured = runCatching { activeStream.capturePhoto() }.getOrNull()?.getOrNull()
            when (captured) {
                is PhotoData.Bitmap -> {
                    val (jpeg, scaled) = FrameCodec.downscaleBitmapToJpeg(captured.bitmap)
                    latestJpegRef.set(jpeg)
                    return@withContext jpeg to scaled
                }
                is PhotoData.HEIC -> {
                    val dup = captured.data.duplicate()
                    dup.rewind()
                    val heicBytes = ByteArray(dup.remaining())
                    dup.get(heicBytes)
                    val decodedBitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
                    if (decodedBitmap != null) {
                        val (jpeg, scaled) = FrameCodec.downscaleBitmapToJpeg(decodedBitmap)
                        if (scaled != decodedBitmap) decodedBitmap.recycle()
                        latestJpegRef.set(jpeg)
                        return@withContext jpeg to scaled
                    }
                }
                null -> Unit
            }
        }
        val fallbackJpeg = latestJpegRef.get() ?: return@withContext null
        fallbackJpeg to latestBitmapRef.get()
    }

    fun stopCameraStream() {
        videoCollectJob?.cancel()
        videoCollectJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
        sessionWatcherJob?.cancel()
        sessionWatcherJob = null

        runCatching { camera?.stream?.stop() }
        runCatching { camera?.stop() }
        runCatching { camera?.close() }
        camera = null

        runCatching { deviceSession?.stop() }
        deviceSession = null

        _status.update {
            it.copy(
                sessionState = DeviceSessionState.STOPPED,
                streamState = StreamState.STOPPED
            )
        }
    }

    fun release() {
        stopCameraStream()
        deviceMetaJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val TAG = "MetaGlassesManager"
    }
}
