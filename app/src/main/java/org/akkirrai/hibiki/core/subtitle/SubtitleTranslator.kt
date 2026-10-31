package org.akkirrai.hibiki.core.subtitle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.akkirrai.hibiki.core.log.AppLogger
import java.util.Locale
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A lazily-created shared [SubtitleTranslator] for one-off calls from UI
 * (Settings "test endpoint" and "list models" buttons). The DI graph's own
 * instance is the one used for actual translation; this one lets SettingsScreen
 * run a probe without depending on DI.
 */
object SubtitleTranslatorShared {
    private var instance: SubtitleTranslator? = null

    fun get(): SubtitleTranslator =
        instance ?: synchronized(this) {
            instance ?: SubtitleTranslator(
                org.akkirrai.hibiki.core.network.AndroidHttpClientFactory.create()
            ).also { instance = it }
        }
}

/**
 * Translates an SRT subtitle file using the engine the user picked in Settings.
 *
 * Supported engines:
 *  - GOOGLE_FREE   : unofficial translate-pa / gtx endpoint (no key)
 *  - MICROSOFT_EDGE: edge.microsoft.com/translate/translatetext (no key)
 *  - DEEPL         : official DeepL API (free/pro key)
 *  - DEEPLX        : self-hosted DeepLX proxy (URL, no key)
 *  - AZURE         : Azure Cognitive Services Translator (key + region)
 *  - GOOGLE_API    : official Google Cloud Translation API (key)
 */

/**
 * Thrown when an upstream translation provider rejects the request because the
 * free/quota tier is exhausted (HTTP 429 or a provider-specific "rate limit"
 * body). Carries the human-readable reason so the UI can surface the exact
 * provider message instead of a generic failure.
 */
class RateLimitException(message: String, val retryAfterMs: Long) : RuntimeException(message)

class SubtitleTranslator(private val client: HttpClient) {

