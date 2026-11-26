package com.geminivision.app.gemini

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiProtocolTest {

    @Test
    fun `buildInteractionRequestJson embeds inline jpeg, previous_interaction_id, and system_instruction`() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val json = GeminiProtocol.buildInteractionRequestJson(
            model = "gemini-4-hydrogen",
            prompt = "What am I looking at?",
            jpegBytes = fakeJpeg,
            previousInteractionId = "int_prev_99",
            systemInstruction = "Be concise."
        )

        val root = JSONObject(json)
        assertEquals("gemini-4-hydrogen", root.getString("model"))
        assertEquals("Be concise.", root.getString("system_instruction"))
        assertEquals("int_prev_99", root.getString("previous_interaction_id"))

        val input = root.getJSONArray("input")
        assertEquals(2, input.length())

        val imageBlock = input.getJSONObject(0)
        assertEquals("image", imageBlock.getString("type"))
        assertEquals("image/jpeg", imageBlock.getString("mime_type"))
        assertArrayEquals(fakeJpeg, Base64.getDecoder().decode(imageBlock.getString("data")))

        val textBlock = input.getJSONObject(1)
        assertEquals("text", textBlock.getString("type"))
        assertEquals("What am I looking at?", textBlock.getString("text"))
    }

    @Test
    fun `parseInteractionResponse extracts model_output steps and surfaces API errors`() {
        val okPayload = """
            {
              "id": "int_abc123",
              "status": "completed",
              "steps": [
                {
                  "type": "model_output",
                  "content": [
                    { "type": "text", "text": "You are holding a ceramic coffee mug." }
                  ]
                }
              ]
            }
        """.trimIndent()
        val okResult = GeminiProtocol.parseInteractionResponse(okPayload)
        assertTrue(okResult.isSuccess)
        assertEquals("int_abc123", okResult.getOrNull()?.interactionId)
        assertEquals("You are holding a ceramic coffee mug.", okResult.getOrNull()?.text)

        val errPayload = """{"error":{"code":401,"message":"API key not valid."}}"""
        val errResult = GeminiProtocol.parseInteractionResponse(errPayload)
        assertTrue(errResult.isFailure)
        assertEquals("API key not valid.", errResult.exceptionOrNull()?.message)
    }

    @Test
    fun `buildLiveSetupJson configures gemini-3_8-live with native audio, voice, and transcriptions`() {
        val raw = GeminiProtocol.buildLiveSetupJson(
            model = "gemini-3.8-live",
            voiceName = "Puck",
            systemInstruction = "Speak briefly."
        )
        val setup = JSONObject(raw).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals(
            "AUDIO",
            setup.getJSONObject("generationConfig")
                .getJSONArray("responseModalities")
                .getString(0)
        )
        assertEquals(
            "Puck",
            setup.getJSONObject("generationConfig")
                .getJSONObject("speechConfig")
                .getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName")
        )
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
    }

    @Test
    fun `realtime input builders use audio, video, text, and audioStreamEnd keys`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val audioRoot = JSONObject(GeminiProtocol.buildRealtimeAudioJson(pcm))
        val audioBlob = audioRoot.getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audioBlob.getString("mimeType"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(audioBlob.getString("data")))

        val jpg = byteArrayOf(9, 8, 7)
        val videoRoot = JSONObject(GeminiProtocol.buildRealtimeVideoJson(jpg))
        val videoBlob = videoRoot.getJSONObject("realtimeInput").getJSONObject("video")
        assertEquals("image/jpeg", videoBlob.getString("mimeType"))
        assertArrayEquals(jpg, Base64.getDecoder().decode(videoBlob.getString("data")))

        val textRoot = JSONObject(GeminiProtocol.buildRealtimeTextJson("Look closer"))
        assertEquals("Look closer", textRoot.getJSONObject("realtimeInput").getString("text"))

        val endRoot = JSONObject(GeminiProtocol.buildRealtimeAudioStreamEndJson())
        assertTrue(endRoot.getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))
    }

    @Test
    fun `parseLiveServerMessage handles setupComplete, audio chunks, transcripts, and barge-in`() {
        val pcmSample = byteArrayOf(10, 20, 30, 40)
        val b64 = Base64.getEncoder().encodeToString(pcmSample)
        val serverMsg = """
            {
              "setupComplete": {},
              "serverContent": {
                "interrupted": true,
                "inputTranscription": { "text": "What sign is that?" },
                "outputTranscription": { "text": "It says Exit 42." },
                "modelTurn": {
                  "parts": [
                    {
                      "inlineData": {
                        "mimeType": "audio/pcm;rate=24000",
                        "data": "$b64"
                      }
                    }
                  ]
                },
                "turnComplete": true
              }
            }
        """.trimIndent()

        val events = GeminiProtocol.parseLiveServerMessage(serverMsg)
        assertTrue(events.any { it is LiveServerEvent.SetupComplete })
        assertTrue(events.any { it is LiveServerEvent.Interrupted })
        assertTrue(events.any { it == LiveServerEvent.InputTranscript("What sign is that?") })
        assertTrue(events.any { it == LiveServerEvent.OutputTranscript("It says Exit 42.") })
        val audio = events.filterIsInstance<LiveServerEvent.AudioChunk>().single()
        assertArrayEquals(pcmSample, audio.pcm24kBytes)
        assertTrue(events.any { it is LiveServerEvent.TurnComplete })
    }
}
