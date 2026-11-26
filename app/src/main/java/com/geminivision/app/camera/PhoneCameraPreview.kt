package com.geminivision.app.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Optional CameraX phone-camera viewfinder so users can test Gemini Vision QA
 * and Gemini Live immediately even when Meta glasses aren't paired yet.
 */
@Composable
fun PhoneCameraPreview(
    modifier: Modifier = Modifier,
    useFrontCamera: Boolean = false,
    onFrameAvailable: (ByteArray, Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                var lastAnalyzedMs = 0L
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(analysisExecutor) { proxy ->
                            try {
                                val now = System.currentTimeMillis()
                                if (now - lastAnalyzedMs >= 250L) {
                                    lastAnalyzedMs = now
                                    FrameCodec.imageProxyToJpegAndBitmap(proxy)?.let { (jpeg, bmp) ->
                                        onFrameAvailable(jpeg, bmp)
                                    }
                                }
                            } finally {
                                proxy.close()
                            }
                        }
                    }

                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }.onFailure { err ->
                    Log.e("PhoneCameraPreview", "CameraX bind failed", err)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}
