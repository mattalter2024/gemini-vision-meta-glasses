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
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
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
    val discoveredDeviceCount: Int = 0,
    val deviceName: String? = null,
    val deviceType: DeviceType? = null,
    val batteryLevel: Int? = null,
    val linkState: LinkState? = null,
    val compatibility: DeviceCompatibility? = null,
    val hingeState: HingeState? = null,
    val donState: DonState? = null,
    val cameraPermissionGranted: Boolean = false,
    val isWaitingForEligibleDevice: Boolean = false,
    val requiresFirmwareUpdate: Boolean = false,
    val requiresGlassesAppUpdate: Boolean = false,
    val lastError: String? = null
) {
    val isRegistered: Boolean
        get() = registrationState == RegistrationState.REGISTERED

    val isStreaming: Boolean
        get() = streamState == StreamState.STREAMING || streamState == StreamState.STARTED

    val isEligibleDeviceReady: Boolean
        get() = discoveredDeviceCount > 0 &&
            linkState == LinkState.CONNECTED &&
            compatibility == DeviceCompatibility.COMPATIBLE

    val diagnosticsLine: String
        get() = buildString {
            append("Reg: ${registrationState.name}")
            append(" • Devices: $discoveredDeviceCount")
            linkState?.let { append(" • Link: ${it.name}") }
            compatibility?.let { append(" • Compat: ${it.name}") }
            hingeState?.takeIf { it != HingeState.UNKNOWN }?.let { append(" • Hinges: ${it.name}") }
        }

    val statusBadgeText: String
        get() = when {
            isStreaming -> buildString {
                append(deviceName ?: "Meta Glasses")
                batteryLevel?.takeIf { it in 0..100 }?.let { append(" • $it%") }
            }
            isWaitingForEligibleDevice -> "Waiting for glasses link..."
            sessionState == DeviceSessionState.STARTING || streamState == StreamState.STARTING ->
                "Connecting glasses camera..."
            isEligibleDeviceReady -> buildString {
                append(deviceName ?: "Meta Glasses")
                batteryLevel?.takeIf { it in 0..100 }?.let { append(" • $it%") }
                append(" (Ready)")
            }
            isRegistered && discoveredDeviceCount > 0 -> buildString {
                append(deviceName ?: "Meta Glasses")
                linkState?.let { append(" • ${it.name.lowercase().replaceFirstChar(Char::uppercase)}") }
            }
            isRegistered -> "Meta Glasses linked (0 active)"
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
 * - Live eligibility monitoring (auto-waits until linkState == CONNECTED & compatibility == COMPATIBLE)
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
    private val activeEligibleDeviceIdRef = AtomicReference<DeviceIdentifier?>(null)

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
                    _status.update { it.copy(discoveredDeviceCount = deviceIds.size) }

                    if (deviceIds.isEmpty()) {
                        activeEligibleDeviceIdRef.set(null)
                        _status.update {
                            it.copy(
                                deviceName = null,
                                deviceType = null,
                                batteryLevel = null,
                                linkState = null,
                                compatibility = null,
                                hingeState = null,
                                donState = null,
                                requiresFirmwareUpdate = false
                            )
                        }
                        return@collectLatest
                    }

                    // Observe metadata for all discovered devices and prefer the first CONNECTED + COMPATIBLE one
                    deviceMetaJob = scope.launch {
                        val metadataMap = Wearables.devicesMetadata
                        for (deviceId in deviceIds) {
                            val metaFlow = metadataMap[deviceId] ?: continue
                            launch {
                                metaFlow.collectLatest { device ->
                                    onDeviceMetadataUpdated(deviceId, device)
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
                    _status.update { it.copy(lastError = "Meta registration error: ${error.description}") }
                }
            }
        }
    }

    private fun onDeviceMetadataUpdated(deviceId: DeviceIdentifier, device: Device) {
        val isEligible = device.linkState == LinkState.CONNECTED &&
            device.compatibility == DeviceCompatibility.COMPATIBLE

        if (isEligible) {
            activeEligibleDeviceIdRef.set(deviceId)
        } else if (activeEligibleDeviceIdRef.get() == deviceId) {
            activeEligibleDeviceIdRef.set(null)
        }

        val currentEligible = activeEligibleDeviceIdRef.get()
        // If another device is already eligible, don't overwrite the primary UI status with a disconnected one
        if (currentEligible != null && currentEligible != deviceId) return

        val needsFirmware = device.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED
        val autoError = when {
            needsFirmware -> "Your Meta glasses firmware needs an update before DAT camera streaming works."
            device.compatibility == DeviceCompatibility.SDK_UPDATE_REQUIRED ->
                "This app's Meta Wearables SDK version is older than required by your glasses."
            else -> null
        }

        _status.update { prev ->
            prev.copy(
                deviceName = device.name.ifBlank { "Meta Glasses" },
                deviceType = device.deviceType,
                batteryLevel = device.batteryLevel,
                linkState = device.linkState,
                compatibility = device.compatibility,
                hingeState = device.hingeState,
                donState = device.donState,
                requiresFirmwareUpdate = needsFirmware,
                lastError = autoError ?: if (isEligible) null else prev.lastError
            )
        }

        // If user already tapped Start (or just granted permission) and was waiting for glasses to connect, start automatically!
        if (isEligible && _status.value.isWaitingForEligibleDevice && deviceSession == null) {
            createAndStartSession(deviceId)
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

    fun openFirmwareUpdate(activity: Activity) {
        runCatching {
            Wearables.openFirmwareUpdate(activity).onFailure { err, _ ->
                _status.update { it.copy(lastError = "Unable to open firmware update: ${err.description}") }
            }
        }
    }

    fun openGlassesAppUpdate(activity: Activity) {
        runCatching {
            Wearables.openDATGlassesAppUpdate(activity).onFailure { err, _ ->
                _status.update { it.copy(lastError = "Unable to open glasses update: ${err.description}") }
            }
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

    fun onAppResumed() {
        scope.launch {
            if (_status.value.isRegistered) {
                refreshCameraPermission()
                val eligibleId = findBestEligibleDeviceId()
                if (eligibleId != null && _status.value.isWaitingForEligibleDevice && deviceSession == null) {
                    createAndStartSession(eligibleId)
                }
            }
        }
    }

    fun startCameraStream() {
        if (deviceSession != null && _status.value.isStreaming) return
        stopCameraStream()

        val snapshot = _status.value
        if (!snapshot.isRegistered) {
            _status.update {
                it.copy(lastError = "Connect your glasses in the Meta AI app first (Registration: ${snapshot.registrationState}).")
            }
            return
        }

        val eligibleId = findBestEligibleDeviceId()
        if (eligibleId == null) {
            val helpfulReason = buildNoEligibleDeviceReason(_status.value)
            _status.update {
                it.copy(
                    isWaitingForEligibleDevice = true,
                    lastError = helpfulReason
                )
            }
            return
        }

        createAndStartSession(eligibleId)
    }

    private fun findBestEligibleDeviceId(): DeviceIdentifier? {
        activeEligibleDeviceIdRef.get()?.let { cached ->
            val dev = Wearables.devicesMetadata[cached]?.value
            if (dev?.linkState == LinkState.CONNECTED && dev.compatibility == DeviceCompatibility.COMPATIBLE) {
                return cached
            }
        }
        val ids = Wearables.devices.value
        for (id in ids) {
            val dev = Wearables.devicesMetadata[id]?.value ?: continue
            if (dev.linkState == LinkState.CONNECTED && dev.compatibility == DeviceCompatibility.COMPATIBLE) {
                activeEligibleDeviceIdRef.set(id)
                return id
            }
        }
        return null
    }

    private fun buildNoEligibleDeviceReason(s: GlassesUiStatus): String {
        return when {
            s.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED ->
                "Glasses firmware update required. Tap 'Update Firmware' to update in the Meta AI app."
            s.compatibility == DeviceCompatibility.SDK_UPDATE_REQUIRED ->
                "SDK update required for your glasses firmware."
            s.hingeState == HingeState.CLOSED ->
                "Glasses hinges are closed. Unfold and wear your Meta glasses, then wait a moment — streaming will start automatically."
            s.discoveredDeviceCount == 0 ->
                "No eligible Meta glasses found yet. Check that: (1) Developer Mode is ON in Meta AI (Settings → App Info → tap version 5×), (2) your glasses are unfolded & connected in Meta AI, and (3) Bluetooth permission is allowed. Waiting automatically..."
            s.linkState == LinkState.CONNECTING ->
                "Meta glasses are connecting... Camera stream will start automatically once connected."
            s.linkState == LinkState.DISCONNECTED ->
                "Meta glasses (${s.deviceName ?: "paired"}) are currently DISCONNECTED from the DAT bridge. Open the Meta AI app, wake/unfold your glasses, and return here — streaming will auto-start."
            else ->
                "Waiting for Meta glasses to become eligible (${s.diagnosticsLine}). Make sure glasses are unfolded, awake, and updated in Meta AI."
        }
    }

    private fun createAndStartSession(deviceId: DeviceIdentifier?) {
        _status.update {
            it.copy(
                isWaitingForEligibleDevice = false,
                requiresGlassesAppUpdate = false,
                lastError = null
            )
        }

        val selector = if (deviceId != null) {
            SpecificDeviceSelector(deviceId)
        } else {
            AutoDeviceSelector()
        }

        val sessionResult = runCatching {
            Wearables.createSession(selector)
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
                val waiting = error == DeviceSessionError.NO_ELIGIBLE_DEVICE
                val needsDatAppUpdate = error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED
                val friendly = when (error) {
                    DeviceSessionError.NO_ELIGIBLE_DEVICE -> buildNoEligibleDeviceReason(_status.value)
                    DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
                        "Meta glasses require a DAT app update. Tap 'Update Glasses App' below."
                    else -> "Glasses session failed: ${throwable?.message ?: error.description}"
                }
                _status.update {
                    it.copy(
                        isWaitingForEligibleDevice = waiting,
                        requiresGlassesAppUpdate = needsDatAppUpdate,
                        lastError = friendly
                    )
                }
            }
        )
    }

    private fun observeSessionAndStartCamera(session: DeviceSession) {
        sessionWatcherJob?.cancel()
        sessionWatcherJob = scope.launch {
            launch {
                session.errors.collect { err ->
                    val needsDatAppUpdate = err == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED
                    _status.update {
                        it.copy(
                            requiresGlassesAppUpdate = needsDatAppUpdate,
                            lastError = "Glasses session error: ${err.description}"
                        )
                    }
                }
            }
            launch {
                session.deviceInfo.collect { device ->
                    _status.update {
                        it.copy(
                            deviceName = device.name.ifBlank { it.deviceName ?: "Meta Glasses" },
                            deviceType = device.deviceType,
                            batteryLevel = device.batteryLevel,
                            linkState = device.linkState,
                            compatibility = device.compatibility,
                            hingeState = device.hingeState,
                            donState = device.donState
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
                    it.copy(lastError = "Unable to attach glasses camera: ${throwable?.message ?: error.description}")
                }
            }
        )
    }

    fun getLatestJpegFrame(): ByteArray? = latestJpegRef.get()

    fun getLatestBitmap(): Bitmap? = latestBitmapRef.get()

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
                streamState = StreamState.STOPPED,
                isWaitingForEligibleDevice = false
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
