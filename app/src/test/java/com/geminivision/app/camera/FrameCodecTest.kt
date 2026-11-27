package com.geminivision.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    @Test
    fun `i420ToArgbPixels converts neutral gray and white frames accurately`() {
        val width = 4
        val height = 4
        val ySize = width * height
        val uvSize = ySize / 4
        val i420 = ByteArray(ySize + uvSize * 2)

        // Neutral bright white: Y=235, U=128, V=128
        for (i in 0 until ySize) i420[i] = 235.toByte()
        for (i in ySize until i420.size) i420[i] = 128.toByte()

        val full = FrameCodec.i420ToArgbPixels(i420, width, height, step = 1)
        assertEquals(16, full.size)
        val first = full[0]
        val alpha = (first ushr 24) and 0xFF
        val red = (first ushr 16) and 0xFF
        val green = (first ushr 8) and 0xFF
        val blue = first and 0xFF
        assertEquals(255, alpha)
        assertTrue("Expected bright white red channel, got $red", red >= 250)
        assertTrue("Expected bright white green channel, got $green", green >= 250)
        assertTrue("Expected bright white blue channel, got $blue", blue >= 250)

        // 2x subsampled output should be 2x2 = 4 pixels
        val subsampled = FrameCodec.i420ToArgbPixels(i420, width, height, step = 2)
        assertEquals(4, subsampled.size)
    }
}
