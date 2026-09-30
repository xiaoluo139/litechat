package com.litechat.app.llm

import com.litechat.app.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation view the model is given. Getting this wrong is exactly how
 * replies end up answering the wrong line.
 */
class PromptBuilderTest {

    private fun convo(vararg pairs: Pair<String, String>) =
        pairs.map { Msg(it.first, it.second) }

    @Test fun namesTheLineThatMustBeAnswered() {
        val prompt = PromptBuilder.userPrompt("老板", "对方是我的老板", convo(
            "other" to "材料看过了吗",
            "me" to "看了一半",
            "other" to "客户催得急",
        ), sidesKnown = true)
        assertTrue(prompt.contains("【要回的那句】客户催得急"))
        assertTrue(prompt.contains("对方：材料看过了吗"))
        assertTrue(prompt.contains("我：看了一半"))
        // the line to answer must not also sit in the background
        assertEquals(1, Regex("客户催得急").findAll(prompt).count())
    }

    @Test fun warnsTheModelWhenSidesAreUnknown() {
        val prompt = PromptBuilder.userPrompt("老板", "同事", convo(
            "other" to "你好",
            "other" to "我刚发出去的那句",
        ), sidesKnown = false)
        assertTrue(prompt.contains("没能确定"))
        assertTrue(prompt.contains("如果是我自己刚发的，就回复它前面那句"))
        assertFalse(prompt.contains(PromptBuilder.LINE_TO_ANSWER))
        // no fabricated speaker labels in this shape
        assertFalse(prompt.contains("对方："))
    }

    @Test fun relationshipIsAlwaysCarried() {
        val prompt = PromptBuilder.userPrompt(null, "对方是我的老板", emptyList(), true)
        assertTrue(prompt.contains("【关系】对方是我的老板"))
    }

    @Test fun blankRelationshipSaysSo() {
        val prompt = PromptBuilder.userPrompt(null, "   ", emptyList(), true)
        assertTrue(prompt.contains("【关系】未说明"))
    }

    @Test fun onlyTheTailIsSent() {
        val many = (1..40).map { Msg("other", "第%02d句".format(it)) }
        val prompt = PromptBuilder.userPrompt(null, "朋友", many, sidesKnown = true, limit = 5)
        assertTrue(prompt.contains("第40句"))
        assertFalse(prompt.contains("第30句"))
    }

    // ---- the anchor is THEIR newest line, not the last line on screen -------

    @Test fun theAnchorIsNotMyOwnMessage() {
        // The reported failure: the user had already replied, so the last line
        // on screen was their own - and the model was told to answer it.
        val prompt = PromptBuilder.userPrompt("陆林晖", "女朋友", convo(
            "other" to "宝宝我去洗澡澡啦",
            "me" to "好哒",
        ), sidesKnown = true)
        assertTrue(prompt.contains("【要回的那句】宝宝我去洗澡澡啦"))
        assertFalse(prompt.contains("【要回的那句】好哒"))
    }

    @Test fun myReplyAfterTheAnchorIsFlaggedAsAlreadySent() {
        val prompt = PromptBuilder.userPrompt(null, "女朋友", convo(
            "other" to "宝宝我去洗澡澡啦",
            "me" to "好哒",
        ), sidesKnown = true)
        assertTrue(prompt.contains("【这句话之后我已经说过（别重复）】"))
        assertTrue(prompt.contains("我：好哒"))
    }

    @Test fun backgroundHoldsEverythingBeforeTheAnchor() {
        val prompt = PromptBuilder.userPrompt("老板", "同事", convo(
            "me" to "早",
            "other" to "材料看了吗",
            "me" to "看了一半",
            "other" to "今天能给个说法吗",
        ), sidesKnown = true)
        assertTrue(prompt.contains("【要回的那句】今天能给个说法吗"))
        assertTrue(prompt.contains("对方：材料看了吗"))
        assertTrue(prompt.contains("我：看了一半"))
        assertFalse(prompt.contains("（别重复）"))   // nothing after the anchor
    }

    @Test fun aScreenWithOnlyMyOwnWordsHasNoAnchor() {
        val prompt = PromptBuilder.userPrompt(null, "朋友", convo(
            "me" to "在吗",
            "me" to "睡了吗",
        ), sidesKnown = true)
        assertFalse(prompt.contains("【要回的那句】"))
        assertTrue(prompt.contains("只有我自己说过的话"))
    }
}
