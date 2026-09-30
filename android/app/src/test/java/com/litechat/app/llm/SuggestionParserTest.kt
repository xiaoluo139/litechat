package com.litechat.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models do not reliably return the JSON they were asked for, so the parser's
 * tolerance is the thing worth pinning down.
 */
class SuggestionParserTest {

    @Test fun parsesTheDocumentedShape() {
        val s = SuggestionParser.parse(
            """{"intent":"催进度","danger":3,"advice":"给个时间","replies":[
                 {"text":"今晚给你","pct":70},{"text":"等我半小时","pct":20},
                 {"text":"抱歉拖了","pct":10}]}""")
        assertEquals("催进度", s.intent)
        assertEquals(3, s.danger)
        assertEquals("给个时间", s.advice)
        assertEquals(3, s.replies.size)
        assertEquals("今晚给你", s.replies[0].text)
        assertEquals(70, s.replies[0].pct)
        assertNull(s.error)
    }

    @Test fun survivesAMarkdownFenceAndPreamble() {
        val s = SuggestionParser.parse(
            "好的，这是建议：\n```json\n{\"intent\":\"闲聊\",\"danger\":1," +
                "\"advice\":\"随便回\",\"replies\":[\"在呢\",\"刚看到\",\"哈哈哈\"]}\n```\n希望有用！")
        assertEquals("闲聊", s.intent)
        assertEquals(3, s.replies.size)
        assertEquals("在呢", s.replies[0].text)
    }

    @Test fun bareStringRepliesGetSpreadPercentages() {
        val s = SuggestionParser.parse("""{"replies":["甲","乙","丙"]}""")
        assertEquals(3, s.replies.size)
        assertTrue("unscored replies must still be ordered", s.replies[0].pct > s.replies[2].pct)
    }

    @Test fun anOutOfRangeDangerBecomesUnknown() {
        assertNull(SuggestionParser.parse("""{"danger":99,"replies":["a"]}""").danger)
        assertNull(SuggestionParser.parse("""{"danger":0,"replies":["a"]}""").danger)
    }

    @Test fun plainProseFallsBackToThreeLines() {
        val s = SuggestionParser.parse("好的\n- 第一句\n- 第二句\n- 第三句\n- 第四句")
        assertNull(s.intent)
        assertEquals(3, s.replies.size)
        assertEquals("好的", s.replies[0].text)
    }

    @Test fun emptyOutputProducesNoRepliesRatherThanGarbage() {
        assertTrue(SuggestionParser.parse("   ").replies.isEmpty())
    }

    @Test fun jsonSurvivesProseAroundIt() {
        // The old parser sliced from the FIRST { to the LAST }, so one brace in
        // the model's prose broke the whole answer and the panel fell back to
        // showing its notes as the candidate replies.
        val s = SuggestionParser.parse(
            "我先把结构说清楚 {\"这里只是个例子\"}\n想好了：\n" +
                "{\"replies\":[\"我马上整理结论发你\",\"半小时内先给你初步结论\"," +
                "\"今天肯定给，先发简要版\"],\"intent\":\"催进度\",\"danger\":6," +
                "\"advice\":\"先给明确时间\"}")
        assertEquals("催进度", s.intent)
        assertEquals("先给明确时间", s.advice)
        assertEquals(3, s.replies.size)
        assertEquals("我马上整理结论发你", s.replies[0].text)
    }

    @Test fun aReasoningDumpIsNotShownAsReplies() {
        // What a reasoning model sends when its output budget runs out
        // mid-thought: `content` never arrives and the notes are all there is.
        val s = SuggestionParser.parse(
            "\n1.  **分析输入：**\n    *   **会话：** 轻聊测试会话\n" +
                "    *   **要回的那句：** 客户那边催得有点急\n" +
                "2.  **分析意图与危险度**：\n    *   约束条件：三条回复都必须直接回应\n" +
                "    *   构思回复：第一条稳妥\n")
        assertTrue("notes are not replies", s.replies.isEmpty())
    }

    @Test fun theAnswerAfterLongReasoningIsStillFound() {
        val s = SuggestionParser.parse(
            "*   **分析输入：**\n*   **约束条件：**\n*   **构思：**\n" +
                "{\"replies\":[\"甲\",\"乙\",\"丙\"],\"intent\":\"闲聊\",\"danger\":1}")
        assertEquals("闲聊", s.intent)
        assertEquals(listOf("甲", "乙", "丙"), s.replies.map { it.text })
    }

    @Test fun theAdvisorStanceComesBackToo() {
        val s = SuggestionParser.parse(
            """{"stance":"他在借客户施压","replies":["甲","乙","丙"],""" +
                """"intent":"催进度","danger":5,"advice":"先稳客户"}""")
        assertEquals("他在借客户施压", s.stance)
        assertEquals(3, s.replies.size)
    }

    @Test fun thePlainAssistantHasNoStance() {
        assertNull(SuggestionParser.parse("""{"replies":["甲"]}""").stance)
    }

    @Test fun onlyThreeRepliesSurvive() {
        // Measured with thinking off: LongCat answered with six near-duplicates.
        // The panel shows three candidates; extras must not push their way in.
        val s = SuggestionParser.parse("""{"replies":["甲","乙","丙","丁","戊","己"]}""")
        assertEquals(3, s.replies.size)
        assertEquals(listOf("甲", "乙", "丙"), s.replies.map { it.text })
    }
}
