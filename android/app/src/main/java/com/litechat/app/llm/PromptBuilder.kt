package com.litechat.app.llm

import com.litechat.app.core.Msg

/**
 * How one conversation is handed to the model.
 *
 * This is the difference between a reply that answers the right line and one
 * that reads as if it belongs to another chat. Two shapes:
 *
 *  * sides known - name the single line that must be answered, and give the
 *    rest as background;
 *  * sides unknown (a flat screen read, or a window showing only one direction)
 *    - hand over the transcript and say outright that the last line might be the
 *    user's own, so the model must not answer the user's own words. Silently
 *    labelling everything "对方" is what caused off-topic replies.
 *
 * Split out of [LlmClient] so it can be unit-tested without a device.
 */
internal object PromptBuilder {

    const val LINE_TO_ANSWER = "【要回的那句】"

    fun userPrompt(
        who: String?,
        relationship: String,
        messages: List<Msg>,
        sidesKnown: Boolean,
        limit: Int = 20
    ): String {
        val recent = messages.takeLast(limit)
        val sb = StringBuilder()
        if (!who.isNullOrBlank()) sb.append("【会话】").append(who).append('\n')
        sb.append("【关系】").append(relationship.ifBlank { "未说明" })
        if (recent.isEmpty()) return sb.toString()
        sb.append('\n')

        if (sidesKnown) {
            // Anchor on the newest line that is actually THEIRS - not simply the
            // last line on screen. The last line is very often the user's own
            // (they just sent something, or the window is scrolled to their own
            // message), and telling the model "this is the line you must answer"
            // while pointing at the user's own words is exactly how replies end
            // up belonging to another conversation.
            val anchor = recent.indexOfLast { it.side == "other" }
            if (anchor < 0) {
                // Everything on screen is the user's own doing: there is nothing
                // to answer, so hand over the transcript and let the model judge.
                sb.append("【提示】这一屏只有我自己说过的话，没有对方的新消息。")
                sb.append("如果确实需要接话，请针对上面最后一句继续。\n")
                sb.append("【看到的对话，越靠下越新】\n")
                for (m in recent) sb.append(m.text).append('\n')
                return sb.toString().trimEnd()
            }
            sb.append(LINE_TO_ANSWER).append(recent[anchor].text).append('\n')
            if (anchor > 0) {
                sb.append("【上文，越靠下越新】\n")
                for (m in recent.subList(0, anchor)) {
                    sb.append(if (m.side == "me") "我：" else "对方：").append(m.text).append('\n')
                }
            }
            val after = recent.subList(anchor + 1, recent.size)
            if (after.isNotEmpty()) {
                // Usually the user's own messages. Without saying so the model
                // cheerfully drafts a reply to a line that has already been
                // answered.
                sb.append("【这句话之后我已经说过（别重复）】\n")
                for (m in after) sb.append("我：").append(m.text).append('\n')
            }
        } else {
            sb.append("【提示】这一屏没能确定哪句是我说的、哪句是对方说的。")
            sb.append("请先判断最后一条是谁发的：如果是对方发的，就回复它；")
            sb.append("如果是我自己刚发的，就回复它前面那句。\n")
            sb.append("【看到的对话，越靠下越新】\n")
            for (m in recent) sb.append(m.text).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
