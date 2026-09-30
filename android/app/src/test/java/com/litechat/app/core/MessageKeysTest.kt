package com.litechat.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of every model call. Getting it wrong is expensive in both
 * directions: too eager and the panel never leaves "分析中…"; too lax and a real
 * message goes unanswered.
 */
class MessageKeysTest {

    private fun incoming(vararg texts: String) = texts.map { Msg("other", it) }

    @Test fun punctuationAndSpacingDoNotMakeANewMessage() {
        val a = MessageKeys.of(incoming("客户那边催得有点急，今天能给个说法吗"))
        val b = MessageKeys.of(incoming("客户那边催得有点急 今天能给个说法吗 "))
        assertEquals(a, b)
    }

    @Test fun asmallOcrErrorIsTheSameMessage() {
        val a = MessageKeys.of(incoming("客户那边催得有点急，今天能给个说法吗"))
        val b = MessageKeys.of(incoming("客户那边催得有急，今天育个说法吗"))
        assertTrue(MessageKeys.looksSame(a, b))
    }

    @Test fun aGenuinelyNewMessageIsNotSwallowed() {
        val a = MessageKeys.of(incoming("客户那边催得有点急，今天能给个说法吗"))
        val b = MessageKeys.of(incoming("那下午三点我们再对一遍吧"))
        assertFalse(MessageKeys.looksSame(a, b))
    }

    @Test fun shortRepliesAreNeverFoldedTogether() {
        assertFalse(MessageKeys.looksSame(
            MessageKeys.of(incoming("好")), MessageKeys.of(incoming("嗯"))))
        assertFalse(MessageKeys.looksSame(
            MessageKeys.of(incoming("行")), MessageKeys.of(incoming("不行"))))
    }

    @Test fun onlyTheNewestIncomingMessageCounts() {
        val a = MessageKeys.of(listOf(Msg("other", "旧消息"), Msg("other", "新的那句")))
        val b = MessageKeys.of(listOf(Msg("other", "旧消息被读歪了"), Msg("other", "新的那句")))
        assertEquals(a, b)
    }

    @Test fun myOwnMessageIsNotTreatedAsIncoming() {
        val key = MessageKeys.of(listOf(Msg("other", "对方说的"), Msg("me", "我回的")))
        assertEquals(MessageKeys.normalize("对方说的"), key)
    }

    @Test fun emptyHistoryProducesNoKey() {
        assertEquals("", MessageKeys.of(emptyList()))
        assertEquals("", MessageKeys.of(incoming("")))
        assertEquals("", MessageKeys.of(listOf(Msg("me", "只有我说话"))))
    }

    @Test fun similarityIsSane() {
        assertEquals(1.0, MessageKeys.similarity("一样的话", "一样的话"), 0.0001)
        assertTrue(MessageKeys.similarity("abcdefgh", "abcdefgh") == 1.0)
        assertTrue(MessageKeys.similarity("abcdefgh", "zzzzzzzz") < 0.2)
    }

    // ---- chrome the chat window draws is not somebody's words -------------

