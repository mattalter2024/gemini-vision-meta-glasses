package com.geminivision.app.data

import android.content.Context
import com.geminivision.app.BuildConfig

enum class VideoSourceMode(val label: String) {
    META_GLASSES("Meta Glasses"),
    PHONE_CAMERA("Phone Camera")
}

data class AppSettings(
    val apiKey: String = "",
    val qaModel: String = DEFAULT_QA_MODEL,
    val liveModel: String = DEFAULT_LIVE_MODEL,
    val liveVoice: String = DEFAULT_LIVE_VOICE,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val autoListenOnStart: Boolean = true, // Ready to listen by voice immediately when app opens
    val keepRunningWhenLocked: Boolean = true, // Foreground service keeps voice & glasses alive while locked
    val autoSpeakQa: Boolean = true,       // Default ON for display-less Meta glasses
    val preferGlassesAudio: Boolean = true,
    val videoSource: VideoSourceMode = VideoSourceMode.META_GLASSES
) {
    companion object {
        const val DEFAULT_QA_MODEL = "gemini-4-hydrogen"
        const val DEFAULT_LIVE_MODEL = "gemini-3.8-live"
        const val DEFAULT_LIVE_VOICE = "Puck"
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a concise, natural hands-free AI assistant running through the user's Meta smart glasses (audio-only, no HUD display). " +
                "Describe what their glasses camera sees clearly and briefly as if speaking aloud. " +
                "Avoid markdown formatting, bullet lists, or long preambles unless asked."

        val QA_MODEL_PRESETS = listOf(
            "gemini-4-hydrogen",
            "gemini-4-argon",
            "gemini-4-neon",
            "gemini-4-carbon",
            "gemini-3.5-flash-lite"
        )

        val LIVE_MODEL_PRESETS = listOf(
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking"
        )

        val VOICE_PRESETS = listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede")
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val savedKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        val effectiveKey = savedKey.ifEmpty { BuildConfig.GEMINI_API_KEY.trim() }
        val savedSource = prefs.getString(KEY_VIDEO_SOURCE, VideoSourceMode.META_GLASSES.name)
        val videoSource = runCatching { VideoSourceMode.valueOf(savedSource ?: "") }
            .getOrDefault(VideoSourceMode.META_GLASSES)

        return AppSettings(
            apiKey = effectiveKey,
            qaModel = prefs.getString(KEY_QA_MODEL, AppSettings.DEFAULT_QA_MODEL)
                ?.trim().orEmpty().ifEmpty { AppSettings.DEFAULT_QA_MODEL },
            liveModel = prefs.getString(KEY_LIVE_MODEL, AppSettings.DEFAULT_LIVE_MODEL)
                ?.trim().orEmpty().ifEmpty { AppSettings.DEFAULT_LIVE_MODEL },
            liveVoice = prefs.getString(KEY_LIVE_VOICE, AppSettings.DEFAULT_LIVE_VOICE)
                ?.trim().orEmpty().ifEmpty { AppSettings.DEFAULT_LIVE_VOICE },
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, AppSettings.DEFAULT_SYSTEM_PROMPT)
                ?.trim().orEmpty().ifEmpty { AppSettings.DEFAULT_SYSTEM_PROMPT },
            autoListenOnStart = prefs.getBoolean(KEY_AUTO_LISTEN_ON_START, true),
            keepRunningWhenLocked = prefs.getBoolean(KEY_KEEP_RUNNING_WHEN_LOCKED, true),
            autoSpeakQa = prefs.getBoolean(KEY_AUTO_SPEAK_QA, true),
            preferGlassesAudio = prefs.getBoolean(KEY_PREFER_GLASSES_AUDIO, true),
            videoSource = videoSource
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_QA_MODEL, settings.qaModel.trim().ifEmpty { AppSettings.DEFAULT_QA_MODEL })
            .putString(KEY_LIVE_MODEL, settings.liveModel.trim().ifEmpty { AppSettings.DEFAULT_LIVE_MODEL })
            .putString(KEY_LIVE_VOICE, settings.liveVoice.trim().ifEmpty { AppSettings.DEFAULT_LIVE_VOICE })
            .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt.trim().ifEmpty { AppSettings.DEFAULT_SYSTEM_PROMPT })
            .putBoolean(KEY_AUTO_LISTEN_ON_START, settings.autoListenOnStart)
            .putBoolean(KEY_KEEP_RUNNING_WHEN_LOCKED, settings.keepRunningWhenLocked)
            .putBoolean(KEY_AUTO_SPEAK_QA, settings.autoSpeakQa)
            .putBoolean(KEY_PREFER_GLASSES_AUDIO, settings.preferGlassesAudio)
            .putString(KEY_VIDEO_SOURCE, settings.videoSource.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "gemini_vision_settings"
        const val KEY_API_KEY = "api_key"
        const val KEY_QA_MODEL = "qa_model"
        const val KEY_LIVE_MODEL = "live_model"
        const val KEY_LIVE_VOICE = "live_voice"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_AUTO_LISTEN_ON_START = "auto_listen_on_start"
        const val KEY_KEEP_RUNNING_WHEN_LOCKED = "keep_running_when_locked"
        const val KEY_AUTO_SPEAK_QA = "auto_speak_qa"
        const val KEY_PREFER_GLASSES_AUDIO = "prefer_glasses_audio"
        const val KEY_VIDEO_SOURCE = "video_source"
    }
}
