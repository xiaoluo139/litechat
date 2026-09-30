package com.litechat.app.capture.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/** One recognized text line. [bounds] is already in SCREEN coordinates. */
data class OcrLine(val text: String, val bounds: Rect)

/**
 * Text recognition over a screenshot.
 *
 * [region] is in BITMAP coordinates (the caller scales node rects by the
 * screenshot's scaleX/scaleY); null means the whole bitmap. The callback is
 * delivered on the main thread and always fires — an engine failure comes back
 * as an empty list, never as an exception on the caller's thread.
 */
interface OcrEngine {
    fun recognize(bitmap: Bitmap, region: Rect?, cb: (List<OcrLine>) -> Unit)
}
