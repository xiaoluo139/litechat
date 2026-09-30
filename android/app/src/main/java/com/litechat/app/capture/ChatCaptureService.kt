package com.litechat.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.litechat.app.capture.ocr.MlKitOcr
import com.litechat.app.capture.ocr.OcrLine
import com.litechat.app.capture.ocr.ScreenCapture
import com.litechat.app.BuildConfig
import com.litechat.app.core.BubbleRect
import com.litechat.app.core.BubbleColour
import com.litechat.app.core.ChatSnapshot
import com.litechat.app.core.MessageKeys
import com.litechat.app.core.Msg
import com.litechat.app.core.Prefs
import com.litechat.app.llm.HttpJson
import com.litechat.app.llm.LlmClient
import com.litechat.app.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service. It reads whichever adapted chat app is in the
 * foreground, detects a new incoming message from the other person, runs one
 * model call off the main thread, and drives the floating overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /**
     * Adapted chat apps, keyed by package name.
     *
     * WeChat is wired in but the user can switch it off (`wechatEnabled`): its
     * message bodies are hidden from ordinary accessibility services, so reading
     * it means screenshot + OCR, which is the thing WeChat's own risk control
     * watches for. Off, the app says so once and never touches it.
     */
    private val adapters =
        buildList {
            add(QQAdapter())
            add(XAdapter())
            add(FeishuAdapter())
            // The real WeChat is read from screenshots (its node tree is empty).
            add(WeChatAdapter())
            if (BuildConfig.DEBUG) {
                // A throwaway app that draws a WeChat-shaped chat screen, so the
                // screenshot path can be verified on a device without WeChat.
                add(WeChatAdapter(pkg = "com.litechat.testchat"))
            }
        }.associateBy { it.pkg }

    /** WeChat is only read when the user has not opted out. */
    private fun wechatBlocked(pkg: String?): Boolean = pkg == PKG_WECHAT && !prefs.wechatEnabled

    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }

    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    /** Last header name we read, and the app it belonged to (see noteOcrConversation). */
    private var lastOcrTitle: String = ""
    private var lastOcrTitlePkg: String = ""
    /** Person we already explained the "locked to somebody else" state for. */
    private var lastPinnedSkipTitle: String = ""
    /** Consecutive automatic WeChat reads that came back with no text at all. */
    private var wechatEmptyReads = 0
    private var analyzing = false
    /**
     * Fingerprint of the newest incoming message we have already asked the model
     * about. Only a change here is worth another round trip: a redrawn bubble, a
     * new timestamp, or the user's own message must not restart the analysis,
     * which is what used to keep the panel on "分析中…" indefinitely.
     */
    private var lastIncomingKey: String = ""

    /**
     * Debug builds only: a receiver that tears the floating window away, the way
     * a ROM does. It exists so "the bubble disappears and never comes back" can
     * be reproduced on demand (see [OverlayController.debugDetachWindow] and
     * tools/verify_overlay_selfheal.py). Release builds never register it.
     */
    private val debugDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DEBUG_DETACH_ACTION -> overlay?.debugDetachWindow()
                // "What does the reader actually see?" - dumps the next picture
                // to files/ocr_shot.png so it can be looked at on a host.
                DEBUG_DUMP_ACTION -> debugDumpShot = true
            }
        }
    }
    private var debugDetachRegistered = false

    /** Debug builds only: write the next captured picture to files/ocr_shot.png. */
    private var debugDumpShot = false
    /** A message arrived while a request was in flight; take it when it lands. */
    private var queuedAnalysis = false
    private val session = ConversationSession()
    private val analysisTasks = ArrayList<Future<*>>()
    private var destroyed = false
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            // The app's "悬浮窗不见了？点这里重新显示" button: an explicit request
            // beats the hide timer and re-adds the window right away.
            key == Prefs.K_RESPAWN_OVERLAY -> main.post { overlay?.respawn() }
            key == "enabled" || key == "whitelist" -> main.post {
                leaveConversation()
                overlay?.hide()
            }
        }
    }

    /** Invalidate callbacks before cancelling workers; interruption alone is not a guard. */
    private fun cancelAnalysis() {
        session.invalidate()
        main.removeCallbacks(debounce)
        pendingSnapshot = null
        analyzing = false
        queuedAnalysis = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        // Stop waiting on the network too: the answer for the old conversation is
        // worthless, and the caller should not sit behind it.
        runCatching { HttpJson.cancelAll() }
        overlay?.resetForNewConversation()
    }

    private fun observeTarget(target: ConversationSession.Target?) {
        val previous = session.target
        // A different WINDOW is not a different conversation. WeChat opens its
        // sticker picker, emoji panel and other popups as their own window, and
        // treating each of those as "the user switched chats" wiped the panel
        // content and made it blink. Only a change of app or title is a real
        // move.
        val sameConversation = session.sameConversation(previous, target)
        if (session.observe(target)) {
            if (sameConversation) {
                // Keep the panel and the dedupe state; the conversation is the
                // same one, only the window handle moved.
                Log.i(TAG, "target window changed within the same conversation")
            } else {
                cancelAnalysis()
                currentSnapshot = null
                activePkg = null
                lastSignature = ""
                lastOcrSignature = ""
                lastIncomingKey = ""
            }
        }
    }

    /**
     * The newest incoming message, as a fingerprint that ignores the punctuation
     * and spacing OCR wobbles on between frames.
     */
    private fun incomingKey(messages: List<Msg>): String {
        return MessageKeys.of(messages)
    }

    private fun leaveConversation() {
        observeTarget(null)
        cancelAnalysis()
        currentSnapshot = null
    }

    /**
     * The name in the header last time we read a chat.
     *
     * For WeChat this is the ONLY conversation identity there is: its node tree
     * is empty, the window never changes, and the contact name only exists as
     * pixels. Without this, switching contacts looked exactly like the same
     * conversation redrawing itself - the dedupe state stayed, the panel kept
     * the previous person's candidates, and the new person was never read.
     *
     * Returns true when the person changed.
     */
    private fun noteOcrConversation(title: String?, pkg: String): Boolean {
        val name = title?.trim().orEmpty()
        if (name.isEmpty() || isTransientTitle(name)) return false
        val previous = lastOcrTitle
        lastOcrTitle = name
        rememberConversation(name)
        if (previous.isEmpty() || pkg != lastOcrTitlePkg) {
            lastOcrTitlePkg = pkg
            return false
        }
        if (MessageKeys.sameName(previous, name)) return false
        Log.i(TAG, "conversation changed in the same window (${previous.length} chars" +
            " -> ${name.length} chars)")   // names are chat content: lengths only
        // Everything we knew belongs to somebody else now.
        lastSignature = ""
        lastOcrSignature = ""
        lastIncomingKey = ""
        currentSnapshot = null
        cancelAnalysis()
        overlay?.resetForNewConversation()
        overlay?.showSwitchingTo(name)
        return true
    }

    /** Names the picker can offer: the conversations this app has actually read. */
    private fun rememberConversation(name: String) {
        // Only names that could be one, and only the few most recent: the header
        // read goes wrong now and then, and a picker full of "姓你生活?" and
        // "仟始1五伦林役还 2方大同s" is worse than a short, current list.
        if (!MessageKeys.plausibleName(name)) return
        val current = prefs.knownConversations
        val next = (listOf(name) + current.filter { it != name }).take(MAX_KNOWN)
        // Compare the RESULT, not "was it already in there": with an empty list
        // the old test skipped the very first name forever.
        if (next != current) prefs.knownConversations = next
    }

    /** Read the live target, never the previous chat's cached/stabilized title. */
    private fun targetFor(root: AccessibilityNodeInfo): ConversationSession.Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (wechatBlocked(pkg) || pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher", true) || pkg == "com.miui.home"
        ) return null
        val adapter = adapters[pkg]
        var messagesSignature: String? = null
        val title = if (adapter != null) {
            val snapshot = adapter.extract(root, resources) ?: return null
            messagesSignature = snapshot.takeIf { it.messages.isNotEmpty() }?.signature()
            // A loading/unknown title cannot prove which conversation is open.
            val t = snapshot.title?.takeUnless { isTransientTitle(it) }
            // ...unless the app's tree is masked (WeChat) and the title has to
            // come from the screenshot. Treating that missing title as "not a
            // chat window" is what made WeChat do nothing at all.
            if (t == null && snapshot.ocrRegion == null) return null
            t
        } else {
            findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        return ConversationSession.Target(pkg, root.windowId, title, messagesSignature)
    }

    private fun isCurrent(token: ConversationSession.Token): Boolean {
        if (destroyed || !prefs.enabled || !session.accepts(token)) return false
        val live = rootInActiveWindow?.let { targetFor(it) }
        // A pure check, deliberately without side effects.
        //
        // This used to call leaveConversation() and overlay.hide() whenever the
        // live window did not match - and the live window is momentarily a
        // DIFFERENT one all the time: the sticker picker, the emoji panel, a
        // system dialog. Each of those hid the bubble and the next accessibility
        // event brought it back, which is exactly the blinking users saw. A real
        // app switch arrives as its own window-state event and is handled there.
        if (live == null) return false
        if (!session.sameConversation(live, token.target)) return false
        return prefs.isAllowed(currentSnapshot?.title ?: live.title)
    }

    /**
     * Debug builds only: which of [isCurrent]'s conditions said no.
     *
     * A dropped read is silent by design (the conversation may simply have
     * moved on), and that made the Android 16 case - where every single read was
     * dropped - look exactly like "the reader stopped working" with nothing in
     * the log to say why.
     */
    private fun whyNotCurrent(token: ConversationSession.Token): String {
        if (destroyed) return "service destroyed"
        if (!prefs.enabled) return "disabled"
        if (!session.accepts(token)) return "token rejected"
        val live = rootInActiveWindow?.let { targetFor(it) }
        if (live == null) return "no live target"
        if (!session.sameConversation(live, token.target)) {
            return "different conversation (${live.pkg} w=${live.windowId} " +
                "t=${live.title}) vs (${token.target.pkg} w=${token.target.windowId} " +
                "t=${token.target.title})"
        }
        if (!prefs.isAllowed(currentSnapshot?.title ?: live.title)) return "not whitelisted"
        return "ok"
    }

    /** Only called on the main thread, including the completion callback. */
    private fun submitAnalysis(task: () -> Unit) {
        try { analysisTasks.add(worker.submit(task)) } catch (_: RejectedExecutionException) { }
    }

    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path. Everything here runs on the main thread: the screenshot
    // callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this)
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /**
     * Where our own panel was when the shutter opened.
     *
     * The picture the platform hands back can be a few hundred ms old, and the
     * panel grows and shrinks as its content changes, so masking it out needs
     * both where it is now and where it was then - grown by a margin (see
     * [panelMask]).
     */
    private var shotCover: Rect? = null

    /**
     * True when the picture being processed is the chat window on its own, with
     * no floating panel in it. Then [activeMask] is empty: dropping lines "under
     * the panel" would delete real messages that the window shot captured
     * perfectly well.
     */
    private var shotWindowScoped = false

    /** Bring the next read forward to the moment the shutter is allowed again. */
    private val throttleRetry = Runnable { runCatching { maybeCapture() } }

    /**
     * A guaranteed re-read every couple of seconds while a chat is on screen.
     *
     * The whole reader used to be event-driven: it only looked at the screen when
     * the chat app handed the accessibility service an event. WeChat hides its
     * message list from accessibility services, and on some builds it simply does
     * not emit a usable event when you switch contacts - so the reader kept the
     * previous conversation, the panel kept the previous person's candidates, and
     * the answer that eventually arrived was still about the person you had left
     * ("换人了还在答上一个人").
     *
     * A poll costs one screenshot every two seconds (the shutter is already
     * throttled to one per second) and removes that whole class of "it never
     * noticed" failure.
     */
    private val slowPoll = object : Runnable {
        override fun run() {
            if (!destroyed && prefs.enabled) {
                // First make sure there is still a bubble to look at. A ROM can
                // take the floating window away at any moment and never say so;
                // this is the net that puts it back (OverlayController.RootView
                // catches the detach itself, this covers the silent removals).
                runCatching { overlay?.ensureVisible() }
                runCatching { maybeCapture() }
            }
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * The panel's rectangle over the last couple of seconds, with when it was
     * seen. The picture the platform hands back is the last one it *presented*,
     * which on a busy screen is a few hundred milliseconds old, and the panel
     * grows and shrinks as its content changes - so a mask built from "where it
     * is right now" misses the shape that was on screen when the frame was
     * taken. Remembering the recent shapes costs a dozen rectangles and closes
     * the hole.
     */
    private val coverTrail = ArrayList<Pair<Long, Rect>>()

    /** What the screen looked like the last time we fired an automatic shot.
     *  This is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    /** WeChat is fully disabled — track whether the notice has been shown for the
     *  current WeChat visit, so it re-appears next visit but does not re-pop on
     *  every event. */
    private var wechatNoticeShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Skill buttons on the panel: re-run with the new prompt straight away.
        overlay?.onSkillChanged = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // The panel's 对话人 picker: "" means follow, a name pins that person.
        overlay?.onConversationPicked = { name ->
            prefs.lockedConversation = name
            Log.i(TAG, "conversation pin -> " +
                (if (name.isEmpty()) "auto" else "${name.length} chars"))
            cancelAnalysis()
            currentSnapshot = null
            lastSignature = ""
            lastOcrSignature = ""
            lastIncomingKey = ""
            overlay?.resetForNewConversation()
            if (name.isNotEmpty()) {
                overlay?.setNote("已锁定「$name」：只分析 TA 的消息。")
            } else {
                overlay?.setNote("已回到自动跟随：你切到谁就读谁。")
            }
            // Read whatever is on screen now, under the new rule.
            pendingSnapshot = null
            currentSnapshot = null
            runCatching { maybeCapture() }
        }
        // Keep the process at foreground importance so OEM power management does
        // not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Debug builds only: let a test tear the floating window away on demand.
        if (BuildConfig.DEBUG && !debugDetachRegistered) {
            try {
                // EXPORTED on purpose: the only sender is `adb shell am
                // broadcast` on a test device, and release builds never
                // register this at all.
                registerReceiver(debugDetachReceiver,
                    IntentFilter().apply {
                        addAction(DEBUG_DETACH_ACTION)
                        addAction(DEBUG_DUMP_ACTION)
                    },
                    Context.RECEIVER_EXPORTED)
                debugDetachRegistered = true
            } catch (e: Throwable) {
                // Never silent: a test hook that quietly does not exist wastes
                // an hour of somebody's evening.
                Log.w(TAG, "debug hook not registered: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // Aggressive ROMs may kill and restart us. On (re)connect, proactively
        // re-show the bubble for whatever chat is already open.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        // ...and then keep looking on our own, so "the other person switched"
        // can never depend on the chat app choosing to tell us.
        main.removeCallbacks(slowPoll)
        main.postDelayed(slowPoll, POLL_INTERVAL_MS)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { leaveConversation(); overlay?.hide(); return }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME or the status bar while
        // the chat app is still foreground — keying off it made the bubble
        // flicker. rootInActiveWindow stays on the chat app while the keyboard is
        // up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into any other app is the bubble menu's "截屏识别一次", and a bubble
        // that is gone cannot be tapped. So we park the idle bubble there instead.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            // A toast this app shows itself arrives as a window of our own
            // package. Treating that as "the user left the chat" hid the bubble
            // and cancelled the analysis the toast was announcing - the flash
            // seen right after tapping a skill button.
            val selfToast = fg == packageName &&
                android.os.SystemClock.elapsedRealtime() < (overlay?.selfToastUntil ?: 0L)
            if (selfToast) return
            if (wechatBlocked(fg)) {
                foregroundPkg = fg
                showWeChatDisabled(auto = true)
                return
            }
            if (fg != null && fg !in adapters) {
                val target = rootInActiveWindow?.let { targetFor(it) }
                if (session.target != target) leaveConversation()
                foregroundPkg = fg
                wechatNoticeShown = false
                // Our OWN window is never "the user left the chat". Several
                // ROMs hand the accessibility active window to the overlay the
                // moment one of its buttons is touched, and hiding on that made
                // the floating panel vanish exactly while it was being used -
                // "点对话人，悬浮窗就闪退了". Leave everything alone instead.
                if (fg == packageName) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "event from our own window; panel kept")
                    return
                }
                val drop = fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) overlay?.hide() else overlay?.showIdle(null)
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        // A null root is a transient (a window is being swapped), and hiding on
        // it took the whole panel away mid-tap. The real "left the chat" cases
        // arrive as their own window-state events.
        val root = rootInActiveWindow ?: return
        // Track the panel's shape continuously: an accessibility event arrives
        // far more often than a screenshot is taken, and the trail is what makes
        // the mask cover a frame that is a few hundred milliseconds old.
        noteCoverTrail()
        val pkg = root.packageName?.toString()
        // The user tapped something on our own panel: that is not a chat window
        // and not a reason to drop the conversation, clear the picker or hide
        // the bubble.
        if (pkg == packageName) return
        if (wechatBlocked(pkg)) { showWeChatDisabled(auto = true); return }
        wechatNoticeShown = false
        // Apps with no dedicated adapter are handled by the generic detector,
        // which is what makes "no setup at all" true: as soon as the screen
        // looks like a chat window (an input box near the bottom plus a column
        // of message text) it is read with screenshot + OCR automatically,
        // instead of asking the user to tap 截屏识别一次 by hand.
        val adapter = adapters[pkg]
        if (adapter == null && !genericChatHere(root, pkg)) {
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            return
        }
        // Outside a chat window the adapter returns null. That must still park an
        // idle bubble so the menu stays reachable.
        val rawSnapshot = if (adapter != null) adapter.extract(root, resources)
        else ChatSnapshot(genericTitle(root), emptyList(), note = GENERIC_NOTE,
            screenRead = true)
        if (rawSnapshot == null) { leaveConversation(); overlay?.showIdle(null); return }
        val target = targetFor(root)
        if (target == null) { leaveConversation(); overlay?.showIdle(null); return }
        observeTarget(target)
        val snapshot = rawSnapshot
        if (!prefs.isAllowed(snapshot.title)) {
            // A whitelist decides who gets answered - it must not take the
            // button away. This used to hide the whole floating window, and on a
            // phone that window is the only way to reach the assistant, so a
            // misread title looked exactly like "悬浮窗按钮不见了".
            leaveConversation()
            overlay?.showIdle(null)
            return
        }
        // In a chat window but the tree holds no text (the app draws its bodies)
        // → screenshot + OCR, subject to ScreenCapture's own throttle and backoff.
        if (snapshot.messages.isEmpty()) {
            // An adapter that explicitly asks for OCR (WeChat) counts as
            // screen-read too: it has no tree to fall back on, so without this
            // the panel would just sit there waiting for a tap the user has no
            // reason to make.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            // "读不到文字时用截屏识别" is a FALLBACK for apps that have a text
            // tree. When the route itself is screen-only ([ChatSnapshot.screenRead]
            // - WeChat hides its message bodies from accessibility services, and
            // the generic detector never had a tree to begin with) reading the
            // pixels is not a fallback, it is the only way in. Gating those on
            // the switch made WeChat the one app that could not be read at all
            // while every other chat app kept working.
            if (prefs.ocrFallback || snapshot.screenRead) {
                // Gate BEFORE the shot, not after the OCR: a chat app whose tree is
                // empty on every content-changed event would otherwise keep a
                // screenshot going out every second forever.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (snapshot.ocrRegion == null) {
                    if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                    if (ocrBusy) return
                    lastOcrSignature = sig
                } else {
                    // WeChat exposes no bubble geometry, so this "signature" would
                    // be a constant and the screenshot would happen exactly once,
                    // ever. ScreenCapture's own >=1s throttle plus the
                    // newest-incoming-message check downstream are the brakes here.
                    if (ocrBusy) return
                }
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "",
                    manual = false, region = snapshot.ocrRegion)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content: nothing to do except put the bubble back if it was
        // killed. Deliberately NOT cancelling an analysis in flight here - a
        // redraw of the same conversation must not throw away a request that is
        // already on its way back.
        if (sig == lastSignature) {
            if (!showing) overlay?.showIdle(snapshot.title)
            return
        }
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" })

        // Trigger only when a NEW incoming message appeared, and only if
        // auto-analyze is on. Showing the idle bubble keeps the menu reachable.
        val key = incomingKey(snapshot.messages)
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze ||
            key.isEmpty() || MessageKeys.looksSame(key, lastIncomingKey)
        ) {
            if (!showing) overlay?.showIdle(snapshot.title)
            return
        }
        lastIncomingKey = key

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        // 400ms: long enough to absorb the burst of events one message produces,
        // short enough that the user does not feel the wait.
        main.postDelayed(debounce, 400)
    }

    /**
     * Foreground is WeChat, which is fully disabled: no node-tree read, no
     * screenshot, no OCR, no fill. A full card would be intrusive when others can
     * see the screen, so the reason is shown once as a small transient toast.
     */
    private fun showWeChatDisabled(auto: Boolean) {
        leaveConversation()
        if (auto && wechatNoticeShown) return
        wechatNoticeShown = true
        overlay?.hide()
        overlay?.toast(WECHAT_DISABLED_MSG)
    }

    /** A placeholder title an app shows only for a moment (e.g. "连接中…") —
     *  never a real conversation title. Blank/null counts too. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    // ------------------------------------------------------ generic detection

    /**
     * Does this screen look like a chat window, without a dedicated adapter?
     *
     * The signal is deliberately narrow, because a false positive means reading
     * (and screenshotting) a screen that is not a conversation at all:
     *   * an editable, enabled, non-password field in the lower part of the
     *     screen — every chat client puts its composer there, and most other
     *     screens do not, and
     *   * at least five visible text nodes above that field — the messages.
     */
    private fun genericChatHere(root: AccessibilityNodeInfo, pkg: String?): Boolean {
        if (!prefs.genericChatDetection) return false
        if (pkg == null || pkg in GENERIC_SKIP_PKGS) return false
        val height = resources.displayMetrics.heightPixels
        val inputBandTop = (height * 0.55f).toInt()
        var composerAtBottom = false
        var textAbove = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 4000) {
            guard++
            val node = stack.removeLast()
            if (node.isVisibleToUser) {
                // Some clients build their composer out of a custom view that
                // never sets isEditable, so the class name counts too.
                val looksEditable = (node.isEditable ||
                    node.className?.toString()?.contains("EditText") == true) &&
                    node.isEnabled && !node.isPassword
                if (looksEditable) {
                    val b = Rect(); node.getBoundsInScreen(b)
                    if (b.centerY() > inputBandTop) composerAtBottom = true
                }
                val t = node.text?.toString()
                if (!t.isNullOrBlank()) {
                    val b = Rect(); node.getBoundsInScreen(b)
                    if (b.centerY() < inputBandTop) textAbove++
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        // A well-known messenger gets the benefit of the doubt; anything else
        // has to prove it with the composer-at-the-bottom shape, because a false
        // positive means screenshotting a screen that is not a conversation.
        if (pkg in KNOWN_CHAT_PKGS) return textAbove >= 3
        return composerAtBottom && textAbove >= 5
    }

    /** Top-of-screen text, if the app has anything readable up there. */
    private fun genericTitle(root: AccessibilityNodeInfo): String? =
        findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
            resources, 0.15, 0.85)

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: run {
            Log.i(TAG, "分析跳过：没有待分析的快照"); return
        }
        // A message that arrived mid-request is not thrown away: it is queued and
        // taken the moment this one lands.
        if (analyzing) {
            queuedAnalysis = true
            return
        }
        if (destroyed || !prefs.enabled) {
            Log.i(TAG, "分析跳过：服务已停用"); return
        }
        val previous = session.token() ?: run {
            Log.i(TAG, "分析跳过：没有会话目标"); return
        }
        if (!isCurrent(previous)) {
            Log.i(TAG, "分析跳过：会话已经变了"); return
        }
        if (!prefs.hasKey()) {
            Log.i(TAG, "分析跳过：接口没配全")
            overlay?.showError("还没设置接口，去设置里填地址和密钥"); return
        }
        val token = session.begin() ?: run {
            Log.i(TAG, "分析跳过：拿不到会话令牌"); return
        }
        analyzing = true
        overlay?.showLoading()
        overlay?.setNote(snapshot.note)
        val client = LlmClient(prefs, this)
        val rel = prefs.relationship
        submitAnalysis {
            val result = client.analyze(snapshot, rel)
            main.post {
                analyzing = false
                analysisTasks.clear()
                if (!isCurrent(token)) {
                    // The answer belongs to a conversation we have left. It must
                    // not be shown - but the lock above must still be released,
                    // and a message that arrived while we were waiting still has
                    // to be analysed. Leaving `analyzing` set was what made the
                    // panel stop generating anything at all for minutes.
                    Log.i(TAG, "dropping an answer for a conversation we left")
                    val again = queuedAnalysis
                    queuedAnalysis = false
                    if (again) runAnalysis()
                    return@post
                }
                if (result.error != null) overlay?.showError(result.error)
                else {
                    // The endpoint refused "don't think out loud" (see
                    // LlmClient), so this answer took the slow road. Say so -
                    // otherwise it just looks like the app is slow.
                    if (LlmClient.hintRejectedForLastCall) {
                        overlay?.setNote((snapshot.note?.plus("\n") ?: "") +
                            "这个接口不认识“关闭思考”的参数，回复会慢一些")
                    }
                    overlay?.showSuggestion(result) { text -> fillInput(token, text) }
                    // Optional: skip the 填入 tap by dropping the top candidate
                    // straight into the input box. Still never sends.
                    if (prefs.autoFill) {
                        result.replies.firstOrNull()?.let { fillInput(token, it.text) }
                    }
                }
                if (queuedAnalysis) {
                    queuedAnalysis = false
                    runAnalysis()
                }
            }
        }
    }

    /** Set while a display-picture retry is booked, so it can be replaced, not stacked. */
    private var displayFallbackTask: Runnable? = null

    /**
     * Re-read the same chat from the whole-display picture.
     *
     * Only reached when a window-scoped picture came back with no content in it.
     * The second shutter waits for the platform's own minimum interval instead
     * of being refused on the spot, and a later call replaces the pending one.
     */
    private fun scheduleDisplayFallback(treeTitle: String?, pkg: String, region: Rect?) {
        displayFallbackTask?.let { main.removeCallbacks(it) }
        val wait = (screenCapture.nextAttemptInMs() + 40).coerceIn(80L, 1200L)
        val task = Runnable {
            runCatching {
                ocrCapture(treeTitle, emptyList(), pkg, manual = false, region = region,
                    displayFallback = true)
            }
        }
        displayFallbackTask = task
        main.postDelayed(task, wait)
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        if (wechatBlocked(pkg)) { showWeChatDisabled(auto = false); return }
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        val target = root?.let { targetFor(it) } ?: run {
            overlay?.toast("无法确认当前会话，请等待标题加载后重试")
            return
        }
        observeTarget(target)
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     * Apps that give us bubble rectangles move them whenever the list scrolls or
     * a message arrives, and keep them put when only chrome redraws.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (the tree knows where
     * the bubbles are and who sent them, just not what they say) or OCR the whole
     * screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String,
                           manual: Boolean, region: Rect? = null,
                           displayFallback: Boolean = false) {
        if (ocrBusy || destroyed || !prefs.enabled) return
        val token = session.token() ?: run {
            Log.i(TAG, "ocr[$pkg] skipped: no session target"); return
        }
        if (!isCurrent(token)) { Log.i(TAG, "ocr[$pkg] skipped: session moved on"); return }
        ocrBusy = true
        shotCover = overlay?.obscuredRect()
        Log.i(TAG, "ocr[$pkg] taking a screenshot (region=${region != null}" +
            " rects=${rects.size} display=${displayFallback})")
        // WeChat: always the whole display, never a window-scoped shot (see
        // ScreenCapture.shoot - the window shot can miss what WeChat draws
        // itself, which is exactly "微信读不了、别的软件都正常").
        //
        // displayFallback: the window shot came back with the app's background
        // only (see the flat check in ocrByRegion) - the display picture has
        // everything the compositor drew, so try that before giving up.
        screenCapture.capture(shouldCapture = { isCurrent(token) },
                              preferWindow = pkg != PKG_WECHAT && !displayFallback) { res ->
            if (!isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    shotCover = null
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read: the signature must not claim this screen is
                    // done. Throttle/interval codes are transient timing, not
                    // something the user can act on, so they are never nagged about.
                    if (!manual) lastOcrSignature = ""
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                    // A throttled shot used to be dropped on the floor, and the
                    // read then waited for the next accessibility event - up to
                    // a full second of dead air between a message arriving and
                    // the candidates appearing. Come back the moment the shutter
                    // is allowed to open again.
                    if (transient && !manual) {
                        val wait = (screenCapture.nextAttemptInMs() + 40).coerceIn(80L, 1200L)
                        main.removeCallbacks(throttleRetry)
                        main.postDelayed(throttleRetry, wait)
                    }
                }
                is ScreenCapture.Result.Ok -> {
                    shotWindowScoped = res.windowScoped
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "ocr[$pkg] shot=${res.bitmap.width}x${res.bitmap.height}" +
                            " scale=${res.scaleX},${res.scaleY}" +
                            " origin=${res.originX},${res.originY} region=$region" +
                            " windowScoped=${res.windowScoped} mask=${panelMask()}")
                    }
                    if (region != null && !manual) {
                        // The app's tree is useless (WeChat): read the message
                        // band from the picture instead.
                        ocrByRegion(res.bitmap, region, treeTitle, pkg, token,
                            res.windowScoped, displayFallback)
                    } else if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback: several hundred ms pass
                        // between reading the tree and the picture arriving.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh,
                            treeTitle, pkg, token)
                    } else {
                        ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, token)
                    }
                }
            }
        }
    }

    /**
     * The part of the screen our own floating panel occupies, in screen
     * coordinates - unioned with where it was when the shutter opened and grown
     * by a margin, because the picture can be a few hundred ms old and the panel
     * resizes with its content. Anything found inside is our own UI, never
     * somebody's message.
     *
     * Returns null when the panel is not on screen at all.
     */
    private fun panelMask(): Rect? {
        noteCoverTrail()
        val now = overlay?.obscuredRect()
        val then = shotCover
        val all = coverTrail.map { it.second } + listOfNotNull(now, then)
        if (all.isEmpty()) return null
        val box = Rect(all.first())
        all.forEach { box.union(it) }
        // A margin, because OCR boxes and window bounds round differently.
        val pad = (14 * resources.displayMetrics.density).toInt()
        box.inset(-pad, -pad)
        return box
    }

    /**
     * The mask to apply to the picture in hand.
     *
     * Empty for a window-scoped shot: the panel is not in that picture at all,
     * and masking would delete the messages that sit behind the panel on screen
     * but are perfectly visible in the window's own pixels.
     */
    private fun activeMask(): Rect? = if (shotWindowScoped) null else panelMask()

    /** Record where the panel is now, dropping anything older than 2 seconds. */
    private fun noteCoverTrail() {
        val r = overlay?.obscuredRect() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        coverTrail.add(now to Rect(r))
        coverTrail.removeAll { now - it.first > COVER_TRAIL_MS }
        if (coverTrail.size > 40) coverTrail.subList(0, coverTrail.size - 40).clear()
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?,
                           pkg: String, token: ConversationSession.Token) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        // Our own panel is in the picture (it cannot be kept out of it - see
        // OverlayController.obscuredRect), so a bubble it covers is skipped
        // rather than read: OCR would come back with the panel's own labels and
        // they would be handed to the model as somebody's message.
        val cover = activeMask()
        val jobs = rects.withIndex().filter { (_, br) ->
            cover == null || !Rect.intersects(cover, br.rect)
        }
        var remaining = jobs.size
        if (remaining == 0) {
            runCatching { bmp.recycle() }
            finishOcrSnapshot(ChatSnapshot(title, emptyList()), pkg, manual = false, token = token)
            return
        }
        for ((i, br) in jobs) {
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg,
                        manual = false, token = token)
                }
            }
        }
    }

    /**
     * Read a chat window out of one screenshot of `region` (screen coordinates).
     *
     * This is the path WeChat needs: its node tree is empty, so there is nothing
     * to crop per bubble and no speaker information in the tree either. Instead
     * the whole message band is OCR'd, the lines are grouped into bubbles, and
     * **the bubble colour says who spoke** - WeChat paints the user's own
     * messages green. That is the same trick the desktop build uses.
     */
    private fun ocrByRegion(bmp: Bitmap, region: Rect, treeTitle: String?, pkg: String,
                            token: ConversationSession.Token,
                            windowScoped: Boolean, displayFallback: Boolean) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        val ox = ocr.originX; val oy = ocr.originY
        // screen -> bitmap: drop the window origin, then apply the capture scale
        fun toBitmap(r: Rect) = Rect(
            ((r.left - ox) * sx).toInt(),
            ((r.top - oy) * sy).toInt(),
            ((r.right - ox) * sx).toInt(),
            ((r.bottom - oy) * sy).toInt()
        )
        val sample: (Int, Int) -> Int = { x, y -> bmp.getPixel(x, y) }

        if (BuildConfig.DEBUG && debugDumpShot) {
            debugDumpShot = false
            runCatching {
                java.io.File(filesDir, "ocr_shot.png").outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.i(TAG, "debug: dumped the captured picture to files/ocr_shot.png")
            }
        }

        val dm = resources.displayMetrics
        val titleBox = wechatTitleBand(dm.widthPixels, dm.heightPixels, dm.density,
            systemBarHeight(resources, "status_bar_height", 24)).toRect()
        // A window with the OS "secure" flag (WeChat can carry one - 防截屏 /
        // app-lock) is not photographed at all: every capture of it comes back
        // as flat black. The OCR then returns nothing, and the panel used to say
        // "没认出文字", which sends everybody looking for the wrong problem.
        // Say what it is, and what to do about it.
        if (isFlat(bmp, toBitmap(region))) {
            runCatching { bmp.recycle() }
            ocrBusy = false
            // A window-scoped picture can come back holding the app's background
            // and nothing else (measured on Android 16 with the emulator's
            // software GPU: the messages the compositor draws were missing, the
            // band was one flat colour). That is not a protected window - the
            // display picture has the content - so try that once before telling
            // the user their chat is unphotographable.
            if (windowScoped && !displayFallback) {
                Log.i(TAG, "ocr[$pkg] window picture had no content - retrying with the display")
                scheduleDisplayFallback(treeTitle, pkg, region)
                return
            }
            Log.i(TAG, "ocr[$pkg] the message area came back flat - window is protected")
            overlay?.setNote("这一屏的截图是纯黑的：微信开了防截屏（或被系统拦截）。" +
                "把微信的「防截屏/应用锁」关掉就能读了。")
            overlay?.showIdle(treeTitle)
            return
        }
        // One OCR pass for both strips. The title strip sits directly on top of
        // the message band, so their union is one contiguous rectangle - and
        // reading it once instead of twice takes a whole round trip off the
        // critical path of every single reply (measured ~0.3 s on a phone).
        val both = Rect(region)
        both.union(titleBox)
        ocr.recognize(bmp, toBitmap(both)) { lines ->
            if (!isCurrent(token)) {
                runCatching { bmp.recycle() }
                ocrBusy = false
                if (BuildConfig.DEBUG) Log.i(TAG, "ocr[$pkg] dropped: ${whyNotCurrent(token)}")
                return@recognize
            }
            // Split back apart by which strip the line sits in, and drop anything
            // our own panel is covering: those pixels are the panel, and reading
            // them back would put "技能 调用军师 取消军师" into the conversation.
            val cover = activeMask()
            val clear = lines.filter {
                cover == null || !cover.contains(it.bounds.centerX(), it.bounds.centerY())
            }
            val titleLines = clear.filter { it.bounds.centerY() < region.top }
            val bodyLines = clear.filter { it.bounds.centerY() >= region.top }
            if (BuildConfig.DEBUG) {
                val dropped = lines.count {
                    cover?.contains(it.bounds.centerX(), it.bounds.centerY()) == true
                }
                Log.i(TAG, "ocr[$pkg] read ${lines.size} line(s), $dropped under the panel")
            }
            val scannedTitle = treeTitle?.takeIf { it.isNotBlank() }
                ?: titleLines.map { it.text.trim() }
                    .firstOrNull { it.isNotEmpty() && !MessageKeys.isNoise(it) }?.take(24)
            if (MessageKeys.isWeChatTopLevelScreen(scannedTitle)) {
                // The chat list / moments / contacts, not a conversation. Say so
                // instead of handing the model a column of contact names.
                runCatching { bmp.recycle() }
                ocrBusy = false
                Log.i(TAG, "ocr[$pkg] not a conversation (top-level screen)")
                overlay?.setNote("这一屏是微信主界面，打开一个聊天再读。")
                overlay?.showIdle(scannedTitle)
                return@recognize
            }
            val msgs = groupOcrLinesColour(bodyLines, region.width()) { box ->
                runCatching {
                    val bb = toBitmap(box)
                    BubbleColour.sideOf(sample, bmp.width, bmp.height,
                        bb.left, bb.top, bb.right, bb.bottom)
                }.getOrNull()
            }
            runCatching { bmp.recycle() }
            finishOcrSnapshot(
                ChatSnapshot(scannedTitle, msgs, note = WECHAT_OCR_NOTE,
                    sidesKnown = true, screenRead = true),
                pkg, manual = false, token = token)
        }
    }

    /**
     * True when the given area of the picture holds essentially one colour.
     *
     * That is what a protected window looks like to a screenshot: black, and
     * nothing else. Sampling a grid is enough - a real chat area always has
     * text, bubbles and a wallpaper in it.
     */
    private fun isFlat(bmp: Bitmap, box: Rect): Boolean {
        val r = Rect(box)
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return true
        if (r.width() < 16 || r.height() < 16) return true
        val first = bmp.getPixel(r.left + r.width() / 16, r.top + r.height() / 16)
        // A 4x4 grid was the whole test, and it is not enough: on a chat whose
        // last two bubbles sit close together the four rows landed either side
        // of them, in the wallpaper - so a perfectly readable conversation was
        // declared "pure black, the window is protected" and never read. 15x15
        // costs about a millisecond and cannot miss content that is on screen.
        for (gy in 1..15) {
            for (gx in 1..15) {
                val p = bmp.getPixel(r.left + r.width() * gx / 16, r.top + r.height() * gy / 16)
                if (kotlin.math.abs((p and 0xFF) - (first and 0xFF)) > 8 ||
                    kotlin.math.abs(((p shr 8) and 0xFF) - ((first shr 8) and 0xFF)) > 8 ||
                    kotlin.math.abs(((p shr 16) and 0xFF) - ((first shr 16) and 0xFF)) > 8) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * OCR lines -> bubbles, with the speaker taken from [sideOf] and the usual
     * left/right rule as the fallback when the pixels say nothing.
     */
    private fun groupOcrLinesColour(
        lines: List<OcrLine>,
        areaWidth: Int,
        sideOf: (Rect) -> String?
    ): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !MessageKeys.isNoise(it.text) }
            .sortedBy { it.bounds.top }
        if (usable.isEmpty()) return emptyList()

        val groups = ArrayList<MutableList<OcrLine>>()
        var current = ArrayList<OcrLine>()
        current.add(usable[0])
        for (i in 1 until usable.size) {
            val prev = usable[i - 1]
            val item = usable[i]
            val gap = item.bounds.top - prev.bounds.bottom
            if (gap > maxOf(prev.bounds.height(), 8) * 1.35f) {
                groups.add(current)
                current = ArrayList()
            }
            current.add(item)
        }
        groups.add(current)

        val out = ArrayList<Msg>()
        for (g in groups) {
            val text = g.joinToString(" ") { it.text }.trim()
            if (text.isEmpty()) continue
            val box = Rect(
                g.minOf { it.bounds.left }, g.minOf { it.bounds.top },
                g.maxOf { it.bounds.right }, g.maxOf { it.bounds.bottom })
            var side = sideOf(box)
            if (side == null) {
                // The colour said nothing (wallpaper, dark theme): fall back to
                // position, which checks the left edge first.
                side = BubbleColour.sideFromPosition(box.left, box.right, areaWidth)
            }
            out.add(Msg(side, text))
        }
        return out
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String,
                               manual: Boolean, token: ConversationSession.Token) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            // Same rule as the WeChat path: whatever our own panel is covering
            // is the panel, not the conversation.
            val cover = activeMask()
            val clear = lines.filter {
                cover == null || !cover.contains(it.bounds.centerX(), it.bounds.centerY())
            }
            val msgs = groupOcrLines(clear)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: clear.firstOrNull()?.text?.trim()?.take(24)
            // A flat screen read cannot tell the two speakers apart, and saying
            // otherwise made the model answer the user's own messages.
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE,
                sidesKnown = false, screenRead = true), pkg, manual, token)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            // Chrome the window draws (timestamps, the blinking "对方正在输入…"
            // strip, "××撤回了一条消息") is dropped here, so it never becomes a
            // message and can never look like one worth re-analysing.
            .filter { it.text.isNotBlank() && !MessageKeys.isNoise(it.text) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp some apps glue onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean,
                                  token: ConversationSession.Token) {
        ocrBusy = false
        if (!isCurrent(token)) return
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            // WeChat can only be read from pixels, so "nothing came back" there
            // always means something worth telling the user about: the system
            // refused the screenshot, or the app is not on a chat page.
            if (pkg == PKG_WECHAT && !manual) {
                wechatEmptyReads++
                if (wechatEmptyReads == 3) {
                    overlay?.setNote("微信这两屏都没读到字：确认微信停在聊天页、" +
                        "系统允许本应用截屏；也可以在浮窗上长按→「截屏识别一次」试一次。")
                }
            }
            // Never leave the panel silently idle on an automatic read: say that
            // nothing was recognised, so "there is no text on this screen" is
            // distinguishable from "the app is broken".
            if (manual) {
                overlay?.showError("这一屏没认出文字")
            } else {
                overlay?.setNote("这一屏没认出文字")
                overlay?.showIdle(snapshot.title)
            }
            return
        }
        if (pkg == PKG_WECHAT) wechatEmptyReads = 0
        if (!prefs.isAllowed(snapshot.title)) {
            Log.i(TAG, "ocr[$pkg] skipped: title not in the whitelist")
            leaveConversation(); overlay?.hide(); return
        }

        // A different name in the header is a different person, and for WeChat
        // this is the ONLY way to see it: its node tree is empty, so the title
        // arrives from the screenshot and the window never changes. Without this,
        // switching contacts kept the previous person's dedupe state and the
        // previous person's candidates on the panel.
        // Everything after this point re-reads the token through session.begin()
        // (see runAnalysis), so cancelling the old request here is safe.
        noteOcrConversation(snapshot.title, pkg)

        // Pinned to one person: read the others, never answer for them.
        val locked = prefs.lockedConversation
        if (locked.isNotEmpty()) {
            val title = snapshot.title
            if (title.isNullOrBlank()) {
                // Fail open, and say why: a pin can only be honoured when the
                // name can be read, and silently answering nobody would look
                // exactly like the app being broken.
                overlay?.setNote("已锁定「$locked」，但这一屏读不到会话名，这次按自动跟随。")
            } else if (!MessageKeys.sameName(locked, title)) {
                // Debug only: these are chat content (the contact name).
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "pinned to another conversation: skipped (read=$title)")
                } else {
                    Log.i(TAG, "pinned to another conversation: skipped")
                }
                // Only say it once per person: the reader keeps looking every
                // second, and re-rendering the panel each time is what wiped the
                // 对话人 list out from under the user's finger.
                if (title != lastPinnedSkipTitle) {
                    lastPinnedSkipTitle = title
                    // Drop the pinned person's cards first. Leaving them up while
                    // the panel says "not analysing" is the other half of "回复
                    // 内容还是关于上一个对话人的".
                    overlay?.resetForNewConversation()
                    overlay?.setNote("当前是「$title」，你锁定的是「$locked」，所以不分析。" +
                        "想跟随当前聊天，点上面的「对话人」改成自动跟随。")
                    overlay?.showIdle(title)
                }
                return
            }
        }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            Log.i(TAG, "ocr[$pkg] skipped: same snapshot signature")
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        lastSignature = sig

        // Decision comes from the snapshot, never from a field: the OCR callback
        // lands long after the event that started it, by which time a member
        // flag has already been reset by the next accessibility event.
        //
        // Note there is no "the last message must be theirs" test here any more.
        // On a screenshot read the newest line is sometimes mis-attributed (a
        // wallpaper or a merged bubble can do it), and requiring it to be
        // "other" silently threw the whole analysis away. The newest-incoming
        // fingerprint below already stops the app answering the user's own
        // words: sending a message does not change it, only a reply does.
        val auto = (prefs.ocrAutoAnalyze || snapshot.screenRead) && prefs.autoAnalyze
        if (BuildConfig.DEBUG) {
            // Debug builds only: this one line carries chat text, which is
            // exactly what the release build must never put in logcat.
            Log.i(TAG, "ocr[$pkg] read=" + snapshot.messages.joinToString(" | ") {
                it.side + ":" + it.text.take(12)
            })
        }
        Log.i(TAG, "ocr[$pkg] decide(v3) screenRead=${snapshot.screenRead} " +
            "autoAnalyze=${prefs.autoAnalyze} latest=${snapshot.latestFrom} auto=$auto")
        if (manual) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
            return
        }
        if (!auto) {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
            return
        }
        // OCR is not perfectly repeatable, so the same message can come back with
        // a different fingerprint. Only a genuinely new incoming message is worth
        // another request.
        val key = incomingKey(snapshot.messages)
        if (key.isEmpty() || MessageKeys.looksSame(key, lastIncomingKey)) {
            Log.i(TAG, "ocr[$pkg] skipped: no new incoming message")
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        lastIncomingKey = key
        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 400)
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootInActiveWindow ?: return null
        if (targetFor(root) != token.target) return null
        val input = when (token.target.pkg) {
            "com.tencent.mobileqq" ->
                root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" ->
                root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            "com.twitter.android" -> findEditable(root)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        input ?: return null
        // Re-read the node after SET_TEXT: the accessibility cache may still hold
        // the previous draft even though the write already succeeded.
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /** Fill without blocking the main thread; all retries re-resolve the original target. */
    private fun fillInput(token: ConversationSession.Token, text: String) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) return
            if (ok) overlay?.toast("已填入，确认后自己发送")
            else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
        }
        if (!isCurrent(token)) { overlay?.toast("会话已变化，请重新分析后填入"); return }
        if (inputFor(token) == null) { finish(false); return }
        GuardedInputWriter(
            resolve = {
                inputFor(token)?.let { node ->
                    object : GuardedInputWriter.Input {
                        override val text: String? get() = node.text?.toString()
                        override fun setText(text: String) = setTextRaw(node, text)
                        override fun focus() { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        override fun paste() { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                }
            },
            later = { delay, action -> main.postDelayed({ action() }, delay) },
            copy = { copyToClipboard(it) },
            complete = { finish(it) }
        ).fill(text)
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                if (found != null) return null // Ambiguous editor: clipboard only.
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("litechat_reply", text))
    }

    override fun onInterrupt() {
        leaveConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        destroyed = true
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        if (debugDetachRegistered) {
            runCatching { unregisterReceiver(debugDetachReceiver) }
            debugDetachRegistered = false
        }
        leaveConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callbacks so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onOcrCapture = null
        overlay?.onSkillChanged = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "LITECHAT"

        /** WeChat's package. Reading it trips its anti-screenshot risk control, so
         *  it is fully disabled: no adapter, no capture, only a one-time notice. */
        private const val PKG_WECHAT = "com.tencent.mm"

        /** How far back the panel-shape trail reaches (see [noteCoverTrail]). */
        private const val COVER_TRAIL_MS = 2000L

        /**
         * How often the screen is re-read even without an accessibility event
         * (see [slowPoll]). Two seconds: fast enough that switching contacts is
         * noticed almost immediately, slow enough not to matter for battery.
         */
        private const val POLL_INTERVAL_MS = 2000L

        /** How many conversations the picker offers. Kept short on purpose. */
        private const val MAX_KNOWN = 6

        private const val WECHAT_DISABLED_MSG =
            "已按你的设置跳过微信（可在设置里打开「读取微信」）"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 读到的，不区分我/对方"

        /** Same caveat, worded for apps we picked up with the generic detector. */
        private const val GENERIC_NOTE = "自动识别：这一屏当作对方说的话来分析"

        /** Screens that have an input box but are definitely not conversations. */
        private val GENERIC_SKIP_PKGS = setOf(
            "com.android.settings", "com.android.chrome", "com.microsoft.emmx",
            "com.tencent.mtt", "com.UCMobile", "com.quark.browser",
            "com.android.vending", "com.eg.android.AlipayGphone"
        )

        /**
         * Messengers we recognise by package, so the generic detector fires on
         * them even if their composer does not look like a standard EditText.
         * WeChat is deliberately absent: the Android app blocks reading it.
         */
        private val KNOWN_CHAT_PKGS = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
            "com.whatsapp", "com.whatsapp.w4b",
            "com.alibaba.android.rimet", "com.tencent.wework", "com.tencent.tim",
            "com.instagram.android", "com.facebook.orca", "com.facebook.mlite",
            "com.discord", "com.Slack", "com.microsoft.teams", "com.skype.raider",
            "com.google.android.apps.messaging", "com.android.mms",
            "jp.naver.line.android", "com.kakao.talk", "com.viber.voip",
            "com.zing.zalo", "com.tencent.androidqqmail"
        )

        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )

        /** Debug-only broadcast: tear the floating window away (see the receiver). */
        private const val DEBUG_DETACH_ACTION = "com.litechat.app.DEBUG_DETACH_OVERLAY"

        /** Debug-only broadcast: dump the next captured picture to files/ocr_shot.png. */
        private const val DEBUG_DUMP_ACTION = "com.litechat.app.DEBUG_DUMP_SHOT"
    }
}
