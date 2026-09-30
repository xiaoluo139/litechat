package com.litechat.app.core

import android.graphics.Rect

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(val side: String, val text: String)

/**
 * A bubble the node tree can locate but not read (some apps draw their message
 * text themselves). [rect] is in screen coordinates; [side] is what the tree
 * could infer around the bubble. The service OCRs each rect to get the words.
 */
data class BubbleRect(val rect: Rect, val side: String)

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground (see ChatAppAdapter).
 *
 * Adapter contract: `extract` returning null means "not in a chat window".
 * Returning a snapshot whose [messages] is empty means "in a chat window, but
 * the tree holds no text" — that is the OCR fallback's cue.
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null,
    /**
     * False when the capture could not tell the two speakers apart - a whole
     * screen OCR, or a window showing only one direction. The prompt then says
     * so instead of mislabelling the user's own message as the other person's,
     * which is how replies ended up answering the wrong line.
     */
    val sidesKnown: Boolean = true,
    /**
     * Screen rectangle to read with screenshot + OCR when the app's node tree
     * is useless - which is exactly WeChat 8.0.52 and later, where the whole
     * tree comes back as an empty root. Null means "the tree is fine".
     *
     * When set, the service OCRs this band, groups the lines into bubbles and
     * works out who spoke from the bubble colour.
     */
    val ocrRegion: Rect? = null,
    /**
     * True when this conversation has to be read from a screenshot - WeChat's
     * hidden node tree, or an app with no adapter at all.
     *
     * It travels with the snapshot rather than living in a field on the service,
     * because the OCR callback lands several hundred milliseconds later and a
     * mutable flag gets overwritten by the next accessibility event in between.
     * That is exactly how WeChat ended up reading the screen and then doing
     * nothing with it.
     */
    val screenRead: Boolean = false
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** A stable signature of the last few messages, to detect real changes. */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") { "${it.side}:${it.text}" }
}

/** One candidate reply with the model's own confidence, 0..100. */
data class RankedReply(val text: String, val pct: Int)

/** Everything the model told us about one conversation, in a single call. */
data class Suggestion(
    val intent: String?,
    val danger: Int?,
    val advice: String?,
    val replies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null,
    /** 军师 mode only: its read of the situation, shown above the replies. */
    val stance: String? = null,
    /** 军师 mode only: the reference documents that went into this answer. */
    val references: List<String> = emptyList()
)
