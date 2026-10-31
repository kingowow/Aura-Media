package org.akkirrai.hibiki.core.subtitle

/**
 * Translation engines the user can pick in Settings → Preferences.
 *
 * MT engines (translate one line / small batch, no LLM prompt):
 *  - GOOGLE_FREE      : unofficial Google endpoint, no key
 *  - MICROSOFT_EDGE   : Microsoft Edge translator, no key
 *  - DEEPL            : official DeepL API, free/pro key
 *  - DEEPLX           : self-hosted DeepLX proxy, URL only
 *  - AZURE            : Azure Cognitive Services, key + region
 *  - GOOGLE_API       : Google Cloud Translation v2, key
 *  - QWEN_MT          : Alibaba Qwen-MT seq2seq model, key
 *
 * LLM engines (chat-completion, uses a translation prompt):
 *  - LLM              : any OpenAI-compatible chat-completions endpoint.
 *                       Pick a provider preset (DeepSeek, OpenAI, Claude, …)
 *                       or type a custom URL. User supplies model + API key.
 */
enum class TranslationEngine(
    val displayName: String,
    /** true = no API key / URL needed at all. */
    val isFree: Boolean,
    /** true = user must enter an API key in Settings. */
    val requiresApiKey: Boolean,
    /** true = user must enter a custom endpoint URL (DeepLX, LLM custom). */
    val requiresUrl: Boolean,
    /** true = user must enter an Azure region. */
    val requiresRegion: Boolean,
    /** true = user must enter a model name (LLM, Qwen-MT). */
    val requiresModel: Boolean,
    val keyHint: String = "",
    val urlHint: String = "",
    val modelHint: String = "",
    val regionHint: String = "",
    val apiKeyDocsUrl: String = "",
) {
    // ── Free (no credentials) ────────────────────────────────────────────────
    GOOGLE_FREE(
        displayName = "Google (رایگان)",
        isFree = true, requiresApiKey = false, requiresUrl = false,
        requiresRegion = false, requiresModel = false,
    ),
    MICROSOFT_EDGE(
        displayName = "Microsoft Edge (رایگان)",
        isFree = true, requiresApiKey = false, requiresUrl = false,
        requiresRegion = false, requiresModel = false,
    ),

    // ── Key required ────────────────────────────────────────────────────────
    DEEPL(
        displayName = "DeepL",
        isFree = false, requiresApiKey = true, requiresUrl = false,
        requiresRegion = false, requiresModel = false,
        keyHint = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx",
        apiKeyDocsUrl = "https://www.deepl.com/pro-api",
    ),
    AZURE(
        displayName = "Azure Translator",
        isFree = false, requiresApiKey = true, requiresUrl = false,
        requiresRegion = true, requiresModel = false,
        keyHint = "کلید Azure Cognitive Services",
        regionHint = "eastasia",
        apiKeyDocsUrl = "https://azure.microsoft.com/en-us/products/ai-services/ai-translator",
    ),
    GOOGLE_API(
        displayName = "Google Translate API",
        isFree = false, requiresApiKey = true, requiresUrl = false,
        requiresRegion = false, requiresModel = false,
        keyHint = "AIza...",
        apiKeyDocsUrl = "https://cloud.google.com/translate/docs/setup",
    ),
    QWEN_MT(
        displayName = "Qwen-MT",
        isFree = false, requiresApiKey = true, requiresUrl = false,
        requiresRegion = false, requiresModel = true,
        keyHint = "sk-...",
        modelHint = "qwen-mt-plus",
        apiKeyDocsUrl = "https://help.aliyun.com/zh/model-studio/qwen-mt-api",
    ),

    // ── URL required (self-hosted) ───────────────────────────────────────────
    DEEPLX(
        displayName = "DeepLX (سرور شخصی)",
        isFree = true, requiresApiKey = false, requiresUrl = true,
        requiresRegion = false, requiresModel = false,
        urlHint = "http://localhost:1188/translate",
    ),

    // ── LLM (OpenAI-compatible chat-completions) ────────────────────────────
    LLM(
        displayName = "LLM (ترجمهٔ هوشمند)",
        isFree = false, requiresApiKey = true, requiresUrl = true,
        requiresRegion = false, requiresModel = true,
        keyHint = "sk-...",
        urlHint = "https://api.deepseek.com/v1/chat/completions",
        modelHint = "deepseek-chat",
        apiKeyDocsUrl = "https://deepseek.com",
    ),
}

/**
 * Preset providers shown as quick-pick chips above the LLM URL field.
 * Each entry: (label, endpoint, default model, whether an API key is required).
 * Users can also type any URL/model/key combination manually.
 */
object LlmProviderPresets {
    data class Preset(
        val label: String,
        val url: String,
        val model: String,
        val needsKey: Boolean = true,
        val docsUrl: String = "",
    )

    val all = listOf(
        Preset("DeepSeek",       "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
        Preset("OpenAI",         "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        Preset("Claude",         "https://api.anthropic.com/v1/messages", "claude-sonnet-4-20250514"),
        Preset("Gemini",         "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.0-flash"),
        Preset("Qwen",           "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"),
        Preset("Moonshot",       "https://api.moonshot.ai/v1/chat/completions", "moonshot-v1-8k"),
        Preset("Mistral",        "https://api.mistral.ai/v1/chat/completions", "mistral-large-latest"),
        Preset("Grok (xAI)",     "https://api.x.ai/v1/chat/completions", "grok-3-mini"),
        Preset("Cohere",         "https://api.cohere.com/v1/chat/completions", "command-r"),
        Preset("YandexGPT",      "https://llm.api.cloud.yandex.net/v1/chat/completions", "yandexgpt-large"),
        Preset("OpenRouter",     "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini"),
        Preset("Groq",           "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
        Preset("Cerebras",       "https://api.cerebras.ai/v1/chat/completions", "llama3.3-70b"),
        Preset("SiliconFlow",    "https://api.siliconflow.cn/v1/chat/completions", "deepseek-ai/DeepSeek-V3"),
        Preset("NVIDIA",         "https://integrate.api.nvidia.com/v1/chat/completions", "meta/llama3.1-70b-instruct"),
        Preset("Doubao",         "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-1-5-lite"),
        Preset("MiniMax",        "https://api.minimax.io/v1/chat/completions", "abab6.5s-chat"),
        Preset("StepFun",        "https://api.stepfun.com/v1/chat/completions", "step-2-16k"),
        Preset("Zhipu GLM",      "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-flash"),
        Preset("Baidu Qianfan",  "https://qianfan.baidubce.com/v2/chat/completions", "ernie-4-tiny"),
        Preset("MiMo",           "https://api.xiaomimimo.com/v1/chat/completions", "mimo-7b"),
        Preset("Azure OpenAI",   "https://YOUR_RESOURCE.openai.azure.com/openai/deployments/YOUR_DEPLOYMENT/chat/completions?api-version=2024-02-01", "gpt-4o"),
        Preset("Custom / لوکال", "http://127.0.0.1:20128/v1/chat/completions", "", needsKey = false),
    )
}
