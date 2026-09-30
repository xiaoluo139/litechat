package com.litechat.app.capture

import com.litechat.app.capture.ocr.OcrScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OCR enlargement cap: bigger text for ML Kit, but bounded memory. */
class OcrScaleTest {

    @Test fun aNormalMessageBandGetsTheFullUpscale() {
        // 1080 x 1800 is what a 1080p phone's message band comes to.
        assertEquals(OcrScale.UPSCALE, OcrScale.factor(1080, 1800), 0.001f)
    }

    @Test fun aHugeCropIsScaledBackToStayInsideThePixelBudget() {
        val f = OcrScale.factor(2160, 4800)
        assertTrue("should not enlarge a 10M-pixel bitmap 2x", f < OcrScale.UPSCALE)
        val pixels = (2160 * f).toLong() * (4800 * f).toLong()
        assertTrue("stayed inside the budget: $pixels",
            pixels <= OcrScale.MAX_UPSCALED_PIXELS + 1_000_000)
    }

    @Test fun neverShrinksBelowOneToOne() {
        assertEquals(1f, OcrScale.factor(8000, 8000), 0.001f)
    }

    @Test fun degenerateSizesDoNotCrash() {
        assertEquals(1f, OcrScale.factor(0, 0), 0.001f)
        assertEquals(1f, OcrScale.factor(-4, 100), 0.001f)
    }
}
