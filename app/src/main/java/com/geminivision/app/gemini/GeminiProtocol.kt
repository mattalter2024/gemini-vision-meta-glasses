package com.geminivision.app.gemini

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

data class QaInteractionResult(
    val interactionId: String?,
    val text: String
)

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    data class AudioChunk(val pcm24kBytes: ByteArray, val mimeType: String) : LiveServerEvent
    data class ModelText(val text: String) : LiveServerEvent
    data class InputTranscript(val text: String) : LiveServerEvent
    data class OutputTranscript(val text: String) : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data class Error(val message: String) : LiveServerEvent
}

/**
 * Pure Kotlin/JSON wire protocol builder and parser for both:
 * 1. Gemini Interactions API (`POST /v1/interactions`) for stateful Camera QA mode
 * 2. Gemini 3.8 Live API WebSocket (`BidiGenerateContent`) for real-time voice + video mode
 */
object GeminiProtocol {
    const val INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
    const val INTERACTIONS_API_REVISION = "2026-05-20"
    const val LIVE_WS_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    fun liveWebSocketUrl(apiKey: String): String {
        return "$LIVE_WS_BASE_URL?key=${apiKey.trim()}"
    }

    /**
     * Builds a Gemini Interactions API request with inline JPEG camera frame,
     * optional `previous_interaction_id` for stateful multi-turn memory, and `system_instruction`.
     */
    fun buildInteractionRequestJson(
        model: String,
        prompt: String,
        jpegBytes: ByteArray?,
        previousInteractionId: String?,
        systemInstruction: String
    ): String {
        val cleanModel = model.trim().removePrefix("models/")
        val root = JSONObject()
            .put("model", cleanModel)

        if (systemInstruction.isNotBlank()) {
            root.put("system_instruction", systemInstruction)
        }
        if (!previousInteractionId.isNullOrBlank()) {
            root.put("previous_interaction_id", previousInteractionId)
        }

        val inputBlocks = JSONArray()
        if (jpegBytes != null && jpegBytes.isNotEmpty()) {
            val base64Jpeg = Base64.getEncoder().encodeToString(jpegBytes)
            inputBlocks.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", base64Jpeg)
                    .put("mime_type", "image/jpeg")
            )
        }
        inputBlocks.put(
            JSONObject()
                .put("type", "text")
                .put("text", prompt)
        )

