package com.litechat.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "put the floating window back" decision.
 *
 * Reported symptom: "悬浮窗按钮总是不见，还经常点不开". One real cause is that
 * the ROM takes the window away (the view is detached) while the app's own
 * state still says it is showing, so nothing ever re-adds it.
 */
class OverlayLivenessTest {

    private fun decide(
        wanted: Boolean = true,
        attached: Boolean = false,
        enabled: Boolean = true,
        canOverlay: Boolean = true,
        force: Boolean = false,
        nowMs: Long = 10_000,
        hiddenUntilMs: Long = 0,
        lastAttemptMs: Long = 0,
    ) = OverlayLiveness.shouldRespawn(
        wanted, attached, enabled, canOverlay, force, nowMs, hiddenUntilMs, lastAttemptMs)

    @Test
    fun aWindowThatIsOnScreenIsLeftAlone() {
        assertFalse(decide(attached = true, lastAttemptMs = 0))
    }

    @Test
    fun aWindowTheSystemTookAwayIsPutBack() {
        // Detached, wanted, permission still granted, long enough since the
        // last attempt: this is the "按钮不见了" case.
        assertTrue(decide(attached = false, lastAttemptMs = 1_000))
    }

    @Test
    fun nothingIsShownWhenTheAssistantIsOffOrNobodyWantsIt() {
        assertFalse(decide(wanted = false, lastAttemptMs = 1_000))
        assertFalse(decide(enabled = false, lastAttemptMs = 1_000))
    }

    @Test
    fun thePermissionIsCheckedBeforeTrying() {
        // addView without the overlay permission throws; do not even try.
        assertFalse(decide(canOverlay = false, lastAttemptMs = 1_000))
    }

    @Test
    fun aBlockedRomIsNotHammered() {
        // Every attempt failed a moment ago: wait for the gap to pass.
        assertFalse(decide(nowMs = 10_000, lastAttemptMs = 9_500))
        assertTrue(decide(nowMs = 10_000, lastAttemptMs = 8_999))
    }

    @Test
    fun aUserRequestedQuietIsObeyedAndThenExpires() {
        assertFalse(decide(nowMs = 10_000, hiddenUntilMs = 60_000, lastAttemptMs = 1_000))
        assertTrue(decide(nowMs = 60_000, hiddenUntilMs = 60_000, lastAttemptMs = 1_000))
    }

    @Test
    fun anExplicitShowFromTheUserBeatsBothBrakes() {
        // "悬浮窗不见了？点这里重新显示" must work immediately, even while a
        // hide timer is running and even right after a failed attempt.
        assertTrue(decide(force = true, hiddenUntilMs = 60_000, nowMs = 10_000,
            lastAttemptMs = 10_000))
        // ...but it still cannot conjure a window without the permission.
        assertFalse(decide(force = true, canOverlay = false))
    }
}
