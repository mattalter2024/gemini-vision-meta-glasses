package com.geminivision.app.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiQaClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    suspend fun ask(
        apiKey: String,
        model: String,
        prompt: String,
        jpegFrame: ByteArray?,
        previousInteractionId: String?,
        systemInstruction: String
    ): Result<QaInteractionResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is missing. Open Settings to add your key.")
            )
        }

        val payload = GeminiProtocol.buildInteractionRequestJson(
            model = model,
            prompt = prompt,
            jpegBytes = jpegFrame,
            previousInteractionId = previousInteractionId,
            systemInstruction = systemInstruction
        )

        val request = Request.Builder()
            .url(GeminiProtocol.INTERACTIONS_URL)
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
            .header("Api-Revision", GeminiProtocol.INTERACTIONS_API_REVISION)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val parsedErr = GeminiProtocol.parseInteractionResponse(body).exceptionOrNull()?.message
                    throw IllegalStateException(parsedErr ?: "HTTP ${response.code}: ${response.message}")
                }
                GeminiProtocol.parseInteractionResponse(body).getOrThrow()
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
