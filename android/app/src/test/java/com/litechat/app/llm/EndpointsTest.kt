package com.litechat.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The URL builder is the most likely place for a "test connection" failure, and
 * it is pure string logic - exactly what a unit test is for.
 */
class EndpointsTest {

    @Test fun openAiCompatibleAppendsChatCompletions() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            Endpoints.build(LlmPresets.PROTOCOL_OPENAI, "https://api.deepseek.com/v1", "deepseek-chat"))
    }

    @Test fun trailingSlashIsNotDuplicated() {
        assertEquals("https://api.openai.com/v1/chat/completions",
            Endpoints.build(LlmPresets.PROTOCOL_OPENAI, "https://api.openai.com/v1/", "gpt-4o-mini"))
    }

    @Test fun aFullEndpointIsUsedVerbatim() {
        // Some gateways put the completions path somewhere unexpected; a user who
        // pastes the whole thing must not get it rewritten.
        val full = "https://gateway.example.com/llm/v2/chat/completions"
        assertEquals(full, Endpoints.build(LlmPresets.PROTOCOL_OPENAI, full, "m"))
    }

    @Test fun anthropicUsesMessages() {
        assertEquals("https://api.anthropic.com/v1/messages",
            Endpoints.build(LlmPresets.PROTOCOL_ANTHROPIC, "https://api.anthropic.com/v1",
                "claude-3-5-sonnet-latest"))
    }

    @Test fun geminiPutsTheModelInThePath() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            Endpoints.build(LlmPresets.PROTOCOL_GEMINI,
                "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"))
    }

    @Test fun geminiKeyGoesIntoTheQueryStringOnce() {
        val url = Endpoints.build(LlmPresets.PROTOCOL_GEMINI, "https://g/v1beta", "m")
        val once = Endpoints.withKeyInQuery(url, "K")
        assertEquals("$url?key=K", once)
        // Calling it twice must not append a second key.
        assertEquals(once, Endpoints.withKeyInQuery(once, "K"))
    }

    @Test fun blankAddressBuildsNothing() {
        assertEquals("", Endpoints.build(LlmPresets.PROTOCOL_OPENAI, "   ", "m"))
    }
}
