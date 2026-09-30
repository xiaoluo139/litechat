package com.litechat.app.llm

import android.util.Log
import android.content.Context
import com.litechat.app.core.ChatSnapshot
import com.litechat.app.core.Prefs
import com.litechat.app.core.Suggestion
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one and only model call this app makes: a chat completion against
 * whatever third-party API the user configured, in whichever of the three wire
 * formats (see [LlmPresets]) that service speaks.
 *
 * One prompt, one round trip: the model returns the read of the conversation
 * AND the three candidate replies as a single JSON object. That is the whole
 * simplification — the upstream project spends two model calls (a judgment call
 * plus a generation call) to get the same three lines.
 */
class LlmClient(private val prefs: Prefs, private val context: Context) {

    /**
     * Analyse a snapshot. Network and parsing failures come back inside
     * [Suggestion.error] rather than being thrown, because the caller is an
     * overlay callback that must always end up showing *something*.
     */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Suggestion {
        val start = System.currentTimeMillis()
        return try {
            val skill = Skills.byId(prefs.skillId)
            var user = buildUserPrompt(snapshot, relationship)
            // The 军师 skill is a knowledge base as well as a persona, and it
            // says to load only the one or two references that fit the question
            // rather than the whole library - which is also what keeps the
            // request small enough to stay fast.
            val refs = Skills.pickReferences(context, skill, user)
            if (refs.isNotEmpty()) {
                user += "\n\n" + refs.joinToString("\n\n") { (title, body) ->
                    "【参考：$title】\n$body"
                }
                Log.i(TAG, "skill=${skill.id} refs=${refs.map { it.first }}")
            }
            val s = askModel(skill, user, System.currentTimeMillis() - start)
            // Time only - no chat text, so this is safe in a release build and
            // is the number to ask for when someone says "it is slow".
            Log.i(TAG, "answered in ${System.currentTimeMillis() - start} ms" +
                " skill=${skill.id} noThinking=${prefs.noThinking}")
            if (s.replies.isEmpty()) {
                s.copy(error = "模型只输出了思考过程，没有给出回复。"
                    + "换一个非推理模型试试，或稍后重试。")
            } else {
                s.copy(references = refs.map { it.first })
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed: ${e.javaClass.simpleName}: ${e.message}")
            // "java.lang.String cannot be cast to java.lang.Boolean" is accurate
            // and completely useless to read. These two only ever come from a
            // stored setting that no longer matches its type, and re-saving the
            // settings page is the actual fix, so say that instead.
            val hint = when (e) {
                is ClassCastException, is NumberFormatException ->
                    "有一条本地设置读不出来（多半是旧版本留下的），" +
                        "去设置页把接口参数重新保存一次就好。"
                else -> e.message ?: "请求失败"
            }
            Suggestion(null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = hint)
        }
    }

    /**
     * One answer, with one retry aimed at reasoning models.
     *
     * A model that thinks before answering can spend its whole output budget
     * doing that and have nothing left to answer with - then `content` is
     * missing and only `reasoning_content` comes back. When that happens (or
     * when the answer holds no usable reply at all) ask once more, telling it
     * not to show the thinking, with room for both.
     */
    private fun askModel(skill: Skills.Skill, user: String, elapsed: Long): Suggestion {
        var text = chat(skill.systemPrompt, user, prefs.maxTokens)
        var parsed = SuggestionParser.parse(text, elapsed)
        if (parsed.replies.isNotEmpty()) return parsed

        Log.i(TAG, "first answer had no replies; retrying with a bigger budget")
        text = chat(skill.systemPrompt + DIRECT_ANSWER_RULE, user,
            RETRY_TOKENS, forceCap = true)
        parsed = SuggestionParser.parse(text, elapsed)
        return parsed
    }

    /**
     * One tiny round trip for the settings connectivity test. Deliberately not
     * [analyze]: it must exercise the ordinary path and return the raw answer.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到",
            prefs.maxTokens).trim()

    // ------------------------------------------------------------- prompt

    private fun buildUserPrompt(snapshot: ChatSnapshot, relationship: String): String {
        return PromptBuilder.userPrompt(
            who = snapshot.title?.takeIf { it.isNotBlank() },
            relationship = relationship,
            messages = snapshot.messages,
            sidesKnown = snapshot.sidesKnown,
        )
    }

    // ------------------------------------------------------------- request

    /**
     * One round trip, dispatched on the configured protocol. Returns the
     * assistant's plain text, whatever envelope it arrived in.
     */
    private fun chat(system: String, user: String, maxTokens: Int,
                     forceCap: Boolean = false): String {
        hintRejectedForLastCall = false
        val endpoint = prefs.endpoint()
        if (endpoint.isBlank()) throw ApiException(null, "还没填接口地址，去设置里填")
        val key = prefs.apiKey.trim()
        val headers = LinkedHashMap<String, String>()
        val url: String
        val body: JSONObject

        when (prefs.protocol) {
            LlmPresets.PROTOCOL_ANTHROPIC -> {
                // Anthropic: key in x-api-key, mandatory anthropic-version,
                // system prompt as its own top-level field.
                if (key.isNotBlank()) headers["x-api-key"] = key
                headers["anthropic-version"] = "2023-06-01"
                url = endpoint
                body = JSONObject()
                    .put("model", prefs.model)
                    .put("max_tokens", if (maxTokens > 0) maxTokens else THINKING_SAFE_TOKENS)
                    .put("system", system)
                    .put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", user)))
            }

            LlmPresets.PROTOCOL_GEMINI -> {
                // Gemini: key rides in the query string; "assistant" is called a
                // model and the system prompt is systemInstruction.
                url = Endpoints.withKeyInQuery(endpoint, key)
                body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts",
                        JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(
                        JSONObject().put("role", "user").put("parts",
                            JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", JSONObject()
                        .put("maxOutputTokens",
                            if (maxTokens > 0) maxTokens else THINKING_SAFE_TOKENS))
            }

            else -> {
                // OpenAI-compatible: the de-facto standard.
                if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
                url = endpoint
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                body = JSONObject()
                    .put("model", prefs.model)
                    .put("messages", messages)
                // Left out unless the caller asked for a specific budget: the
                // OpenAI shape is spoken by gateways that disagree about this
                // field. The retry in [askModel] passes one.
                if (forceCap) body.put("max_tokens", maxTokens)
            }
        }

        // Temperature: only when the user typed one. Reasoning models (o1/o3,
        // DeepSeek-R1, …) reject the parameter outright, so "blank = omit" is
        // what keeps the app working across every vendor without a toggle.
        val temp = prefs.temperature.trim().toDoubleOrNull()
        if (temp != null) {
            if (prefs.protocol == LlmPresets.PROTOCOL_GEMINI) {
                body.optJSONObject("generationConfig")?.put("temperature", temp)
            } else {
                body.put("temperature", temp)
            }
        }

        headers.putAll(HttpJson.attribution(url))
        headers.putAll(prefs.headerMap())

        // Ask the model not to think out loud - see [Prefs.noThinking]. An
        // endpoint that does not know the field rejects the whole request, so
        // the catch below drops it and remembers that for this host.
        val wantHint = prefs.noThinking && prefs.protocol != LlmPresets.PROTOCOL_ANTHROPIC
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val sendHint = wantHint && host !in rejectedHosts
        if (sendHint) {
            if (prefs.protocol == LlmPresets.PROTOCOL_GEMINI) {
                body.optJSONObject("generationConfig")
                    ?.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            } else {
                body.put("thinking", JSONObject().put("type", "disabled"))
                body.put("chat_template_kwargs",
                    JSONObject().put("enable_thinking", false))
            }
        }
        try {
            return extractText(HttpJson.post(url, body, headers))
        } catch (e: ApiException) {
            if (!sendHint || e.status !in listOf(400, 415, 422)) throw e
            Log.i(TAG, "endpoint rejected the no-thinking hint; retrying without it")
            rejectedHosts.add(host)
            hintRejectedForLastCall = true
            body.remove("thinking")
            body.remove("chat_template_kwargs")
            body.optJSONObject("generationConfig")?.remove("thinkingConfig")
            return extractText(HttpJson.post(url, body, headers))
        }
    }

    /** Pull the assistant text out of whichever envelope came back. */
    private fun extractText(resp: JSONObject): String {
        // Anthropic
        resp.optJSONArray("content")?.let { arr ->
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                if (part.optString("type") == "text" || part.has("text")) {
                    sb.append(part.optString("text"))
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        // Google Gemini
        resp.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    parts.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        // OpenAI-compatible (content may be a string or an array of parts)
        val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        msg?.optString("content")?.let { if (it.isNotBlank()) return it }
        msg?.optJSONArray("content")?.let { arr ->
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                sb.append(part.optString("text").ifBlank { part.optString("content") })
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        // A reasoning model that ran out of room answers with its thinking and
        // no answer at all: "content" is missing (not empty - absent) and
        // "reasoning_content" holds the chain of thought. Handing that back as
        // the answer is what made the panel show three lines of the model
        // muttering to itself. Say nothing instead, so [askModel] retries with
        // a budget that leaves room for both.
        if (msg?.optString("reasoning_content")?.isNotBlank() == true) return ""
        // Some gateways put the text at the top level.
        resp.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        resp.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        throw ApiException(null, "没读懂服务商返回的内容：${resp.toString().take(200)}")
    }

    companion object {
        private const val TAG = "LITECHAT"

        /** Hosts that answered "I do not know that field" - asked once, then left alone. */
        private val rejectedHosts: MutableSet<String> =
            java.util.Collections.synchronizedSet(HashSet<String>())

        /** True when the last call had to drop the hint, so the panel can say why it is slow. */
        @Volatile var hintRejectedForLastCall: Boolean = false
            private set

        /**
         * Output budget for one reply draft. Not a target - a cap - and
         * deliberately generous, because a reasoning model spends tokens
         * thinking before it writes anything. Measured against LongCat-2.0 on a
         * two-line conversation: 320 tokens were consumed entirely by the
         * thinking and `content` came back missing.
         */
        private const val THINKING_SAFE_TOKENS = 1600

        /** The one retry gets room for a long chain of thought AND the answer. */
        private const val RETRY_TOKENS = 3200

        /** Appended on the retry: stop showing the work, just answer. */
        private const val DIRECT_ANSWER_RULE =
            "\n不要输出思考过程、分析或解释。想清楚后直接给出那个 JSON 对象，第一个字符就是 {"
    }
}
