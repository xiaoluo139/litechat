package com.litechat.app.llm

import com.litechat.app.core.RankedReply
import com.litechat.app.core.Suggestion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns whatever the model wrote into a [Suggestion].
 *
 * Models are asked for one JSON object but they do not always oblige: they wrap
 * it in a ```json fence, add a sentence of preamble, answer with a bare array of
 * strings, or ignore the format entirely. This is the piece that has to cope, so
 * it is its own unit-tested object rather than a private method.
 */
internal object SuggestionParser {

    fun parse(content: String, latencyMs: Long = 0L): Suggestion {
        // Every complete {...} in the text, the LAST one first. The old parser
        // sliced from the first "{" to the last "}", so a single brace in the
        // model's prose broke the whole answer; starting at the end also matches
        // how a reasoning model writes, with the answer after its notes.
        for (chunk in jsonObjects(content).asReversed()) {
            val parsed = runCatching { JSONObject(chunk) }.getOrNull() ?: continue
            run {
                val replies = parseReplies(parsed.optJSONArray("replies"))
                if (replies.isNotEmpty()) {
                    return Suggestion(
                        intent = parsed.optString("intent").takeIf { it.isNotBlank() },
                        danger = parsed.optInt("danger", 0).takeIf { it in 1..9 },
                        advice = parsed.optString("advice").takeIf { it.isNotBlank() },
                        // Three is the contract; a model that answers with six
                        // (measured on LongCat with thinking off) must not push
                        // extras at the panel.
                        replies = replies.take(3),
                        latencyMs = latencyMs,
                        stance = parsed.optString("stance").takeIf { it.isNotBlank() }
                    )
                }
            }
        }
        // Salvaging plain prose only helps when the model answered in prose. A
        // model cut off mid-thought, or one that wrapped its reasoning in
        // bullets, would otherwise have its own notes shown as the candidate
        // replies - which is exactly what "答非所问" looked like.
        if (looksLikeReasoning(content)) {
            return Suggestion(null, null, null, emptyList(), latencyMs)
        }
        val lines = content.split('\n')
            .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"', '“') }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .filter { !it.contains("**") && !it.endsWith("：") && !it.endsWith(":") }
            .filter { it.length <= 60 }        // notes run long, replies do not
            .take(3)
        return Suggestion(null, null, null,
            lines.take(3).mapIndexed { i, t -> RankedReply(t, if (i == 0) 60 else 40) },
            latencyMs)
    }

    /** Balanced `{...}` chunks, in the order they appear. */
    internal fun jsonObjects(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when {
                ch == '"' -> inString = true
                ch == '{' -> { if (depth == 0) start = i; depth++ }
                ch == '}' && depth > 0 -> {
                    depth--
                    if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1 }
                }
            }
        }
        return out
    }

    /** True when the text reads like the model's notes rather than an answer. */
    internal fun looksLikeReasoning(text: String): Boolean {
        val marks = listOf("**", "*   ", "-   ", "分析输入", "约束条件", "上下文：",
            "分析意图", "构思回复", "让我", "首先，", "步骤")
        val hits = marks.count { it in text }
        return hits >= 2 || text.count { it == '\n' } > 12
    }

    /** Replies arrive either as `["a","b"]` or `[{"text":"a","pct":40}, …]`. */
    fun parseReplies(arr: JSONArray?): List<RankedReply> {
        arr ?: return emptyList()
        val out = ArrayList<RankedReply>()
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is String -> item.trim().takeIf { it.isNotEmpty() }
                    ?.let { out.add(RankedReply(it, 0)) }
                is JSONObject -> {
                    val t = item.optString("text").ifBlank { item.optString("reply") }.trim()
                    if (t.isNotEmpty()) {
                        out.add(RankedReply(t, item.optInt("pct", item.optInt("score", 0))))
                    }
                }
            }
        }
        if (out.isNotEmpty() && out.all { it.pct <= 0 }) {
            return out.mapIndexed { i, r -> r.copy(pct = if (i == 0) 45 else 30) }
        }
        return out
    }
}
