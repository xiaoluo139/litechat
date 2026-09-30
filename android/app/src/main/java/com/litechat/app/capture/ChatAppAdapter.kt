package com.litechat.app.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.litechat.app.BuildConfig
import com.litechat.app.core.BubbleRect
import com.litechat.app.core.ChatSnapshot
import com.litechat.app.core.Msg

/**
 * Per-app capture rules. An adapter turns one messaging app's open chat window
 * into a neutral [ChatSnapshot]; everything downstream (the model call, the
 * overlay, the fill) is app-agnostic.
 *
 * [extract]'s three-way contract:
 * - `null`            → not in this app's chat window (list screen, moments,
 *                       settings…). The service does nothing at all.
 * - messages empty    → in a chat window, but the tree carries no message text.
 *                       The service falls back to screenshot + on-device OCR.
 * - messages non-empty→ normal capture.
 *
 * Adding an app is one class plus one line in [ChatCaptureService.adapters].
 */
interface ChatAppAdapter {
    val pkg: String
    fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot?
}

/** Shared helpers. */
private fun looksLikeTimestamp(t: String): Boolean =
    Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(t) ||
        Regex("""\d+月\d+日""").containsMatchIn(t) ||
        t == "昨天" || t == "今天"

/**
 * Conversation title in the top action bar: the topmost short, roughly centered
 * text above the first message bubble. Constrained so we never grab an in-chat
 * timestamp. Used by QQ as a fallback when its title id is absent, by X, and by
 * the bubble menu's manual OCR capture.
 */
