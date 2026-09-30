package com.litechat.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whose bubble is this, read from the pixels.
 *
 * This is what replaces the node tree WeChat hides from accessibility services.
 * Note what is deliberately NOT tested here any more: "is this screen a
 * conversation or a contact list". The first version had that check keyed on
 * WeChat's grey chat background, and it silently rejected every conversation
 * with a custom wallpaper - a very common thing to set, and worse than
 * occasionally analysing a screen that turns out to be a list.
 */
class BubbleColourTest {

    private val width = 40
    private val height = 40

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun screen(colour: Int): (Int, Int) -> Int =
        { _x, _y -> colour }

    @Test fun aGreenBubbleIsMine() {
        // WeChat's outgoing bubble colour
        assertEquals("me", BubbleColour.sideOf(
            screen(rgb(149, 236, 105)), width, height, 5, 5, 35, 30))
    }

    @Test fun aWhiteBubbleIsTheirs() {
        assertEquals("other", BubbleColour.sideOf(
            screen(rgb(255, 255, 255)), width, height, 5, 5, 35, 30))
    }

    @Test fun aWallpaperBehindTheBubblesSaysNothing() {
        // A photo wallpaper is not something this knows about, so the caller
        // falls back to geometry instead of being handed a guess. Getting this
        // wrong in the "other" direction would mean answering the user's own
        // words.
        assertNull(BubbleColour.sideOf(
            screen(rgb(90, 60, 40)), width, height, 5, 5, 35, 30))
    }

    @Test fun aDarkBubbleSaysNothing() {
        assertNull(BubbleColour.sideOf(
            screen(rgb(32, 32, 36)), width, height, 5, 5, 35, 30))
    }

    @Test fun theGreyChatBackgroundIsNotABubble() {
        assertNull(BubbleColour.sideOf(
            screen(rgb(237, 237, 237)), width, height, 5, 5, 35, 30))
    }

    @Test fun aDegenerateBoxSaysNothing() {
        assertNull(BubbleColour.sideOf(
            screen(rgb(149, 236, 105)), width, height, 10, 10, 10, 10))
    }

    @Test fun onlyTheBoxInteriorIsSampled() {
        // A green bubble inside a wallpaper: the box covers the bubble, the
        // outside of it does not leak in.
        val sample = { x: Int, y: Int ->
            if (x in 10..30 && y in 10..30) rgb(149, 236, 105) else rgb(20, 20, 20)
        }
        assertEquals("me", BubbleColour.sideOf(sample, width, height, 10, 10, 30, 30))
    }

    // ---- position, for when the colour says nothing ------------------------

    private val area = 1000

    @Test fun aBubbleHuggingTheLeftIsTheirs() {
        assertEquals("other", BubbleColour.sideFromPosition(60, 700, area))
    }

    @Test fun aBubbleHuggingTheRightIsMine() {
        assertEquals("me", BubbleColour.sideFromPosition(300, 940, area))
    }

    @Test fun aLongMessageFromThemIsStillTheirs() {
        // Reaches most of the way across, but its left edge gives it away - the
        // old centre-only rule called this one "me".
        assertEquals("other", BubbleColour.sideFromPosition(60, 900, area))
    }

    @Test fun aLongMessageFromMeIsStillMine() {
        assertEquals("me", BubbleColour.sideFromPosition(300, 940, area))
    }

    @Test fun theEdgeRuleIsCheckedBeforeTheCentre() {
        // Neither edge hugs a side, so the centre decides - and it leans right.
        assertEquals("me", BubbleColour.sideFromPosition(450, 700, area))
    }

    @Test fun aDeadCentreBubbleFallsToTheSaferAnswer() {
        // Ambiguous. "other" is the safer guess: a wrong "me" would mean the app
        // never replies at all.
        assertEquals("other", BubbleColour.sideFromPosition(380, 620, area))
    }

    @Test fun aDegenerateWidthDoesNotCrash() {
        assertEquals("other", BubbleColour.sideFromPosition(0, 0, 0))
    }
}
