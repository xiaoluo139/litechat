package com.litechat.app.core

/**
 * When the floating window has to be put back on screen.
 *
 * The assistant's window is one the system is free to take away: aggressive
 * ROMs (MIUI / HyperOS, EMUI, ColorOS) drop `TYPE_APPLICATION_OVERLAY` windows
 * of apps they decide are idle, and toggling the 悬浮窗 permission does the
 * same. Nothing tells the app that it happened - the view is merely detached -
 * so the controller has to notice on its own and re-add the window. This is
 * that decision, kept pure so the failure mode can be pinned down by tests.
 *
 * The failure it guards against is "the bubble is gone and never comes back":
 * the app used to treat "we once added a view" as "it is on screen", so every
 * "park a bubble only if there is none" check skipped the re-add for the rest
 * of the service's life.
 *
 * The two brakes are deliberate: a user who just asked for quiet is obeyed, and
 * a ROM that keeps refusing `addView` is not hammered once a second.
 */
object OverlayLiveness {

    /** Never attempt two re-adds closer together than this. */
    const val MIN_GAP_MS = 1000L

    fun shouldRespawn(
        wanted: Boolean,        // the service still wants a bubble on screen
        attached: Boolean,      // the view really is on screen right now
        enabled: Boolean,       // the assistant is switched on
        canOverlay: Boolean,    // the 悬浮窗 permission is still granted
        force: Boolean,         // an explicit "show it again" from the user
        nowMs: Long,
        hiddenUntilMs: Long,
        lastAttemptMs: Long,
        minGapMs: Long = MIN_GAP_MS,
    ): Boolean {
        if (attached) return false              // nothing to do
        if (!canOverlay) return false           // adding it would just throw
        if (force) return true                  // the user asked for it back
        if (!wanted || !enabled) return false   // nobody wants a bubble
        if (nowMs < hiddenUntilMs) return false // "隐藏 10 分钟" is still running
        return nowMs - lastAttemptMs >= minGapMs
    }
}
