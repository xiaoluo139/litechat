package com.litechat.app.core

/**
 * Reading a chat screen from pixels alone.
 *
 * WeChat hides its message bodies from accessibility services, so the node tree
 * gives nothing to work with and the conversation has to come from a
 * screenshot. One thing is still readable from those pixels and is worth having:
 *
 *  * **who spoke** - WeChat paints the user's own bubbles green and everyone
 *    else's white, which beats guessing from the message text.
 *
 * There deliberately is no "is this even a conversation?" test here. The first
 * version had one, keyed on WeChat's grey chat background - and it silently
 * rejected every conversation with a custom wallpaper, which is a very common
 * thing to set. Guessing wrong in that direction means the app does nothing at
 * all, which is far worse than analysing a screen that turns out to be a list.
 *
 * Every method takes a `sample(x, y)` lambda and plain integers rather than a
 * Bitmap and a Rect, so the rules can be unit-tested without a device - and
 * without pulling android.graphics into the test classpath.
 */
internal object BubbleColour {

    /** Below this, a "green" tint is just noise in the screenshot. */
    private const val GREEN_MARGIN = 24

    /**
     * A near-white bubble belongs to the other person in the light theme.
     * WeChat's incoming bubbles are pure white while the page behind them is
     * #EDEDED, so the threshold sits between the two: grey is background, not
     * somebody's message, and must fall through to the geometry rule.
     */
    private const val BRIGHT = 246
    private const val NEUTRAL_SPREAD = 20

    /**
     * Who spoke, judged from where the bubble sits - the fallback for when the
     * colour says nothing (a wallpaper, a dark theme, a custom skin).
     *
     * The LEFT gap is checked first on purpose. WeChat puts the other person's
     * bubbles hard against the left and the user's own hard against the right,
     * and a long message from either side can reach past the middle - so
     * "which edge does it hug" beats "which side is its centre on", which is
     * what this used to ask and what mislabelled long messages.
     *
     * `left` and `right` are relative to the cropped message area.
     */
    fun sideFromPosition(left: Int, right: Int, areaWidth: Int): String {
        if (areaWidth <= 0) return "other"
        val leftGap = left
        val rightGap = areaWidth - right
        val edge = maxOf(8, (areaWidth * 0.12).toInt())
        if (leftGap <= edge) return "other"
        if (rightGap <= edge) return "me"
        val centre = (left + right) / 2
        val mid = areaWidth / 2
        val band = maxOf(8, (areaWidth * 0.06).toInt())
        return when {
            centre > mid + band -> "me"
            centre < mid - band -> "other"
            // Genuinely ambiguous. "other" is the safer guess: a wrong "me"
            // would mean the app never replies at all.
            else -> "other"
        }
    }

    /**
     * "me" / "other" / null for one bubble, or null when the pixels say nothing.
     *
     * Only a clearly green bubble is called "me", and only a clearly bright
     * neutral one is called "other": dark themes and custom backgrounds fall
     * through to the caller's geometry rule instead of being mislabelled - and a
     * wrong "other" would mean replying to the user's own words.
     */
    fun sideOf(
        sample: (Int, Int) -> Int,
        width: Int,
        height: Int,
        left: Int, top: Int, right: Int, bottom: Int
    ): String? {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (fy in intArrayOf(25, 50, 75)) {
            val y = top + h * fy / 100
            if (y < 0 || y >= height) continue
            for (fx in intArrayOf(15, 35, 50, 65, 85)) {
                val x = left + w * fx / 100
                if (x < 0 || x >= width) continue
                val p = sample(x, y)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0) return null
        val av = r / n
        val ag = g / n
        val ab = b / n
        if (ag - maxOf(av, ab) > GREEN_MARGIN) return "me"
        val darkest = minOf(av, ag, ab)
        val spread = maxOf(av, ag, ab) - darkest
        if (darkest >= BRIGHT && spread <= NEUTRAL_SPREAD) return "other"
        return null
    }

}
