package com.litechat.app.llm

/**
 * One entry of the "which model service do I use" dropdown.
 *
 * [protocol] decides the wire format, not the vendor:
 *  - [PROTOCOL_OPENAI]   POST {base}/chat/completions  — the de-facto standard,
 *    spoken by OpenAI, DeepSeek, Qwen, GLM, Kimi, SiliconFlow, Volcengine,
 *    OpenRouter, Groq, Ollama, LM Studio, One-API gateways and most others.
 *  - [PROTOCOL_ANTHROPIC] POST {base}/messages with x-api-key + anthropic-version.
 *  - [PROTOCOL_GEMINI]    POST {base}/models/{model}:generateContent?key=...
 *
 * A user who picks 自定义 can mix any base URL with any protocol, which is what
 * makes "works with every LLM API" true in practice: everything that is not
 * OpenAI-shaped is either Anthropic-shaped, Gemini-shaped, or has an
 * OpenAI-compatible endpoint (nearly all of them do).
 */
data class LlmPreset(
    val id: String,
    val label: String,
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val hint: String = ""
)

object LlmPresets {

    const val PROTOCOL_OPENAI = "openai"
    const val PROTOCOL_ANTHROPIC = "anthropic"
    const val PROTOCOL_GEMINI = "gemini"

    val all: List<LlmPreset> = listOf(
        LlmPreset("openai", "OpenAI", PROTOCOL_OPENAI,
            "https://api.openai.com/v1", "gpt-4o-mini"),
        LlmPreset("deepseek", "DeepSeek 深度求索", PROTOCOL_OPENAI,
            "https://api.deepseek.com/v1", "deepseek-chat"),
        LlmPreset("dashscope", "通义千问（阿里云百炼）", PROTOCOL_OPENAI,
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        LlmPreset("zhipu", "智谱 GLM", PROTOCOL_OPENAI,
            "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        LlmPreset("moonshot", "月之暗面 Kimi", PROTOCOL_OPENAI,
            "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        LlmPreset("siliconflow", "硅基流动 SiliconFlow", PROTOCOL_OPENAI,
            "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        LlmPreset("volcengine", "火山方舟（豆包）", PROTOCOL_OPENAI,
            "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k"),
        LlmPreset("hunyuan", "腾讯混元", PROTOCOL_OPENAI,
            "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest"),
        LlmPreset("minimax", "MiniMax 稀宇", PROTOCOL_OPENAI,
            "https://api.minimax.chat/v1", "abab6.5s-chat"),
        LlmPreset("baichuan", "百川智能", PROTOCOL_OPENAI,
            "https://api.baichuan-ai.com/v1", "Baichuan4"),
        LlmPreset("stepfun", "阶跃星辰 Step", PROTOCOL_OPENAI,
            "https://api.stepfun.com/v1", "step-1-8k"),
        LlmPreset("lingyiwanwu", "零一万物 Yi", PROTOCOL_OPENAI,
            "https://api.lingyiwanwu.com/v1", "yi-lightning"),
        LlmPreset("openrouter", "OpenRouter（聚合多模型）", PROTOCOL_OPENAI,
            "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
        LlmPreset("groq", "Groq（超快推理）", PROTOCOL_OPENAI,
            "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        LlmPreset("anthropic", "Anthropic Claude", PROTOCOL_ANTHROPIC,
            "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest"),
        LlmPreset("gemini", "Google Gemini", PROTOCOL_GEMINI,
            "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
        LlmPreset("oneapi", "One API / New API 中转站", PROTOCOL_OPENAI,
            "http://127.0.0.1:3000/v1", "gpt-4o-mini",
            hint = "把地址换成你自己的中转站地址"),
        LlmPreset("ollama", "Ollama（本机离线）", PROTOCOL_OPENAI,
            "http://127.0.0.1:11434/v1", "qwen2.5:7b",
            hint = "先在电脑/手机上手起 ollama serve"),
        LlmPreset("lmstudio", "LM Studio（本机离线）", PROTOCOL_OPENAI,
            "http://127.0.0.1:1234/v1", "local-model"),
        LlmPreset("custom", "自定义（任意兼容服务）", PROTOCOL_OPENAI,
            "", "", hint = "自己填地址、协议、模型，可对接任何大模型 API")
    )

    val default: LlmPreset = all.first { it.id == "deepseek" }

    /** The entry whose address / key / model the user fills in themselves. */
    val custom: LlmPreset = all.first { it.id == "custom" }

    /**
     * The preset behind a stored provider id.
     *
     * An id this build no longer ships - a preset that was removed in a later
     * version - falls back to 自定义 and not to some other vendor. The saved
     * base URL, key and model are the user's own either way, and labelling that
     * endpoint "DeepSeek" would be a lie the moment the settings page opens.
     */
    fun byId(id: String?): LlmPreset = all.firstOrNull { it.id == id } ?: custom

    /** Labels for the settings dropdown, in the same order as [all]. */
    val labels: List<String> = all.map { it.label }

    fun protocolLabel(protocol: String): String = when (protocol) {
        PROTOCOL_ANTHROPIC -> "Anthropic 协议"
        PROTOCOL_GEMINI -> "Google Gemini 协议"
        else -> "OpenAI 兼容协议"
    }

    val protocolIds = listOf(PROTOCOL_OPENAI, PROTOCOL_ANTHROPIC, PROTOCOL_GEMINI)
    val protocolLabels = listOf("OpenAI 兼容协议", "Anthropic 协议", "Google Gemini 协议")
}
