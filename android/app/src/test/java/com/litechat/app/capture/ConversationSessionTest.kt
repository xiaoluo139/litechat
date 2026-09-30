package com.litechat.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session token is what stops a slow model reply from being pasted into the
 * wrong conversation after the user has switched chats. Every one of these cases
 * was a real bug class in the upstream project.
 */
class ConversationSessionTest {
    private val chatA = ConversationSession.Target("com.tencent.mobileqq", 7, "Alice")
    private val chatB = chatA.copy(title = "Bob")

    @Test fun unchangedConversationAcceptsBothCallbacks() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        assertFalse(session.observe(chatA))
        assertTrue(session.accepts(request))
    }

    @Test fun switchingChatRejectsOldResultsAndFill() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatB)
        assertFalse(session.accepts(request))
        assertTrue(session.accepts(session.begin()!!))
    }

    @Test fun returningToOriginalChatDoesNotReviveOldRequest() {
        val session = ConversationSession()
        session.observe(chatA)
        val old = session.begin()!!
        session.observe(chatB)
        session.observe(chatA)
        assertFalse(session.accepts(old))
    }

    @Test fun sameTitleInDifferentAppIsDifferentTarget() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatA.copy(pkg = "com.twitter.android"))
        assertFalse(session.accepts(request))
    }

    @Test fun newMessagesInvalidateEvenWhenTitlesMatch() {
        val session = ConversationSession()
        session.observe(chatA.copy(messagesSignature = "other:hello"))
        val request = session.begin()!!
        session.observe(chatA.copy(messagesSignature = "other:goodbye"))
        assertFalse(session.accepts(request))
    }

    @Test fun leavingChatInvalidatesResultsAndDelayedWrites() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(null)
        assertFalse(session.accepts(request))
        assertNull(session.begin())
    }

    @Test fun explicitInvalidationRejectsScreenshotAndNetworkCallbacks() {
        val session = ConversationSession()
        session.observe(chatA)
        val screenshot = session.token()!!
        val request = session.begin()!!
        session.invalidate()
        assertFalse(session.accepts(screenshot))
        assertFalse(session.accepts(request))
    }

    @Test fun aDifferentWindowHandleIsStillTheSameConversation() {
        // WeChat opens the sticker picker, the emoji panel and so on as their own
        // windows. Treating each of those as "you switched chats" used to throw
        // away an answer that was already on its way - and, worse, leave the
        // analysis lock held, so nothing was generated again until the user
        // changed conversations.
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatA.copy(windowId = 99))
        assertTrue(session.sameConversation(chatA, chatA.copy(windowId = 99)))
        assertTrue(session.accepts(request))
    }

    @Test fun aNewMessageStillMakesAnInFlightAnswerStale() {
        val session = ConversationSession()
        session.observe(chatA.copy(messagesSignature = "other:hello"))
        val request = session.begin()!!
        session.observe(chatA.copy(windowId = 99, messagesSignature = "other:hello2"))
        assertFalse(session.accepts(request))
    }
}
