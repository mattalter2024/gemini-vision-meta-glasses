package com.geminivision.app

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class GeminiVisionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this)
            .onSuccess {
                Log.i(TAG, "Meta Wearables DAT SDK initialized (devMode=${Wearables.isDevMode})")
            }
            .onFailure { error, throwable ->
                Log.e(TAG, "Failed to initialize Meta Wearables DAT SDK: $error", throwable)
            }
    }

    private companion object {
        const val TAG = "GeminiVisionApp"
    }
}
