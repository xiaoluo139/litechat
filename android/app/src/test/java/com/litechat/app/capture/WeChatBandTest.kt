package com.litechat.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the messages are on a WeChat chat screen.
 *
 * The fixed parts (title bar, input bar) are the same dp on every phone, but the
 * status bar and navigation bar are not: a notch makes the status bar twice as
 * tall, and gesture navigation makes the bottom bar half as tall. Getting these
 * wrong crops into the wrong place, so the platform's real values are used and
 * these tests pin down what they do.
 */
class WeChatBandTest {

    private val density = 2.625f          // a typical 420dpi phone
    private val width = 1080
    private val height = 2400
    private val baselineStatus = (24 * density).toInt()
    private val baselineNav = (48 * density).toInt()

    @Test fun theBandStartsBelowTheTitleBar() {
        val band = wechatMessageBand(width, height, density, baselineStatus, baselineNav)
        assertEquals(0, band.left)
        assertEquals(width, band.right)
        // status bar + 48dp title bar
        assertEquals(baselineStatus + (48 * density).toInt(), band.top)
    }

    @Test fun theBandStopsAboveTheInputAndNavigationBars() {
        val band = wechatMessageBand(width, height, density, baselineStatus, baselineNav)
        assertEquals(height - (54 * density).toInt() - baselineNav, band.bottom)
        assertTrue("the band must still hold some messages", band.height > height / 2)
    }

    @Test fun aTallerNotchStatusBarPushesTheBandDown() {
        val notch = baselineStatus * 2
        val plain = wechatMessageBand(width, height, density, baselineStatus, baselineNav)
        val notched = wechatMessageBand(width, height, density, notch, baselineNav)
        assertEquals(notch - baselineStatus, notched.top - plain.top)
    }

    @Test fun gestureNavigationLetsTheBandReachLower() {
        val gesture = (24 * density).toInt()
        val plain = wechatMessageBand(width, height, density, baselineStatus, baselineNav)
        val gestureBand = wechatMessageBand(width, height, density, baselineStatus, gesture)
        assertEquals(baselineNav - gesture, gestureBand.bottom - plain.bottom)
    }

    @Test fun missingPlatformValuesFallBackToTheBaseline() {
        assertEquals(
            wechatMessageBand(width, height, density, baselineStatus, baselineNav),
            wechatMessageBand(width, height, density))
    }

    @Test fun theTitleStripSitsBetweenTheStatusBarAndTheMessages() {
        val title = wechatTitleBand(width, height, density, baselineStatus)
        val messages = wechatMessageBand(width, height, density, baselineStatus, baselineNav)
        assertEquals(baselineStatus, title.top)
        assertEquals(messages.top, title.bottom)
    }

    @Test fun anAbsurdStatusBarCannotEatTheWholeScreen() {
        val band = wechatMessageBand(width, height, density, height, 0)
        assertTrue("top is clamped", band.top <= height / 3)
        assertTrue("and there is still room below it", band.bottom > band.top)
    }

    @Test fun anEdgeToEdgeWindowKeepsTheBandAboveTheNavigationBar() {
        // Android 15 forces edge to edge on apps that target it, and WeChat does
        // it on modern phones: the window spans the whole display, so its bottom
        // edge is BELOW the navigation bar and the bar has to come off.
        // Measured on an Android 15 emulator: displayMetrics.heightPixels was
        // 2146 for a 2400-pixel display, and the window ran 0..2400.
        val appArea = 2146
        val screen = 2400
        val band = wechatMessageBand(
            width, appArea, density, baselineStatus, baselineNav, screen)
        assertEquals(screen - baselineNav - (54 * density).toInt(), band.bottom)
        assertTrue("reaches lower than the app-area guess did",
            band.bottom > wechatMessageBand(
                width, appArea, density, baselineStatus, baselineNav).bottom)
    }

    @Test fun anInsetWindowIsNotChargedForTheNavigationBarTwice() {
        // The other half of the same problem, and the one that used to break
        // real phones: when the window already stops above the navigation bar
        // (Android 11-14 app, not edge to edge), subtracting the bar again lifts
        // the bottom of the band by a whole navigation bar - the newest message
        // falls out of the crop and the app answers nothing at all.
        val screen = 2400
        val windowBottom = screen - baselineNav          // already inset
        val band = wechatMessageBand(
            width, screen, density, baselineStatus, baselineNav, windowBottom)
        // Same answer as the app-area form: content ends at the window bottom.
        assertEquals(windowBottom - (54 * density).toInt(), band.bottom)
    }

    @Test fun theSmallerOfTheTwoBottomsWins() {
        // Whichever of "the window's edge" and "the display minus the bar" is
        // higher wins, so the band can never reach into the composer.
        val screen = 2400
        val generousWindow = screen                        // edge to edge
        val band = wechatMessageBand(
            width, screen, density, baselineStatus, baselineNav, generousWindow)
        assertTrue("bottom stays above the window edge", band.bottom < screen)
        assertTrue("and above the navigation bar",
            band.bottom <= screen - baselineNav - (54 * density).toInt())
    }

    @Test fun aWindowBottomThatIsNotAWindowIsIgnored() {
        assertEquals(
            wechatMessageBand(width, height, density, baselineStatus, baselineNav),
            wechatMessageBand(width, height, density, baselineStatus, baselineNav, 40))
        assertTrue(
            wechatMessageBand(width, height, density, baselineStatus, baselineNav,
                height * 8).bottom <= height)
    }

    @Test fun anAbsurdNavigationBarHeightIsClamped() {
        val band = wechatMessageBand(width, height, density, baselineStatus, height)
        assertTrue("bottom still inside the screen", band.bottom < height)
        assertTrue("and the band is still usable", band.height > height / 4)
    }
}