        root.put("input", inputBlocks)
        return root.toString()
    }

    /**
     * Parses a Gemini Interactions API response (`steps` schema with `model_output` blocks).
     */
    fun parseInteractionResponse(responseBody: String): Result<QaInteractionResult> {
        return runCatching {
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val msg = errObj?.optString("message") ?: json.optString("error", "Unknown Gemini API error")
                throw IllegalStateException(msg)
            }

            val id = json.optString("id", "").ifBlank { null }
            val textParts = mutableListOf<String>()

            val steps = json.optJSONArray("steps")
            if (steps != null) {
                for (i in 0 until steps.length()) {
                    val step = steps.optJSONObject(i) ?: continue
                    if (step.optString("type") != "model_output") continue
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val block = content.optJSONObject(j) ?: continue
                        if (block.optString("type") == "text") {
                            val t = block.optString("text", "")
                            if (t.isNotEmpty()) textParts += t
                        }
                    }
                }
            }

            if (textParts.isEmpty()) {
                val outputs = json.optJSONArray("outputs")
                if (outputs != null) {
                    for (i in 0 until outputs.length()) {
                        val block = outputs.optJSONObject(i) ?: continue
                        if (block.optString("type") == "text") {
                            val t = block.optString("text", "")
                            if (t.isNotEmpty()) textParts += t
                        }
                    }
                }
            }

            val combined = textParts.joinToString("\n").trim()
            if (combined.isEmpty()) {
                val status = json.optString("status", "unknown")
                throw IllegalStateException("Gemini returned no text output (status=$status).")
            }

            QaInteractionResult(interactionId = id, text = combined)
        }
    }

    fun buildLiveSetupJson(
        model: String,
        voiceName: String,
        systemInstruction: String
    ): String {
        val normalizedModel = if (model.startsWith("models/")) model.trim() else "models/${model.trim()}"

        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put(
                "prebuiltVoiceConfig",
                JSONObject().put("voiceName", voiceName.ifBlank { "Puck" })
            )
        )

        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speechConfig)

        val setup = JSONObject()
            .put("model", normalizedModel)
            .put("generationConfig", generationConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())

        if (systemInstruction.isNotBlank()) {
            setup.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
                )
            )
        }

        return JSONObject().put("setup", setup).toString()
    }

    /**
     * Sends 16 kHz PCM audio using the current `realtimeInput.audio` field
     * (replaces deprecated `mediaChunks`).
     */
    fun buildRealtimeAudioJson(pcm16kBytes: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(pcm16kBytes)
        val blob = JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", b64)
        return JSONObject()
            .put("realtimeInput", JSONObject().put("audio", blob))
            .toString()
    }

    /**
     * Sends a JPEG camera frame using the current `realtimeInput.video` field
     * (replaces deprecated `mediaChunks`).
     */
    fun buildRealtimeVideoJson(jpegBytes: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(jpegBytes)
        val blob = JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", b64)
        return JSONObject()
            .put("realtimeInput", JSONObject().put("video", blob))
            .toString()
    }

    /**
     * Sends real-time text input via `realtimeInput.text`.
     */
    fun buildRealtimeTextJson(text: String): String {
        return JSONObject()
            .put("realtimeInput", JSONObject().put("text", text))
            .toString()
    }

    /**
     * Signals Hybrid VAD / end of mic stream (e.g. when the user mutes their microphone).
     */
    fun buildRealtimeAudioStreamEndJson(): String {
        return JSONObject()
            .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
            .toString()
    }

    fun parseLiveServerMessage(rawJson: String): List<LiveServerEvent> {
        val events = mutableListOf<LiveServerEvent>()
        val root = runCatching { JSONObject(rawJson) }.getOrElse { err ->
            return listOf(LiveServerEvent.Error("Malformed server frame: ${err.message}"))
        }

        if (root.has("error")) {
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message") ?: root.optString("error", "Live API error")
            events += LiveServerEvent.Error(msg)
            return events
        }

        if (root.has("setupComplete")) {
            events += LiveServerEvent.SetupComplete
        }

        val serverContent = root.optJSONObject("serverContent") ?: return events

        if (serverContent.optBoolean("interrupted", false)) {
            events += LiveServerEvent.Interrupted
        }

        val inputTranscription = serverContent.optJSONObject("inputTranscription")
            ?: serverContent.optJSONObject("inputAudioTranscription")
        val inputText = inputTranscription?.optString("text", "").orEmpty()
        if (inputText.isNotEmpty()) {
            events += LiveServerEvent.InputTranscript(inputText)
        }

        val outputTranscription = serverContent.optJSONObject("outputTranscription")
            ?: serverContent.optJSONObject("outputAudioTranscription")
        val outputText = outputTranscription?.optString("text", "").orEmpty()
        if (outputText.isNotEmpty()) {
            events += LiveServerEvent.OutputTranscript(outputText)
        }

        val modelTurn = serverContent.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.optString("text", "")
                if (text.isNotEmpty()) {
                    events += LiveServerEvent.ModelText(text)
                }
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    val mimeType = inlineData.optString("mimeType", "")
                    val data = inlineData.optString("data", "")
                    if (mimeType.startsWith("audio/") && data.isNotEmpty()) {
                        runCatching { Base64.getDecoder().decode(data) }
                            .onSuccess { bytes ->
                                events += LiveServerEvent.AudioChunk(bytes, mimeType)
                            }
                    }
                }
            }
        }

        if (serverContent.optBoolean("turnComplete", false)) {
            events += LiveServerEvent.TurnComplete
        }

        return events
    }
}
