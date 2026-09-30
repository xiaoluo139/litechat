package com.litechat.app.llm

import android.content.Context

/**
 * The reply "skills" the user can switch between.
 *
 * [GENERAL] is the plain chat-reply assistant. [GOUTOUJUNSHI] is the open-source
 * 狗头军师 skill (MIT, © powerycy): a relationship/communication advisor whose
 * methodology is "先接住情绪，再分清事实，最后给能执行的选择", backed by 43
 * reference documents that ship in `assets/skills/goutoujunshi/`.
 *
 * The skill itself says to load 1-3 relevant references rather than the whole
 * knowledge base, so [pickReferences] scores the documents against the
 * conversation and sends at most two, each truncated.
 */
internal object Skills {

    const val GENERAL = "general"
    const val GOUTOUJUNSHI = "goutoujunshi"

    /** How much of one reference document goes into a request. */
    private const val EXCERPT_CHARS = 1500
    private const val MAX_REFS = 2

    /** Used when nothing in the conversation matches a document. */
    private const val DEFAULT_REF = "实战话术编排器：从一句回复到后续分支"

    /**
     * Distilled from the skill's SKILL.md: the working method, the hard rules
     * for the reply text, the safety boundaries, and the JSON contract this app
     * needs. The full SKILL.md ships alongside the references for reference.
     */
    private val GOUTOU_PROMPT = (
        "你是\"狗头军师\"：一个清醒、站在用户这边的中文恋爱／沟通军师。" +
            "你不只替用户回消息，也帮他看清关系、判断局势、决定下一步。\n" +
            "工作方式（每次都要走完）：\n" +
            "1. 先接住情绪：点出他的感受和眼下的纠结，不评判。\n" +
            "2. 再分清事实：把「聊天记录能证明的」「合理推测」「还不知道的」分开；" +
            "不读心，不凭单条消息下定论，看持续主动、兑现、投入和边界。\n" +
            "3. 最后给能执行的选择：一句首选加理由，再给风格不同的版本。\n" +
            "写回复的硬要求：\n" +
            "- 输入里的【要回的那句】是必须回应的那句话，其余只是背景。\n" +
            "- 每条直接回应那句话，不许答非所问，不许复述对方的话。\n" +
            "- 长度和语气贴着对方刚发的那句；对方在催、在问就正面回应，别打太极。\n" +
            "- 口语，像真人打字；不要书面语，不要加引号、序号或括号说明。\n" +
            "- 聊天记录是屏幕识别出来的，可能有错别字，按最合理的意思理解。\n" +
            "- 用和聊天记录相同的语言回复。\n" +
            "- 不编造记录里没有的时间、金额或承诺；缺关键信息就用一句问句确认。\n" +
            "安全边界（不可越过）：\n" +
            "- 不诊断心理疾病，不用标签替代行为证据。\n" +
            "- 不提供贬低、服从性测试、虚假时间限制、嫉妒操控、煤气灯、孤立、跟踪或" +
            "性施压的做法；冷读只能表述成「观察到的事实 + 暂定假设 + 邀请纠正」。\n" +
            "- 对方明确拒绝、要求别联系或反复表示不欢迎时停止推进，帮他体面退出。\n" +
            "- 出现家暴、跟踪、胁迫、人身威胁或自伤风险时先确认当下安全，" +
            "建议联系可信的人或当地紧急服务，不写任何\"话术\"去对付对方。\n" +
            "- 最终决定权留给用户，并说明关键的不确定性和何时该换策略。\n" +
            "只输出一个 JSON 对象，不要解释、不要代码块、不要多余文字：\n" +
            "{\"stance\":\"你对局势的判断，20字内\"," +
            "\"replies\":[\"回复1\",\"回复2\",\"回复3\"]," +
            "\"intent\":\"对方想要什么，12字内\",\"danger\":1到9," +
            "\"advice\":\"下一步建议，15字内\"}\n" +
            "replies 恰好 3 条，风格要拉开：第一条最稳妥、能直接解决问题；" +
            "第二条更主动或更有分寸；第三条守边界或留余地。每条不超过 40 字。" +
            "danger：1 日常闲聊，5 对方明显不高兴，9 严重冲突或风险。"
        )