    @Test fun timestampsAndDatesAreNoise() {
        for (text in listOf("14:33", "14：33", "下午 2:30", "2026年9月29日",
                            "9月29日", "昨天", "今天", "星期一", "周三")) {
            assertTrue("should be noise: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun aDateSeparatorWithAClockOnItIsAlsoNoise() {
        // Exactly what a client draws between messages, and the format that used
        // to slip through and be read as a message.
        for (text in listOf("昨天 22：18", "昨天 22:35", "星期一 22:18",
                            "9月29日 22:18", "2026年9月29日 22:18", "22:18:35")) {
            assertTrue("should be noise: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun aRealMessageThatStartsLikeADateIsKept() {
        for (text in listOf("今天下午三点见", "9月29日开会", "昨天那事我想想", "我在长沙")) {
            assertFalse("should be kept: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun systemStripsAreNoise() {
        for (text in listOf("对方正在输入…", "你撤回了一条消息", "“张三”撤回了一条消息",
                            "以下为新消息", "以上是打招呼的内容",
                            "消息已发出，但被对方拒收了", "“李四”拍了拍你",
                            "通话时长 00:31")) {
            assertTrue("should be noise: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun realMessagesSurviveTheFilter() {
        for (text in listOf("好", "嗯", "在吗", "1", "ok", "明天见", "下午三点见",
                            "我看了，已读不回是吧", "我撤回了一条消息，你收到了吗")) {
            assertFalse("should be kept: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun mangledTypingStripsAreNoise() {
        // Measured on a real crop: "对方正在输入…" comes back like this often
        // enough that matching the phrase exactly is not enough.
        for (text in listOf("三在窪入一", "对方正在输λ", "正在输入", "对方在输入")) {
            assertTrue("should be noise: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun aRealSentenceAboutTypingIsKept() {
        // The garble rule is capped at six characters on purpose.
        for (text in listOf("我在输入法里找不到那个字", "正在开会，等会说", "现在在路上了")) {
            assertFalse("should be kept: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun chromeIsSkippedWhenPickingTheNewestMessage() {
        // Exactly the flicker that made the panel throw suggestions away: the
        // "对方正在输入…" strip and a timestamp appearing under the last message.
        val key = MessageKeys.of(listOf(
            Msg("other", "客户那边催得有点急"),
            Msg("other", "14: 33"),
            Msg("other", "对方正在输入…"),
        ))
        assertEquals(MessageKeys.of(listOf(Msg("other", "客户那边催得有点急"))), key)
    }

    @Test fun wechatTopLevelScreensAreNotConversations() {
        // WeChat's node tree is empty, so the only way to tell its chat list from
        // a conversation is the name in the strip above the messages. Reading the
        // chat list as a conversation handed the model a column of contact names.
        //
        // Only the chat list is refused. Every extra entry was another way for a
        // real conversation to be turned away, and WeChat going unreadable is a
        // much worse failure than offering to answer a contact list once.
        for (title in listOf("微信", "微信(3)", "微信（12）")) {
            assertTrue("should be a top-level screen: $title",
                MessageKeys.isWeChatTopLevelScreen(title))
        }
    }

    @Test fun theOtherWeChatTabsAreReadRatherThanRefused() {
        // 通讯录 / 发现 / 我 / 朋友圈 are no longer treated as "not a
        // conversation": the reader would rather look at a list than refuse a
        // chat, and a group can legitimately be called any of these.
        for (title in listOf("通讯录", "发现", "我", "朋友圈")) {
            assertFalse("should be read, not refused: $title",
                MessageKeys.isWeChatTopLevelScreen(title))
        }
    }

    @Test fun aContactNameThatMerelyStartsWithChromeWordsIsStillAConversation() {
        // A rule that matched on "starts with" would refuse to read real chats:
        // groups and contacts are named freely.
        for (title in listOf("微信团队", "我发现了一个宝藏店", "通讯录备份", "朋友圈代购",
                             "李沅汐", "陆林晖", "项目群")) {
            assertFalse("should still be a conversation: $title",
                MessageKeys.isWeChatTopLevelScreen(title))
        }
    }

    @Test fun anUnreadableTitleIsNotTreatedAsATopLevelScreen() {
        // Fail open: no title means "cannot tell", and refusing to read would
        // look exactly like the app being broken.
        for (title in listOf(null, "", "   ")) {
            assertFalse(MessageKeys.isWeChatTopLevelScreen(title))
        }
    }

    @Test fun twoShortNamesThatShareACharacterAreDifferentPeople() {
        // The reported bug: after switching to the next contact the panel kept
        // answering the previous one. The old rule called any 50%-similar pair
        // "the same person", and a two-character name is 50% similar to every
        // name it shares a character with - so the switch was never noticed.
        val pairs = listOf(
            "老刘" to "老李",
            "老王" to "老张",
            "张三" to "张四",
            "李四" to "刘四",
            "小王" to "小李",
        )
        for ((a, b) in pairs) {
            assertFalse("must be a switch: $a -> $b", MessageKeys.sameName(a, b))
        }
    }

    @Test fun theSameNameStillSurvivesAMisreadStroke() {
        // A longer name keeps one character of slack, so OCR noise does not wipe
        // the panel and re-ask the model every second.
        val pairs = listOf(
            "李沅汐" to "李沅沙",
            "陆林晖" to "陆林辉",
            "项目群" to "项目群",
            "材料评审群" to "材料评审群",
        )
        for ((a, b) in pairs) {
            assertTrue("should be the same person: $a / $b", MessageKeys.sameName(a, b))
        }
    }

    @Test fun aDifferentSurnameIsAlwaysADifferentPerson() {
        assertFalse(MessageKeys.sameName("李沅汐", "王沅汐"))
    }

    @Test fun anUnreadableNameIsNotASwitch() {
        // Nothing to compare: wait for the next look rather than wiping the panel.
        assertTrue(MessageKeys.sameName("李沅汐", ""))
        assertTrue(MessageKeys.sameName("", "李沅汐"))
    }

    @Test fun aTimestampReadWithSpacesIsStillNoise() {
        // The screen reader puts spaces between the digits surprisingly often
        // ("15:46" comes back as "1 5 ： 4 6"), and that line then became the
        // newest "message" - which the model was asked to answer.
        for (text in listOf("15:46", "1 5 ： 4 6", "1 5:4 6", "03：45", "昨 天 2 2:1 8".replace(" ", ""))) {
            assertTrue("should be noise: $text", MessageKeys.isNoise(text))
        }
    }

    @Test fun aConversationNameHasToLookLikeOne() {
        // The reported mess: the picker held "姓你生活?", "进生活7" and
        // "仟始1五伦林役还 2方大同s" - fragments of a misread header, kept
        // forever as if they were people.
        for (junk in listOf("供布)", "姓你生活?", "你生活?", "进你生活?", "进生活7",
                            "仟始1五伦林役还 2方大同s", "始1酒伦#林杰a方大\"", "在吗？",
                            "今天下午三点见，你看行不行", "a方大", "", "   ")) {
            assertFalse("should not be remembered: $junk", MessageKeys.plausibleName(junk))
        }
    }

    @Test fun realNamesAreStillRemembered() {
        for (name in listOf("张三", "李沅汐", "陆林晖", "项目群", "材料评审群",
                            "妈妈", "老同学", "eggs", "Alice", "问答与知识")) {
            assertTrue("should be remembered: $name", MessageKeys.plausibleName(name))
        }
    }
}
