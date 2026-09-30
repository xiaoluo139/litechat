package com.litechat.app.capture

import com.litechat.app.capture.ocr.resolveCaptureGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a screenshot sits on screen.
 *
 * This mapping is what every later coordinate is built on - the crop for the
 * message band, the bubble boxes, the pixels the speaker is read from. Getting
 * it wrong does not produce a bad answer, it produces no answer at all: every
 * bubble is read from the wrong part of the screen, all of them come back as
 * "mine", and the app never sees a new incoming message.
 *
 * The failure it pins down: a full-screen picture (1080x2400) that the window
 * bounds claimed started at x=666 - an app-switch animation caught mid-slide.
 */
class CaptureGeometryTest {

    @Test fun aFullScreenPictureIsAlwaysAtTheOrigin() {
        val g = resolveCaptureGeometry(1080, 2400, 1080, 2400, 0, 0, 0, 0)
        assertEquals(0, g.originX)
        assertEquals(0, g.originY)
        assertEquals(1f, g.scaleX, 0.001f)
        assertEquals(1f, g.scaleY, 0.001f)
    }

    @Test fun aWindowOffsetThatCannotFitTheDisplayIsIgnored() {
        // The real one from a Pixel/Android 15 emulator: the window bounds say
        // the window starts at x=666 and runs to 1746 on a 1080-wide screen.
        val g = resolveCaptureGeometry(1080, 2400, 1080, 2400, 666, 0, 1746, 2400)
        assertEquals("a picture as wide as the screen cannot start 666px right", 0, g.originX)
        assertEquals(0, g.originY)
        assertEquals(1f, g.scaleX, 0.001f)
    }

    @Test fun aNegativelyOffsetWindowIsIgnored() {
        val g = resolveCaptureGeometry(1080, 2400, 1080, 2400, -40, 0, 1040, 2400)
        assertEquals(0, g.originX)
    }

    @Test fun aDegenerateWindowBoundsAreIgnored() {
        val g = resolveCaptureGeometry(1080, 2400, 1080, 2400, 0, 0, 0, 0)
        assertEquals(0, g.originX)
        assertEquals(1f, g.scaleX, 0.001f)
    }

    @Test fun aPictureTallerThanTheDisplayKeepsNoVerticalOffset() {
        // Downvote the offset on the axis that contradicts, keep the other one.
        val g = resolveCaptureGeometry(1080, 2400, 1080, 2400, 0, 254, 1080, 2400)
        assertEquals(0, g.originX)
        assertEquals("the picture covers the screen height, so y=254 is wrong", 0, g.originY)
    }

    @Test fun aRealInsetWindowKeepsItsOrigin() {
        // A window that is genuinely shorter (inset above the navigation bar):
        // 1080 x 1879 starting at y=254.
        val g = resolveCaptureGeometry(1080, 1879, 1080, 2400, 0, 254, 1080, 2133)
        assertEquals(0, g.originX)
        assertEquals(254, g.originY)
        assertEquals(1f, g.scaleX, 0.001f)
        assertEquals(1f, g.scaleY, 0.001f)
    }

    @Test fun aScaledWindowShotMapsBackToScreenPixels() {
        // A 540x1200 window delivered as a 1080x2400 picture: 2x on both axes.
        val g = resolveCaptureGeometry(1080, 2400, 1080, 2400, 0, 0, 540, 1200)
        assertEquals(2f, g.scaleX, 0.001f)
        assertEquals(2f, g.scaleY, 0.001f)
        assertEquals(0, g.originX)
    }
}
