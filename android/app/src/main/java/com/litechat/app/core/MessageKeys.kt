package com.litechat.app.core

/**
 * Decides whether an incoming message is genuinely new.
 *
 * This is the gate in front of every model call, so getting it wrong is
 * expensive in both directions: too eager and the panel sits on "分析中…"
 * forever while OCR wobbles over the same bubble; too lax and a real message is
 * never answered.
 *
 * Split out of the capture service so it can be unit-tested without an Android
 * device - the same rules back the desktop build.
 */
internal object MessageKeys {

    /** Dropped before comparing: OCR wobbles on these between frames. */
    private val IGNORED = Regex("""[\s，。！？,.!?、~～…:：;；"'“”‘’]+""")

    /**
     * Lines a chat window draws that are not somebody's words: the timestamp
     * between messages, the date separator, and the "对方正在输入…" strip that
     * blinks in and out while the other person types. Treating any of them as a
     * new message threw the suggestions away over and over, so the user could
     * never click one.
     */
    private val NOISE_LINE = Regex(
        """^(?:""" +
            // A date separator carries an OPTIONAL trailing clock, because that
            // is how a chat client draws it: "昨天 22:18", "星期一 22:18",
            // "9月29日 22:18". Without the clock the line failed to match and
            // the timestamp was read as a message — the newest "message" on
            // screen became a time, which is what made the panel say
            // "没分清谁说的" and left the model nothing to answer.
            """(?:昨天|今天|前天|星期[一二三四五六日天]|周[一二三四五六日天])""" +
            """(?:\s*(?:上午|下午|凌晨|中午|晚上))?""" +
            """(?:\s*\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?)?""" +
            """|\d{1,2}\s*月\s*\d{1,2}\s*日""" +
            """(?:\s*(?:上午|下午|星期[一二三四五六日天]|周[一二三四五六日天]))?""" +
            """(?:\s*\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?)?""" +
            """|\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日""" +
            """(?:\s*(?:上午|下午))?(?:\s*\d{1,2}\s*[:：]\s*\d{2})?""" +
            """|(?:(?:上午|下午|凌晨|中午|晚上)\s*)?""" +
            // The recogniser often puts a space between the digits of a clock
            // ("15:46" comes back as "1 5 ： 4 6"), and requiring them adjacent
            // let the timestamp survive the filter and become the newest
            // "message" on screen.
            """\d(?:\s*\d)?\s*[:：]\s*\d(?:\s*\d)?""" +
            """(?:\s*[:：]\s*\d(?:\s*\d))?\s*(?:上午|下午|AM|PM|am|pm)?""" +
            """|[^\w\u4e00-\u9fff]+""" +
            """)$""",
        RegexOption.IGNORE_CASE)

    /**
     * System strips, matched only when the line is barely longer than the phrase
     * itself - so a real message that happens to contain the words is kept.
     */
    private val NOISE_PHRASES = listOf(
        "正在输入", "撤回了一条消息", "以上是打招呼", "以下为新消息",
        "消息已发出，但被对方拒收", "对方已开启朋友验证", "拍了拍",
        "该消息已过期", "已被领取", "通话时长", "已取消", "语音通话",
        "视频通话", "你已添加了", "现在可以开始聊天了", "新的朋友",
    )
    private const val NOISE_SLACK = 6

    /**
     * WeChat draws its own screens' names in the very strip a conversation puts
     * the contact's name in: the chat list says "微信", the contacts tab says
     * "通讯录", the moments page says "朋友圈".
     *
     * WeChat's node tree is empty, so the reader works from the picture alone
     * and has no other way to tell a conversation from the top level. Without
     * this test it OCRs the middle of the chat list, reads a column of contact
     * names as if they were messages, and offers to answer them - which is both
     * a wrong analysis and a screenshot taken every second for nothing.
     *
     * Exact matches only, on purpose: a group can be *named* anything, so a
     * loose rule would refuse to read real conversations.
     */
    private val WECHAT_TOP_LEVEL_TITLES =
        // Only the chat list. It is the one screen whose middle column is a
        // dense list of contact names that could be mistaken for messages, and
        // "微信" is not a name a chat can carry (the official account is
        // 微信团队). The other tabs were dropped on purpose: each extra entry is
        // another way for a real conversation to be refused, and WeChat
        // becoming unreadable is far worse than offering to reply to a contact
        // list once.
        setOf("微信")

    /** Is the strip above the message area the name of a WeChat top-level page? */
    fun isWeChatTopLevelScreen(title: String?): Boolean {
        val t = title?.trim() ?: return false
        if (t.isEmpty()) return false
        // "微信(3)" - a badge can end up in the same strip as the name.
        val bare = t.replace(Regex("""[（(]\d+[）)]$"""), "").trim()
        return bare in WECHAT_TOP_LEVEL_TITLES
    }

    /** Longest name worth remembering; WeChat's header caps out around here. */
    private const val MAX_NAME_LEN = 12

    /**
     * Characters that essentially never appear in the name of a chat, but show
     * up constantly when the header read goes wrong and picks up a sentence.
     */
    private val NAME_JUNK =
        "，。！？、：；“”‘’《》〈〉()（）[]【】{}<>@#￥%…&*+=|\\/!?,.:;'\"".toSet()

