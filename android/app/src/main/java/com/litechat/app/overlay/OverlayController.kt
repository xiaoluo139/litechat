package com.litechat.app.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.litechat.app.BuildConfig
import com.litechat.app.core.OverlayLiveness
import com.litechat.app.core.Prefs
import com.litechat.app.core.RankedReply
import com.litechat.app.core.Suggestion
import com.litechat.app.llm.Skills
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing the read of the conversation plus 3 candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    /**
     * Whether the bubble is supposed to be on screen right now.
     *
     * Set by every `show*` / [toggle]; cleared by [hide]. [ensureVisible] uses it
     * so the watchdog never resurrects a bubble the user put away on purpose.
     */
    private var wanted = false

    /** "隐藏 10 分钟" is running until this timestamp (elapsedRealtime, ms). */
    private var hiddenUntil = 0L

    /** Last time an `addView` was tried, so a ROM that keeps refusing is not hammered. */
    private var lastAttemptAt = 0L

    /** Detaches seen back to back, and when the last one happened. */
    private var respawnBurst = 0
    private var lastDetachAt = 0L

    /** True while *we* are the ones removing the window, so the detach is not a surprise. */
    private var removingSelf = false

    /**
     * The window itself.
     *
     * A plain FrameLayout cannot tell us that the ROM took it off the screen.
     * `TYPE_APPLICATION_OVERLAY` windows are detached by aggressive ROMs
     * (MIUI/HyperOS and friends) whenever they feel like it, and by the system
     * when the 悬浮窗 permission is toggled. Without this hook the controller
     * kept believing `root != null` meant "it is on screen", so nothing ever put
     * it back - the reported "悬浮窗按钮总是不见".
     */
    private inner class RootView(context: android.content.Context) : FrameLayout(context) {
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            onWindowDetached()
        }
    }

    /**
     * True while the 对话人 list is on screen.
     *
     * The reader keeps working in the background, and every read used to end in
     * [showIdle] or [showSuggestion] - which rebuilds the panel's content. With
     * the list open that meant it was wiped about a second after it appeared:
     * the user taps 对话人, sees the list, and it is gone before they can touch
     * it ("点对话人就闪退"). While this is set, background refreshes keep their
     * result and leave the list alone.
     */
    private var pickerOpen = false
    private var pendingError: String? = null
    /** Last conversation name the panel was told about, for re-rendering. */
    private var lastTitle: String? = null

    var onManualAnalyze: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /** The skill buttons were tapped; re-run so the new prompt takes effect. */
    var onSkillChanged: (() -> Unit)? = null

    /** The conversation picker was used: "" = follow, a name = pin to that person. */
    var onConversationPicked: ((String) -> Unit)? = null

    /** (button, skill id) for the 通用 / 军师 selector. */
    private val skillButtons = ArrayList<Pair<TextView, String>>()

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null

    private var lastSuggestion: Suggestion? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when the last model call failed, so the panel can say so. */
    private var replyError: String? = null

    private val main = Handler(Looper.getMainLooper())
    private var loadingStart = 0L
    private var loadingLabel: TextView? = null
    /** Small right-aligned status in the panel header ("更新中…  1.2 秒"). */
    private var headerStatus: TextView? = null

    private val red = Color.parseColor("#DC2626")
    private val lockColour = Color.parseColor("#FDE68A")
    private val accent = Color.parseColor("#10A37F")

    /**
     * Ticks the elapsed seconds under "分析中…". A frozen label is
     * indistinguishable from a hang, and the user has no way to tell a slow
     * model from a dead one.
     */
    private val ticker = object : Runnable {
        override fun run() {
            val label = loadingLabel ?: return
            val secs = (SystemClock.elapsedRealtime() - loadingStart) / 1000.0
            label.text = "更新中…  ${"%.1f".format(secs)} 秒"
            main.postDelayed(this, 200)
        }
    }

    private fun stopTicker() {
        main.removeCallbacks(ticker)
        loadingLabel = null
    }

    /**
     * Is the bubble really on screen?
     *
     * `root != null` only means "we once added a view": the ROM can detach it at
     * any time, and callers use this to decide whether to park an idle bubble -
     * answering "yes" for a window that is no longer there is exactly how the
     * button disappeared for good.
     */
    fun isShowing(): Boolean = root?.isAttachedToWindow == true

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 255, 255, 255)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#22000000"))
    }

    // ---------------------------------------------------------------- window

    /**
     * Forgets the window without touching the WindowManager.
     *
     * [keepExpanded] is what separates the two cases: a deliberate [hide] puts
     * the bubble away collapsed, while a window the system took away comes back
     * the way it was (content and all) instead of looking like it never opened.
     */
    private fun forgetViews(keepExpanded: Boolean) {
        stopTicker()
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null
        headerStatus = null; loadingLabel = null
        pickerOpen = false
        if (!keepExpanded) expanded = false
    }

    /**
     * The window left the screen without us asking.
     *
     * See [RootView]. Mark it as gone and book a re-add; [ensureVisible] from the
     * service's own poll is the second net, for removals that never reach
     * `onDetachedFromWindow`.
     */
    private fun onWindowDetached() {
        if (removingSelf) return
        if (root == null) return
        android.util.Log.w(TAG, "floating window detached by the system; respawning")
        forgetViews(keepExpanded = true)
        val now = SystemClock.elapsedRealtime()
        respawnBurst = if (now - lastDetachAt < BURST_WINDOW_MS) respawnBurst + 1 else 1
        lastDetachAt = now
        if (respawnBurst > MAX_RESPAWN_BURST) {
            // Somebody (usually the ROM's "后台弹出界面" restriction) is stripping
            // the window the instant it is added. Re-adding it twice a second
            // forever would just burn battery, so pause and say why once.
            hiddenUntil = now + BURST_COOLDOWN_MS
            if (respawnBurst == MAX_RESPAWN_BURST + 1) {
                toast("系统一直在收走悬浮窗：请允许本应用的「后台弹出界面」，" +
                    "并把省电策略设为「无限制」。助手会稍后自己再试。")
            }
            return
        }
        main.removeCallbacks(respawnTask)
        main.postDelayed(respawnTask, RESPAWN_DELAY_MS)
    }

    private val respawnTask = Runnable { if (wanted) ensureRoot() }

    /**
     * Put the window back if the system took it away.
     *
     * Called from the service's 2-second poll and on every window-state event:
     * both are cheap (an `isAttachedToWindow` check) and one of them runs even
     * when the ROM stopped delivering the other.
     */
    fun ensureVisible() {
        if (!wanted) return
        if (root?.isAttachedToWindow == true) return
        ensureRoot()
    }

    /**
     * Debug builds only: tear the window out from under the controller.
     *
     * This is what a ROM does when it takes the floating window away - the view
     * is detached with no notification other than `onDetachedFromWindow`, which
     * is exactly the case that used to leave the user without a bubble for the
     * rest of the service's life. `tools/verify_overlay_selfheal.py` fires this
     * over adb and checks the bubble comes back on its own.
     */
    fun debugDetachWindow() {
        val r = root ?: return
        android.util.Log.w(TAG, "debug: detaching the window underneath the controller")
        runCatching { wm.removeView(r) }
    }

    /**
     * An explicit "show it again" - the app's own button.
     *
     * Beats both brakes (a running hide timer, the gap between attempts),
     * because the user is looking at a screen without a bubble and asked for it.
     */
    fun respawn() {
        wanted = true
        hiddenUntil = 0L
        ensureRoot(force = true)
        if (root != null && lastSuggestion != null && !pickerOpen) render(lastSuggestion!!)
    }

    private fun ensureRoot(force: Boolean = false) {
        if (root?.isAttachedToWindow == true) return
        // A view that is not attached is a memory of a window, not a window.
        if (root != null) forgetViews(keepExpanded = true)
        val now = SystemClock.elapsedRealtime()
        if (!OverlayLiveness.shouldRespawn(
                wanted = wanted,
                attached = false,
                enabled = prefs.enabled,
                canOverlay = canOverlay(),
                force = force,
                nowMs = now,
                hiddenUntilMs = hiddenUntil,
                lastAttemptMs = lastAttemptAt)) {
            return
        }
        if (!canOverlay()) {
            // The permission can be revoked while the service keeps running.
            android.util.Log.w(TAG, "overlay: canDrawOverlays=false")
            return
        }
        lastAttemptAt = now
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = RootView(ctx)
        r.addView(buildPanel())
        r.addView(buildBubble(params))
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e(TAG, "overlay addView failed: ${e.message}"); root = null
        }
        // A window the system took away comes back the way it was, so a panel
        // the user was reading does not turn into a bare bubble mid-sentence.
        if (root != null && expanded) {
            panel?.visibility = View.VISIBLE
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            root?.let { runCatching { wm.updateViewLayout(it, params) } }
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val b = TextView(ctx).apply {
            text = "轻聊"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 16, 163, 127))
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(316), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56) // sit just below the bubble
            }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "轻聊分析"; setTextColor(Color.parseColor("#111827")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerStatus = TextView(ctx).apply {
            setTextColor(accent); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(headerStatus)
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        // Which skill answers: one button to call the 军师, one to cancel it.
        // Two labelled buttons rather than one that changes its mind - which
        // one is doing what is then never a guess. The 军师 is a whole
        // methodology plus a knowledge base, so it is a deliberate choice.
        val skills = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        skills.addView(TextView(ctx).apply {
            text = "技能"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11f
            setPadding(0, 0, dp(8), 0)
        })
        for ((label, id) in listOf(
            "调用军师" to Skills.GOUTOUJUNSHI,
            "取消军师" to Skills.GENERAL)) {
            val button = TextView(ctx).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
                setOnClickListener { chooseSkill(id) }
            }
            skillButtons.add(button to id)
            skills.addView(button)
        }
        p.addView(skills)
        refreshSkillButtons()

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays clear of the chat input box.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()
            ).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
        setPadding(dp(10), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    private fun refreshSkillButtons() {
        val active = prefs.skillId
        for ((view, id) in skillButtons) {
            // The button that describes what is happening right now is the
            // highlighted one: "调用军师 ✓" while the 军师 answers, "取消军师 ✓"
            // once it is off again.
            val on = id == active
            view.background = card(14, if (on) accent else Color.parseColor("#F3F4F6"))
            view.setTextColor(if (on) Color.WHITE else Color.parseColor("#374151"))
            view.text = when {
                id == Skills.GOUTOUJUNSHI && on -> "调用军师 ✓"
                id == Skills.GENERAL && on -> "取消军师 ✓"
                id == Skills.GOUTOUJUNSHI -> "调用军师"
                else -> "取消军师"
            }
        }
    }

    /**
     * The two buttons do exactly what they say: 调用军师 switches to the 军师
     * and 取消军师 switches back to the plain assistant. Tapping the one that
     * is already active says so instead of doing something surprising.
     */
    private fun chooseSkill(id: String) {
        if (prefs.skillId == id) {
            toast(if (id == Skills.GOUTOUJUNSHI) "军师已经在用了" else "当前是通用模式，军师没开")
            return
        }
        prefs.skillId = id
        refreshSkillButtons()
        toast(if (id == Skills.GOUTOUJUNSHI) "已调用狗头军师，重新分析中…"
        else "已取消军师，回到通用模式")
        onSkillChanged?.invoke()
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var longFired = false
        val longPress = Runnable { if (!moved) { longFired = true; showBubbleMenu() } }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // Keep a margin from both side edges: the extreme edge is the
                    // system back-gesture zone, which steals touches.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) true
                    else if (moved) { prefs.bubbleX = params.x; prefs.bubbleY = params.y; true }
                    else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(196), FrameLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(56) }
        }
        menu.addView(menuItem("截屏识别一次") { root?.removeView(menu); onOcrCapture?.invoke() })
        menu.addView(menuItem("重新分析") { root?.removeView(menu); onManualAnalyze?.invoke() })
        menu.addView(menuItem("打开设置") { openSettings(); root?.removeView(menu) })
        // Timed, not permanent: this used to remove the window with nothing that
        // would ever bring it back, so "隐藏" meant "the bubble is gone until
        // you reinstall". The app screen also has a 重新显示 button.
        menu.addView(menuItem("隐藏助手 10 分钟") { hideForAWhile(10) })
        menu.addView(menuItem("取消") { root?.removeView(menu) })
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.parseColor("#111827")); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        // Deferred by one main-loop turn. These handlers remove views - the menu
        // itself, or the whole window for "隐藏助手（本次）" - and doing that
        // while the touch is still being dispatched is what took the floating
        // window away under the finger.
        setOnClickListener { post { onClick() } }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.litechat.app.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        // Build the window first if there is not one yet (the overlay permission
        // can be granted after the service starts, and the window is also taken
        // down when the user leaves the chat).
        //
        // The order matters: flipping `expanded` before this check left the flag
        // saying "the panel is open" while there was nothing on screen at all -
        // and after that every later `if (!expanded) toggle()` was skipped, so
        // the panel could never be opened again for the life of the service.
        wanted = true
        ensureRoot()
        val params = lp ?: return
        expanded = !expanded
        if (BuildConfig.DEBUG) {
            android.util.Log.i(TAG, "overlay toggle expanded=$expanded root=${root != null}")
        }
        if (expanded) {
            // Open the panel from the left, fully on-screen and up high.
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX; params.y = collapsedY
        }
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?) {
        if (pickerOpen) return
        wanted = true
        ensureRoot(); bubble?.alpha = 0.55f
        if (!title.isNullOrBlank()) lastTitle = title
        // Either there is nothing yet, or the panel is empty for some other
        // reason — either way an empty panel must never stay literally blank.
        if (lastSuggestion == null || contentBox?.childCount == 0) {
            val views = ArrayList<View>()
            if (!title.isNullOrBlank()) views.add(line("当前会话：$title", "#374151", 13f))
            // Whatever the panel was told to explain - "you are locked to
            // somebody else", "this screen is WeChat's home" - belongs here.
            noteText?.let { if (it.isNotBlank()) views.add(hint(it)) }
            // The picker belongs on the empty panel too: "who am I answering" is
            // a choice you make *before* there is anything to answer, and the
            // desktop build has always shown it there. On the phone it used to
            // appear only once suggestions existed, so a fresh panel had no way
            // to pick a person at all.
            views.add(conversationRow())
            views.add(bigButton("分析当前对话") { onManualAnalyze?.invoke() })
            setContent(views)
        }
    }

    /**
     * The conversation changed under us.
     *
     * The previous person's candidates have to go the moment the switch is
     * noticed: leaving them on screen for the three to five seconds the new
     * answer takes is exactly what made the panel look like it was still
     * answering the person you had just switched away from.
     */
    fun showSwitchingTo(name: String) {
        if (pickerOpen) return
        ensureRoot(); bubble?.alpha = 1f
        lastTitle = name
        lastSuggestion = null
        lastFill = null
        replyError = null
        headerStatus?.text = ""
        headerStatus?.setTextColor(accent)
        panel?.background = card(18, panelBg(), stroke = true)
        panel?.visibility = View.VISIBLE
        setContent(listOf(
            line("已切到「$name」", "#111827", 15f, true),
            hint("正在读这段对话，读完就出候选。"),
            conversationRow()))
        if (!expanded) toggle()
    }

    /**
     * Drop whatever suggestion belonged to the previous conversation. Call this
     * before showing anything for a different chat window — otherwise a leftover
     * [lastFill] could fill the wrong chat's input box.
     */
    fun resetForNewConversation() {
        lastSuggestion = null
        lastFill = null
        noteText = null
        replyError = null
        headerStatus?.text = ""
        headerStatus?.setTextColor(accent)
        contentBox?.removeAllViews()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, Color.parseColor("#10A37F"))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Same reason as [menuItem]: a handler here may rebuild this very panel.
        setOnClickListener { post { onClick() } }
    }

    fun showLoading() {
        if (pickerOpen) return
        wanted = true
        ensureRoot(); bubble?.alpha = 1f
        replyError = null
        // If suggestions are already on screen they stay exactly where they are:
        // wiping the candidate cards while a finger is reaching for one is what
        // made the panel impossible to use. Progress shows in the header instead.
        loadingLabel = if ((contentBox?.childCount ?: 0) > 0) {
            headerStatus
        } else {
            val placeholder = hint("正在想一句合适的回复…  0.0 秒")
            setContent(listOf(conversationRow(), placeholder))
            placeholder
        }
        loadingStart = SystemClock.elapsedRealtime()
        main.removeCallbacks(ticker)
        main.post(ticker)
        if (!expanded) toggle()
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) { noteText = note }

    /**
     * Where the panel is, in screen coordinates, so the reader can throw away
     * anything it finds there.
     *
     * The panel is NOT hidden for a screenshot any more, and that is deliberate.
     * Hiding it never worked reliably: INVISIBLE, layer alpha 0 and even
     * removing the window from the WindowManager all left it inside the picture,
     * because once the window stops drawing the framework stops producing
     * frames for it and the compositor keeps serving the last one - measured
     * here, the window was detached for 293 ms and the panel was still in the
     * bitmap. The reader then found our own labels ("技能 调用军师 取消军师",
     * "对话人 自动跟随") in the middle of the chat text and handed them to the
     * model as if somebody had said them.
     *
     * Masking costs nothing, cannot be defeated by a stale frame, and it also
     * removes the once-a-second blink of the panel that hiding caused.
     */
    fun obscuredRect(): Rect? {
        val r = root ?: return null
        // The VIEW's own position on screen, not lp.x/lp.y. Those are the
        // window's coordinates, and the window's origin is not where the window
        // is drawn: measured on the emulator the panel appeared about 150 px
        // below lp.y (the overlay window is laid out inside the app area, below
        // the status bar, while lp.y counts from the top of the display). A mask
        // built from lp was therefore shifted up and missed the panel entirely.
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        val out = Rect(loc[0], loc[1], loc[0] + r.width, loc[1] + r.height)
        // Only while the panel is actually open. A collapsed panel keeps its old
        // laid-out rectangle, and unioning that in forever meant "the panel is
        // somewhere over there" long after it was closed - the reader then threw
        // away every line that happened to sit in that area (measured: 17 of the
        // 21 lines of a chat), which looks exactly like "识别不到".
        val p = panel
        if (expanded && p != null && p.width > 0 && p.height > 0) {
            p.getLocationOnScreen(loc)
            out.union(loc[0], loc[1], loc[0] + p.width, loc[1] + p.height)
        }
        if (out.width() <= 0 || out.height() <= 0) return null
        return out
    }

    fun showError(msg: String) {
        if (pickerOpen) { pendingError = msg; return }
        wanted = true
        ensureRoot(); bubble?.alpha = 1f
        headerStatus?.text = ""
        // If usable suggestions are already on screen, an error from a background
        // refresh must not replace them - the user would lose the reply they were
        // about to send. The failure goes in the header instead.
        if ((contentBox?.childCount ?: 0) > 0 && lastSuggestion != null) {
            headerStatus?.text = "更新失败"
            headerStatus?.setTextColor(red)
            toast(msg)
            return
        }
        headerStatus?.setTextColor(accent)
        setContent(listOf(
            line("出错了", "#DC2626", 14f, true),
            hint(msg)))
    }

    /** A neutral one-time notice (used when the foreground is WeChat). */
    fun showNotice(msg: String) {
        wanted = true
        ensureRoot(); bubble?.alpha = 1f
        resetForNewConversation()
        setContent(listOf(
            line("提示", "#10A37F", 14f, true),
            hint(msg)))
        if (!expanded) toggle()
    }

    /** The one model call's result: read of the chat + 3 ranked replies. */
    fun showSuggestion(s: Suggestion, onFill: (String) -> Unit) {
        lastSuggestion = s
        lastFill = onFill
        replyError = s.error
        wanted = true
        // The list of people must survive a background answer arriving: it is
        // kept in [lastSuggestion] and drawn when the list closes.
        if (!pickerOpen) render(s)
    }

    /**
     * When this app's own toast is on screen.
     *
     * A toast is a window, and it belongs to our package, so a window-state
     * event for it looks exactly like "the user opened LiteChat". The capture
     * service used to read it that way and took the bubble away for a moment -
     * the flash seen after tapping a skill button. It now ignores its own
     * toasts until this timestamp passes.
     */
    var selfToastUntil: Long = 0L
        private set

    fun toast(msg: String) {
        selfToastUntil = SystemClock.elapsedRealtime() + TOAST_GUARD_MS
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    /** Take the window down for good (the assistant was switched off, or there is no chat). */
    fun hide() {
        wanted = false
        hiddenUntil = 0L
        removeNow()
    }

    /**
     * "隐藏助手 10 分钟" from the bubble menu.
     *
     * [wanted] stays true on purpose: when the timer runs out [ensureVisible]
     * puts the bubble back on its own, so this is a pause and not a one-way door.
     */
    fun hideForAWhile(minutes: Int) {
        hiddenUntil = SystemClock.elapsedRealtime() + minutes * 60_000L
        wanted = true
        removeNow()
        toast("悬浮窗已隐藏 $minutes 分钟，到点会自动回来")
    }

    private fun removeNow() {
        val r = root ?: return
        // Set while we are the one removing it: RootView must not read our own
        // removeView as "the system took it away" and immediately respawn.
        removingSelf = true
        runCatching { wm.removeView(r) }
        removingSelf = false
        forgetViews(keepExpanded = false)
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        stopTicker()
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun render(s: Suggestion) {
        ensureRoot(); bubble?.alpha = 1f
        headerStatus?.text = ""
        headerStatus?.setTextColor(accent)
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = ArrayList<View>()

        noteText?.let { if (it.isNotBlank()) views.add(hint(it)) }

        // Who is being answered - the automatic follower, or one pinned person.
        views.add(conversationRow())

        // 军师 mode says so, and shows the read of the situation plus which
        // reference documents went into the answer. Without this the two skills
        // look like the same assistant with slightly different wording.
        if (prefs.skillId == Skills.GOUTOUJUNSHI) {
            val bits = ArrayList<String>()
            bits.add("军师模式")
            s.stance?.takeIf { it.isNotBlank() }?.let { bits.add("判断：$it") }
            if (s.references.isNotEmpty()) {
                bits.add("参考：" + s.references.joinToString("、"))
            }
            views.add(line(bits.joinToString("　·　"), "#0d8c6c", 12f, true))
        }

        s.danger?.let {
            views.add(dangerBadge(it))
            tintBubbleDanger(it)
        }
        s.intent?.let {
            views.add(line("对方意图：$it", "#111827", 15f, true))
        }
        s.advice?.let {
            views.add(line("建议：$it", "#374151", 13f))
        }
        if (s.intent == null && s.advice == null && s.danger == null) {
            views.add(hint("模型没有给出判断，只给了候选回复"))
        }

        views.add(divider())
        views.add(line("候选回复", "#9CA3AF", 12f))
        val fill = lastFill ?: {}
        if (s.replies.isEmpty()) {
            views.add(hint(replyError?.let { "接口出错：$it" } ?: "（没有候选回复）"))
        } else {
            s.replies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r, fill))
            }
        }
        views.add(reAnalyzeBtn())

        setContent(views)
        if (!expanded) toggle()
    }

    /**
     * Who is being answered: 自动跟随, or one pinned person.
     *
     * Tapping the row swaps the panel for the list; tapping a row applies it and
     * comes straight back. The names are the ones this app has actually read, so
     * picking one always matches what it will read again - and it never clicks
     * inside the chat app to open a chat, which is the one thing this tool
     * deliberately does not do.
     */
    private fun conversationRow(): View {
        val locked = prefs.lockedConversation
        // One full-width button, not a small chip. The old row was a grey
        // "自动跟随" pill 24 dp tall sitting next to a tiny grey label - easy to
        // overlook and easy to miss with a finger, which is why it kept coming
        // back as "手机端没有对话人的选择按钮".
        return TextView(ctx).apply {
            text = if (locked.isNotEmpty()) "对话人：锁定 $locked　▾" else "对话人：自动跟随　▾"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = card(12, if (locked.isNotEmpty()) lockColour else Color.parseColor("#EEF1F5"))
            setTextColor(if (locked.isNotEmpty()) Color.parseColor("#78350F") else Color.parseColor("#111827"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (BuildConfig.DEBUG) android.util.Log.i(TAG, "conversation button tapped")
                showConversationPicker()
            }
        }
    }

    /** The list itself: follow automatically, or pin somebody by name. */
    private fun showConversationPicker() {
        // Rebuilding the panel from inside one of its own click handlers is what
        // took the floating window away: the view that is dispatching the touch
        // (or the window it lives in) gets removed in the middle of the dispatch.
        // Every entry point below therefore defers by one main-loop turn.
        ensureRoot()
        panel?.visibility = View.VISIBLE
        if (!expanded) toggle()
        pickerOpen = true
        try {
            buildConversationPicker()
        } catch (e: Throwable) {
            // Never take the service down for a picker: say so and log it.
            android.util.Log.e(TAG, "conversation picker failed", e)
            setContent(listOf(hint("对话人列表出错了，点「返回」继续用自动跟随。")))
        }
    }

    private fun buildConversationPicker() {
        val views = ArrayList<View>()
        val locked = prefs.lockedConversation
        views.add(line("这一屏要回答谁？", "#111827", 15f, true))
        views.add(hint("程序不会去点你的聊天软件。选一个人＝只分析 TA 的消息；" +
            "选「自动跟随」＝跟着你打开的聊天走。"))

        fun option(label: String, detail: String, active: Boolean, pick: () -> Unit) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = card(12, if (active) Color.parseColor("#E8F5EE") else Color.parseColor("#F7F8FA"))
                setOnClickListener { post { pick() } }
            }
            box.addView(line((if (active) "✓ " else "") + label, "#111827", 14f, true))
            if (detail.isNotEmpty()) box.addView(hint(detail))
            views.add(box)
        }

        option("自动跟随", "默认：跟着你切换的聊天走", locked.isEmpty()) {
            onConversationPicked?.invoke("")
            showConversationPicker()
        }
        val known = prefs.knownConversations
        if (known.isEmpty()) {
            views.add(hint("还没读到过会话名。先打开一个聊天，程序读一次就会出现在这里。"))
        } else {
            views.add(hint("只保留最近读到的 " + known.size + " 个（最多 6 个）。"))
        }
        known.forEach { name ->
            option(name, "锁定后只分析 TA", name == locked) {
                onConversationPicked?.invoke(name)
                showConversationPicker()
            }
        }
        if (known.isNotEmpty()) {
            // A list full of misreads is worse than an empty one, and the only
            // way out used to be reinstalling the app.
            views.add(bigButton("清空这个名单") {
                prefs.knownConversations = emptyList()
                showConversationPicker()
            })
        }
        views.add(bigButton("返回") { closeConversationPicker() })
        setContent(views)
    }

    /** Leaving the list: show whatever the background produced meanwhile. */
    private fun closeConversationPicker() {
        pickerOpen = false
        val s = lastSuggestion
        val err = pendingError
        pendingError = null
        when {
            s != null -> render(s)
            err != null -> showError(err)
            else -> showIdle(lastTitle)
        }
    }

    private fun dangerBadge(level: Int): View {
        val color = dangerColor(level)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "紧张度 $level/9"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(level); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, r: RankedReply, onFill: (String) -> Unit): View {
        val top = rank == 1
        val cardBg = if (top) Color.parseColor("#E7F7F1") else Color.parseColor("#F3F4F6")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            text = if (r.pct > 0) "推荐 $rank · ${r.pct}%" else "候选 $rank"
            setTextColor(Color.parseColor("#10A37F")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            text = r.text; setTextColor(Color.parseColor("#111827")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("复制", false) { copy(r.text) })
        // Fill, then collapse so the input box + keyboard are visible.
        btns.addView(pill("填入", true) {
            android.util.Log.d(TAG, "overlay: fill tapped")
            onFill(r.text)
            if (expanded) toggle()
        })
        c.addView(btns)
        return c
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#10A37F"))
        background = card(18, if (primary) Color.parseColor("#10A37F") else Color.parseColor("#FFFFFF"),
            stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tintBubbleDanger(level: Int) {
        val color = dangerColor(level)
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("litechat_reply", text))
        toast("已复制")
    }

    private fun dangerColor(level: Int): Int = when {
        level >= 6 -> Color.parseColor("#DC2626")
        level >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(level: Int): String = when {
        level >= 8 -> "很紧张"
        level >= 6 -> "要小心"
        level >= 3 -> "留神"
        else -> "轻松"
    }

    companion object {
        private const val TAG = "LITECHAT"

        /** Long enough to cover a LENGTH_SHORT toast plus the event it fires. */
        private const val TOAST_GUARD_MS = 2000L

        /**
         * How long to wait before re-adding a window the system just took away.
         * Short, because the user is looking at a screen with no bubble, but not
         * zero: the ROM is usually in the middle of its own window transaction.
         */
        private const val RESPAWN_DELAY_MS = 400L

        /** Detaches closer together than this count as one burst. */
        private const val BURST_WINDOW_MS = 5000L

        /** After this many quick detaches, stop fighting the ROM for a while. */
        private const val MAX_RESPAWN_BURST = 3

        /** How long to stay quiet once the ROM has clearly won that fight. */
        private const val BURST_COOLDOWN_MS = 30_000L
    }
}