internal fun findTitleInActionBar(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources,
    minCenterRatio: Double = 0.25,
    maxCenterRatio: Double = 0.75
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * minCenterRatio).toInt()
    val maxCenterX = (width * maxCenterRatio).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var best: String? = null
    var bestTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.centerX() in minCenterX..maxCenterX) {
                if (b.top < bestTop) { bestTop = b.top; best = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return best
}

/**
 * Mobile QQ (com.tencent.mobileqq). Nodes are not obfuscated: message bodies are
 * plain TextViews carrying `id/mjn`, so collecting only that id already excludes
 * timestamps, sender nicknames (`id/mjq`) and full-width system notice strips.
 *
 * The whole app lives under one SplashActivity (fragment architecture), so
 * "are we in a chat window" can only be answered by the tree itself — here, by
 * the chat input box `id/input`. No input box → not a chat → null; input box
 * but no `id/mjn` bodies → empty snapshot (OCR fallback's cue).
 *
 * Sender side: QQ pins the avatar to the outer edge of its own side. A long
 * incoming message can push its centre past mid-screen, so we compare which
 * edge of the bubble hugs its avatar column instead of using the centre point.
 */
class QQAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mobileqq"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<Bubble>()
        var firstBubbleTop = Int.MAX_VALUE
        var title: String? = null
        var hasInput = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Bubble(b.top, b.left, b.right, text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            if (!hasInput && id == INPUT_ID) hasInput = true
            if (id == TITLE_ID && title == null) text?.let { if (it.isNotBlank()) title = it }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (bubbles.isEmpty() && !hasInput) return null

        if (title == null) title = findTitleInActionBar(root, firstBubbleTop, width, res)
        if (bubbles.isEmpty()) return ChatSnapshot(title, emptyList())

        val avatarEdge = (width * 0.13).toInt()
        bubbles.sortBy { it.top }
        val msgs = bubbles.map { b ->
            val dl = kotlin.math.abs(b.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - b.right)
            Msg(if (dr < dl) "me" else "other", b.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Bubble(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        private const val BUBBLE_ID = "com.tencent.mobileqq:id/mjn"
        private const val TITLE_ID = "com.tencent.mobileqq:id/371"
        private const val INPUT_ID = "com.tencent.mobileqq:id/input"
    }
}

/** Only my own Feishu bubbles carry the sent/read strip. */
private const val FEISHU_READ_STATE_ID = "time_read_state_container_align_bubble"

/** WeChat's message bubble container. */
private const val WECHAT_BUBBLE_ID = "com.tencent.mm:id/bkl"

/** Chinese sentence punctuation - a real message line has it, a title never does. */
private val WECHAT_TITLE_EXCLUDE_PUNCT = Regex("""[，。？！、]""")

/** A WeChat group title's "(N)" member-count suffix, half- or full-width. */
private val WECHAT_GROUP_COUNT_SUFFIX = Regex("""[（(]\d+[）)]""")

/**
 * WeChat conversation title. A group's pinned announcement or a stray message
 * can sit in the same "topmost, short, centred" band [findTitleInActionBar]
 * searches, so a candidate must not read like a sentence (no Chinese
 * punctuation) and must sit above the first bubble; among what is left, a group
 * title's trailing "(N)" member count wins when present.
 */
internal fun findWeChatTitle(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * 0.25).toInt()
    val maxCenterX = (width * 0.75).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var bestPlain: String? = null
    var bestPlainTop = Int.MAX_VALUE
    var bestCounted: String? = null
    var bestCountedTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text) &&
            !WECHAT_TITLE_EXCLUDE_PUNCT.containsMatchIn(text)
        ) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.bottom < firstBubbleTop &&
                b.centerX() in minCenterX..maxCenterX
            ) {
                if (WECHAT_GROUP_COUNT_SUFFIX.containsMatchIn(text)) {
                    if (b.top < bestCountedTop) { bestCountedTop = b.top; bestCounted = text }
                } else if (b.top < bestPlainTop) { bestPlainTop = b.top; bestPlain = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return bestCounted ?: bestPlain
}

/**
 * WeChat (com.tencent.mm).
 *
 * WeChat 8.0.52+ hides message bodies from ordinary accessibility services, so
 * this adapter has three possible outcomes and the service uses all of them:
 *
 *   * not a chat window → null;
 *   * a chat window whose bubbles carry text (older builds, or a service the
 *     disguise in SelectToSpeakService gets through) → real messages;
 *   * a chat window whose bubbles are geometry only → an empty message list
 *     plus [ChatSnapshot.bubbleRects], which makes the service OCR each bubble
 *     rect individually. That is the path that makes WeChat work at all.
 *
 * Side always comes from the bubble's horizontal centre: WeChat right-aligns
 * the user's own messages and left-aligns everyone else's.
 */
class WeChatAdapter(override val pkg: String = "com.tencent.mm") : ChatAppAdapter {

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<Bubble>()
        var firstBubbleTop = Int.MAX_VALUE
        var isChat = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            if (node.viewIdResourceName == WECHAT_BUBBLE_ID) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.width() > 4 && b.height() > 4) {
                    isChat = true
                    bubbles.add(Bubble(b.top, b.bottom, b.left, b.right,
                        node.text?.toString() ?: ""))
                    if (b.top < firstBubbleTop) firstBubbleTop = b.top
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (isChat) {
            val title = findWeChatTitle(root, firstBubbleTop, width, res)
            val ordered = bubbles.sortedBy { it.top }
            val rects = ordered.map {
                BubbleRect(Rect(it.left, it.top, it.right, it.bottom),
                    wechatSide(it.left, it.right, width))
            }
            val msgs = ordered.filter { it.text.isNotBlank() }
                .map { Msg(wechatSide(it.left, it.right, width), it.text) }
            return ChatSnapshot(title, msgs, rects)
        }

        // WeChat 8.0.52+ hands accessibility services an empty root: no bubble
        // node, no text, nothing. Returning null here (as the first version did)
        // meant "not in a chat window", so the screenshot fallback never ran and
        // WeChat simply did not work at all. Instead we declare the screen
        // readable by OCR and let the service do the seeing.
        return ChatSnapshot(
            title = null,
            messages = emptyList(),
            note = WECHAT_OCR_NOTE,
            sidesKnown = false,
            screenRead = true,
            ocrRegion = with(res.displayMetrics) {
                val band = wechatMessageBand(widthPixels, heightPixels, density,
                    systemBarHeight(res, "status_bar_height", 24),
                    systemBarHeight(res, "navigation_bar_height", 48),
                    windowBottom(root))
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("LITECHAT", "wechat band=${band.toRect()} rootBottom=" +
                        windowBottom(root) + " display=${widthPixels}x$heightPixels" +
                        " density=$density")
                }
                band.toRect()
            }
        )
    }

    /** The window's own bottom edge in screen pixels, or -1 when it says nothing. */
    private fun windowBottom(root: AccessibilityNodeInfo): Int {
        val b = Rect()
        return runCatching {
            root.getBoundsInScreen(b)
            if (b.bottom > 0 && b.width() > 0 && b.height() > 0) b.bottom else -1
        }.getOrDefault(-1)
    }

    private data class Bubble(
        val top: Int, val bottom: Int, val left: Int, val right: Int, val text: String
    )
}

