package com.geminivision.app.gemini

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class LiveConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class GeminiLiveClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    interface Listener {
        fun onConnectionStateChanged(state: LiveConnectionState)
        fun onServerEvent(event: LiveServerEvent)
        fun onClosed(reason: String)
        fun onError(message: String)
    }

    @Volatile
    private var activeSocket: WebSocket? = null
    private val setupReady = AtomicBoolean(false)

    val isReady: Boolean
        get() = setupReady.get() && activeSocket != null

    fun connect(
        apiKey: String,
        model: String,
        voiceName: String,
        systemInstruction: String,
        listener: Listener
    ) {
        disconnect()
        if (apiKey.isBlank()) {
            listener.onError("Gemini API key is missing. Open Settings to add your key.")
            return
        }

        listener.onConnectionStateChanged(LiveConnectionState.CONNECTING)

        val request = Request.Builder()
            .url(GeminiProtocol.liveWebSocketUrl(apiKey))
            .build()

        activeSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setupJson = GeminiProtocol.buildLiveSetupJson(
                    model = model,
                    voiceName = voiceName,
                    systemInstruction = systemInstruction
                )
                webSocket.send(setupJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatchServerPayload(text, listener)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                dispatchServerPayload(bytes.utf8(), listener)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setupReady.set(false)
                activeSocket = null
                listener.onConnectionStateChanged(LiveConnectionState.DISCONNECTED)
                val label = reason.ifBlank { "Session ended ($code)" }
                listener.onClosed(label)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setupReady.set(false)
                activeSocket = null
                listener.onConnectionStateChanged(LiveConnectionState.DISCONNECTED)
                val httpDetail = response?.let { " (HTTP ${it.code})" }.orEmpty()
                listener.onError("${t.message ?: "WebSocket connection failed"}$httpDetail")
            }
        })
    }

    private fun dispatchServerPayload(payload: String, listener: Listener) {
        val events = GeminiProtocol.parseLiveServerMessage(payload)
        for (event in events) {
            if (event is LiveServerEvent.SetupComplete) {
                setupReady.set(true)
                listener.onConnectionStateChanged(LiveConnectionState.CONNECTED)
            }
            listener.onServerEvent(event)
        }
    }

    fun sendAudioPcm16k(pcmBytes: ByteArray): Boolean {
        val ws = activeSocket ?: return false
        if (!setupReady.get() || pcmBytes.isEmpty()) return false
        return ws.send(GeminiProtocol.buildRealtimeAudioJson(pcmBytes))
    }

    fun sendAudioStreamEnd(): Boolean {
        val ws = activeSocket ?: return false
        if (!setupReady.get()) return false
        return ws.send(GeminiProtocol.buildRealtimeAudioStreamEndJson())
    }

    fun sendVideoFrameJpeg(jpegBytes: ByteArray): Boolean {
        val ws = activeSocket ?: return false
        if (!setupReady.get() || jpegBytes.isEmpty()) return false
        return ws.send(GeminiProtocol.buildRealtimeVideoJson(jpegBytes))
    }

    fun sendTextNudge(text: String): Boolean {
        val ws = activeSocket ?: return false
        if (!setupReady.get() || text.isBlank()) return false
        return ws.send(GeminiProtocol.buildRealtimeTextJson(text.trim()))
    }

    fun disconnect() {
        setupReady.set(false)
        val ws = activeSocket
        activeSocket = null
        ws?.close(1000, "User disconnected")
    }
}
