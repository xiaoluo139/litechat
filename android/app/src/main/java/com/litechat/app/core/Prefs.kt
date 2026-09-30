package com.litechat.app.core

import android.content.Context
import com.litechat.app.llm.LlmPresets

/**
 * App-private config store. The whole model setup is ONE third-party API entry:
 * an address, a key and a model — see [baseUrl] / [apiKey] / [model]. The wire
 * format is chosen by [protocol], and the preset dropdown only pre-fills the
 * three fields.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code). Only key *lengths* are ever logged.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // ------------------------------------------------------ defensive reads
    //
    // SharedPreferences throws ClassCastException when a key is read with a
    // different type than it was written with. That used to take the whole
    // analysis down: one value left behind by an older build (or hand-written
    // into the config file) and the panel showed "出错了 /
    // java.lang.String cannot be cast to java.lang.Boolean" instead of a
    // candidate reply - while every other setting was perfectly fine.
    //
    // A wrong-typed value is now read as best it can and otherwise ignored, so
    // one bad entry costs one setting, never the feature.

    private fun boolOf(key: String, fallback: Boolean): Boolean = try {
        sp.getBoolean(key, fallback)
    } catch (_: ClassCastException) {
        when (val raw = sp.all[key]) {
            is String -> raw.equals("true", true) || raw == "1"
            is Number -> raw.toInt() != 0
            is Boolean -> raw
            else -> fallback
        }
    }

    private fun intOf(key: String, fallback: Int): Int = try {
        sp.getInt(key, fallback)
    } catch (_: ClassCastException) {
        when (val raw = sp.all[key]) {
            is String -> raw.trim().toIntOrNull() ?: fallback
            is Number -> raw.toInt()
            else -> fallback
        }
    }

    private fun stringOf(key: String, fallback: String): String = try {
        sp.getString(key, fallback) ?: fallback
    } catch (_: ClassCastException) {
        when (val raw = sp.all[key]) {
            is String -> raw
            is Number, is Boolean -> raw.toString()
            else -> fallback
        }
    }

    private fun stringSetOf(key: String): Set<String> = try {
        sp.getStringSet(key, emptySet()) ?: emptySet()
    } catch (_: ClassCastException) {
        (sp.all[key] as? String)
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    }

    // ------------------------------------------------------------ the API

    /** Preset id from [LlmPresets] ("deepseek", "openai", "custom", …). */
    var providerId: String
        get() = stringOf(K_PROVIDER, LlmPresets.default.id)
        set(v) = sp.edit().putString(K_PROVIDER, v.trim()).apply()

    /** "openai" | "anthropic" | "gemini" — decides the request/response shape. */
    var protocol: String
        get() = stringOf(K_PROTOCOL, LlmPresets.PROTOCOL_OPENAI)
        set(v) = sp.edit().putString(K_PROTOCOL, v.trim()).apply()

    /** Service root, e.g. `https://api.deepseek.com/v1`. */
    var baseUrl: String
        get() = stringOf(K_BASE, LlmPresets.default.baseUrl)
        set(v) = sp.edit().putString(K_BASE, v.trim()).apply()

    var apiKey: String
        get() = stringOf(K_KEY, "")
        set(v) = sp.edit().putString(K_KEY, v.trim()).apply()

    var model: String
        get() = stringOf(K_MODEL, LlmPresets.default.model)
        set(v) = sp.edit().putString(K_MODEL, v.trim()).apply()

    /** Extra request headers, one `Name: value` per line. Blank = none. */
    var extraHeaders: String
        get() = stringOf(K_HEADERS, "")
        set(v) = sp.edit().putString(K_HEADERS, v.trim()).apply()

    /** Blank = do not send the parameter at all (some reasoning models reject it). */
    var temperature: String
        get() = stringOf(K_TEMP, "")
        set(v) = sp.edit().putString(K_TEMP, v.trim()).apply()

    /**
     * Output cap for one reply draft. Not a target - a cap - and deliberately
     * generous: a reasoning model spends tokens thinking before it writes
     * anything, and a tight cap leaves it with nothing to answer with.
     * Measured against LongCat-2.0 on a two-line conversation: 320 tokens were
     * consumed entirely by the thinking, `content` came back missing, and the
     * panel showed the model's notes as the candidate replies.
     */
    var maxTokens: Int
        get() = intOf(K_MAXTOK, 1600)
        set(v) = sp.edit().putInt(K_MAXTOK, v.coerceIn(64, 8192)).apply()

    /**
     * Ask the model not to think out loud.
     *
     * Reasoning models generate their thinking as ordinary output tokens, and
     * that dominates the wait. Measured against LongCat-2.0 on the same 军师
     * request: 14-25 s with thinking, 3.4-5.2 s without. Nothing here shows the
     * thinking, so it is pure waiting. An endpoint that does not understand the
     * field answers 400 and [com.litechat.app.llm.LlmClient] drops it and
     * remembers that.
     */
    var noThinking: Boolean
        get() = boolOf(K_NO_THINKING, true)
        set(v) = sp.edit().putBoolean(K_NO_THINKING, v).apply()

    /**
     * The conversations the panel has actually read, newest first.
     *
     * These are the names as the app read them - for WeChat that means OCR, so a
     * name can come back slightly wrong. That is fine here: the picker lists
     * what the app saw, and pinning one of those strings matches what it will
     * see again.
     */
    var knownConversations: List<String>
        get() = stringOf(K_KNOWN, "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        set(v) = sp.edit()
            // Six, not twelve: this list is a shortcut to the handful of chats
            // somebody answers, and a long one is mostly OCR misreads.
            .putString(K_KNOWN, v.filter { it.isNotBlank() }.distinct().take(6).joinToString("\n"))
            .apply()

    /** Empty = follow whatever chat is on screen. A name = answer only that one. */
    var lockedConversation: String
        get() = stringOf(K_LOCKED, "")
        set(v) = sp.edit().putString(K_LOCKED, v.trim()).apply()

    // -------------------------------------------------------- behaviour

    /** Free-text describing who the other person is; goes into the prompt. */
    var relationship: String
        get() = stringOf(K_REL, DEFAULT_REL)
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = boolOf(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = boolOf(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = intOf(K_OPACITY, 94).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = intOf(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    var bubbleX: Int
        get() = intOf(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations".
     */
    var whitelist: Set<String>
        get() = stringSetOf(K_WHITELIST)
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /**
     * A button in the app that asks the running service to put the floating
     * window back.
     *
     * It is a counter, not a flag: every press has to reach the service, and a
     * boolean would only fire the first time. The service watches this key and
     * re-adds the overlay window on each new value (see [ChatCaptureService]).
     */
    var respawnOverlay: Int
        get() = intOf(K_RESPAWN, 0)
        set(v) = sp.edit().putInt(K_RESPAWN, v).apply()

    /** Fall back to screenshot + on-device OCR when an app's tree has no text. */
    var ocrFallback: Boolean
        get() = boolOf(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    /** Also auto-analyze snapshots that came from OCR (off: costs a screenshot). */
    var ocrAutoAnalyze: Boolean
        get() = boolOf(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    /**
     * Watch apps that have no dedicated adapter. When the foreground app looks
     * like a chat window (an input box near the bottom plus a column of message
     * text), read it with screenshot + OCR and analyze it automatically, so the
     * user never has to tap "截屏识别一次" by hand.
     */
    var genericChatDetection: Boolean
        get() = boolOf(K_GENERIC, true)
        set(v) = sp.edit().putBoolean(K_GENERIC, v).apply()

    /**
     * Put the top candidate straight into the chat input box as soon as it is
     * generated. Sending still needs a human finger - this only saves the tap.
     */
    var autoFill: Boolean
        get() = boolOf(K_AUTO_FILL, false)
        set(v) = sp.edit().putBoolean(K_AUTO_FILL, v).apply()

    /**
     * Read WeChat (com.tencent.mm). On by default because it is the app most
     * people want, but switchable: WeChat hides its message bodies from
     * accessibility services, so reading it means screenshot + OCR, and
     * screenshots are what WeChat's own risk control watches for.
     */
    var wechatEnabled: Boolean
        get() = boolOf(K_WECHAT, true)
        set(v) = sp.edit().putBoolean(K_WECHAT, v).apply()

    /**
     * Which reply skill to use: "general" or "goutoujunshi". Chosen with the
     * buttons on the floating panel; kept so the choice survives a restart.
     */
    var skillId: String
        get() = stringOf(K_SKILL, "general")
        set(v) = sp.edit().putString(K_SKILL, v.trim()).apply()

    // ---------------------------------------------------------- helpers

    fun hasKey(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * The full POST URL for one chat round trip.
     *
     * A user who pastes a complete endpoint (…/chat/completions, …/messages or
     * …:generateContent) gets it used verbatim, so any odd gateway still works
     * without hand-editing the protocol dropdown.
     */
    fun endpoint(): String {
        return com.litechat.app.llm.Endpoints.build(protocol, baseUrl, model)
    }

    /** Parsed `Name: value` lines from [extraHeaders]. */
    fun headerMap(): Map<String, String> {
        val raw = extraHeaders.trim()
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('\n').forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val name = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (name.isNotEmpty()) out[name] = value
            }
        }
        return out
    }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    companion object {
        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "litechat_assistant"

        /** Public so the service can watch it; see [respawnOverlay]. */
        const val K_RESPAWN_OVERLAY = "respawn_overlay"

        private const val K_PROVIDER = "provider_id"
        private const val K_PROTOCOL = "protocol"
        private const val K_BASE = "base_url"
        private const val K_KEY = "api_key"
        private const val K_MODEL = "model"
        private const val K_HEADERS = "extra_headers"
        private const val K_TEMP = "temperature"
        private const val K_MAXTOK = "max_tokens"
        private const val K_NO_THINKING = "no_thinking"
        private const val K_KNOWN = "known_conversations"
        private const val K_LOCKED = "locked_conversation"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_AUTO = "auto_analyze"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_WHITELIST = "whitelist"
        private const val K_RESPAWN = K_RESPAWN_OVERLAY
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_GENERIC = "generic_chat_detection"
        private const val K_AUTO_FILL = "auto_fill"
        private const val K_WECHAT = "wechat_enabled"
        private const val K_SKILL = "skill_id"

        const val DEFAULT_REL = "对方是我的朋友，我们平时随便聊"
    }
}
