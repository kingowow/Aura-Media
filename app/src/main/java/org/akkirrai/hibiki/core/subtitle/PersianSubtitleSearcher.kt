package org.akkirrai.hibiki.core.subtitle

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.akkirrai.hibiki.core.log.AppLogger
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/** What the player knows about the current episode when a subtitle search starts. */
data class SubtitleQuery(
    val title: String,
    val episodeNumber: Int?,
    val isMovie: Boolean,
)

/** A subtitle release found on a source site, before its file has been downloaded. */
data class SubtitleSearchResult(
    val title: String,
    val pageUrl: String,
    val source: String,
    val language: String? = null,
    val releaseNotes: String? = null,
    val directFileUrl: String? = null,
    /**
     * Set on a 3fa.ir release whose file sits behind an adcd gate: the real download link only
     * appears in a browser after a few seconds, so the entry is shown last and must be opened in
     * a web view instead of downloaded.
     */
    val openInBrowser: Boolean = false,
)

/**
 * The files inside a release that turned out to be an archive: the viewer picks the exact track
 * when a season pack holds one file per episode.
 */
data class SubtitleArchiveListing(
    val result: SubtitleSearchResult,
    val entries: List<SubtitleArchiveEntry>,
    val archiveBytes: ByteArray,
    val directDownloadUrls: List<Pair<String, String>> = emptyList(),
    val downloadReferer: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface SubtitleSearchOutcome {
    data class Found(val file: File, val label: String) : SubtitleSearchOutcome
    data object NotFound : SubtitleSearchOutcome
    data class Failed(val message: String?) : SubtitleSearchOutcome
}

/**
 * Searches the proven Persian-subtitle sources and resolves the best matching file to SRT on
 * disk. The parsing mirrors the user's proven `zirnev.py` CLI and Rosetta's keyless
 * OpenSubtitles call:
 *
 *  - subkade.ir      : WordPress REST search + file links inside the post
 *  - subnama.click   : HTML card search, release pages link a ZIP
 *  - isubtitles.org  : HTML search + per-language download links (the Persian ones are ZIPs)
 *  - subhd.tv        : Chinese site, session-based download API - only for the translate flow
 *  - opensubtitles   : keyless REST API (Rosetta pattern) - direct zipDownloadLink
 *  - 3fa.ir          : WordPress site, adcd gate - the download link appears after a delay,
 *                      so the release is marked to open in a browser and listed LAST
 *
 * A single-file release is applied as-is; a ZIP release exposes its inner subtitle files so the
 * viewer picks the exact track (a season pack carries one file per episode).
 */
class PersianSubtitleSearcher(
    private val context: Context,
    private val client: HttpClient,
) {
    private data class Page(
        val title: String,
        val url: String,
        val source: String,
        val language: String? = null,
        val releaseNotes: String? = null,
        val directFileUrl: String? = null,
    )

    private data class FileLink(
        val label: String,
        val url: String,
        val referer: String,
        val source: String,
        val headers: Map<String, String> = emptyMap(),
        /** A 3fa adcd gate: the real download link only appears in a browser. */
        val isGate: Boolean = false,
        /** The language code the site reported for this file (isubtitles/opensubtitles). */
        val lang: String? = null,
    )

    // ---------------------------------------------------------------- public search API

    /**
     * Resolves the best matching subtitle to SRT on disk. With [persianOnly] (the default) only
     * Persian links are accepted; the translate flow calls it with `persianOnly = false` to grab
     * a foreign-language subtitle (English preferred) that it then translates.
     *
     * [languageFilter] when "fa" keeps only Persian results; when "all" or null, everything passes.
     */
    suspend fun searchAndResolve(
        query: SubtitleQuery,
        persianOnly: Boolean = true,
        languageFilter: String? = null,
    ): SubtitleSearchOutcome {
        val releases = searchReleases(
            text = query.title,
            episodeNumber = query.episodeNumber,
            isMovie = query.isMovie,
            forTranslation = !persianOnly,
            languageFilter = languageFilter,
        )
        if (releases.isEmpty()) return SubtitleSearchOutcome.NotFound
        var sawDownloadError = false
        for (result in releases.take(6)) {
            if (result.openInBrowser) continue
            val listing = inspectRelease(result, query.episodeNumber) ?: continue
            val outcome = applyRelease(listing, listing.entries.firstOrNull()?.name, query.episodeNumber)
            when (outcome) {
                is SubtitleSearchOutcome.Found -> return outcome
                is SubtitleSearchOutcome.Failed -> sawDownloadError = true
                SubtitleSearchOutcome.NotFound -> Unit
            }
        }
        return if (sawDownloadError) SubtitleSearchOutcome.Failed(null) else SubtitleSearchOutcome.NotFound
    }

    /**
     * Searches every source for [text] and returns the matching releases, best first. 3fa is
     * always listed last (its download link appears behind an ad gate after a delay, so it is
     * the least likely to download cleanly). [languageFilter] is "fa" for Persian-only, "all" or
     * null for every language.
     *
     * [onPartial] is called as each source batch completes, with the best-so-far result list,
     * so the UI can show findings immediately instead of waiting for the slowest source.
     */
    suspend fun searchReleases(
        text: String,
        episodeNumber: Int? = null,
        isMovie: Boolean = true,
        forTranslation: Boolean = false,
        languageFilter: String? = null,
        onPartial: (List<SubtitleSearchResult>) -> Unit = {},
    ): List<SubtitleSearchResult> {
        val searchText = text.trim()
        if (searchText.isEmpty()) return emptyList()
        // Query variants: the bare title first (fastest, most hits); the episode-suffixed forms
        // only when the primary found nothing. Running all variants in parallel multiplied every
        // source's request count, which is what made the search feel slow.
        val variants = buildList {
            add(searchText)
            if (episodeNumber != null && !isMovie) {
                add("$searchText $episodeNumber")
                add("$searchText episode $episodeNumber")
            }
        }
        val matchQuery = SubtitleQuery(searchText, episodeNumber, isMovie)
        var pages = emptyList<Page>()
        for (variant in variants) {
            if (pages.any { score(matchQuery, it.title) >= 0.0 }) break
            coroutineScope {
                // All six sources run in parallel; their findings stream into [pages] as each
                // one completes (in source-priority order, so the reliable sites lead).
                val subkade = async { runCatching { subkadeSearch(variant) }.getOrDefault(emptyList()) }
                val subnama = async { runCatching { subnamaSearch(variant) }.getOrDefault(emptyList()) }
                // subhd only when translating (a foreign subtitle that will be translated).
                val subhd = async {
                    if (forTranslation) runCatching { subhdSearch(variant) }.getOrDefault(emptyList())
                    else emptyList()
                }
                // Rosetta keyless OpenSubtitles.
                val openSubtitles = async {
                    runCatching { openSubtitlesSearch(variant, languageFilter) }.getOrDefault(emptyList())
                }
                // 3fa LAST: adcd gate - opened in a browser.
                val threeFa = async {
                    if (languageFilter == "fa" || languageFilter == null) {
                        runCatching { threeFaSearch(variant) }.getOrDefault(emptyList())
                    } else emptyList()
                }
                val deferreds = listOf(subkade, subnama, subhd, openSubtitles, threeFa)
                for (d in deferreds) {
                    val found = d.await()
                    if (found.isEmpty()) continue
                    pages = (pages + found).distinctBy { it.url }
                    onPartial(sortResults(pages, matchQuery))
                }
                // A variant that found nothing may still be retried by the next variant.
                if (pages.none { score(matchQuery, it.title) >= 0.0 }) pages = emptyList()
            }
        }
        return sortResults(pages, matchQuery)
    }

    /**
     * Scores and orders the accumulated pages, always keeping 3fa at the very bottom (its
     * download gate makes it the least useful result) and the reliable sites lead.
     */
    private fun sortResults(pages: List<Page>, matchQuery: SubtitleQuery): List<SubtitleSearchResult> {
        val scored = pages.map { page -> PublishedPagePair(page, score(matchQuery, page.title)) }
        val sorted = scored
            .filter { it.score >= 0.0 }
            .sortedWith(
                compareByDescending<PublishedPagePair> { it.score }
                    .thenBy { if (it.page.source == "3fa") 1 else 0 }
            )
            .distinctBy { it.page.url }
        val results = sorted.map { pair ->
            val page = pair.page
            SubtitleSearchResult(
                title = page.title,
                pageUrl = page.url,
                source = page.source,
                language = page.language,
                releaseNotes = page.releaseNotes,
                openInBrowser = page.source == "3fa",
            )
        }
        return results.take(MAX_RESULTS)
    }

    /**
     * Downloads a release and, when it is an archive, returns the list of files inside it so the
     * viewer can choose one. A release that is a single subtitle file comes back with an empty
     * list and [SubtitleArchiveListing.archiveBytes] holding that file.
     */
    suspend fun inspectRelease(
        result: SubtitleSearchResult,
        episodeNumber: Int? = null,
    ): SubtitleArchiveListing? {
        val page = Page(result.title, result.pageUrl, result.source, result.language)
        val links = runCatching { linksFor(page, result.directFileUrl) }.getOrDefault(emptyList())

        // 3fa gate: the file is not reachable without a browser - surface it as a gate listing so
        // the UI can offer "open in browser" instead of a dead download.
        val gate = links.firstOrNull { it.isGate }
        if (gate != null) {
            return SubtitleArchiveListing(result, emptyList(), ByteArray(0))
        }

        // A release page can list several separate files (e.g. subkade season packs: one
        // zip per episode, or one file per language). Expose them as a selectable list so
        // the viewer picks the right track instead of silently taking the first one.
        if (links.size > 1) {
            return SubtitleArchiveListing(
                result = result,
                entries = links.map { SubtitleArchiveEntry(name = it.label, size = 0L) },
                archiveBytes = ByteArray(0),
                directDownloadUrls = links.map { it.label to it.url },
                downloadReferer = page.url,
            )
        }

        if (links.isEmpty()) {
            // The release is the file itself (a direct link the site printed).
            val direct = result.directFileUrl ?: return null
            val link = FileLink(
                label = direct.substringAfterLast('/').substringBefore('?'),
                url = direct,
                referer = result.pageUrl,
                source = result.source,
            )
            val bytes = runCatching { download(link) }.getOrNull() ?: return null
            val entries = if (SubtitleArchive.isArchive(bytes)) SubtitleArchive.listEntries(bytes) else emptyList()
            return SubtitleArchiveListing(result, entries, bytes)
        }

        // A post can list several files (one per language). Pick the most usable, prefer a
        // single subtitle file over an archive when both exist.
        for (link in links) {
            val bytes = runCatching { download(link) }.getOrNull() ?: continue
            if (SubtitleArchive.isArchive(bytes)) {
                val entries = SubtitleArchive.listEntries(bytes)
                return SubtitleArchiveListing(result, entries, bytes)
            }
            // A plain subtitle file: apply it right away (an empty entries list signals this).
            return SubtitleArchiveListing(result, emptyList(), bytes)
        }
        return null
    }

    /**
     * Downloads a single subtitle [url] (the one the viewer tapped inside a web view, e.g. 3fa's
     * ad-gated link) and wraps it the same way [inspectRelease] does, so the usual apply
     * pipeline (`SubtitleArchiveListing` -> [applyRelease]) can pick it up. [referer] is the page
     * that printed the link; some hosts 403 the direct file without it.
     */
    suspend fun inspectUrl(
        result: SubtitleSearchResult,
        url: String,
        referer: String?,
    ): SubtitleArchiveListing? {
        val link = FileLink(
            label = url.substringAfterLast('/').substringBefore('?'),
            url = url,
            referer = referer ?: url,
            source = result.source,
        )
        val bytes = runCatching { download(link) }.getOrNull() ?: return null
        val entries = if (SubtitleArchive.isArchive(bytes)) SubtitleArchive.listEntries(bytes) else emptyList()
        return SubtitleArchiveListing(result, entries, bytes)
    }

    /**
     * A 3fa gate release: nothing to download (the file opens in a browser). The empty entry list
     * plus zero archive bytes is the UI's signal to show the "open in browser" affordance.
     */
    suspend fun applyRelease(
        listing: SubtitleArchiveListing,
        entryName: String?,
        episodeNumber: Int? = null,
    ): SubtitleSearchOutcome = try {
        // Direct-file listing (subkade with several files): download the chosen file.
        val directBytes = if (listing.directDownloadUrls.isNotEmpty() && entryName != null) {
            val url = listing.directDownloadUrls.firstOrNull { it.first == entryName }?.second
                ?: listing.directDownloadUrls.firstOrNull()?.second
            if (url != null) {
                val link = FileLink(
                    label = entryName,
                    url = url,
                    referer = listing.downloadReferer ?: url,
                    source = listing.result.source,
                )
                runCatching { download(link) }.getOrNull()
            } else null
        } else null

        val bytes = if (directBytes != null) directBytes
        else if (entryName != null) {
            SubtitleArchive.extract(listing.archiveBytes, entryName)
        } else {
            listing.archiveBytes
        }
        if (bytes == null || bytes.isEmpty()) {
            // Either a 3fa gate release (nothing to download) or an extraction miss.
            if (listing.archiveBytes.isEmpty()) {
                SubtitleSearchOutcome.Failed("gate")
            } else {
                SubtitleSearchOutcome.Failed(null)
            }
        } else {
            val srt = SubtitleFormat.toSrt(bytes, entryName ?: listing.result.title, episodeNumber)
            if (srt.isNullOrBlank() || !SubtitleFormat.hasCues(srt)) {
                SubtitleSearchOutcome.Failed(null)
            } else {
                val file = saveText(
                    key = listing.result.pageUrl + "|" + (entryName ?: listing.result.title),
                    srt = srt,
                )
                if (file == null) {
                    SubtitleSearchOutcome.Failed(null)
                } else {
                    SubtitleSearchOutcome.Found(file, "زیرنویس (hibiki)")
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.w(TAG, "[subs.apply.failed] ${e.javaClass.simpleName}: ${e.message}")
        SubtitleSearchOutcome.Failed(e.message)
    }

    // ---------------------------------------------------------------- sources

    /**
     * subkade.ir - a WordPress subtitle site. Search through the WP REST API (the proven
     * `zirnev.py` path); fall back to the HTML `?s=` listing when the API is empty.
     */
    private suspend fun subkadeSearch(query: String): List<Page> {
        val api = runCatching {
            val body: String = client.get("https://subkade.ir/wp-json/wp/v2/search") {
                parameter("search", query)
                parameter("per_page", 8)
                header(USER_AGENT_HEADER, USER_AGENT)
                header("Accept", "application/json")
            }.body()
            val array = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
                ?: return@runCatching emptyList()
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val subtype = obj.str("subtype")
                if (subtype != "post" && subtype != "page") return@mapNotNull null
                val title = (obj["title"] as? JsonObject)?.str("rendered")
                val url = obj.str("url")
                if (title.isNullOrBlank() || url.isNullOrBlank()) return@mapNotNull null
                Page(cleanTitle(title), url, "subkade")
            }
        }.getOrDefault(emptyList())
        if (api.isNotEmpty()) return api.take(8)

        val html = runCatching { fetchText("https://subkade.ir/?s=${encode(query)}", "https://subkade.ir/") }.getOrNull()
        if (html.isNullOrBlank()) return emptyList()
        return Jsoup.parse(html)
            .select("article h2 a[href], article h3 a[href], article a[href]")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (!href.contains("subkade.ir")) return@mapNotNull null
                val title = a.text().trim()
                if (title.length < 3 || title.equals("read more", ignoreCase = true)) return@mapNotNull null
                Page(cleanTitle(title), href, "subkade")
            }
            .distinctBy { it.url }
            .take(8)
    }

    private suspend fun subkadeLinks(page: Page): List<FileLink> {
        val html = fetchText(page.url, page.url)
        val links = Jsoup.parse(html)
            .select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val path = href.substringBefore("?")
                if (!SUBTITLE_EXTENSION.containsMatchIn(path)) return@mapNotNull null
                val rawLabel = a.text().trim().ifBlank { path.substringAfterLast('/') }
                val label = if (rawLabel.equals("دانلود زیرنویس", ignoreCase = true) ||
                    rawLabel.equals("download subtitle", ignoreCase = true)
                ) {
                    "$rawLabel (${path.substringAfterLast('/')})"
                } else rawLabel
                FileLink(
                    label = label,
                    url = href,
                    referer = page.url,
                    source = "subkade",
                )
            }
            .distinctBy { it.url }
        return links
    }

    /**
     * subnama.click - a Persian-only site (not WordPress). Card search with the JSON-LD file
     * references (the proven `zirnev.py` path); download links live on the release page.
     */
    private suspend fun subnamaSearch(query: String): List<Page> {
        val html = runCatching { fetchText("https://subnama.click/?q=${encode(query)}", "https://subnama.click/") }.getOrNull()
        if (html.isNullOrBlank()) return emptyList()
        val document = Jsoup.parse(html)
        // The card markup carries the release name; the href is the post URL.
        val pages = document.select("a[href*=/fa/posts/]").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val title = a.attr("aria-label").ifBlank { a.text() }
            if (title.isBlank()) return@mapNotNull null
            val notes = a.closest("article")?.selectFirst("p.card-episode")?.text()?.trim()
            Page(
                title = cleanTitle(title.replace("دانلود زیرنویس فارسی", "").trim(' ', '·', '-')),
                url = absolute("https://subnama.click", href),
                source = "subnama",
                releaseNotes = notes?.takeIf { it.isNotBlank() },
            )
        }
        if (pages.isNotEmpty()) return pages.distinctBy { it.url }.take(8)
        // Fallback: any post link on the page.
        return document.select("a[href$=/]").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.startsWith("/fa/posts/")) return@mapNotNull null
            val title = a.attr("aria-label").ifBlank { a.text() }
            if (title.isBlank()) return@mapNotNull null
            Page(cleanTitle(title), absolute("https://subnama.click", href), "subnama")
        }.distinctBy { it.url }.take(8)
    }

    private suspend fun subnamaLinks(page: Page): List<FileLink> {
        val html = fetchText(page.url, page.url)
        val document = Jsoup.parse(html)
        // The proven `zirnev.py` path: subtitle/zip references in the page, plus JSON-LD encoding.
        val regex = Regex(
            """href\s*=\s*["\']([^"']+\.(?:srt|ass|ssa|vtt|sub|txt|zip|rar|7z)(?:\?[^"']*)?)["\']""",
            RegexOption.IGNORE_CASE,
        )
        val seen = mutableSetOf<String>()
        val links = mutableListOf<FileLink>()
        for (match in regex.findAll(document.html())) {
            val ref = Jsoup.parse(match.groupValues[1]).text().ifBlank { match.groupValues[1] }
            val url = absolute(page.url, ref)
            val key = url.substringBefore("?").lowercase()
            if (key in seen) continue
            seen.add(key)
            links += FileLink(
                label = url.substringAfterLast('/').substringBefore('?'),
                url = url,
                referer = page.url,
                source = "subnama",
            )
        }
        if (links.isNotEmpty()) return links
        return emptyList()
    }

    /**
     * isubtitles.org - HTML search; the detail page lists per-language download links. The
     * Persian links are ZIPs; the language slug is captured so the right file is picked.
     */
    private suspend fun isubtitlesSearch(query: String): List<Page> {
        val html = runCatching { fetchText("https://isubtitles.org/search?kwd=${encode(query)}", "https://isubtitles.org/") }.getOrNull()
        if (html.isNullOrBlank()) return emptyList()
        return Jsoup.parse(html)
            .select("a[href$=-subtitles][title]")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (!href.endsWith("-subtitles")) return@mapNotNull null
                Page(cleanTitle(a.attr("title")), "https://isubtitles.org$href", "isubtitles")
            }
            .filter { it.title.isNotBlank() }
            .take(8)
    }

    private suspend fun isubtitlesLinks(page: Page): List<FileLink> {
        val html = fetchText(page.url, page.url)
        val document = Jsoup.parse(html)
        val regex = Regex("href=\"(/download/[^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
        val seen = mutableSetOf<String>()
        val links = mutableListOf<FileLink>()
        for (match in regex.findAll(document.html())) {
            val href = match.groupValues[1]
            val label = cleanTitle(match.groupValues[2].ifBlank { href.substringAfterLast('/') })
            val abs = "https://isubtitles.org$href"
            if (abs in seen) continue
            seen.add(abs)
            // The slug after the language path segment is the language code.
            val lang = Regex("/download/[^/]+/([^/]+)/\\d+").find(abs)?.groupValues?.get(1)
            links += FileLink(label, abs, page.url, "isubtitles", lang = lang)
        }
        // Also catch any subtitle-extension link printed on the page.
        if (links.isEmpty()) {
            links += Jsoup.parse(document.html())
                .select("a[href]")
                .mapNotNull { a ->
                    val href = a.attr("href")
                    if (!SUBTITLE_EXTENSION.containsMatchIn(href.substringBefore('?'))) return@mapNotNull null
                    FileLink(label = a.text().ifBlank { href.substringAfterLast('/') }, url = href, referer = page.url, source = "isubtitles")
                }
                .distinctBy { it.url }
        }
        return links
    }

    /**
     * OpenSubtitles - keyless REST API following the Rosetta pattern exactly:
     * `https://rest.opensubtitles.org/search/query-<query>[/sublanguageid-<lang>]`. The query
     * MUST be fully lowercase and percent-encoded (an uppercase or raw-space path makes the API
     * answer 302 with an empty body). The response is a bare array of subtitle objects, each
     * carrying a `ZipDownloadLink` (PascalCase, exactly as the live API and Rosetta's
     * `@SerialName` fields report; no auth header needed).
     */
    private suspend fun openSubtitlesSearch(query: String, languageFilter: String?): List<Page> {
        // Live-tested: the language path filter answers [] for "fas" (a valid OpenSubtitles
        // language id, but not what the /search endpoint accepts here). The working code is
        // "per" — the same one Rosetta's Language.Persian uses, and what the response items
        // carry as SubLanguageID / LanguageName="Persian".
        val lang = when (languageFilter) {
            "fa", "fas", "farsi", "per" -> "per"
            "en", "eng", "english" -> "eng"
            "zh", "chi", "chinese" -> "chi"
            "ja", "jpn", "japanese" -> "jpn"
            "ko", "kor", "korean" -> "kor"
            "ar", "ara", "arabic" -> "ara"
            else -> ""
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        // The API lowercases the query itself; a raw uppercase letter or space in the path is
        // answered with an empty 302 instead of JSON. Encode the lowercase form.
        val encodedQuery = cleanQuery.lowercase().replace(" ", "%20")
        val url = buildString {
            append("https://rest.opensubtitles.org/search/query-")
            append(encodedQuery)
            if (lang.isNotEmpty()) {
                append("/sublanguageid-")
                append(lang)
            }
        }
        val body: String = runCatching {
            client.get(url) {
                header(USER_AGENT_HEADER, USER_AGENT)
                header("Accept", "application/json")
            }.body<String>()
        }.getOrNull() ?: return emptyList()
        // The API can answer a 302 with an empty or HTML body for a bad/unknown path; only a
        // JSON array is usable, anything else is treated as "no results" for this source.
        if (body.isBlank() || !body.startsWith("[")) return emptyList()
        val array = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.str("MovieName") ?: obj.str("movieName") ?: obj.str("SubFileName") ?: ""
            val downloadLink = obj.str("ZipDownloadLink") ?: obj.str("zipDownloadLink") ?: ""
            if (title.isBlank() || downloadLink.isBlank()) return@mapNotNull null
            val year = obj.str("MovieYear")
            val season = obj.str("SeriesSeason")?.takeIf { it.isNotBlank() }
            val episode = obj.str("SeriesEpisode")?.takeIf { it.isNotBlank() }
            val langName = obj.str("LanguageName") ?: obj.str("subLanguage") ?: null
            val releaseNotes = listOfNotNull(
                year?.takeIf { it.isNotBlank() },
                season?.let { "Season $it" },
                episode?.let { "Episode $it" },
            ).joinToString(" · ")
            Page(
                title = cleanTitle(title),
                url = downloadLink,
                source = "opensubtitles",
                language = langName,
                releaseNotes = releaseNotes,
                directFileUrl = downloadLink,
            )
        }.distinctBy { it.url }.take(8)
    }

    /** OpenSubtitles hands back a direct download URL already (the Rosetta zipDownloadLink). */
    private suspend fun openSubtitlesLinks(page: Page, directFileUrl: String?): List<FileLink> {
        val url = directFileUrl?.takeIf { it.isNotBlank() } ?: page.url
        if (url.isBlank()) return emptyList()
        return listOf(
            FileLink(
                label = (page.language ?: url.substringAfterLast('/')).let { "$it (opensubtitles)" },
                url = url,
                referer = "https://opensubtitles.org/",
                source = "opensubtitles",
                lang = page.language,
            )
        )
    }

    /**
     * 3fa.ir - WordPress subtitle site behind an adcd gate. The direct download link only appears
     * on the page after a few seconds, so the release is marked [SubtitleSearchResult.openInBrowser]
     * and listed last. The proven `zirnev.py` path searches the WP REST API first, then HTML.
     */
    private suspend fun threeFaSearch(query: String): List<Page> {
        val api = runCatching {
            val body: String = client.get("https://3fa.ir/wp-json/wp/v2/search") {
                parameter("search", query)
                parameter("per_page", 8)
                header(USER_AGENT_HEADER, USER_AGENT)
                header("Accept", "application/json")
            }.body()
            val array = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
                ?: return@runCatching emptyList()
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val subtype = obj.str("subtype")
                if (subtype != "post" && subtype != "page") return@mapNotNull null
                val title = (obj["title"] as? JsonObject)?.str("rendered")
                val url = obj.str("url")
                if (title.isNullOrBlank() || url.isNullOrBlank()) return@mapNotNull null
                Page(cleanTitle(title), url, "3fa")
            }
        }.getOrDefault(emptyList())
        if (api.isNotEmpty()) return api.take(8)

        val html = runCatching { fetchText("https://3fa.ir/?s=${encode(query)}", "https://3fa.ir/") }.getOrNull()
        if (html.isNullOrBlank()) return emptyList()
        return Jsoup.parse(html)
            .select("article h2 a[href], article h3 a[href], article a[href]")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (!href.contains("3fa.ir")) return@mapNotNull null
                val title = a.text().trim()
                if (title.length < 3 || title.equals("read more", ignoreCase = true)) return@mapNotNull null
                Page(cleanTitle(title), href, "3fa")
            }
            .distinctBy { it.url }
            .take(8)
    }

    /**
     * 3fa links: direct file links on the page first; when none exist the adcd gate means the
     * download link appears after a delay in a browser, so the link is flagged [FileLink.isGate].
     */
    private suspend fun threeFaLinks(page: Page): List<FileLink> {
        val html = runCatching { fetchText(page.url, page.url) }.getOrNull()
        if (html.isNullOrBlank()) {
            return listOf(FileLink("صفحه‌ی ۳fa (باز کردن در مرورگر)", page.url, page.url, "3fa", isGate = true))
        }
        val links = Jsoup.parse(html)
            .select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val path = href.substringBefore('?')
                if (!SUBTITLE_EXTENSION.containsMatchIn(path)) return@mapNotNull null
                FileLink(path.substringAfterLast('/'), href, page.url, "3fa")
            }
            .distinctBy { it.url }
        if (links.isNotEmpty()) return links
        // No direct link on the page - it is the adcd gate.
        val gate = Regex("""href="([^"]*adcd[^"]*)"""").find(html)
        return if (gate != null) {
            val raw = gate.groupValues[1].replace("#038", "&")
            listOf(FileLink("دانلود با مرورگر (gate)", absolute(page.url, raw), page.url, "3fa", isGate = true))
        } else {
            listOf(FileLink("صفحه‌ی ۳fa (باز کردن در مرورگر)", page.url, page.url, "3fa", isGate = true))
        }
    }

    /** The file links of a release page, dispatched by source. */
    private suspend fun linksFor(page: Page, directFileUrl: String? = null): List<FileLink> = when (page.source) {
        "subkade" -> subkadeLinks(page)
        "subnama" -> subnamaLinks(page)
        "subhd" -> subhdLinks(page)
        "opensubtitles" -> openSubtitlesLinks(page, directFileUrl)
        "3fa" -> threeFaLinks(page)
        else -> listOf(FileLink(page.title, page.url, page.url, page.source))
    }

    // ---------------------------------------------------------------- subhd (Chinese, translate only)

    private suspend fun subhdSearch(query: String): List<Page> {
        val html = runCatching { fetchText("https://subhd.tv/search/${encode(query)}", "https://subhd.tv/") }.getOrNull()
        if (html.isNullOrBlank()) return emptyList()
        val document = Jsoup.parse(html)
        return document.select("a[href^=/a/]").mapNotNull { a ->
            val href = a.attr("href")
            val id = href.substringAfter("/a/").substringBefore('?').trim('/')
            if (id.isBlank()) return@mapNotNull null
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            Page(cleanTitle(title), "https://subhd.tv/a/$id", "subhd")
        }.distinctBy { it.url }.take(8)
    }

    /**
     * subhd.tv download flow (the proven `zirnev.py` session): fetch the detail page to seed a
     * session, read the `sid`, POST `prepare-download`, hit the returned download page, then POST
     * `down` for the final file URL, and download it.
     */
    private suspend fun subhdLinks(page: Page): List<FileLink> {
        val id = page.url.substringAfterLast('/')
        if (id.isBlank()) return emptyList()
        val baseHeaders = mapOf(
            "Referer" to page.url,
            "Origin" to "https://subhd.tv",
            "X-Requested-With" to "XMLHttpRequest",
        )
        // Seed the session (SubHD uses a fresh-session flow like the reference).
        runCatching {
            client.get(page.url) {
                header(USER_AGENT_HEADER, USER_AGENT)
                header("Referer", "https://subhd.tv/")
            }
        }
        val prepare = runCatching {
            client.post("https://subhd.tv/api/sub/prepare-download") {
                header(USER_AGENT_HEADER, USER_AGENT)
                baseHeaders.forEach { (name, value) -> header(name, value) }
                contentType(ContentType.Application.Json)
                setBody("""{"sid":"$id"}""")
            }.body<String>()
        }.getOrNull() ?: return emptyList()
        val json = runCatching { Json.parseToJsonElement(prepare) }.getOrNull() as? JsonObject ?: return emptyList()
        if (json.str("success") != "true") return emptyList()
        val downPath = json.str("url")?.takeIf { it.startsWith("/down/") } ?: return emptyList()
        val downPage = "https://subhd.tv$downPath"
        runCatching {
            client.get(downPage) {
                header(USER_AGENT_HEADER, USER_AGENT)
                header("Referer", page.url)
            }
        }
        val final = runCatching {
            client.post("https://subhd.tv/api/sub/down") {
                header(USER_AGENT_HEADER, USER_AGENT)
                header("Referer", downPage)
                header("Origin", "https://subhd.tv")
                header("X-Requested-With" , "XMLHttpRequest")
                contentType(ContentType.Application.Json)
                setBody("""{"sid":"$id"}""")
            }.body<String>()
        }.getOrNull() ?: return emptyList()
        val finalJson = runCatching { Json.parseToJsonElement(final) }.getOrNull() as? JsonObject ?: return emptyList()
        if (!(finalJson.str("success") == "true" && finalJson.str("pass") == "true" && !finalJson.str("url").isNullOrBlank())) {
            return emptyList()
        }
        val fileUrl = finalJson.str("url")?.let { absolute("https://subhd.tv/", it) } ?: return emptyList()
        return listOf(
            FileLink(
                label = id,
                url = fileUrl,
                referer = downPage,
                source = "subhd",
                headers = baseHeaders,
            )
        )
    }

    // ---------------------------------------------------------------- matching

    /** Returns a score (>= 0 keeps the candidate), or -1 to reject. */
    private fun score(query: SubtitleQuery, candidateTitle: String): Double {
        val normQuery = normalize(query.title)
        val normCandidate = normalize(candidateTitle)
        if (normQuery.isEmpty() || normCandidate.isEmpty()) return -1.0
        val queryTokens = normQuery.split(' ').filter { it.length > 1 }
        if (queryTokens.isEmpty()) return -1.0
        val overlap = queryTokens.count { normCandidate.contains(it) }.toDouble() / queryTokens.size
        val containsAll = normCandidate.contains(normQuery)
        if (overlap < 0.6 && !containsAll) return -1.0
        var score = overlap * 60.0 + (if (containsAll) 25.0 else 0.0)
        val candidateEpisode = SubtitleFormat.extractEpisode(candidateTitle)
        val wanted = query.episodeNumber
        if (wanted != null && !query.isMovie) {
            score += when {
                candidateEpisode == null -> 5.0
                candidateEpisode == wanted -> 40.0
                else -> return -1.0
            }
        } else if (candidateEpisode != null) {
            score -= 45.0
        }
        return score
    }

    private fun normalize(title: String): String = title
        .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
        .lowercase()
        .replace(Regex("""\bsubtitles?\b"""), " ")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

    // ---------------------------------------------------------------- download

    private suspend fun download(link: FileLink): ByteArray? {
        if (link.isGate) return null
        val bytes: ByteArray = client.get(link.url) {
            header(USER_AGENT_HEADER, USER_AGENT)
            header("Referer", link.referer)
            link.headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank() && !name.equals("User-Agent", true)) {
                    header(name, value)
                }
            }
        }.body()
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart().lowercase()
        if (head.startsWith("#extm3u")) {
            return resolveSubtitlePlaylist(String(bytes, Charsets.UTF_8), link)
        }
        if (head.startsWith("<!doctype") || head.startsWith("<html")) {
            // A redirect/landing page: look for the real subtitle/zip link and follow it once.
            val text = String(bytes, Charsets.UTF_8)
            val match = Regex(
                """href="([^"]+\.(?:zip|srt|ass|ssa|vtt|rar|7z)(?:\?[^"]*)?)"""",
                RegexOption.IGNORE_CASE,
            ).find(text) ?: return null
            val resolved = URI(link.url).resolve(match.groupValues[1]).toString()
            return client.get(resolved) {
                header(USER_AGENT_HEADER, USER_AGENT)
                header("Referer", link.url)
                link.headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank() && !name.equals("User-Agent", true)) {
                        header(name, value)
                    }
                }
            }.body()
        }
        return bytes
    }

    /**
     * Merges the segments of an HLS subtitle playlist into one subtitle payload. VTT segments are
     * concatenated (WEBVTT headers stripped after the first); ASS/SSA segments are merged by keeping
     * the first segment's header lines plus every Dialogue line. Relative segment URLs are resolved
     * against the playlist URL.
     */
    private suspend fun resolveSubtitlePlaylist(playlistText: String, link: FileLink): ByteArray? {
        val base = URI(link.url)
        val segments = playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { base.resolve(it).toString() }
            .distinct()
            .take(MAX_PLAYLIST_SEGMENTS)
            .toList()
        if (segments.isEmpty()) return null

        val parts = segments.mapNotNull { segUrl ->
            runCatching {
                client.get(segUrl) {
                    header(USER_AGENT_HEADER, USER_AGENT)
                    header("Referer", link.referer)
                    link.headers.forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank() && !name.equals("User-Agent", true)) {
                            header(name, value)
                        }
                    }
                }.body<ByteArray>()
            }.getOrNull()
        }
        if (parts.isEmpty()) return null
        val texts = parts.map { String(it, Charsets.UTF_8) }

        if (texts.first().contains("WEBVTT", ignoreCase = true)) {
            val merged = StringBuilder()
            texts.forEach { seg ->
                seg.lineSequence().forEach { raw ->
                    val line = raw.trimEnd('\r')
                    when {
                        line.equals("WEBVTT", ignoreCase = true) -> Unit
                        line.startsWith("NOTE") -> Unit
                        line.startsWith("STYLE") -> Unit
                        line.startsWith("X-TIMESTAMP-MAP", ignoreCase = true) -> Unit
                        else -> merged.append(line).append('\n')
                    }
                }
            }
            return merged.toString().toByteArray(Charsets.UTF_8)
        }
        if (texts.first().contains("[Script Info]") || texts.first().contains("Dialogue:", ignoreCase = true)) {
            val headerLines = texts.first().lineSequence()
                .takeWhile { !it.startsWith("Dialogue:", ignoreCase = true) }
                .joinToString("\n")
            val dialogues = texts.flatMap { seg ->
                seg.lineSequence().filter { it.startsWith("Dialogue:", ignoreCase = true) }.toList()
            }
            return (headerLines + "\n" + dialogues.joinToString("\n")).toByteArray(Charsets.UTF_8)
        }
        return texts.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** Downloads a single subtitle URL directly (used when translating the video's own track). */
    suspend fun downloadDirect(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = runCatching {
        download(
            FileLink(
                label = url.substringAfterLast('/'),
                url = url,
                referer = headers["Referer"] ?: url,
                source = "direct",
                headers = headers,
            ),
        )
    }.getOrNull()

    /** Persists already-SRT text (translated output or a converted file) into the subtitles directory. */
    fun saveText(key: String, srt: String): File? = runCatching {
        val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
        val file = File(dir, sha1(key).take(16) + ".srt")
        file.writeText(srt, Charsets.UTF_8)
        file
    }.getOrNull()

    private suspend fun fetchText(url: String, referer: String? = null): String = client.get(url) {
        header(USER_AGENT_HEADER, USER_AGENT)
        header("Accept-Language", "fa,fa-IR;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) header("Referer", referer)
    }.body()

    private fun cleanTitle(raw: String): String = Jsoup.parse(raw).text()
        .replace(Regex("""\s+"""), " ")
        .removeSuffix(" Subtitles")
        .trim()

    private fun absolute(base: String, href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> base + href
        else -> base + "/" + href
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** A scored release candidate: the page plus its match score, for the source-ordered sort. */
    private data class PublishedPagePair(
        val page: Page,
        val score: Double,
    )

    companion object {
        private const val TAG = "PersianSubtitleSearcher"
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private const val MAX_PLAYLIST_SEGMENTS = 400
        private const val MAX_RESULTS = 40
        private val SUBTITLE_EXTENSION = Regex("""\.(srt|ass|ssa|vtt|sub|txt|zip|rar|7z)(?:$|\?)""", RegexOption.IGNORE_CASE)
    }
}
