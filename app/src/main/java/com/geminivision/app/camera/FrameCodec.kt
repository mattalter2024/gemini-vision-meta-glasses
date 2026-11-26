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
 * Converts video frames from both Meta Wearables DAT (I420 / NV21 ByteBuffers)
 * and Android CameraX (YUV_420_888 ImageProxy) into compact rotated JPEGs + Bitmaps
 * suitable for Gemini Vision QA and 1 FPS Gemini Live streaming.
 */
object FrameCodec {

    /**
     * Converts an I420 planar ByteBuffer (standard output of Meta DAT VideoFrame)
     * into a downscaled JPEG + Bitmap.
     */
    fun i420BufferToJpegAndBitmap(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        maxEdge: Int = 768,
        jpegQuality: Int = 75
    ): Pair<ByteArray, Bitmap>? {
        if (width <= 0 || height <= 0) return null
        val dup = buffer.duplicate()
        dup.rewind()
        val ySize = width * height
        val expectedSize = ySize + (ySize / 2)
        if (dup.remaining() < expectedSize) return null

        val i420 = ByteArray(expectedSize)
        dup.get(i420)
        val nv21 = i420ToNv21(i420, width, height)
        return nv21ToJpegAndBitmap(nv21, width, height, rotationDegrees = 0, maxEdge = maxEdge, jpegQuality = jpegQuality)
    }

    /**
     * Converts a CameraX ImageProxy (YUV_420_888) into a properly rotated, downscaled JPEG + Bitmap.
     */
    fun imageProxyToJpegAndBitmap(
        image: ImageProxy,
        maxEdge: Int = 768,
        jpegQuality: Int = 75
    ): Pair<ByteArray, Bitmap>? {
        val nv21 = imageProxyToNv21(image) ?: return null
        return nv21ToJpegAndBitmap(
            nv21 = nv21,
            width = image.width,
            height = image.height,
            rotationDegrees = image.imageInfo.rotationDegrees,
            maxEdge = maxEdge,
            jpegQuality = jpegQuality
        )
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

    fun i420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val chromaSize = ySize / 4
        val nv21 = ByteArray(ySize + chromaSize * 2)
        System.arraycopy(i420, 0, nv21, 0, ySize)

        val uOffset = ySize
        val vOffset = ySize + chromaSize
        var dst = ySize
        for (i in 0 until chromaSize) {
            nv21[dst++] = i420[vOffset + i] // V
            nv21[dst++] = i420[uOffset + i] // U
        }
        return nv21
    }

    private fun nv21ToJpegAndBitmap(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        maxEdge: Int,
        jpegQuality: Int
    ): Pair<ByteArray, Bitmap>? {
        val rawOut = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), 85, rawOut)) return null
        val rawJpeg = rawOut.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return null
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
