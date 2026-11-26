package com.geminivision.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Full-duplex PCM audio engine optimized for display-less Meta smart glasses:
 * - Routes bidirectional voice through the paired glasses' Bluetooth HFP/SCO / BLE headset
 *   (falling back cleanly to the phone mic/speaker when glasses Bluetooth audio is unavailable).
 * - Captures 16 kHz 16-bit mono PCM (for Gemini Live API input).
 * - Plays 24 kHz 16-bit mono PCM (from Gemini Live API output) with instant flush on barge-in.
 */
class LiveAudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null

    private var recordThread: Thread? = null
    private var playbackThread: Thread? = null

    private val _audioRouteLabel = MutableStateFlow("Phone Audio")
    val audioRouteLabel: StateFlow<String> = _audioRouteLabel.asStateFlow()

    private val _isGlassesAudioActive = MutableStateFlow(false)
    val isGlassesAudioActive: StateFlow<Boolean> = _isGlassesAudioActive.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _isGeminiSpeaking = MutableStateFlow(false)
    val isGeminiSpeaking: StateFlow<Boolean> = _isGeminiSpeaking.asStateFlow()

    fun setMuted(isMuted: Boolean) {
        muted.set(isMuted)
        if (isMuted) _micLevel.value = 0f
    }

    /**
     * Routes communication audio to paired Meta glasses (Bluetooth SCO / BLE Headset) if available.
     */
    fun configureAudioRouting(preferGlassesBluetooth: Boolean): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (preferGlassesBluetooth) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val btDevice = audioManager.availableCommunicationDevices.firstOrNull { dev ->
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (btDevice != null && audioManager.setCommunicationDevice(btDevice)) {
                    val label = btDevice.productName?.toString()?.takeIf { it.isNotBlank() }
                        ?: "Meta Glasses BT"
                    _audioRouteLabel.value = label
                    _isGlassesAudioActive.value = true
                    return true
                }
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoAvailableOffCall) {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                    _audioRouteLabel.value = "Bluetooth Glasses"
                    _isGlassesAudioActive.value = true
                    return true
                }
            }
        }

        // Fallback to phone speakerphone so hands-free conversation still works cleanly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        _audioRouteLabel.value = "Phone Speaker"
        _isGlassesAudioActive.value = false
        return false
    }

    fun releaseAudioRouting() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        _isGlassesAudioActive.value = false
    }

    @SuppressLint("MissingPermission")
    fun start(
        preferGlassesBluetooth: Boolean,
        onPcm16kCaptured: (ByteArray) -> Unit
    ): Result<Unit> {
        stop()
        return runCatching {
            configureAudioRouting(preferGlassesBluetooth)

            val minRecBuf = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf * 2
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Unable to initialize microphone recorder."
            }

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            }

            val minPlayBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord = recorder
            audioTrack = track
            running.set(true)

            recorder.startRecording()
            track.play()

            // ~100ms chunks at 16 kHz 16-bit mono = 3200 bytes
            recordThread = thread(name = "GeminiMicCapture", isDaemon = true) {
                val readBuf = ByteArray(3200)
                while (running.get()) {
                    val read = recorder.read(readBuf, 0, readBuf.size)
                    if (read > 0) {
                        if (!muted.get()) {
                            val chunk = readBuf.copyOf(read)
                            _micLevel.value = computeNormalizedRms(chunk)
                            onPcm16kCaptured(chunk)
                        } else {
                            _micLevel.value = 0f
                        }
                    }
                }
            }

            playbackThread = thread(name = "GeminiAudioPlayback", isDaemon = true) {
                while (running.get()) {
                    val chunk = runCatching {
                        playbackQueue.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }.getOrNull()

                    if (chunk != null && running.get()) {
                        _isGeminiSpeaking.value = true
                        runCatching { track.write(chunk, 0, chunk.size) }
                    } else if (playbackQueue.isEmpty()) {
                        _isGeminiSpeaking.value = false
                    }
                }
                _isGeminiSpeaking.value = false
            }
        }.onFailure {
            stop()
        }
    }

    fun enqueuePlaybackPcm24k(pcmBytes: ByteArray) {
        if (running.get() && pcmBytes.isNotEmpty()) {
            playbackQueue.offer(pcmBytes)
        }
    }

    /**
     * Immediately stops currently buffered Gemini speech when the user interrupts (barge-in).
     */
    fun flushPlayback() {
        playbackQueue.clear()
        _isGeminiSpeaking.value = false
        runCatching {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        playbackQueue.clear()
        _micLevel.value = 0f
        _isGeminiSpeaking.value = false

        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null

        recordThread?.interrupt()
        recordThread = null
        playbackThread?.interrupt()
        playbackThread = null

        releaseAudioRouting()
    }

    private fun computeNormalizedRms(pcm16Le: ByteArray): Float {
        val sampleCount = pcm16Le.size / 2
        if (sampleCount == 0) return 0f
        var sumSq = 0.0
        var i = 0
        while (i + 1 < pcm16Le.size) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += sample * sample.toDouble()
            i += 2
        }
        val rms = sqrt(sumSq / sampleCount)
        return (rms / 12000.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
    }
}
