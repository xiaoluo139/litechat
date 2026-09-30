package com.litechat.app.llm

/**
 * Works out the POST URL for one chat round trip.
 *
 * Split out of [com.litechat.app.core.Prefs] so it can be unit-tested without an
 * Android context: getting this wrong is the single most likely reason "test
 * connection" fails, and it is pure string logic.
 */
internal object Endpoints {

    fun build(protocol: String, baseUrl: String, model: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return when (protocol) {
            // A complete endpoint pasted into the address box is used verbatim,
            // so an unusual gateway path still works.
            LlmPresets.PROTOCOL_ANTHROPIC ->
                if (base.endsWith("/messages")) base else "$base/messages"
            LlmPresets.PROTOCOL_GEMINI ->
                if (base.endsWith(":generateContent")) base
                else "$base/models/${model.trim()}:generateContent"
            else ->
                if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
    }

    /** Gemini carries the key in the query string rather than a header. */
    fun withKeyInQuery(url: String, key: String): String {
        if (url.contains("key=")) return url
        val sep = if (url.contains('?')) "&" else "?"
        return url + sep + "key=" + key
    }
}