/**
 * Who sent a WeChat bubble: the user's own messages are right-aligned, everyone
 * else's left-aligned, so the centre of the bubble says which side it is on.
 * Split out so the rule can be unit-tested - getting it wrong is exactly how a
 * reply ends up answering the user's own words.
 */
internal fun wechatSide(left: Int, right: Int, width: Int): String =
    if ((left + right) / 2 > width / 2) "me" else "other"

/** Shown on the panel when WeChat had to be read from a screenshot. */
internal const val WECHAT_OCR_NOTE = "微信隐藏了消息文字，这里用截屏识别"

/**
 * A rectangle in SCREEN pixels.
 *
 * Deliberately not `android.graphics.Rect`: that class is a stub on the JVM unit
 * test classpath, so any rule expressed with it cannot actually be tested - and
 * these rules decide where a screenshot gets cropped on every phone.
 */
internal data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun toRect(): Rect = Rect(left, top, right, bottom)
}

/**
 * Where the messages are on a WeChat chat screen, in SCREEN coordinates.
 *
 * The top is the status bar plus the title bar, the bottom is the input strip;
 * both are fixed dp heights in WeChat, so they are converted with the display
 * density rather than guessed as a fraction of the screen.
 *
 * [height] is what the caller can measure from `displayMetrics`, which for an
 * accessibility service is the area a normal app may use - the screen minus the
 * status and navigation bars. [windowBottomPx] is the bottom edge of the window
 * the node tree handed us, which is taller than that when the app draws edge to
 * edge. Both are needed: using the app-area height alone left the band a status
 * bar short on an edge-to-edge screen, so the newest message - the one line
 * worth reading - fell just below the crop and the app looked like it could see
 * a conversation but never the line it had to answer.
 */
internal fun wechatMessageBand(
    width: Int,
    height: Int,
    density: Float,
    statusBarPx: Int = -1,
    navBarPx: Int = -1,
    windowBottomPx: Int = -1
): ScreenBox {
    // The status bar and navigation bar are the two things that differ between
    // phones - a notch or a punch-hole makes the status bar twice as tall as the
    // 24dp baseline, and gesture navigation makes the bottom bar half as tall as
    // the 48dp baseline. The caller passes the device's real values; the
    // fallbacks only apply when the platform will not tell us.
    val statusBar = if (statusBarPx > 0) statusBarPx else (24 * density).toInt()
    val titleBar = (48 * density).toInt()
    val inputBar = (54 * density).toInt()
    // Plausible only: a real window bottom is at least half the screen and not
    // more than half again as tall as what the platform reported.
    val usableWindow =
        if (windowBottomPx in (height / 2)..(height * 3 / 2)) windowBottomPx else 0
    val displayHeight = maxOf(height, usableWindow)
    // A navigation bar is never a big share of the screen. The per-orientation
    // resource has been seen returning the landscape value (145dp on a device
    // whose real bar is 48dp), and that alone used to swallow the bottom of the
    // conversation.
    val navBar = (if (navBarPx > 0) navBarPx else (48 * density).toInt())
        .coerceIn(0, (displayHeight * 0.12f).toInt().coerceAtLeast(1))
    val top = (statusBar + titleBar).coerceAtMost(height / 3)
    // Where the app's content ends. Two honest answers disagree per device:
    //   * the window's own bottom edge - correct when the window is inset above
    //     the navigation bar (most apps on Android 11-14), and
    //   * the display minus the navigation bar - correct when the window spans
    //     the display (edge to edge, which Android 15 forces on apps that target
    //     it, and which WeChat does on modern phones).
    // Taking the smaller of the two is right in both cases and can never reach
    // below the conversation into the composer.
    val contentBottom = when {
        usableWindow > 0 -> minOf(usableWindow, displayHeight - navBar)
        else -> displayHeight - navBar
    }
    val bottom = (contentBottom - inputBar).coerceAtLeast(height / 2)
    return ScreenBox(0, top, width, bottom)
}

/** The title strip of a WeChat chat screen, in SCREEN coordinates. */
internal fun wechatTitleBand(
    width: Int,
    height: Int,
    density: Float,
    statusBarPx: Int = -1
): ScreenBox {
    val statusBar = if (statusBarPx > 0) statusBarPx else (24 * density).toInt()
    val titleBar = (48 * density).toInt()
    return ScreenBox(0, statusBar, width, statusBar + titleBar)
}

