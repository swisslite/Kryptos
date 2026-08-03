package com.kryptos.android

import com.kryptos.android.core.ImageBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverScaleTests {

    private fun scaled(width: Int, height: Int): Long {
        val sample = ImageBridge.sampleSizeFor(width, height, ImageBridge.COVER_TARGET_PIXELS)
        return (width / sample).toLong() * (height / sample).toLong()
    }

    @Test
    fun keepsEveryCameraUnderTheTarget() {
        val cameras = listOf(
            4000 to 3000,
            8064 to 6048,
            8160 to 6120,
            9248 to 6936,
            12000 to 9000,
            16320 to 12240,
        )
        for ((w, h) in cameras) {
            val pixels = scaled(w, h)
            assertTrue("$w x $h -> $pixels", pixels <= ImageBridge.COVER_TARGET_PIXELS)
            assertTrue("$w x $h -> $pixels", pixels >= 3_000_000L)
        }
    }

    @Test
    fun leavesSmallPhotosUntouched() {
        assertEquals(1, ImageBridge.sampleSizeFor(4000, 3000, ImageBridge.COVER_TARGET_PIXELS))
        assertEquals(1, ImageBridge.sampleSizeFor(1920, 1080, ImageBridge.COVER_TARGET_PIXELS))
    }

    @Test
    fun handlesDegenerateSizes() {
        assertEquals(1, ImageBridge.sampleSizeFor(0, 0, ImageBridge.COVER_TARGET_PIXELS))
        assertEquals(1, ImageBridge.sampleSizeFor(-5, 10, ImageBridge.COVER_TARGET_PIXELS))
    }
}