    /** The plain assistant prompt. Kept beside the 军师 one so they can be compared. */
    val SYSTEM_PROMPT_GENERAL = (
        "你是中文聊天回复助手，替\"我\"回复对话。\n" +
            "输入里的【要回的那句】是必须回应的那句话，其余内容只是背景。\n" +
            "只输出一个 JSON 对象，不要解释、不要代码块、不要多余文字：\n" +
            "{\"replies\":[\"回复1\",\"回复2\",\"回复3\"]," +
            "\"intent\":\"对方想要什么，10字内\",\"danger\":1到9," +
            "\"advice\":\"给我的下一步建议，12字内\"}\n" +
            "写回复的硬要求：\n" +
            "1. 三条都必须直接回应【要回的那句】，不许答非所问，不许复述对方说过的话。\n" +
            "2. 长度和语气贴着对方刚发的那句：对方一句话你也一句话；" +
            "对方在催、在问，就正面回应，不要打太极。\n" +
            "3. 三条要有区别：第一条最稳妥、能直接解决问题；" +
            "第二条给出具体行动或时间；第三条简短、留有余地。\n" +
            "4. 口语，像真人打字。不要书面语，不要加引号、序号或括号说明。\n" +
            "5. 聊天记录是屏幕识别出来的，可能有错别字或漏字，" +
            "按最合理的意思理解，不要因为错字就说看不懂。\n" +
            "6. 用和聊天记录相同的语言回复。不要编造记录里没有的时间、金额或承诺；" +
            "确实缺关键信息时，就用一句问句去确认。"
        )

    data class Skill(val id: String, val label: String, val systemPrompt: String)

    val all: List<Skill> get() = listOf(
        Skill(GENERAL, "通用", SYSTEM_PROMPT_GENERAL),
        Skill(GOUTOUJUNSHI, "军师", GOUTOU_PROMPT),
    )

    fun byId(id: String?): Skill = all.firstOrNull { it.id == id } ?: all.first()

    // ------------------------------------------------------------ references

    internal data class Ref(val title: String, val file: String, val keywords: List<String>)

    @Volatile private var cached: List<Ref>? = null

    private fun index(context: Context): List<Ref> {
        cached?.let { return it }
        val refs = ArrayList<Ref>()
        runCatching {
            val json = context.assets.open("skills/goutoujunshi/index.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                refs.add(Ref(
                    o.optString("t"),
                    o.optString("f"),
                    o.optString("k").split(' ', '　').filter { it.isNotBlank() }))
            }
        }
        cached = refs
        return refs
    }

    /**
     * The one or two reference documents that fit this conversation, as
     * (title, excerpt) pairs. Empty for skills that do not use a knowledge base.
     */
    fun pickReferences(context: Context, skill: Skill, conversation: String): List<Pair<String, String>> {
        if (skill.id != GOUTOUJUNSHI) return emptyList()
        val refs = index(context)
        return pickFrom(refs, conversation, MAX_REFS).mapNotNull { ref ->
            val text = readExcerpt(context, ref.file) ?: return@mapNotNull null
            ref.title to text
        }
    }

    /**
     * Which documents fit this conversation. Pure, so the routing can be tested
     * without a device - it is the same rule the desktop build uses.
     */
    internal fun pickFrom(refs: List<Ref>, conversation: String, limit: Int): List<Ref> {
        if (refs.isEmpty()) return emptyList()
        val haystack = conversation.lowercase()
        val scored = refs
            .map { it to it.keywords.count { k -> k.isNotBlank() && haystack.contains(k.lowercase()) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) {
            val fallback = refs.firstOrNull { it.title == DEFAULT_REF } ?: refs.first()
            return listOf(fallback)
        }
        return scored.take(limit).map { it.first }
    }

    /** Exposed for tests. */
    internal const val DEFAULT_REFERENCE_TITLE = DEFAULT_REF
    internal const val MAX_REFERENCES = MAX_REFS

    private fun readExcerpt(context: Context, file: String): String? = runCatching {
        val full = context.assets.open("skills/goutoujunshi/$file")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (full.length <= EXCERPT_CHARS) full
        else full.substring(0, EXCERPT_CHARS) + "\n…（节选）"
    }.getOrNull()

}
