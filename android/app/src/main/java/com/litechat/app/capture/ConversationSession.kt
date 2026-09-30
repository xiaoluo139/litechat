package com.litechat.app.capture

/** Main-thread session state. A return to the same chat never revives an old request. */
internal class ConversationSession {
    data class Target(
        val pkg: String,
        val windowId: Int,
        val title: String?,
        val messagesSignature: String? = null
    )

    data class Token(val target: Target, val revision: Long)

    var target: Target? = null
        private set
    private var revision = 0L

    /**
     * Same app AND same conversation, ignoring which window handle shows it.
     *
     * Deliberately not `==` on [Target]: the window handle changes every time a
     * sticker picker, emoji panel or system dialog opens, and treating that as
     * "you left the conversation" wiped the panel and threw away answers.
     */
    fun sameConversation(a: Target?, b: Target?): Boolean {
        if (a == null || b == null) return false
        if (a.pkg != b.pkg) return false
        if (a.title == null || b.title == null) return true
        return a.title == b.title
    }

    /**
     * Same conversation AND the same messages in it.
     *
     * A new message makes an answer that is already on the wire stale - its
     * anchor is the previous message - so the caller drops it and asks again
     * with the newer screen. That is why this is stricter than
     * [sameConversation]: what matters for an answer is the message it answers.
     */
    fun sameState(a: Target?, b: Target?): Boolean =
        sameConversation(a, b) && a?.messagesSignature == b?.messagesSignature

    fun observe(next: Target?): Boolean {
        if (target == next) return false
        val previous = target
        target = next
        // Only a real conversation move invalidates work in flight. A new window
        // handle for the same app and the same title is a sticker picker or an
        // emoji panel opening, and bumping the revision for that threw away
        // answers that were already on their way.
        if (!sameConversation(previous, next)) invalidate()
        return true
    }

    fun invalidate() { revision++ }

    fun token(): Token? = target?.let { Token(it, revision) }

    fun begin(): Token? {
        invalidate()
        return token()
    }

    fun accepts(token: Token): Boolean =
        token.revision == revision && sameState(token.target, target)
}