    /**
     * Could this plausibly be the name of a chat?
     *
     * The name comes from OCR of the strip above the messages, and when that
     * read misses it comes back as a fragment of whatever else was on screen: a
     * message preview ("姓你生活?"), a slogan, or digits and Latin letters glued
     * to Chinese ("仟始1五伦林役还 2方大同s"). All of those were remembered as
     * "conversations", so the picker filled up with entries nobody could
     * recognise - the reported "太多了，太乱了".
     *
     * Generous about real names (Chinese or Latin, up to 12 characters) and
     * strict about the shapes that are nearly always a misread.
     */
    fun plausibleName(name: String?): Boolean {
        val t = name?.trim().orEmpty()
        if (t.isEmpty() || t.length > MAX_NAME_LEN) return false
        // A name is one word: an inner space is two OCR fragments joined up.
        if (t.any { it.isWhitespace() }) return false
        // Digits and sentence punctuation do not belong in a name.
        if (t.any { it.isDigit() }) return false
        if (t.any { it in NAME_JUNK }) return false
        // A mix of scripts ("a方大") is a half-read line.
        val cjk = t.count { it.code in 0x4E00..0x9FFF }
        val latin = t.count { it.isLetter() && it.code < 128 }
        if (cjk > 0 && latin > 0) return false
        return cjk > 0 || latin > 0
    }

    /**
     * Characters the typing strip is built from. The strip gets read badly
     * often enough that matching "正在输入" exactly is not enough - it comes
     * back as things like "三在窪入一". A very short line holding two
     * *different* ones of these is chrome; the six-character cap is what keeps
     * real replies such as "现在在路上了" out of it.
     */
    private const val TYPING_MARK_CHARS = "在入输正"
    private const val TYPING_MAX_LEN = 6

    /** Below this length, two strings one character apart are different messages. */
    private const val MIN_FUZZY_LENGTH = 8

    /** Above this similarity, it is the same message read twice. */
    private const val SAME_THRESHOLD = 0.82

    /** Fingerprint of the newest incoming message, or "" when there is none. */
    fun of(messages: List<Msg>): String {
        // Only the other person's words count. Falling back to my own last
        // message would make the app analyse the user's own reply.
        val last = messages.asReversed()
            .firstOrNull { it.side == "other" && !isNoise(it.text) }
            ?.text ?: return ""
        return normalize(last).takeLast(48)
    }

    /** True for chrome the chat window draws, not something a person said. */
    fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (NOISE_LINE.matches(t)) return true
        if (NOISE_PHRASES.any { it in t && t.length <= it.length + NOISE_SLACK }) return true
        return looksLikeTypingGarble(t)
    }

    /** True for a mangled read of the blinking "对方正在输入…" strip. */
    fun looksLikeTypingGarble(t: String): Boolean {
        if (t.isEmpty() || t.length > TYPING_MAX_LEN) return false
        return t.toSet().count { it in TYPING_MARK_CHARS } >= 2
    }

    fun normalize(text: String): String = IGNORED.replace(text, "")

    /**
     * True when two header names are the same person seen through OCR.
     *
     * Header names are short, so [looksSame] would call any one-character
     * difference a different person - and a title that reads "李沅汐" one frame
     * and "李沅汐" with a stray mark the next would then wipe the panel and
     * re-ask the model every second. Only a clearly different name is a switch.
     */
    fun sameName(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x == y) return true
        // One unreadable header is not a switch: wait for a second opinion.
        if (x.isEmpty() || y.isEmpty()) return true
        // Anything shorter than three characters gets no tolerance at all.
        //
        // The old rule treated any 50%-similar pair as the same person, and a
        // two-character name is 50% similar to every name it shares a character
        // with: 老刘/老李, 老王/老张, 李四/刘四. In a chat app that is the
        // normal length of a contact's name, so switching between two of them
        // looked like "the same person redrawing", the panel kept the previous
        // person's candidates and the new person was never answered - "我都切到
        // 下一个联系人了，回复内容还是上一个人的".
        //
        // A wrong "different" only costs one extra read; a wrong "same" answers
        // the wrong person, so the rule leans towards "different".
        if (minOf(x.length, y.length) < 3) return false
        // Longer names do get one character of slack, because OCR misreads a
        // stroke here and there - but only when the SURNAME agrees, since that
        // is the part a Chinese name can least afford to lose.
        if (x[0] != y[0]) return false
        return similarity(x, y) >= 0.66
    }

    /**
     * True when `b` is (almost certainly) the same message as `a` seen through a
     * slightly different OCR read.
     */
    fun looksSame(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        // Short messages matter too much to fold together: "好" and "嗯" are one
        // character apart and mean completely different things.
        if (minOf(a.length, b.length) < MIN_FUZZY_LENGTH) return false
        return similarity(a, b) >= SAME_THRESHOLD
    }

    /** 1.0 = identical, 0.0 = nothing in common (normalised Levenshtein). */
    fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / longest
    }

    /** Two-row Levenshtein; the strings here are at most a few dozen characters. */
    private fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
