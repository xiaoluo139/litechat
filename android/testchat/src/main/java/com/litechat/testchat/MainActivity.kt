package com.litechat.testchat

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A WeChat-shaped conversation, for testing the screenshot reader.
 *
 * Deliberate details, because the reader keys on them:
 *  * the message background is a WALLPAPER (a photo-like colour), not WeChat's
 *    default grey - most people set one, and the first version of the reader
 *    used "is the background grey?" to decide whether it was looking at a
 *    conversation at all, so a wallpaper made it silently do nothing;
 *  * the user's own bubbles are #95EC69 green, everyone else's are white;
 *  * the title bar and input bar are the same dp heights the reader crops away.
 */
class MainActivity : Activity() {

    /** Stands in for a chat wallpaper photo. */
    private val bg = Color.parseColor("#5B7C99")
    private val incoming = Color.WHITE
    private val outgoing = Color.parseColor("#95EC69")

    private var followIndex = 0

    /** Two conversations, about completely different things, to switch between. */
    private val threads = listOf(
        "李沅汐" to listOf(
            false to "昨天那份材料你看过了吗？",
            false to "客户那边催得有点急，今天能给个说法吗",
            true to "我早上看了一半，下午给你答复",
            true to "行，那我三点再来问你，我先把手上这份发你",
        ),
        "老刘" to listOf(
            false to "明天的机票订好了吗",
            false to "我这边一共三个人去，行李有点多",
            true to "航班号发我一下，我发给同事",
            true to "好，我等你消息，路上注意安全",
        ),
    )
    private var threadIndex = 0

    /** Delivered one per tap on the input bar, to act out a live conversation. */
    private val FOLLOW_UPS = listOf(
        "材料我明天上午给你",
        "那下午的会改到四点了，你能来吗",
        "那个客户又打电话过来了",
    )

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#EDEDED")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // ---- title bar (48dp, under the 24dp status bar).
        // Tapping it switches to the other conversation - the "same window,
        // different person" case, which is where a reader that only looks at
        // the messages used to keep answering the previous person. The bar is
        // cropped away by the reader, so nothing it reads changes.
        val titleView = TextView(this).apply {
            text = threads[threadIndex].first
            textSize = 24f                      // big enough for OCR to read
            setTextColor(Color.parseColor("#191919"))
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        root.addView(titleView)

        // ---- messages, pushed to the bottom like a real conversation
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(bg)
        }
        root.addView(spacer)

        // The screen ends on the USER'S own message, which is exactly the case
        // the anchor used to get wrong: it took the last line on screen as "the
        // line to answer" and told the model to reply to the user's own words.
        // Messages are inserted above tailGap, so the list stays bottom-anchored.
        val tailGap = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
            setBackgroundColor(bg)
        }
        root.addView(tailGap)
        val messageViews = ArrayList<View>()
        fun renderThread() {
            for (v in messageViews) root.removeView(v)
            messageViews.clear()
            for ((mine, text) in threads[threadIndex].second) {
                val v = bubble(text, mine)
                root.addView(v, root.indexOfChild(tailGap))
                messageViews.add(v)
            }
        }
        titleView.setOnClickListener {
            threadIndex = (threadIndex + 1) % threads.size
            titleView.text = threads[threadIndex].first
            renderThread()
        }

        renderThread()

        // ---- input bar (54dp), the reader crops this away too
        // Tapping the input bar appends a new INCOMING message, which is how the
        // "a message arrives → candidates appear" path is checked on a device.
        // The bar itself is unchanged, so the region the reader crops is too.
        root.addView(FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
            isClickable = true
            setOnClickListener {
                val text = FOLLOW_UPS[followIndex % FOLLOW_UPS.size]
                followIndex++
                root.addView(
                    bubble(text, mine = false),
                    root.indexOfChild(tailGap))
            }
            addView(TextView(this@MainActivity).apply {
                text = "＋"
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(54), dp(54))
            })
        })

        setContentView(root)
        // Android 15+ draws every window edge to edge, so without this the input
        // bar is laid out *under* the navigation bar: it is visible on a
        // screenshot but the system owns those touches, which made the bar
        // impossible to tap in an automated test. Pad the whole screen by the
        // real insets, the way a well-behaved chat app does - the reader's band
        // arithmetic assumes the input strip sits above the navigation bar.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
    }

    /** One bubble, left or right aligned, sized to its text. */
    private fun bubble(text: String, mine: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.parseColor("#191919"))
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (mine) outgoing else incoming)
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(12)
        lp.marginEnd = dp(12)
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        layoutParams = lp
        if (mine) {
            (lp as LinearLayout.LayoutParams).gravity = Gravity.END
        } else {
            lp.gravity = Gravity.START
        }
        maxWidth = resources.displayMetrics.widthPixels - dp(90)
    }
}
