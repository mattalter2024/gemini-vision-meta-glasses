package com.geminivision.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Main-thread-safe voice-first engine for display-less Meta smart glasses:
 * - Automatically speaks Gemini QA answers aloud into the connected glasses speakers via TTS.
 * - Supports always-ready hands-free listening: starts listening on launch, silently re-arms
 *   after speech timeouts, pauses while Gemini speaks so it doesn't hear itself, and resumes
 *   listening automatically once TTS completes.
 */
class VoiceAssistHelper(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingRearmRunnable: Runnable? = null

    private var continuousMode = false
    private var currentOnPartial: ((String) -> Unit)? = null
    private var currentOnFinal: ((String) -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null
    private var onTtsDoneCallback: (() -> Unit)? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.06f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        mainHandler.post { onTtsDoneCallback?.invoke() }
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        mainHandler.post { onTtsDoneCallback?.invoke() }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _isSpeaking.value = false
                        mainHandler.post { onTtsDoneCallback?.invoke() }
                    }
                })
                ttsReady = true
            }
        }
    }

    fun speak(text: String, onFinished: (() -> Unit)? = null) {
        onTtsDoneCallback = onFinished
        if (!ttsReady || text.isBlank()) {
            onFinished?.invoke()
            return
        }
        // Pause mic before speaking so Gemini doesn't transcribe its own voice
        pauseListeningOnly()
        val cleaned = text
            .replace("**", "")
            .replace("*", "")
            .replace("`", "")
            .trim()
        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() {
        onTtsDoneCallback = null
        tts?.stop()
        _isSpeaking.value = false
    }

    fun startListening(
        continuous: Boolean = true,
        onPartial: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                onError("Speech recognition is not available on this phone.")
                return@post
            }
            continuousMode = continuous
            currentOnPartial = onPartial
            currentOnFinal = onFinalResult
            currentOnError = onError

            stopSpeaking()
            launchRecognizerInternal()
        }
    }

    private fun launchRecognizerInternal() {
        cancelPendingRearm()
        destroyRecognizerInternal()

        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }
            .getOrElse { err ->
                _isListening.value = false
                currentOnError?.invoke("Unable to start voice recognizer: ${err.message}")
                return
            }

        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _isListening.value = false
            }

            override fun onError(error: Int) {
                _isListening.value = false
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        if (continuousMode && !_isSpeaking.value) {
                            scheduleRearm(450L)
                        }
                    }
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        continuousMode = false
                        currentOnError?.invoke("Microphone permission is required for voice input.")
                    }
                    else -> {
                        if (continuousMode && !_isSpeaking.value) {
                            scheduleRearm(900L)
                        } else {
                            currentOnError?.invoke("Voice recognition error ($error)")
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    currentOnFinal?.invoke(text)
                } else if (continuousMode && !_isSpeaking.value) {
                    scheduleRearm(350L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    currentOnPartial?.invoke(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        _isListening.value = true
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                _isListening.value = false
                if (continuousMode) scheduleRearm(700L)
            }
    }

    private fun scheduleRearm(delayMs: Long) {
        cancelPendingRearm()
        val task = Runnable {
            if (continuousMode && !_isSpeaking.value) {
                launchRecognizerInternal()
            }
        }
        pendingRearmRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelPendingRearm() {
        pendingRearmRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRearmRunnable = null
    }

    private fun destroyRecognizerInternal() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun pauseListeningOnly() {
        mainHandler.post {
            cancelPendingRearm()
            _isListening.value = false
            destroyRecognizerInternal()
        }
    }

    fun stopListening() {
        mainHandler.post {
            continuousMode = false
            cancelPendingRearm()
            _isListening.value = false
            destroyRecognizerInternal()
        }
    }

    fun release() {
        stopListening()
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
    }
}