/**
 * Ask the platform for a system bar's real height.
 *
 * `status_bar_height` and `navigation_bar_height` are `@hide` framework
 * resources, but every ROM ships them and they are the only way to learn the
 * truth on a device with a notch or with gesture navigation, where a hardcoded
 * 24dp/48dp is simply wrong.
 */
internal fun systemBarHeight(res: Resources, name: String, fallbackDp: Int): Int {
    val id = res.getIdentifier(name, "dimen", "android")
    if (id > 0) {
        val px = runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0)
        if (px > 0) return px
    }
    return (fallbackDp * res.displayMetrics.density).toInt()
}

/** Does this bubble carry the "sent / read" strip that only mine have? */
private fun feishuHasReadState(bubble: AccessibilityNodeInfo): Boolean {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(bubble)
    var guard = 0
    while (stack.isNotEmpty() && guard < 400) {
        guard++
        val node = stack.removeLast()
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith(FEISHU_READ_STATE_ID)) return true
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return false
}

/**
 * Feishu bubble rectangles in SCREEN coordinates, top to bottom, with the side
 * the read-receipt strip implies.
 *
 * Split out of [FeishuAdapter.extract] so the capture service can call it again
 * from inside the screenshot callback: several hundred ms pass between reading
 * the tree and the picture arriving, and a list that scrolled in between would
 * make us crop the wrong rows.
 */
internal fun collectFeishuBubbleRects(
    root: AccessibilityNodeInfo,
    res: Resources
): List<BubbleRect> {
    val height = res.displayMetrics.heightPixels
    val topBand = (height * 0.14).toInt()      // action bar + tab row
    val bottomBand = (height * 0.84).toInt()   // input box + keyboard
    val rects = ArrayList<BubbleRect>()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var guard = 0
    while (stack.isNotEmpty() && guard < 6000) {
        guard++
        val node = stack.removeLast()
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith(":id/bubble_content_container")) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.width() > 0 && b.height() > 0 && b.bottom > topBand && b.top < bottomBand) {
                rects.add(BubbleRect(Rect(b), if (feishuHasReadState(node)) "me" else "other"))
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    rects.sortBy { it.rect.top }
    return rects
}

/**
 * Feishu / Lark (com.ss.android.lark). Nodes are not obfuscated, but the message
 * text is DRAWN, not laid out as views: the tree gives us bubble rectangles and
 * chrome, and almost never a body. So this adapter is a hybrid — it reports what
 * it can read as messages (usually nothing) and always reports the bubble
 * geometry in [ChatSnapshot.bubbleRects] for the service to OCR rect by rect.
 *
 * Side: Feishu left-aligns everyone, so geometry says nothing. What does say
 * something is the read-receipt strip that only hangs off MY bubbles.
 */
class FeishuAdapter : ChatAppAdapter {
    override val pkg = "com.ss.android.lark"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val topBand = (height * 0.14).toInt()
        val bottomBand = (height * 0.84).toInt()

        var isChat = false
        var title: String? = null
        val items = ArrayList<Triple<Int, Int, String>>()
        val rects = collectFeishuBubbleRects(root, res)

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName ?: ""
            if (id.endsWith(":id/message") || id.endsWith(":id/bubble_content_container") ||
                id.endsWith(":id/kb_rich_text_content")) isChat = true
            if (id.endsWith(":id/group_name")) node.text?.toString()?.let { if (title == null) title = it }

            val text = node.text?.toString()
            val cls = node.className?.toString()
            if (!text.isNullOrBlank() && cls == "android.widget.TextView" &&
                !isChrome(id) && !looksLikeTimestamp(text)
            ) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.top in (topBand + 1) until bottomBand) {
                    items.add(Triple(b.top, b.centerX(), text.trim()))
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (!isChat) return null

        if (items.isEmpty()) return ChatSnapshot(title, emptyList(), rects)

        items.sortBy { it.first }
        val msgs = items.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs, rects)
    }

    /** Non-message UI text to skip: title, sender name, time, system notices,
     *  the input EditText. Bodies have no id (bare TextView) so they pass. */
    private fun isChrome(id: String): Boolean =
        id.endsWith(":id/group_name") ||
            id.endsWith(":id/name_tv") ||
            id.endsWith(":id/date_tv") ||
            id.endsWith(":id/system_label") ||
            id.endsWith(":id/kb_rich_text_content") ||
            id.endsWith(":id/thread_title_tv") ||
            id.endsWith(":id/thread_subtitle_tv")
}

