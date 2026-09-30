package com.litechat.app

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.litechat.app.core.Prefs
import com.litechat.app.core.ChatSnapshot
import com.litechat.app.core.Msg
import com.litechat.app.core.Suggestion
import com.litechat.app.llm.LlmClient
import kotlin.math.roundToInt

/**
 * Home / setup screen. One card with a live readiness summary, a guided
 * permission checklist (each row reflects its real granted state), a link to the
 * one settings page, and a prominent on/off switch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private lateinit var pasteInput: EditText
    private lateinit var pasteResult: LinearLayout
    private val a11yComponent =
        "com.litechat.app/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    private val accent = Color.parseColor("#10A37F")
    private val green = Color.parseColor("#16A34A")
    private val red = Color.parseColor("#DC2626")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        container.removeAllViews()

        container.addView(text("轻聊助手", 24f, ink, bold = true))
        container.addView(text(
            "在聊天软件旁边读对方的消息，一键给出 3 条候选回复。发送永远由你自己点。",
            13f, sub).apply { setPadding(0, dp(6), 0, dp(16)) })

        val a11y = isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val key = prefs.hasKey()
        val ready = a11y && overlay && key

        container.addView(statusCard(ready, a11y, overlay, key))
        container.addView(hintLine("聊天内容只发往你自己配置的接口 · 截图只在本机识别"))

        container.addView(sectionLabel("权限设置"))
        container.addView(permCard("无障碍权限", "读取当前聊天窗口的消息文字", a11y) {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        container.addView(permCard("悬浮窗权限", "在聊天窗口上方显示分析卡片", overlay) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        })
        container.addView(permCard("自启动 + 省电无限制",
            "小米/HyperOS 必做，否则后台被冻结、读不到消息", null) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        })
        // The floating window is the only way to reach the assistant while
        // chatting, and ROMs remove it whenever they like (MIUI/HyperOS also
        // need 后台弹出界面 allowed). The service puts it back by itself; this is
        // the button for when somebody is looking at a screen without a bubble.
        container.addView(permCard("悬浮窗不见了？",
            "点这里让助手马上重新显示悬浮窗", null, "重新显示") {
            prefs.respawnOverlay = prefs.respawnOverlay + 1
            toast("已让助手重新显示悬浮窗")
        })

        container.addView(sectionLabel("接口"))

        // 对话人 selection lives here as well as on the floating panel. This
        // window is an ordinary activity, so it can always be tapped - the
        // floating panel cannot, on some ROMs - and it is where somebody looks
        // when "there is no button to choose the person".
        container.addView(sectionLabel("对话人"))
        container.addView(conversationPickerCard())

        // Escape hatch for a WeChat that refuses to be photographed.
        container.addView(sectionLabel("微信读不到时"))
        container.addView(pasteCard())

        container.addView(actionRow("接口设置", providerSummary()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        val toggle = bigToggle(prefs.enabled)
        toggle.setOnClickListener {
            prefs.enabled = !prefs.enabled
            build()
        }
        container.addView(toggle)

        container.addView(text(
            "微信、QQ、X（推特私信）、飞书都支持；其它聊天软件（钉钉、Telegram 等）" +
                "只要看着像聊天窗口就会自动识别，不用手动添加。微信可在设置里关掉。",
            11f, sub).apply { setPadding(dp(2), dp(16), dp(2), 0) })
        container.addView(text(
            "基于 Jev 聊天助手（github.com/jev-chat/jev-chat-jarvis）二次开发的简化版 · MIT\n" +
                "军师技能来自狗头军师（github.com/powerycy/goutoujunshi）· MIT",
            10f, sub).apply { setPadding(dp(2), dp(8), dp(2), 0) })
    }

    private fun providerSummary(): String {
        val name = com.litechat.app.llm.LlmPresets.byId(prefs.providerId).label
        return if (prefs.hasKey()) "$name · ${prefs.model}" else "还没设置，点进去填一个接口就能用"
    }

    /**
     * Who the assistant answers.
     *
     * The floating panel has the same choice, but a floating window can be
     * unusable on some ROMs; this one is a normal screen, so it always works.
     */
    private fun conversationPickerCard(): View {
        val locked = prefs.lockedConversation
        val c = cardBox()
        c.addView(text("正在回答谁", 15f, ink, bold = true))
        c.addView(text(
            "程序不会去点你的聊天软件。「自动跟随」= 跟着你切换的聊天走；" +
                "选一个人 = 只分析 TA 的消息。", 12f, sub).apply { setPadding(0, dp(4), 0, dp(6)) })

        fun optionRow(label: String, detail: String, active: Boolean, pick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundBg(dp(10),
                    if (active) Color.parseColor("#E8F5EE") else Color.parseColor("#F7F8FA"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener { pick() }
            }
            row.addView(text((if (active) "✓ " else "") + label, 14f, ink, bold = true))
            row.addView(text(detail, 12f, sub).apply { setPadding(0, dp(2), 0, 0) })
            c.addView(row)
        }

        optionRow("自动跟随", "跟着你切换的聊天走", locked.isEmpty()) {
            prefs.lockedConversation = ""
            build()
        }
        val known = prefs.knownConversations
        if (known.isEmpty()) {
            c.addView(text("还没读到过会话名：打开一个聊天，程序读一次就会出现在这里。",
                12f, sub).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            c.addView(text("只保留最近读到的 " + known.size + " 个（最多 6 个）。",
                12f, sub).apply { setPadding(0, dp(8), 0, 0) })
        }
        known.forEach { name ->
            optionRow(name, "只分析 TA 的消息", name == locked) {
                prefs.lockedConversation = name
                build()
            }
        }
        if (known.isNotEmpty()) {
            // One tap to start over: a list that filled up with OCR misreads had
            // no way of being cleared short of reinstalling the app.
            c.addView(TextView(this).apply {
                text = "清空这个名单"
                textSize = 13f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(sub)
                background = roundBg(dp(10), Color.parseColor("#F3F4F6"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    prefs.knownConversations = emptyList()
                    build()
                }
            })
        }
        return c
    }

    /**
     * Paste a conversation, get the same three candidates.
     *
     * Some phones mark WeChat's window "secure" (防截屏 / app-lock), and then no
     * screenshot of it can exist - not by this app, not by the system's own
     * screenshot button. That is a device-side rule and nothing on our side can
     * go around it (the whole point of the flag). This is the way to keep
     * working: copy the messages out of WeChat, paste them here, and the same
     * prompt, skill and model produce the same candidates.
     */
    private fun pasteCard(): View {
        val c = cardBox()
        c.addView(text("粘一段聊天记录也能出回复", 15f, ink, bold = true))
        c.addView(text(
            "如果微信开了防截屏，程序截不到它的画面（系统级限制，任务截图工具都拍不到）。" +
                "长按微信消息选「复制」或直接手打，一行一句粘到下面即可。" +
                "行首写「对方：」「我：」可以区分谁说的。", 12f, sub).apply { setPadding(0, dp(4), 0, dp(6)) })

        pasteInput = EditText(this).apply {
            hint = "对方：明天的机票订好了吗\n我：还没定\n对方：今天能定下来吗"
            setTextSize(13f)
            gravity = Gravity.TOP
            minLines = 4
            maxLines = 8
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundBg(dp(10), Color.parseColor("#F7F8FA"))
        }
        c.addView(pasteInput)

        c.addView(TextView(this).apply {
            text = "生成 3 条回复"
            textSize = 15f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundBg(dp(12), accent)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { runPasteAnalysis() }
        })

        pasteResult = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(pasteResult)
        return c
    }

    private fun runPasteAnalysis() {
        val thread = parsePastedThread(pasteInput.text.toString())
        if (thread.isEmpty()) {
            toast("先把聊天记录粘进来（一行一句）")
            return
        }
        pasteResult.removeAllViews()
        pasteResult.addView(text("正在生成…", 12f, sub).apply { setPadding(0, dp(8), 0, 0) })
        val rel = prefs.relationship
        Thread {
            val s = runCatching {
                LlmClient(prefs, applicationContext)
                    .analyze(ChatSnapshot("粘贴的聊天", thread), rel)
            }.getOrElse {
                Suggestion(null, null, null, emptyList(), 0,
                    error = it.message ?: "请求失败")
            }
            runOnUiThread { showPasteResult(s) }
        }.start()
    }

    /** `我：…` is the user, `对方：…` is the other person, anything else is theirs. */
    private fun parsePastedThread(raw: String): List<Msg> {
        val me = Regex("^(?:我|自己|me|ME)\\s*[:：]")
        val other = Regex("^(?:对方|他|她|ta|TA|them|THEM)\\s*[:：]")
        val out = ArrayList<Msg>()
        for (line0 in raw.split('\n')) {
            val line = line0.trim()
            if (line.isEmpty()) continue
            val m = me.find(line)
            val o = other.find(line)
            when {
                m != null -> out.add(Msg("me", line.substring(m.range.last + 1).trim()))
                o != null -> out.add(Msg("other", line.substring(o.range.last + 1).trim()))
                else -> out.add(Msg("other", line))
            }
        }
        return out.filter { it.text.isNotEmpty() }
    }

    private fun showPasteResult(s: Suggestion) {
        pasteResult.removeAllViews()
        s.error?.let { pasteResult.addView(text("出错：$it", 12f, red).apply { setPadding(0, dp(8), 0, 0) }) }
        s.stance?.takeIf { it.isNotBlank() }?.let {
            pasteResult.addView(text("军师判断：$it", 13f, accent, bold = true)
                .apply { setPadding(0, dp(8), 0, 0) })
        }
        s.intent?.let {
            pasteResult.addView(text("对方意图：$it", 14f, ink, bold = true)
                .apply { setPadding(0, dp(8), 0, 0) })
        }
        s.advice?.let { pasteResult.addView(text("建议：$it", 12f, sub)) }
        s.replies.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(text("${i + 1}. ${r.text}（${r.pct}%）", 14f, ink).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(btn("复制", true) { copyToClipboard(r.text) })
            pasteResult.addView(row)
        }
        if (s.replies.isEmpty() && s.error == null) {
            pasteResult.addView(text("模型这次没给出回复，再点一次试试。", 12f, sub)
                .apply { setPadding(0, dp(8), 0, 0) })
        }
    }

    private fun copyToClipboard(t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("reply", t))
        toast("已复制：" + t.take(18))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- cards

    private fun statusCard(ready: Boolean, a11y: Boolean, overlay: Boolean, key: Boolean): View {
        val c = cardBox()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(dot(if (ready) green else red).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
        })
        head.addView(text(if (ready) "已就绪，可以用了" else "还没就绪",
            16f, if (ready) green else ink, bold = true))
        c.addView(head)
        c.addView(checkLine("无障碍", a11y))
        c.addView(checkLine("悬浮窗", overlay))
        c.addView(checkLine("接口", key, okWord = "已设置", noWord = "未设置"))
        return c
    }

    private fun checkLine(label: String, ok: Boolean,
                          okWord: String = "已开启", noWord: String = "未开启"): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        row.addView(text(if (ok) "✓" else "✗", 14f, if (ok) green else red, bold = true).apply {
            width = dp(22)
        })
        row.addView(text("$label ${if (ok) okWord else noWord}", 13f, sub))
        return row
    }

    private fun permCard(title: String, desc: String, granted: Boolean?, action: String? = null,
                         onClick: () -> Unit): View {
        val c = cardBox()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        if (granted == true) {
            left.addView(text("✓ 已开启", 12f, green, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        }
        row.addView(left)
        row.addView(btn(action ?: if (granted == true) "已开启" else "去开启", granted != true, onClick))
        c.addView(row)
        return c
    }

    private fun actionRow(title: String, desc: String, onClick: () -> Unit): View {
        val c = cardBox()
        c.setOnClickListener { onClick() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(text("›", 22f, sub))
        c.addView(row)
        return c
    }

    private fun bigToggle(on: Boolean): View = TextView(this).apply {
        text = if (on) "助手已开启 · 点一下关闭" else "助手已关闭 · 点一下开启"
        textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (on) Color.WHITE else accent)
        background = roundBg(dp(14), if (on) accent else Color.WHITE, stroke = !on)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) }
    }

    // ---------------------------------------------------------------- atoms

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(dp(14), Color.WHITE)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun sectionLabel(t: String) = text(t, 12f, sub, bold = true).apply {
        setPadding(dp(2), dp(18), 0, dp(2))
    }

    private fun hintLine(t: String) = text(t, 11f, sub).apply { setPadding(dp(2), dp(8), 0, 0) }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun btn(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (enabled) Color.WHITE else sub)
        background = roundBg(dp(10), if (enabled) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (enabled) setOnClickListener { onClick() }
    }

    private fun roundBg(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(a11yComponent)
    }
}
