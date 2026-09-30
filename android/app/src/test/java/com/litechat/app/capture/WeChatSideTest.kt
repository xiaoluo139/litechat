package com.litechat.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WeChat right-aligns the user's own bubbles. This rule decides which line the
 * reply is aimed at, so it is worth pinning down.
 */
class WeChatSideTest {

    private val screenWidth = 1200

    @Test fun rightAlignedBubblesAreMine() {
        // a short bubble hugging the right edge
        assertEquals("me", wechatSide(900, 1150, screenWidth))
    }

    @Test fun leftAlignedBubblesAreTheirs() {
        assertEquals("other", wechatSide(60, 520, screenWidth))
    }

    @Test fun aLongIncomingMessageIsStillTheirs() {
        // WeChat lets their long messages wrap almost to the right edge, so the
        // left edge is what identifies them
        assertEquals("other", wechatSide(60, 1120, screenWidth))
    }

    @Test fun aLongOutgoingMessageIsStillMine() {
        assertEquals("me", wechatSide(200, 1150, screenWidth))
    }

    @Test fun exactlyCentredFallsToThem() {
        // Ambiguous, and the safer answer: nothing is drafted towards the user's
        // own words.
        assertEquals("other", wechatSide(300, 900, screenWidth))
    }
}