    // Dedicated OkHttp client for LLM / Qwen-MT requests with a long (180 s) timeout.
    // The shared parserClient (injected as `client`) carries a 30 s request timeout
    // (BeakoKitHttpPolicy), too short for slow LLM responses. Fast MT engines keep
    // using the injected `client`.
    private val llmClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 180_000L
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 180_000L
            }
        }
    }

    /**
     * Shared gate for every LLM HTTP request (batched or single-line). It is a field rather than
     * a local because a long file is translated as many request-sized groups concurrently —
     * without a shared semaphore each group would open its own pair of connections and flood
     * the provider. Never acquired while already holding a permit (no nesting), so it cannot
     * deadlock.
     */
    private val llmPermits = Semaphore(LLM_MAX_CONCURRENT_REQUESTS)

    /**
     * Shared "the provider is rate-limiting us right now" gate. A free-tier gateway answers 429
     * to one request; every other request that is already in flight (or about to start) would
     * then hammer the same wall and turn a short cooldown into a multi-minute crawl. So a rate
     * limit parks the whole translator until the provider's own Retry-After has passed — every
     * later request waits here instead of failing on its own.
     */
    private val llmGateUntilMs = AtomicLong(0L)

    /** Waits out an active rate-limit cooldown, if any. */
    private suspend fun awaitLlmGate() {
        val until = llmGateUntilMs.get()
        if (until <= 0L) return
        val wait = until - System.currentTimeMillis()
        if (wait > 0L) {
            AppLogger.w(TAG, "[translate.llm_gate] waiting ${wait}ms for the provider to cool down")
            delay(wait)
        }
    }

    /** Parks every later LLM request until the provider says it is ready again. */
    private fun parkLlmGate(untilMs: Long) {
        llmGateUntilMs.updateAndGet { current -> maxOf(current, untilMs) }
    }


    private data class Cue(val startMs: Long, val endMs: Long, val lines: List<String>)

    /**
     * Optional per-line translation cache (populated by PlayerViewModel after
     * calling [SubtitleTranslationCache.init] with the app context). When set,
     * LLM lines that are already cached skip the network entirely, making
     * re-plays of the same episode near-instant.
     */
    var cache: SubtitleTranslationCache? = null

    /**
     * Translates [srt] into [target] using [engine].
     * [apiKey], [apiUrl], [apiRegion] come from AppPreferences per-engine fields.
     * [onProgress] receives 0..100 per finished batch.
     *
     * The whole file is translated before anything is handed back: there is no progressive /
     * partial application — the finished SRT is the only thing the player ever loads.
     *
     * [onProgress] follows *resolved lines* across all batches (not published batches): with the
     * LLM engine one slow request would otherwise freeze the percentage even though the rest of the
     * file keeps translating.
     */
    suspend fun translate(
        srt: String,
        target: SubtitleTranslationTarget,
        engine: TranslationEngine,
        apiKey: String = "",
        apiUrl: String = "",
        apiRegion: String = "",
        apiModel: String = "",
        onProgress: (Int) -> Unit,
    ): String {
        val cues = parseCues(srt)
        if (cues.isEmpty()) throw IllegalArgumentException("no cues to translate")

        // Batch into chunks to stay under URL / request-body limits.
        // LLM batches are much larger than MT ones: one chat-completions request carries a whole
        // batch of numbered lines, so fewer, bigger batches mean far fewer requests — which is
        // what free-tier endpoints (rate limits) and the user's wall-clock actually care about.
        val batches = mutableListOf<List<Cue>>()
        var current = mutableListOf<Cue>()
        var currentChars = 0
        for (cue in cues) {
            val cueChars = cue.lines.sumOf(String::length) + 1
            val maxCues = when {
                engine == TranslationEngine.LLM -> LLM_BATCH_CUES
                else -> MAX_BATCH_CUES
            }
            val maxChars = when {
                engine == TranslationEngine.LLM -> LLM_BATCH_CHARS
                else -> MAX_BATCH_CHARS
            }
            if (current.isNotEmpty() &&
                (currentChars + cueChars > maxChars || current.size >= maxCues)
            ) {
                batches += current.toList()
                current = mutableListOf()
                currentChars = 0
            }
            current += cue
            currentChars += cueChars
        }
        if (current.isNotEmpty()) batches += current.toList()

        val out = mutableListOf<Cue>()
        val totalLines = cues.sumOf { it.lines.size }
        val completedLines = AtomicInteger(0)
        fun reportLineProgress() {
            val done = completedLines.get()
            onProgress(if (done >= totalLines) 100 else (done * 100) / totalLines)
        }

        // Appends one finished batch to the output list, in cue order.
        fun appendBatch(batch: List<Cue>, translated: List<String>) {
            var cursor = 0
            for (cue in batch) {
                out += Cue(cue.startMs, cue.endMs, translated.subList(cursor, cursor + cue.lines.size))
                cursor += cue.lines.size
            }
        }

        if (engine == TranslationEngine.LLM) {
            // LLM: start every request-sized group right away (individual requests are still
            // gated by the shared llmPermits semaphore). The groups are independent, so their
            // results are collected in cue order afterwards — one slow group can no longer hold
            // back the ones that already finished.
            coroutineScope {
                val pending = batches.map { batch ->
                    async {
                        translateLines(
                            batch.flatMap { it.lines }, target, engine, apiKey, apiUrl, apiRegion, apiModel,
                        ) {
                            completedLines.addAndGet(it)
                            reportLineProgress()
                        }
                    }
                }
                pending.forEachIndexed { index, result ->
                    appendBatch(batches[index], result.await())
                }
            }
        } else {
            // MT engines are already fast enough; keep them sequential to stay gentle on the
            // free endpoints (and to pace requests with the delay below).
            batches.forEachIndexed { index, batch ->
                val flat = batch.flatMap { it.lines }
                appendBatch(
                    batch,
                    translateLines(flat, target, engine, apiKey, apiUrl, apiRegion, apiModel),
                )
                onProgress(((index + 1) * 100) / batches.size)
            }
        }
        onProgress(100)
        return buildSrt(out)
    }

    // ── old signature for callers that haven't been updated yet ──────────────
    suspend fun translate(
        srt: String,
        targetLanguageCode: String,
        onProgress: (Int) -> Unit,
    ): String {
        val target = SubtitleTranslationTarget.entries.firstOrNull { it.code == targetLanguageCode }
            ?: SubtitleTranslationTarget.PERSIAN
        return translate(srt, target, TranslationEngine.GOOGLE_FREE, onProgress = onProgress)
    }

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Sends a trivial translation through the configured engine and returns
     * a short result string (for the Settings "test" button).
     */
    suspend fun testEndpoint(
        target: SubtitleTranslationTarget,
        engine: TranslationEngine,
        apiKey: String = "",
        apiUrl: String = "",
        apiRegion: String = "",
        apiModel: String = "",
    ): String {
        val sample = "Hello, how are you?"
        val result = when (engine) {
            TranslationEngine.LLM -> translateLlm(sample, target, apiUrl, apiKey, apiModel)
            TranslationEngine.QWEN_MT -> translateQwenMt(sample, target.code, apiKey, apiModel)
            else -> {
                val tinySrt = "1\n00:00:00,000 --> 00:00:01,000\n" + sample + "\n\n"
                translate(tinySrt, target, engine, apiKey, apiUrl, apiRegion, apiModel) { }
            }
        }
        return if (result.isBlank()) "empty response" else result
    }

    /**
     * Calls /v1/models on an OpenAI-compatible endpoint to list available model IDs.
     * Returns empty list when the endpoint doesn't support that route.
     */
    suspend fun listModels(url: String, apiKey: String): List<String> {
        val modelsUrl = deriveModelsUrl(url) ?: return emptyList()
        return try {
            val body: String = client.get(modelsUrl) {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                contentType(io.ktor.http.ContentType.Application.Json)
            }.body()
            val root = Json.parseToJsonElement(body)
            val data = (root as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
            data.mapNotNull {
                val obj = it as? JsonObject ?: return@mapNotNull null
                (obj["id"] as? JsonPrimitive)?.content
            }.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun deriveModelsUrl(chatUrl: String): String? {
        val trimmed = chatUrl.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = java.net.URI(trimmed)
            val port = if (uri.port != -1) ":" + uri.port else ""
            val scheme = uri.scheme ?: "https"
            val path = uri.path ?: ""
            val match = Regex("""^/v\d+""").find(path)
            val basePath = if (match != null) match.value else "/v1"
            scheme + "://" + uri.host + port + basePath + "/models"
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun translateLines(
        lines: List<String>,
        target: SubtitleTranslationTarget,
        engine: TranslationEngine,
        apiKey: String,
        apiUrl: String,
        apiRegion: String,
        apiModel: String,
        onLinesResolved: ((Int) -> Unit)? = null,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        // LLM engines: translate a numbered group of lines per request (far fewer round-trips,
        // far less rate-limit pressure), keeping a strict 1:1 mapping. Any line the grouped
        // answer does not deliver falls back to the proven one-line-per-request path, so cue
        // alignment can never drift. Everything shares llmPermits, bounding total concurrency.
        if (engine == TranslationEngine.LLM) {
            val cache = cache
            return coroutineScope {
                val translated = arrayOfNulls<String>(lines.size)
                val pending = mutableListOf<Int>()
                var resolvedImmediately = 0
                lines.forEachIndexed { index, line ->
                    if (line.isBlank()) {
                        translated[index] = line
                        resolvedImmediately++
                        return@forEachIndexed
                    }
                    // Cache lookup first — a hit means zero network.
                    val ck = cache?.key(engine.name, apiModel, target.code, line)
                    val hit = ck?.let { cache?.get(it) }
                    if (hit != null) {
                        translated[index] = hit
                        resolvedImmediately++
                    } else {
                        pending += index
                    }
                }
                if (resolvedImmediately > 0) onLinesResolved?.invoke(resolvedImmediately)

                suspend fun store(index: Int, value: String) {
                    translated[index] = value
                    if (value.isNotBlank()) {
                        cache?.put(cache.key(engine.name, apiModel, target.code, lines[index]), value)
                    }
                    onLinesResolved?.invoke(1)
                }

                if (pending.isNotEmpty()) {
                    groupLineIndices(pending, lines).map { group ->
                        async {
                            val batch = requestLlmGroup(
                                group.map { lines[it] }, target, apiUrl, apiKey, apiModel,
                            )
                            // Lines the batch could not deliver go out one per request.
                            val missing = group.indices.filter { batch[it] == null }
                            if (missing.isNotEmpty()) {
                                coroutineScope {
                                    missing.map { offset ->
                                        async {
                                            val lineIndex = group[offset]
                                            // requestLlmLine backs off outside the permit; the
                                            // generic path is only a last resort.
                                            val single = requestLlmLine(
                                                lines[lineIndex], target, apiUrl, apiKey, apiModel,
                                            ) ?: llmPermits.withPermit {
                                                translateChunk(
                                                    lines[lineIndex], target, engine,
                                                    apiKey, apiUrl, apiRegion, apiModel,
                                                )
                                            }
                                            store(lineIndex, single)
                                        }
                                    }.awaitAll()
                                }
                            }
                            group.forEachIndexed { offset, lineIndex ->
                                batch[offset]?.let { store(lineIndex, it) }
                            }
                        }
                    }.awaitAll()
                }
                List(lines.size) { index -> translated[index] ?: "" }
            }
        }
        // MT engines: batch, then fall back to per-line on mismatch.
        val joined = lines.joinToString("\n")
        val translated = translateChunk(joined, target, engine, apiKey, apiUrl, apiRegion, apiModel)
        val parts = translated.split("\n")
        if (parts.size == lines.size) return parts
        AppLogger.w(TAG, "[translate.split_mismatch] expected=${lines.size} got=${parts.size}; per-line fallback")
        return lines.map { line ->
            if (line.isBlank()) line
            else translateChunk(line, target, engine, apiKey, apiUrl, apiRegion, apiModel)
        }
    }

    private suspend fun translateChunk(
        text: String,
        target: SubtitleTranslationTarget,
        engine: TranslationEngine,
        apiKey: String,
        apiUrl: String,
        apiRegion: String,
        apiModel: String,
    ): String {
        var lastError: Exception? = null
        repeat(6) { attempt ->
            try {
                val result = when (engine) {
                    TranslationEngine.GOOGLE_FREE    -> translateGoogleFree(text, target.code)
                    TranslationEngine.MICROSOFT_EDGE -> translateEdge(text, target.code)
                    TranslationEngine.DEEPL          -> translateDeepl(text, target, apiKey)
                    TranslationEngine.DEEPLX         -> translateDeeplx(text, target, apiUrl)
                    TranslationEngine.AZURE          -> translateAzure(text, target.code, apiKey, apiRegion)
                    TranslationEngine.GOOGLE_API     -> translateGoogleApi(text, target.code, apiKey)
                    TranslationEngine.QWEN_MT        -> translateQwenMt(text, target.code, apiKey, apiModel)
                    TranslationEngine.LLM            -> translateLlm(text, target, apiUrl, apiKey, apiModel)
                }
                if (result.isBlank() && text.isNotBlank()) error("blank translation")
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                lastError = e
                // Respect the provider's Retry-After; fall back to exponential backoff.
                delay((e.retryAfterMs).coerceIn(1_000L, 120_000L))
            } catch (e: Exception) {
                lastError = e
                delay(700L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("translate failed")
    }

    // ── GOOGLE FREE (translate-pa / gtx) ─────────────────────────────────────

    private suspend fun translateGoogleFree(text: String, targetCode: String): String {
        // Try translate-pa first (new endpoint, works where gtx is blocked),
        // then fall back to the legacy gtx endpoint.
        for (url in GOOGLE_FREE_ENDPOINTS) {
            try {
                return if (url.contains("/translate_a/")) {
                    translateGtxLegacy(url, text, targetCode)
                } else {
                    translateGooglePa(url, text, targetCode)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* try next */ }
        }
        // Both failed — re-try legacy as last resort to surface the real error
        return translateGtxLegacy(GOOGLE_GTX_LEGACY, text, targetCode)
    }

    private suspend fun translateGooglePa(endpoint: String, text: String, targetCode: String): String {
        val lines = text.split("\n")
        val nonBlankIdx = lines.indices.filter { lines[it].isNotBlank() }
        if (nonBlankIdx.isEmpty()) return text
        val payload = nonBlankIdx.map { escapeHtml(lines[it]) }

        val body: String = client.post(endpoint) {
            contentType(ContentType.parse("application/json+protobuf"))
            header("X-Goog-API-Key", GOOGLE_PA_KEY)
            setBody(Json.encodeToString(JsonArray.serializer(),
                buildJsonArray {
                    add(buildJsonArray {
                        payload.forEach { add(JsonPrimitive(it)) }
                        add(JsonPrimitive("auto"))
                        add(JsonPrimitive(targetCode))
                    })
                    add(JsonPrimitive("te_lib"))
                }
            ))
        }.body()
        val root = Json.parseToJsonElement(body) as? JsonArray ?: error("unexpected payload")
        val translations = root.firstOrNull() as? JsonArray ?: error("empty payload")
        val out = lines.toMutableList()
        nonBlankIdx.forEachIndexed { j, lineIdx ->
            val t = (translations.getOrNull(j) as? JsonPrimitive)?.content
                ?: error("missing translation for line $lineIdx")
            out[lineIdx] = t
        }
        return out.joinToString("\n")
    }

    private suspend fun translateGtxLegacy(endpoint: String, text: String, targetCode: String): String {
        val lines = text.split("\n")
        val out = lines.map { line ->
            if (line.isBlank()) line
            else {
                val body: String = client.get(endpoint) {
                    parameter("client", "gtx")
                    parameter("sl", "auto")
                    parameter("tl", targetCode)
                    parameter("dt", "t")
                    parameter("q", line)
                    header("User-Agent", USER_AGENT)
                }.body()
                val root = Json.parseToJsonElement(body) as? JsonArray ?: error("unexpected payload")
                val segments = root.firstOrNull() as? JsonArray ?: error("empty payload")
                segments.mapNotNull { seg ->
                    val fields = seg as? JsonArray
                    (fields?.getOrNull(0) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                }.joinToString("")
            }
        }
        return out.joinToString("\n")
    }

    // ── MICROSOFT EDGE ────────────────────────────────────────────────────────

    private suspend fun translateEdge(text: String, targetCode: String): String {
        // Azure code remaps (same as rockbenben/subtitle-translator)
        val azureTarget = AZURE_CODE_MAP[targetCode] ?: targetCode
        val url = "$EDGE_ENDPOINT?from=&to=${azureTarget}&isEnterpriseClient=false"
        val body: String = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(JsonArray.serializer(),
                buildJsonArray { add(JsonPrimitive(text)) }
            ))
        }.body()
        val root = Json.parseToJsonElement(body) as? JsonArray ?: error("unexpected payload")
        val translated = ((root.firstOrNull() as? JsonObject)
            ?.get("translations") as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("text") as? JsonPrimitive }
            ?.content
            ?: error("invalid Edge response")
        return translated
    }

    // ── DEEPL ─────────────────────────────────────────────────────────────────

    private suspend fun translateDeepl(
        text: String,
        target: SubtitleTranslationTarget,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) error("DeepL API key is required")
        val targetCode = target.deeplCode ?: error("DeepL does not support ${target.label}")
        // Free API key ends with ":fx" → use api-free.deepl.com
        val host = if (apiKey.trimEnd().endsWith(":fx")) DEEPL_FREE_HOST else DEEPL_PRO_HOST
        val body: String = client.post("$host/v2/translate") {
            contentType(ContentType.Application.Json)
            header("Authorization", "DeepL-Auth-Key $apiKey")
            setBody(buildJsonObject {
                putJsonArray("text") { add(JsonPrimitive(text)) }
                put("target_lang", targetCode)
                put("tag_handling", "html")
            }.toString())
        }.body()
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("unexpected DeepL payload")
        return ((root["translations"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("text") as? JsonPrimitive }
            ?.content) ?: error("invalid DeepL response")
    }

    // ── DEEPLX (self-hosted) ──────────────────────────────────────────────────

    private suspend fun translateDeeplx(
        text: String,
        target: SubtitleTranslationTarget,
        apiUrl: String,
    ): String {
        val url = apiUrl.trim().ifBlank { error("DeepLX URL is required") }
        val targetCode = target.deeplCode ?: target.code.uppercase()
        val body: String = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("text", text)
                put("target_lang", targetCode)
                put("source_lang", "auto")
            }.toString())
        }.body()
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("unexpected DeepLX payload")
        return (root["data"] as? JsonPrimitive)?.content
            ?: error("invalid DeepLX response")
    }

    // ── AZURE COGNITIVE SERVICES ──────────────────────────────────────────────

    private suspend fun translateAzure(
        text: String,
        targetCode: String,
        apiKey: String,
        region: String,
    ): String {
        if (apiKey.isBlank()) error("Azure API key is required")
        if (region.isBlank()) error("Azure region is required")
        val azureTarget = AZURE_CODE_MAP[targetCode] ?: targetCode
        val url = "$AZURE_ENDPOINT?api-version=3.0&to=$azureTarget"
        val body: String = client.post(url) {
            contentType(ContentType.Application.Json)
            header("Ocp-Apim-Subscription-Key", apiKey)
            header("Ocp-Apim-Subscription-Region", region)
            setBody(buildJsonArray { add(buildJsonObject { put("Text", text) }) }.toString())
        }.body()
        val root = Json.parseToJsonElement(body) as? JsonArray ?: error("unexpected Azure payload")
        return (((root.firstOrNull() as? JsonObject)
            ?.get("translations") as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("text") as? JsonPrimitive }
            ?.content) ?: error("invalid Azure response")
    }

    // ── GOOGLE CLOUD TRANSLATION API ──────────────────────────────────────────

    private suspend fun translateGoogleApi(
        text: String,
        targetCode: String,
        apiKey: String,
    ): String {
        if (apiKey.isBlank()) error("Google API key is required")
        val body: String = client.post("$GOOGLE_API_ENDPOINT?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("q", text)
                put("target", targetCode)
                put("format", "text")
            }.toString())
        }.body()
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("unexpected Google API payload")
        return ((((root["data"] as? JsonObject)
            ?.get("translations") as? JsonArray)
            ?.firstOrNull() as? JsonObject)
            ?.get("translatedText") as? JsonPrimitive)
            ?.content ?: error("invalid Google API response")
    }

    // ── QWEN-MT (Alibaba seq2seq MT model) ───────────────────────────────────

    private suspend fun translateQwenMt(
        text: String,
        targetCode: String,
        apiKey: String,
        model: String,
    ): String {
        if (apiKey.isBlank()) error("Qwen-MT API key is required")
        val effectiveModel = model.ifBlank { "qwen-mt-plus" }
        val qwenTarget = QWEN_MT_CODE_MAP[targetCode] ?: targetCode
        val url = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        val content = "Translate the following text to $qwenTarget:\n$text"
        val translationOptions = buildJsonObject {
            put("source_lang", "auto")
            put("target_lang", qwenTarget)
        }
        val body: String = llmClient.post(url) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(buildJsonObject {
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", content)
                    })
                }
                put("model", effectiveModel)
                put("stream", false)
                put("translation_options", translationOptions)
            }.toString())
        }.body()
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("unexpected Qwen-MT payload")
        return (((root["choices"] as? JsonArray)
            ?.firstOrNull() as? JsonObject)
            ?.let { (it["message"] as? JsonObject)?.get("content") as? JsonPrimitive }
            ?.content) ?: error("invalid Qwen-MT response")
    }

    // ── LLM (OpenAI-compatible chat-completions) ─────────────────────────────


    /**
     * Collapses an LLM answer to its first non-empty candidate line. Mirrors
     * subtitle-translator's `firstTranslationCandidate`: short/ambiguous inputs
     * sometimes make seq2seq / completion-style models emit a newline-separated
     * list of synonyms ("maybe / probably / likely") — we keep only the first,
     * so cue alignment stays 1:1 and no line shifts.
     */
    private fun firstTranslationCandidate(raw: String): String =
        raw.trim().split("\r?\n").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    private suspend fun translateLlm(
        text: String,
        target: SubtitleTranslationTarget,
        apiUrl: String,
        apiKey: String,
        model: String,
    ): String {
        val raw = requestLlmText(llmUserPrompt(text, target), apiUrl, apiKey, model)
        return firstTranslationCandidate(raw)
    }

    /** One-line translation prompt — the format [translateLlm] has always used. */
    private fun llmUserPrompt(text: String, target: SubtitleTranslationTarget): String =
        "Please respect the original meaning, maintain the original format, and " +
            "rewrite the following content in ${target.label}.\n\n$text"

    /**
     * Posts one chat-completions request with the shared translation system prompt and returns
     * the raw assistant text (which may span several lines — callers decide how to collapse it).
     * Understands the OpenAI-compatible, Anthropic and Gemini wire formats.
     */
    private suspend fun requestLlmText(
        userPrompt: String,
        apiUrl: String,
        apiKey: String,
        model: String,
        requestTimeoutMs: Long? = null,
        maxOutputTokens: Int? = null,
    ): String {
        val url = apiUrl.trim().ifBlank {
            error("LLM endpoint URL is required — pick a preset or type one")
        }
        val effectiveModel = model

        // Default prompts match the repo's config.ts so quality stays consistent.
        val systemPrompt = LLM_SYSTEM_PROMPT

        // Detect endpoint type from the URL so the request body matches the wire protocol.
        val isClaude = url.contains("/v1/messages") || url.contains("anthropic.com")
        val isGemini = url.contains("generativelanguage.googleapis.com") ||
                url.contains(":generateContent")

        val resp = when {
            isClaude -> {
                // Anthropic /v1/messages: system is a top-level param, max_tokens required.
                llmClient.post(url) {
                    requestTimeoutMs?.let { ms -> timeout { requestTimeoutMillis = ms } }
                    contentType(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                    setBody(buildJsonObject {
                        if (effectiveModel.isNotBlank()) put("model", effectiveModel)
                        put("system", systemPrompt)
                        put("max_tokens", 4096)
                        putJsonArray("messages") {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", userPrompt)
                            })
                        }
                    }.toString())
                }
            }
            isGemini -> {
                // Google Gemini :generateContent: x-goog-api-key header.
                llmClient.post(url) {
                    requestTimeoutMs?.let { ms -> timeout { requestTimeoutMillis = ms } }
                    contentType(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header("x-goog-api-key", apiKey)
                    setBody(buildJsonObject {
                        put("systemInstruction", buildJsonObject {
                            putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt) }) }
                        })
                        putJsonArray("contents") {
                            add(buildJsonObject {
                                putJsonArray("parts") { add(buildJsonObject { put("text", userPrompt) }) }
                            })
                        }
                        maxOutputTokens?.let { tokens ->
                            put("generationConfig", buildJsonObject { put("maxOutputTokens", tokens) })
                        }
                    }.toString())
                }
            }
            else -> {
                // OpenAI-compatible: Bearer auth, messages array.
                llmClient.post(url) {
                    requestTimeoutMs?.let { ms -> timeout { requestTimeoutMillis = ms } }
                    contentType(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                    setBody(buildJsonObject {
                        putJsonArray("messages") {
                            add(buildJsonObject {
                                put("role", "system"); put("content", systemPrompt)
                            })
                            add(buildJsonObject {
                                put("role", "user"); put("content", userPrompt)
                            })
                        }
                        if (effectiveModel.isNotBlank()) put("model", effectiveModel)
                        maxOutputTokens?.let { put("max_tokens", it) }
                        put("temperature", 0.3)
                        put("stream", false)
                    }.toString())
                }
            }
        }
        handleUpstreamError(resp)
        val body: String = resp.body()

        val root = Json.parseToJsonElement(body) as? JsonObject
            ?: error("invalid LLM response: not a JSON object: ${body.take(200)}")

        // Some gateways answer a rate limit with HTTP 200 and an error *body* — e.g.
        // {"error":{"message":"You've reached the API rate limit for free users…","type":"…"}}.
        // That body matches none of the success shapes below, so it has to be recognised here,
        // otherwise the whole translation dies with "unrecognized format" instead of waiting
        // the cooldown out. The provider's own message is surfaced to the log.
        val errorNode = root["error"]
        if (errorNode != null && errorNode !is JsonNull) {
            val errorText = when (errorNode) {
                is JsonPrimitive -> errorNode.content
                is JsonObject -> (
                    (errorNode["message"] as? JsonPrimitive)?.content
                        ?: (errorNode["msg"] as? JsonPrimitive)?.content
                        ?: errorNode.toString()
                    )
                else -> errorNode.toString()
            }
            val errorLower = errorText.lowercase()
            if ("rate limit" in errorLower || "rate_limit" in errorLower ||
                "quota" in errorLower || "too many requests" in errorLower ||
                "resource_exhausted" in errorLower
            ) {
                throw RateLimitException("Rate limit — ${errorText.take(200)}", 30_000L)
            }
            error("LLM provider error: ${errorText.take(300)}")
        }

        // OpenAI-compatible: { choices: [{ message: { content: "..." } }] }
        val openAI = ((root["choices"] as? JsonArray)
            ?.firstOrNull() as? JsonObject)
            ?.let { (it["message"] as? JsonObject)?.get("content") as? JsonPrimitive }
            ?.content
        if (openAI != null) return openAI

        // Anthropic Claude: { content: [{ type: "text", text: "..." }] }
        val claude = ((root["content"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
            ?.map { it.content })
            ?.joinToString("")
        if (claude != null && claude.isNotBlank()) return claude

        // Gemini: { candidates: [{ content: { parts: [{ text: "..." }] } }] }
        val gemini = (((root["candidates"] as? JsonArray)
            ?.firstOrNull() as? JsonObject)
            ?.get("content") as? JsonObject)
            ?.let { (it["parts"] as? JsonArray) }
            ?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
            ?.map { it.content }
            ?.joinToString("")
        if (gemini != null && gemini.isNotBlank()) return gemini

        // Fallback: some gateways wrap the result differently
        val alt = (root["output"] as? JsonPrimitive)?.content
            ?: (root["text"] as? JsonPrimitive)?.content
            ?: (root["translation"] as? JsonPrimitive)?.content
        if (alt != null && alt.isNotBlank()) return alt

        error("invalid LLM response — unrecognized format: ${body.take(300)}")
    }

    /**
     * Translates [lines] in a single numbered request. Returns one entry per input line; a null
     * entry means "this line was not delivered" and the caller translates it on its own, so the
     * 1:1 cue alignment never drifts even when the model mangles the format.
     */
    private suspend fun translateLlmBatch(
        lines: List<String>,
        target: SubtitleTranslationTarget,
        apiUrl: String,
        apiKey: String,
        model: String,
    ): Array<String?> {
        if (lines.isEmpty()) return emptyArray()
        // A single line has nothing to align: use the plain, well-tested one-line prompt.
        if (lines.size == 1) {
            return arrayOf(translateLlm(lines[0], target, apiUrl, apiKey, model).ifBlank { null })
        }
        val numbered = lines.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
        val userPrompt = buildString {
            append("Please respect the original meaning, maintain the original format, and rewrite ")
            append("the following ").append(lines.size).append(" numbered subtitle lines in ")
            append(target.label).append(".\n\n")
            append("Reply with exactly ").append(lines.size).append(" lines, in the same order, ")
            append("each line starting with its original number followed by \". \". Translate ")
            append("every line fully: do not merge, split, omit, add, renumber, summarize, or ")
            append("add any explanation.\n\n")
            append(numbered)
        }
        val raw = requestLlmText(
            userPrompt, apiUrl, apiKey, model,
            requestTimeoutMs = LLM_BATCH_REQUEST_TIMEOUT_MS,
            maxOutputTokens = LLM_BATCH_MAX_TOKENS,
        )
        return parseNumberedTranslations(raw, lines.size)
    }

    /**
     * One line, one request, with polite back-off *outside* the shared permit — the fallback
     * path must not freeze every other request behind its rate-limit sleeps (that is exactly
     * what turned a short 429 burst into a multi-minute crawl). Returns null when even this
     * fails; the caller then uses the generic retrying path as a last resort.
     */
    private suspend fun requestLlmLine(
        line: String,
        target: SubtitleTranslationTarget,
        apiUrl: String,
        apiKey: String,
        model: String,
    ): String? {
        var backoffMs = 1_500L
        repeat(LLM_LINE_ATTEMPTS) { attempt ->
            try {
                awaitLlmGate()
                val out = llmPermits.withPermit {
                    translateLlm(line, target, apiUrl, apiKey, model)
                }
                if (out.isNotBlank()) return out
                backoffMs = 1_500L
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                backoffMs = e.retryAfterMs.coerceIn(1_000L, LLM_LINE_MAX_BACKOFF_MS)
                parkLlmGate(System.currentTimeMillis() + backoffMs)
            } catch (e: Exception) {
                if (attempt >= LLM_LINE_ATTEMPTS - 1) {
                    AppLogger.w(TAG, "[translate.llm_line_failed] ${e.javaClass.simpleName}: ${e.message}")
                    return null
                }
                backoffMs = 1_500L * (attempt + 1)
            }
            delay(backoffMs)
        }
        return null
    }

    /**
     * Runs one grouped request with a few attempts. Back-off sleeps happen *outside* the shared
     * permit, so a rate-limited or hung provider slows this group down without freezing every
     * other in-flight group behind it, and a grouped request is given a much shorter timeout
     * than a one-line one — a hung request would otherwise stall the in-order progressive
     * pipeline (chunks can only be published in cue order) for minutes.
     *
     * A rate limit is *waited out* with a growing back-off instead of being fought with the
     * per-line fallback: flooding a429-ing endpoint with single-line requests only extends the
     * cooldown. Only a sustained limit (or a non-transient error) degrades the group to
     * line-by-line, returning all-nulls for the caller.
     */
    private suspend fun requestLlmGroup(
        lines: List<String>,
        target: SubtitleTranslationTarget,
        apiUrl: String,
        apiKey: String,
        model: String,
    ): Array<String?> {
        var backoffMs = 0L
        repeat(LLM_BATCH_ATTEMPTS) { attempt ->
            try {
                // A rate limit seen by a sibling request parks every request — including this
                // one — until the provider's cooldown is over.
                awaitLlmGate()
                return llmPermits.withPermit {
                    translateLlmBatch(lines, target, apiUrl, apiKey, model)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                backoffMs = e.retryAfterMs.coerceIn(1_000L, 30_000L)
                    .coerceAtLeast(15_000L * (attempt + 1))
                // Everyone else waits for the same cooldown instead of failing on its own.
                parkLlmGate(System.currentTimeMillis() + backoffMs)
            } catch (e: Exception) {
                if (attempt > 0) {
                    AppLogger.w(
                        TAG,
                        "[translate.llm_batch_failed] group=${lines.size} ${e.javaClass.simpleName}: ${e.message}",
                    )
                    return arrayOfNulls(lines.size)
                }
                // A timeout is often transient — one quick retry before degrading.
                backoffMs = 1_500L
            }
            delay(backoffMs)
        }
        AppLogger.w(TAG, "[translate.llm_batch.rate_limited] group=${lines.size}; line-by-line fallback")
        return arrayOfNulls(lines.size)
    }

    /**
     * Extracts "N. translation" lines out of a batched LLM answer. Returns one slot per source
     * line, null where nothing could be attributed — the caller re-translates just those lines
     * one by one. An answer with no numbering at all is accepted positionally, but only when it
     * contains exactly the expected number of non-empty lines.
     */
    private fun parseNumberedTranslations(raw: String, expected: Int): Array<String?> {
        val out = arrayOfNulls<String>(expected)
        val responseLines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (line in responseLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val match = LLM_NUMBERED_LINE.matchEntire(trimmed) ?: continue
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: continue
            if (index !in 0 until expected) continue
            val value = match.groupValues[2].trim()
                .removeSurrounding("**").removeSurrounding("__")
                .removeSurrounding("`").removeSurrounding("*").trim()
            if (value.isEmpty()) continue
            if (out[index] == null) out[index] = value
        }
        if (out.all { it == null }) {
            val content = responseLines.map(String::trim).filter(String::isNotBlank)
            if (content.size == expected) {
                content.forEachIndexed { index, value -> out[index] = value }
            }
        }
        return out
    }

    /** Splits pending line indices into request-sized groups (by count and by source chars). */
    private fun groupLineIndices(pending: List<Int>, lines: List<String>): List<List<Int>> {
        val groups = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0
        for (index in pending) {
            val lineChars = lines[index].length + 1
            if (current.isNotEmpty() &&
                (current.size >= LLM_MAX_LINES_PER_REQUEST || chars + lineChars > LLM_MAX_CHARS_PER_REQUEST)
            ) {
                groups += current.toList()
                current = mutableListOf()
                chars = 0
            }
            current += index
            chars += lineChars
        }
        if (current.isNotEmpty()) groups += current.toList()
        return groups
    }


    /**
     * Inspects an upstream HTTP response. On 429 / a provider "rate limit" body
     * it throws [RateLimitException] carrying the provider's own message and the
     * Retry-After value; on any other 4xx/5xx it throws a descriptive error with
     * the body excerpt. Success responses fall through silently.
     */
    private suspend fun handleUpstreamError(resp: HttpResponse) {
        val status = resp.status
        if (status.value in 200..299) return
        val body = resp.body<String>()
        val bodyLower = body.lowercase()
        val retryAfter = resp.headers["Retry-After"]?.toLongOrNull()
        val rateLimited = status.value == 429 ||
            "rate limit" in bodyLower || "rate_limit" in bodyLower ||
            "quota" in bodyLower || "too many requests" in bodyLower
        if (rateLimited) {
            val waitMs = retryAfter?.times(1000) ?: 30_000L
            throw RateLimitException("Rate limit — ${body.take(200)}", waitMs)
        }
        throw RuntimeException("HTTP ${status.value} from translation endpoint: ${body.take(300)}")
    }

    // ── SRT parsing / building ────────────────────────────────────────────────

    private fun parseCues(srt: String): List<Cue> {
        val normalized = srt.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split(Regex("\n{2,}")).mapNotNull { block ->
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { SRT_TIME.containsMatchIn(it) }
            if (timingIndex < 0) return@mapNotNull null
            val match = SRT_TIME.find(lines[timingIndex]) ?: return@mapNotNull null
            val text = lines.drop(timingIndex + 1).filter(String::isNotBlank)
            if (text.isEmpty()) return@mapNotNull null
            Cue(timeMs(match.groupValues, 1), timeMs(match.groupValues, 5), text)
        }
    }

    private fun buildSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1); append('\n')
            append(srtTime(cue.startMs)); append(" --> "); append(srtTime(cue.endMs)); append('\n')
            append(cue.lines.joinToString("\n")); append("\n\n")
        }
    }

    private fun timeMs(groups: List<String>, offset: Int): Long {
        val h = groups[offset].toLongOrNull() ?: 0L
        val m = groups[offset + 1].toLongOrNull() ?: 0L
        val s = groups[offset + 2].toLongOrNull() ?: 0L
        val milli = (groups[offset + 3] + "000").take(3).toLongOrNull() ?: 0L
        return ((h * 60 + m) * 60 + s) * 1000 + milli
    }

    private fun srtTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1000
        return "%02d:%02d:%02d,%03d".format(Locale.ROOT, h, m, s, total % 1000)
    }

    private fun escapeHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val TAG = "SubtitleTranslator"

        // Google free (translate-pa new endpoint + gtx legacy fallback)
        private const val GOOGLE_PA_ENDPOINT =
            "https://translate-pa.googleapis.com/v1/translateHtml"
        private const val GOOGLE_GTX_LEGACY =
            "https://translate.googleapis.com/translate_a/single"
        private const val GOOGLE_GTX_LEGACY_FALLBACK =
            "https://translate.google.com/translate_a/single"
        private val GOOGLE_FREE_ENDPOINTS = listOf(GOOGLE_PA_ENDPOINT, GOOGLE_GTX_LEGACY, GOOGLE_GTX_LEGACY_FALLBACK)
        // Public key embedded in Google's te_lib loader — same semantics as the old client=gtx.
        private const val GOOGLE_PA_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Izia0Eltd9sko"

        // Microsoft Edge
        private const val EDGE_ENDPOINT =
            "https://edge.microsoft.com/translate/translatetext"

        // DeepL
        private const val DEEPL_FREE_HOST = "https://api-free.deepl.com"
        private const val DEEPL_PRO_HOST  = "https://api.deepl.com"

        // Azure
        private const val AZURE_ENDPOINT =
            "https://api.cognitive.microsofttranslator.com/translate"

        // Google Cloud
        private const val GOOGLE_API_ENDPOINT =
            "https://translation.googleapis.com/language/translate/v2"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private const val MAX_BATCH_CHARS = 700
        private const val MAX_BATCH_CUES = 40

        // LLM batching: how many lines (and source chars) go into one cue-batch (equal to one
        // chat-completions request), how many requests may be in flight at once, and how hard a
        // rate-limited group retries before it gives way to the line-by-line path. Keep the
        // concurrency low — free endpoints throttle by request rate, and bigger groups are the
        // safe way to be faster, not more parallelism. Bigger batches also cut per-request
        // overhead (system prompt, headers, connection setup): ~120 dialogue lines per request
        // stays well under typical context/output limits, and LLM_BATCH_MAX_TOKENS scales with it.
        private const val LLM_BATCH_CHARS = 8_000
        private const val LLM_BATCH_CUES = 120
        private const val LLM_MAX_LINES_PER_REQUEST = 120
        private const val LLM_MAX_CHARS_PER_REQUEST = 9_000
        private const val LLM_MAX_CONCURRENT_REQUESTS = 2
        private const val LLM_BATCH_ATTEMPTS = 3
        private const val LLM_LINE_ATTEMPTS = 5
        private const val LLM_LINE_MAX_BACKOFF_MS = 30_000L
        // Grouped requests must not hang the whole-file translation for as long as the generous
        // one-line timeout allows (see requestLlmGroup) — but a 120-line answer legitimately
        // needs a while, so the group timeout matches the client's long request timeout.
        private const val LLM_BATCH_REQUEST_TIMEOUT_MS = 180_000L
        // Output cap for a grouped request: a 120-line answer needs headroom — a line can grow
        // well past its source length (Persian) — and without an explicit cap some gateways
        // truncate it to a tiny default and lines get "lost".
        private const val LLM_BATCH_MAX_TOKENS = 8_192

        private const val LLM_SYSTEM_PROMPT =
            "You are a professional translator. Respond only with the content, " +
                "either translated or rewritten. Do not add explanations, comments, or any extra text."

        // "N. text" / "N) text" / "N: text" — tolerant of markdown emphasis around the number.
        private val LLM_NUMBERED_LINE = Regex("""^[\s>*_#-]*(\d{1,5})[.):\-]\s*(.*)$""")

        // Azure / Edge use non-standard codes for some languages
        private val AZURE_CODE_MAP = mapOf("zh" to "zh-Hans", "zh-TW" to "zh-Hant")

        // Qwen-MT language code remaps (differs from Google for a few languages)
        private val QWEN_MT_CODE_MAP = mapOf(
            "zh-TW" to "zh-tw",
            "pt"    to "pt",
            "fil"   to "tl",
        )

        private val SRT_TIME = Regex(
            """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""",
        )
    }
}
