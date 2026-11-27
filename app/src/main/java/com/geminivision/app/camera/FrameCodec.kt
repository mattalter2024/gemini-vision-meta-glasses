package com.geminivision.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * High-speed video frame utilities:
 * - Fast direct I420 -> ARGB_8888 Bitmap conversion (no JPEG round-trip!) for smooth ~24 FPS Meta glasses preview.
 * - On-demand JPEG encoding only when sending a frame to Gemini (1 FPS Live or single QA capture).
 */
object FrameCodec {

    /**
     * Converts a raw I420 ByteBuffer from Meta DAT directly into an ARGB_8888 Bitmap
     * without any JPEG compression/decompression overhead.
     * Uses 2x subsampling automatically when width or height > maxEdge for ultra-fast preview rendering.
     */
    fun i420BufferToBitmapFast(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        maxEdge: Int = 720
    ): Bitmap? {
        if (width <= 1 || height <= 1) return null
        val dup = buffer.duplicate()
        dup.rewind()
        val ySize = width * height
        val expectedSize = ySize + (ySize / 2)
        if (dup.remaining() < expectedSize) return null

        val i420 = ByteArray(expectedSize)
        dup.get(i420)

        val step = if (maxOf(width, height) > maxEdge) 2 else 1
        val outW = (width + step - 1) / step
        val outH = (height + step - 1) / step
        val pixels = i420ToArgbPixels(i420, width, height, step)

        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    /**
     * Pure Kotlin fast integer BT.601 YUV I420 -> ARGB_8888 conversion (JVM unit-testable).
     */
    fun i420ToArgbPixels(
        i420: ByteArray,
        width: Int,
        height: Int,
        step: Int = 1
    ): IntArray {
        val outW = (width + step - 1) / step
        val outH = (height + step - 1) / step
        val pixels = IntArray(outW * outH)

        val ySize = width * height
        val chromaW = width / 2
        val uPlaneOffset = ySize
        val vPlaneOffset = ySize + (ySize / 4)

        var outIdx = 0
        var y = 0
        while (y < height) {
            val yRow = y * width
            val uvRow = (y shr 1) * chromaW
            var x = 0
            while (x < width) {
                val yVal = (i420[yRow + x].toInt() and 0xFF) - 16
                val uvIdx = uvRow + (x shr 1)
                val uVal = (i420[uPlaneOffset + uvIdx].toInt() and 0xFF) - 128
                val vVal = (i420[vPlaneOffset + uvIdx].toInt() and 0xFF) - 128

                val y1192 = 1192 * (if (yVal < 0) 0 else yVal)
                var r = (y1192 + 1634 * vVal) shr 10
                var g = (y1192 - 833 * vVal - 400 * uVal) shr 10
                var b = (y1192 + 2066 * uVal) shr 10

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                pixels[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                x += step
            }
            y += step
        }
        return pixels
    }

    /**
     * Compresses a Bitmap to JPEG on demand (called only when sending a frame to Gemini QA or 1 FPS Live).
     */
    fun bitmapToJpeg(
        source: Bitmap,
        maxEdge: Int = 768,
        jpegQuality: Int = 78
    ): ByteArray {
        val scaled = scaleBitmapIfNeeded(source, maxEdge)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        if (scaled != source) {
            scaled.recycle()
        }
        return out.toByteArray()
    }

    fun downscaleBitmapToJpeg(
        source: Bitmap,
        maxEdge: Int = 1024,
        jpegQuality: Int = 82
    ): Pair<ByteArray, Bitmap> {
        val scaled = scaleBitmapIfNeeded(source, maxEdge)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        return out.toByteArray() to scaled
    }

    /**
     * Converts a CameraX ImageProxy (YUV_420_888) into a properly rotated, downscaled JPEG + Bitmap
     * for optional Phone Camera fallback mode.
     */
    fun imageProxyToJpegAndBitmap(
        image: ImageProxy,
        maxEdge: Int = 768,
        jpegQuality: Int = 75
    ): Pair<ByteArray, Bitmap>? {
        val nv21 = imageProxyToNv21(image) ?: return null
        val rawOut = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        if (!yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 82, rawOut)) return null
        val rawJpeg = rawOut.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return null
        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val scaled = scaleBitmapIfNeeded(bitmap, maxEdge)
        if (scaled != bitmap) bitmap.recycle()

        val finalOut = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, finalOut)
        return finalOut.toByteArray() to scaled
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun imageProxyToNv21(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return null
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        for (row in 0 until height) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, pos, width)
            pos += width
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val halfH = height / 2
        val halfW = width / 2

        for (row in 0 until halfH) {
            for (col in 0 until halfW) {
                val idx = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuf.get(idx)
                nv21[pos++] = uBuf.get(idx)
            }
        }
        return nv21
    }
}