/** Trailing "8:11 上午" / "10:29 下午" / "8:11 AM" stamp X glues onto a message. */
private val X_TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}\s*(上午|下午|AM|PM|am|pm)?$""")

/** X uses "。" as a field separator, so a message can end with a run of them. */
private val X_TRAILING_DOTS = Regex("""。+$""")

/**
 * Split one X DM row's contentDescription into (sender, body).
 *
 * "你：你这个说的就是那个虚拟人物，是吗？。8:11 上午。Read。"
 *      → ("你", "你这个说的就是那个虚拟人物，是吗？")
 *
 * The sender is everything before the FIRST separator (full-width "：" in the
 * Chinese UI, ": " as a rough fallback elsewhere); the rest is the body plus
 * chrome — the read receipt, the timestamp, and the "。" gluing them on — which
 * is stripped from the tail in that order. Null when there is no separator or
 * nothing is left.
 */
private fun parseXDesc(desc: String): Pair<String, String>? {
    val full = desc.indexOf('：')
    val half = desc.indexOf(": ")
    val cut: Int
    val skip: Int
    when {
        full >= 0 && (half < 0 || full <= half) -> { cut = full; skip = 1 }
        half >= 0 -> { cut = half; skip = 2 }
        else -> return null
    }
    val sender = desc.substring(0, cut).trim()
    var body = desc.substring(cut + skip).trim()
    for (tail in arrayOf("Read。", "Read", "已读。", "已读")) {
        if (body.endsWith(tail)) { body = body.removeSuffix(tail).trim(); break }
    }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    X_TAIL_TIME.find(body)?.let { body = body.substring(0, it.range.first).trim() }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    if (sender.isEmpty() || body.isEmpty()) return null
    return sender to body
}

/**
 * X / Twitter (com.twitter.android) direct messages.
 *
 * The DM thread is Compose UI: each message is a bare `android.view.View` with
 * NO resource-id, full screen width and empty text — the whole message lives in
 * contentDescription. An attachment row nests the quoted post's own TextViews;
 * we only take the row View's own desc, never its children.
 *
 * Every screen runs under the same MainActivity, so "are we in a DM thread" can
 * only be answered by the tree: a thread has the message EditText AND either a
 * message-row shape or the "私信" placeholder, while the list has neither.
 *
 * Side comes from the sender label ("你" / "You"), not geometry — every row is
 * full width no matter who spoke.
 */
class XAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val rows = ArrayList<Row>()
        var firstRowTop = Int.MAX_VALUE
        var hasInput = false
        var hasMessageRowShape = false
        var hasDmLabel = false
        var hasNewDmMarker = false
        var sawListHeading = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString()
            if (!hasInput && (node.isEditable || cls == "android.widget.EditText")) hasInput = true

            val desc = node.contentDescription?.toString()
            if (desc == "新私信" || desc == "New message") hasNewDmMarker = true
            if (cls == "android.view.View" && !desc.isNullOrBlank() && !desc.contains(", @")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.left == 0 && b.right == width) {
                    if (desc.contains('：') || desc.contains(": ")) hasMessageRowShape = true
                    val parsed = parseXDesc(desc)
                    if (parsed != null) {
                        rows.add(Row(b.top, parsed.first, parsed.second))
                        if (b.top < firstRowTop) firstRowTop = b.top
                    }
                }
            }

            if (cls == "android.widget.TextView") {
                val text = node.text?.toString()?.trim()
                if (text == "私信" || text == "Message" || text == "发送私信") hasDmLabel = true
                if (text == "聊天" || text == "Messages") sawListHeading = true
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (hasNewDmMarker || (sawListHeading && rows.isEmpty())) return null
        if (!hasInput || !(hasMessageRowShape || hasDmLabel)) return null

        // X left-aligns the thread title, so widen the "roughly centered" band.
        val title = findTitleInActionBar(root, firstRowTop, width, res, 0.15, 0.85)
        if (rows.isEmpty()) return ChatSnapshot(title, emptyList())
        rows.sortBy { it.top }
        val msgs = rows.map { Msg(if (it.sender == "你" || it.sender == "You") "me" else "other", it.text) }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val sender: String, val text: String)
}
