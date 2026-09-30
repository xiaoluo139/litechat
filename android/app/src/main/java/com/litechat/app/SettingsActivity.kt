package com.litechat.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.litechat.app.core.Prefs
import com.litechat.app.llm.LlmClient
import com.litechat.app.llm.LlmPresets
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The single settings page. Everything the app needs is here: which third-party
 * API to call (preset + address + key + model), how the replies should sound
 * (relationship), and a few behaviour switches.
 *
 * One API entry is the whole point of the simplified build — there is no judge /
 * reply / vision split to configure.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout

    private lateinit var providerSpinner: Spinner
    private lateinit var protocolSpinner: Spinner
    private lateinit var baseUrlEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var tempEdit: EditText
    private lateinit var maxTokensEdit: EditText
    private lateinit var headersEdit: EditText
    private lateinit var relationshipEdit: EditText
    private lateinit var whitelistEdit: EditText
    private lateinit var autoAnalyzeSwitch: SwitchCompat
    private lateinit var ocrFallbackSwitch: SwitchCompat
    private lateinit var ocrAutoSwitch: SwitchCompat
    private lateinit var genericSwitch: SwitchCompat
    private lateinit var wechatSwitch: SwitchCompat
    private lateinit var autoFillSwitch: SwitchCompat
    private lateinit var noThinkingSwitch: SwitchCompat
    private lateinit var opacityBar: SeekBar
    private lateinit var status: TextView
    private lateinit var hint: TextView

    /** Spinner callbacks fire while we are populating them; ignore those. */
    private var loading = true

    /**
     * The preset the fields currently reflect. [providerSpinner] fires an
     * onItemSelected callback on the first layout pass as well — without this,
     * that initial callback would overwrite a saved custom address/model with
     * the preset's defaults every time the page opens.
     */
    private var appliedProviderPos = -1

    private val accent = Color.parseColor("#10A37F")
    private val green = Color.parseColor("#16A34A")
    private val red = Color.parseColor("#DC2626")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private val io = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        // The confirm buttons are pinned to the bottom of the screen instead of
        // trailing the form. The form is long, and a save button at the end of it
        // is exactly what people could not find.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        container.padForSystemBars()
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildActionBar())
        setContentView(root)
        build()
        load()
    }

    /** Always-visible bottom bar: status line + 确认保存 + 保存并测试. */
    private fun buildActionBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE) }
            setPadding(dp(16), dp(10), dp(16), dp(14))
            elevation = dp(8).toFloat()
        }
        status = text("填好后点右边的按钮保存", 12f, sub).apply {
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        bar.addView(status)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val save = TextView(this).apply {
            text = "确认保存"; textSize = 15f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = roundBg(dp(12), accent)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
            setOnClickListener {
                save()
                status.setTextColor(green)
                status.text = "已保存 ✔ 现在回到聊天窗口就能用了"
            }
        }
        val test = TextView(this).apply {
            text = "保存并测试连接"; textSize = 14f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setTextColor(accent)
            background = roundBg(dp(12), Color.WHITE, stroke = true)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { save(); testConnection() }
        }
        row.addView(save)
        row.addView(test)
        bar.addView(row)
        return bar
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        container.addView(text("接口设置", 22f, ink, bold = true))
        container.addView(text("填一个第三方大模型接口就能用。地址、密钥、模型三样齐全即可。",
            12f, sub).apply { setPadding(0, dp(6), 0, dp(6)) })

        // ---- provider
        container.addView(sectionLabel("服务商"))
        val c1 = card()
        c1.addView(fieldLabel("服务商预设"))
        providerSpinner = spinner(LlmPresets.labels)
        providerSpinner.onItemSelectedListener = simpleListener { pos ->
            if (loading) return@simpleListener
            if (pos == appliedProviderPos) return@simpleListener
            appliedProviderPos = pos
            val preset = LlmPresets.all[pos]
            if (preset.id != "custom") {
                protocolSpinner.setSelection(LlmPresets.protocolIds.indexOf(preset.protocol).coerceAtLeast(0))
                baseUrlEdit.setText(preset.baseUrl)
                modelEdit.setText(preset.model)
            }
            hint.text = preset.hint.ifBlank { LlmPresets.protocolLabel(preset.protocol) }
            status.text = ""
        }
        c1.addView(providerSpinner)
        hint = text("", 11f, sub).apply { setPadding(0, dp(6), 0, 0) }
        c1.addView(hint)
        container.addView(c1)

        // ---- the API
        container.addView(sectionLabel("接口"))
        val c2 = card()
        c2.addView(fieldLabel("协议（决定请求格式）"))
        protocolSpinner = spinner(LlmPresets.protocolLabels)
        c2.addView(protocolSpinner)
        c2.addView(fieldLabel("接口地址（base URL）"))
        baseUrlEdit = edit("例如 https://api.deepseek.com/v1", singleLine = true)
        baseUrlEdit.inputType = InputType.TYPE_TEXT_VARIATION_URI
        c2.addView(baseUrlEdit)
        c2.addView(fieldLabel("API Key"))
        keyEdit = edit("粘贴你的密钥", singleLine = true)
        keyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        c2.addView(keyEdit)
        c2.addView(fieldLabel("模型名"))
        modelEdit = edit("例如 deepseek-chat", singleLine = true)
        c2.addView(modelEdit)
        container.addView(c2)

        // ---- advanced
        container.addView(sectionLabel("高级（可留空）"))
        val c3 = card()
        c3.addView(fieldLabel("temperature"))
        tempEdit = edit("留空则用服务商的默认值（推理模型建议留空）", singleLine = true)
        tempEdit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        c3.addView(tempEdit)
        c3.addView(fieldLabel("回复长度上限（token）"))
        maxTokensEdit = edit("1024", singleLine = true)
        maxTokensEdit.inputType = InputType.TYPE_CLASS_NUMBER
        c3.addView(maxTokensEdit)
        c3.addView(fieldLabel("额外请求头（每行一条 Name: value）"))
        headersEdit = edit("一般不用填", singleLine = false)
        headersEdit.minLines = 2
        c3.addView(headersEdit)
        container.addView(c3)

        // ---- style / behaviour
        container.addView(sectionLabel("回复风格与行为"))
        val c4 = card()
        c4.addView(fieldLabel("对方是谁 / 关系"))
        relationshipEdit = edit("例如：对方是我的女朋友，我们在一起两年了", singleLine = false)
        relationshipEdit.minLines = 2
        c4.addView(relationshipEdit)

        autoAnalyzeSwitch = switchRow(c4, "对方发来新消息就自动分析", "关掉后只在点悬浮球时分析")
        noThinkingSwitch = switchRow(c4, "让模型别想太久（更快，推荐）",
            "推理模型会先想一大段再回答，光想就要 10-25 秒；关掉后实测 3-5 秒。" +
                "个别接口不认识这个参数，程序会自动忽略它，不会报错")
        ocrFallbackSwitch = switchRow(c4, "读不到文字时用截屏识别", "本地 OCR，截图不上传")
        ocrAutoSwitch = switchRow(c4, "截屏识别后也自动分析", "关掉更省，点一下才分析")
        genericSwitch = switchRow(c4, "自动识别其它聊天软件",
            "没有专门适配的软件（钉钉、Telegram 等），只要看着像聊天窗口就自动读")
        wechatSwitch = switchRow(c4, "读取微信",
            "微信把消息正文藏起来了，读它要截屏识别；不想被截图就关掉这一项")
        autoFillSwitch = switchRow(c4, "自动把推荐回复填进输入框",
            "省一次点击；发送永远要你自己按")

        c4.addView(fieldLabel("悬浮窗不透明度"))
        opacityBar = SeekBar(this).apply {
            max = 40   // 60..100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        c4.addView(opacityBar)

        c4.addView(fieldLabel("只对哪些会话生效（每行一个关键词，留空=全部）"))
        whitelistEdit = edit("留空表示所有会话都生效", singleLine = false)
        whitelistEdit.minLines = 2
        c4.addView(whitelistEdit)
        container.addView(c4)

        container.addView(text(
            "提示：换服务商后点下面的「保存并测试连接」，状态行会告诉你是密钥、地址还是模型的问题。",
            11f, sub).apply { setPadding(dp(4), dp(16), dp(4), 0) })
    }

    // ---------------------------------------------------------------- load/save

    private fun load() {
        loading = true
        val preset = LlmPresets.byId(prefs.providerId)
        val providerPos = LlmPresets.all.indexOfFirst { it.id == preset.id }.coerceAtLeast(0)
        providerSpinner.setSelection(providerPos)
        appliedProviderPos = providerPos
        protocolSpinner.setSelection(
            LlmPresets.protocolIds.indexOf(prefs.protocol).coerceAtLeast(0))
        baseUrlEdit.setText(prefs.baseUrl)
        keyEdit.setText(prefs.apiKey)
        modelEdit.setText(prefs.model)
        tempEdit.setText(prefs.temperature)
        maxTokensEdit.setText(prefs.maxTokens.toString())
        headersEdit.setText(prefs.extraHeaders)
        relationshipEdit.setText(prefs.relationship)
        whitelistEdit.setText(prefs.whitelist.joinToString("\n"))
        autoAnalyzeSwitch.isChecked = prefs.autoAnalyze
        ocrFallbackSwitch.isChecked = prefs.ocrFallback
        ocrAutoSwitch.isChecked = prefs.ocrAutoAnalyze
        genericSwitch.isChecked = prefs.genericChatDetection
        wechatSwitch.isChecked = prefs.wechatEnabled
        autoFillSwitch.isChecked = prefs.autoFill
        noThinkingSwitch.isChecked = prefs.noThinking
        opacityBar.progress = (prefs.overlayOpacity - 60).coerceIn(0, 40)
        hint.text = preset.hint.ifBlank { LlmPresets.protocolLabel(preset.protocol) }
        status.text = ""
        loading = false
    }

    private fun save() {
        prefs.providerId = LlmPresets.all[providerSpinner.selectedItemPosition].id
        prefs.protocol = LlmPresets.protocolIds[protocolSpinner.selectedItemPosition]
        prefs.baseUrl = baseUrlEdit.text.toString()
        prefs.apiKey = keyEdit.text.toString()
        prefs.model = modelEdit.text.toString()
        prefs.temperature = tempEdit.text.toString()
        prefs.maxTokens = maxTokensEdit.text.toString().trim().toIntOrNull() ?: 1024
        prefs.extraHeaders = headersEdit.text.toString()
        prefs.relationship = relationshipEdit.text.toString()
        prefs.whitelist = whitelistEdit.text.toString()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.autoAnalyze = autoAnalyzeSwitch.isChecked
        prefs.ocrFallback = ocrFallbackSwitch.isChecked
        prefs.ocrAutoAnalyze = ocrAutoSwitch.isChecked
        prefs.genericChatDetection = genericSwitch.isChecked
        prefs.wechatEnabled = wechatSwitch.isChecked
        prefs.autoFill = autoFillSwitch.isChecked
        prefs.noThinking = noThinkingSwitch.isChecked
        prefs.overlayOpacity = 60 + opacityBar.progress
    }

    // ---------------------------------------------------------------- test

    private fun testConnection() {
        if (!prefs.hasKey()) {
            status.setTextColor(red)
            status.text = "地址、密钥、模型都要填"
            return
        }
        status.setTextColor(sub)
        status.text = "正在测试…"
        val snapshot = prefs.apiKey
        io.execute {
            val result = runCatching { LlmClient(prefs, this@SettingsActivity).ping() }
            runOnUiThread {
                // The user may have typed a different key while the call was in
                // flight; the answer then belongs to the old one.
                if (prefs.apiKey != snapshot) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        status.setTextColor(green)
                        status.text = "连接成功，模型回复：${it.take(40)}"
                    },
                    onFailure = {
                        status.setTextColor(red)
                        status.text = "连接失败：${it.message}"
                    }
                )
            }
        }
    }

    // ---------------------------------------------------------------- atoms

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(dp(14), Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun sectionLabel(t: String) = text(t, 12f, sub, bold = true).apply {
        setPadding(dp(2), dp(16), 0, dp(2))
    }

    private fun fieldLabel(t: String) = text(t, 12f, sub).apply {
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun edit(hintText: String, singleLine: Boolean): EditText = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#F7F8FA"))
            setStroke(dp(1), Color.parseColor("#E5E7EB"))
        }
        if (singleLine) {
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@SettingsActivity,
            android.R.layout.simple_spinner_dropdown_item, items)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun switchRow(parent: LinearLayout, title: String, desc: String): SwitchCompat {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 14f, ink))
        left.addView(text(desc, 11f, sub).apply { setPadding(0, dp(2), 0, 0) })
        val sw = SwitchCompat(this)
        row.addView(left)
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun roundBg(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }

    /** Minimal OnItemSelectedListener without the boilerplate. */
    private fun simpleListener(onSelect: (Int) -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) = onSelect(position)

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
}
