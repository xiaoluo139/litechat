package com.litechat.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply skills and the reference routing that feeds the 狗头军师 one.
 *
 * The asset half needs a device; everything that can be decided without one is
 * checked here, including the routing rule the desktop build shares.
 */
class SkillsTest {

    private fun refs(vararg pairs: Pair<String, String>) = pairs.mapIndexed { i, (title, kws) ->
        Skills.Ref(title, "references/doc-%02d.md".format(i + 1), kws.split(' ').filter { it.isNotBlank() })
    }

    @Test fun bothSkillsExistAndDiffer() {
        assertEquals(2, Skills.all.size)
        assertTrue(Skills.byId("general") != Skills.byId("goutoujunshi"))
    }

    @Test fun anUnknownSkillFallsBackToThePlainAssistant() {
        assertEquals(Skills.GENERAL, Skills.byId("nonsense").id)
        assertEquals(Skills.GENERAL, Skills.byId(null).id)
    }

    @Test fun theStewardPromptCarriesItsMethodAndBoundaries() {
        val prompt = Skills.byId(Skills.GOUTOUJUNSHI).systemPrompt
        assertTrue(prompt.contains("先接住情绪"))      // the working method
        assertTrue(prompt.contains("【要回的那句】"))   // the app's anchor contract
        assertTrue(prompt.contains("家暴"))            // the safety boundary
        assertTrue(prompt.contains("\"replies\""))     // the JSON contract
    }

    @Test fun thePlainPromptDoesNotMentionTheSteward() {
        assertFalse(Skills.byId(Skills.GENERAL).systemPrompt.contains("狗头军师"))
    }

    @Test fun onlyTheStewardIsAskedForASituationRead() {
        // The extra field is what makes the two modes visibly different in the
        // panel instead of the same assistant with different wording.
        assertTrue(Skills.byId(Skills.GOUTOUJUNSHI).systemPrompt.contains("\"stance\""))
        assertFalse(Skills.byId(Skills.GENERAL).systemPrompt.contains("\"stance\""))
    }

    @Test fun routingPicksTheDocumentThatMatches() {
        val index = refs(
            "实战话术编排器：从一句回复到后续分支" to "怎么回 回复 话术 邀约",
            "聊天化被动为主动：引导互动的实用指南" to "不回 已读不回 冷淡 忽冷忽热",
            "07-沟通冲突与修复" to "道歉 吵架 冷战",
        )
        val picked = Skills.pickFrom(index, "对方一直已读不回，忽冷忽热，我该怎么办", 2)
        assertEquals("聊天化被动为主动：引导互动的实用指南", picked.first().title)
    }

    @Test fun routingFallsBackToThePlaybook() {
        val index = refs(
            "实战话术编排器：从一句回复到后续分支" to "怎么回 回复 话术 邀约",
            "07-沟通冲突与修复" to "道歉 吵架 冷战",
        )
        val picked = Skills.pickFrom(index, "嗯嗯好的", 2)
        assertEquals(1, picked.size)
        assertEquals(Skills.DEFAULT_REFERENCE_TITLE, picked.first().title)
    }

    @Test fun routingSendsAtMostTheLimit() {
        val index = refs(
            "实战话术编排器：从一句回复到后续分支" to "回复",
            "07-沟通冲突与修复" to "道歉 吵架",
            "03-依恋理论与情绪调节" to "焦虑 依恋",
        )
        val picked = Skills.pickFrom(index, "吵架了又很焦虑，想道歉", 2)
        assertTrue(picked.size <= 2)
    }

    @Test fun anEmptyLibraryIsNotACrash() {
        assertTrue(Skills.pickFrom(emptyList(), "随便", 2).isEmpty())
    }
}
