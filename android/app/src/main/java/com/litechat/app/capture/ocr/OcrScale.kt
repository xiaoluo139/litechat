package com.litechat.app.capture.ocr

import kotlin.math.sqrt

/**
 * How much to enlarge a screenshot before OCR.
 *
 * Chat text is small and ML Kit reads it noticeably better enlarged - the same
 * finding the desktop build made when it moved to a 3x crop. The factor is
 * capped by pixel count so a tall crop can never allocate a bitmap big enough
 * to hurt on a low-end phone.
 *
 * Pure, so the cap can be unit-tested without a device.
 */
internal object OcrScale {

    /** Aim for this much enlargement... */
    const val UPSCALE = 2f

    /** ...but never allocate more than this many pixels doing it. */
    const val MAX_UPSCALED_PIXELS = 12_000_000L

    /**
     * Debug builds only: forces a scale so the two settings can be compared on
     * a real device without a rebuild (see `Prefs`/`ChatCaptureService`). The
     * release build always uses [UPSCALE]; nothing writes this field there.
     */
    @Volatile var debugOverride: Float = 0f

    fun factor(width: Int, height: Int): Float {
        if (debugOverride > 0f) return debugOverride
        if (width <= 0 || height <= 0) return 1f
        val room = sqrt(MAX_UPSCALED_PIXELS.toDouble() / (width.toDouble() * height)).toFloat()
        return minOf(UPSCALE, maxOf(1f, room))
    }
}
