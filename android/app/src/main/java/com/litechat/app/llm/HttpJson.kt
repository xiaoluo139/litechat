package com.litechat.app.llm

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * Carries the HTTP status (null = transport failure) and the first 200 chars of
 * the response body, so the settings page can show the real reason a call
 * failed instead of a generic "request failed".
 */
class ApiException(
    val status: Int?,
    val snippet: String
) : RuntimeException(buildMessage(status, snippet)) {

    companion object {
        fun buildMessage(status: Int?, snippet: String): String =
            if (status != null) "HTTP $status：${snippet.take(200)}"
            else "请求失败：${snippet.take(200)}"
    }
}

/**
 * Shared POST-JSON helper: UTF-8 body, exponential backoff on 429/5xx, no retry
 * on other 4xx, and every failure normalized to [ApiException]. The key is a
 * parameter (or a header) and is never logged.
 */
object HttpJson {

    /**
     * Two attempts, not three: the user is watching. A third retry with the
     * old backoff could keep the panel on "分析中…" for minutes, and the
     * servers that answer 429/5xx under load rarely recover inside that window
     * anyway.
     */
    private const val MAX_ATTEMPTS = 2

    /** Live connections, so a cancelled analysis stops waiting immediately. */
    private val inFlight: MutableSet<HttpURLConnection> =
        Collections.synchronizedSet(HashSet())

    /**
     * Abort every request currently waiting on the network.
     *
     * Called when the user switches conversations: the old answer is worthless
     * and the caller should not be stuck behind it.
     */
    fun cancelAll() {
        val copy = synchronized(inFlight) { inFlight.toList() }
        copy.forEach { runCatching { it.disconnect() } }
        synchronized(inFlight) { inFlight.clear() }
    }

    /**
     * @param headers extra request headers (auth, anthropic-version, attribution).
     */
    fun post(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10000,
        readTimeoutMs: Int = 40000
    ): JSONObject {
        var attempt = 0
        var last: ApiException? = null
        while (attempt < MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("已取消")
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                inFlight.add(conn)
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code in 500..599) {
                    last = ApiException(code, "服务繁忙（已自动重试）")
                    attempt++
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                // Branch on the status code FIRST: reading the body must never be
                // able to lose the status.
                if (code !in 200..299) {
                    val errText = readBody(conn.errorStream)
                    throw ApiException(code, friendly(code, errText))
                }
                val text = readBody(conn.inputStream)
                if (text.isBlank()) throw ApiException(code, "响应体为空")
                return JSONObject(text)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: ApiException) {
                if (e.status != null && e.status in 400..499) throw e  // client error: no retry
                last = e
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } catch (e: Exception) {
                // A timeout is not worth retrying: the read timeout IS the
                // user's patience budget, and a reasoning model that already
                // took that long will take it again. Reset-style failures are
                // the ones a second attempt actually fixes.
                if (e is java.net.SocketTimeoutException) throw ApiException(
                    null, "等太久了，接口还没返回（可能是推理模型，或网络慢）")
                last = ApiException(null, describe(e))
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.let { inFlight.remove(it) }
                conn?.disconnect()
            }
        }
        throw last ?: ApiException(null, "请求失败")
    }

    /** Body text, or "" — a null stream or a read failure never costs us the status. */
    private fun readBody(stream: InputStream?): String {
        stream ?: return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Pull `error.message` out of any of the three vendors' error envelopes, so
     * the user sees "Incorrect API key provided" instead of raw JSON.
     */
    fun friendly(code: Int, raw: String): String {
        val msg = runCatching {
            val o = JSONObject(raw)
            o.optJSONObject("error")?.optString("message")
                ?: o.optJSONObject("error")?.optString("msg")
                ?: o.optString("message")
                ?: o.optString("msg")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val head = when (code) {
            401 -> "密钥无效或未授权"
            403 -> "没有权限（可能是模型未开通/未实名）"
            404 -> "地址或模型名不对"
            413 -> "请求太大"
            429 -> "频率超限或余额不足"
            else -> "请求失败"
        }
        return if (msg != null) "$head：$msg" else "$head：${raw.take(200)}"
    }

    /** OpenRouter wants attribution headers; other hosts accept unknown ones. */
    fun attribution(url: String): Map<String, String> =
        if (url.contains("openrouter.ai", ignoreCase = true))
            mapOf("HTTP-Referer" to "https://litechat.local", "X-Title" to "LiteChat")
        else emptyMap()

    /** Human-readable transport failures (no key material ever appears here). */
    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("timed out") || m.contains("timeout", true) -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") -> "域名解析失败，地址填错或无网络"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") -> "无法连接该地址"
            m.contains("CertPath") || m.contains("SSL") -> "HTTPS 证书校验失败"
            else -> m
        }
    }
}
