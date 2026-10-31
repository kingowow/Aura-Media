package org.akkirrai.hibiki.core.subtitle

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Converts downloaded subtitle payloads of any common format into plain SRT text.
 * Unlike Rosetta (which merely renames files to .srt), every format here is actually parsed
 * and rewritten: ZIP archives are unpacked (and, when a wanted episode is known, the matching
 * file inside is chosen), ASS/SSA and TTML events are converted cue by cue, WebVTT timestamps
 * are rewritten, and SubRIP is normalized.
 */
object SubtitleFormat {

    private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val ASS_TIME = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,3})""")
    private val HTML_TAG = Regex("""<[^>]+>""")
    // Character classes, not escapes: Android's ICU regex rejects a bare/escaped '}' form.
    private val ASS_OVERRIDE = Regex("[{][^}]*[}]")
    private val TTML_CUE = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "vtt")

    /**
     * Returns SRT text, or null when the payload is not a convertible subtitle.
     * [episode], when known, makes ZIP selection pick the matching episode's file instead of
     * the biggest one (series packs bundle every episode into a single archive).
     */
    fun toSrt(bytes: ByteArray, nameHint: String, episode: Int? = null): String? {
        if (bytes.isEmpty()) return null
        val inflated = maybeGunzip(bytes)
        val payload = decodeText(inflated) ?: return null
        val head = payload.take(4096)
        return when {
            isZip(payload, inflated) -> fromZip(inflated, nameHint, episode)
            head.contains("WEBVTT") -> vttToSrt(payload)
            head.contains("[Script Info]") || head.contains("[Events]") ||
                head.contains("Dialogue:", ignoreCase = true) -> assToSrt(payload)
            SRT_TIME.containsMatchIn(payload) -> normalizeSrt(payload)
            head.contains("<tt", ignoreCase = true) || head.contains("ttml", ignoreCase = true) ||
                (payload.contains("<p", ignoreCase = true) && payload.contains("begin=")) -> ttmlToSrt(payload)
            else -> null
        }
    }

    /** True when the text contains at least one cue with timestamps. */
    fun hasCues(srt: String): Boolean = SRT_TIME.findAll(srt).count() >= 1

    /**
     * Best-effort episode extraction from a page title or file name. Understands the common
     * fansub shapes: `S01E05`, `1x05`, `season 1 episode 5`, `episode 5`, `ep05`, a trailing
     * `- 05` / `_05`, and split parts like `- 01A` / `01B` (both mean episode 1).
     */
    fun extractEpisode(text: String): Int? {
        var t = text.substringAfterLast('/')
        val ext = t.substringAfterLast('.', "").lowercase()
        if (ext in SUBTITLE_EXT || ext in setOf("sub", "idx", "txt", "mkv", "mp4", "xml", "ttml", "dfxp", "zip")) {
            t = t.substringBeforeLast('.')
        }
        Regex("""(?i)\bs(?:eason)?\s*[-_.]?\s*(\d{1,2})\s*[-_.]?\s*e(?:p)?\s*[-_.]?(\d{1,3})""").find(t)
            ?.let { return it.groupValues[2].toIntOrNull() }
        Regex("""\b(\d{1,2})\s*[xX]\s*(\d{1,3})\b""").find(t)?.let { return it.groupValues[2].toIntOrNull() }
        Regex("""(?i)season\s*(\d{1,2})[^0-9]{0,20}?episode\s*(\d{1,3})""").find(t)
            ?.let { return it.groupValues[2].toIntOrNull() }
        Regex("""(?i)\bepisodes?\s*[-_.]?\s*(\d{1,3})\b""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""(?i)\bep?[-_.]?\s*(\d{1,3})\b""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        // "- 05" / "_05" / ".01A" / "- 01A" (optional single trailing part-letter), bounded so
        // that release resolutions like "-1080p" never match.
        Regex("""[.\-_]\s*(\d{1,3})\s*[A-Za-z]?(?=[\s._\-\]\)]|$)""").find(t)
            ?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""^(\d{1,3})$""").find(t.trim())?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** OpenSubtitles' classic download endpoint serves gzip-compressed SubRIP. */
    private fun maybeGunzip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) return bytes
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        }.getOrDefault(bytes)
    }

    private fun isZip(text: String, bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (text.isEmpty() || !text.first().isLetter() || bytes[2] == 0x03.toByte())

    /** BOM-aware decoding; UTF-16 files appear on some Persian sites. */
    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.size > 1 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charsets.UTF_16LE)
        }
        if (bytes.size > 1 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Picks the best subtitle file inside an archive. Series packs contain every episode, and
     * movie packs often carry a source-ad `.txt` next to the real file, so we filter junk and —
     * when [episode] is known — prefer the entry naming that exact episode (rejecting entries
     * that clearly name a different one).
     */
    private fun fromZip(bytes: ByteArray, nameHint: String, episode: Int?): String? {
        val candidates = mutableListOf<Pair<String, ByteArray>>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val data = zip.readBytes()
                        if (ext in SUBTITLE_EXT && data.size >= MIN_SUBTITLE_BYTES && !isJunkName(name)) {
                            candidates += name to data
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }.onFailure { return null }
        if (candidates.isEmpty()) return null
        val best = candidates.maxByOrNull { (name, data) -> zipEntryScore(name, data, episode) }
            ?: return null
        return toSrt(best.second, best.first.ifBlank { nameHint }, episode)
    }

    private fun zipEntryScore(name: String, data: ByteArray, episode: Int?): Double {
        val low = name.lowercase()
        var score = 0.0
        if (low.contains("persian") || low.contains("farsi") ||
            Regex("""(^|[^a-z])fa([^a-z]|$)""").containsMatchIn(low)
        ) score += 100.0
        val entryEpisode = extractEpisode(name)
        if (episode != null) {
            score += when {
                entryEpisode == null -> 4.0
                entryEpisode == episode -> 80.0
                // A file naming a different episode belongs to another part of the season.
                else -> -200.0
            }
        }
        score += when (low.substringAfterLast('.', "")) {
            "srt" -> 10.0
            "ass", "ssa" -> 6.0
            else -> 3.0
        }
        return score + data.size.coerceAtMost(400_000) / 400_000.0
    }

    private fun isJunkName(name: String): Boolean {
        val low = name.lowercase()
        return JUNK_NAME.containsMatchIn(low)
    }

    private fun vttToSrt(payload: String): String? {
        val lines = payload.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = mutableListOf<Triple<Long, Long, String>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val arrow = line.indexOf("-->")
            if (arrow > 0 && line.take(arrow).contains(Regex("\\d"))) {
                val timing = line
                val texts = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    texts += stripTags(lines[i])
                    i++
                }
                val m = SRT_TIME.find(timing) ?: continue
                cues += Triple(timeMs(m.groupValues, 1), timeMs(m.groupValues, 5), texts.joinToString("\n"))
            } else {
                i++
            }
        }
        if (cues.isEmpty()) return null
        return cuesToSrt(cues)
    }

    private fun assToSrt(payload: String): String? {
        val lines = payload.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var inEvents = false
        var format: List<String> = emptyList()
        val cues = mutableListOf<Triple<Long, Long, String>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            if (format.isEmpty()) {
                format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            }
            val startIdx = format.indexOf("start")
            val endIdx = format.indexOf("end")
            val textIdx = format.indexOf("text")
            if (startIdx < 0 || endIdx < 0 || textIdx < 0 || textIdx < startIdx) continue
            val parts = line.substringAfter(':').trim().split(",", limit = textIdx + 1)
            if (parts.size <= textIdx) continue
            val start = assTimeMs(parts[startIdx].trim()) ?: continue
            val end = assTimeMs(parts[endIdx].trim()) ?: continue
            if (end <= start) continue
            val text = parts[textIdx]
                .replace(ASS_OVERRIDE, "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .lines()
                .joinToString("\n") { stripTags(it).trim() }
                .trim()
            if (text.isNotBlank()) cues += Triple(start, end, text)
        }
        if (cues.isEmpty()) return null
        return cuesToSrt(cues.sortedBy { it.first })
    }

    /** TTML / DFXP (`<tt>…<p begin= end=>`) as carried by many HLS/DASH streams. */
    private fun ttmlToSrt(payload: String): String? {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        for (m in TTML_CUE.findAll(payload)) {
            val attrs = m.groupValues[1]
            val start = ttmlTimeMs(attrValue(attrs, "begin") ?: continue) ?: continue
            val end = attrValue(attrs, "end")?.let { ttmlTimeMs(it) }
                ?: (attrValue(attrs, "dur")?.let { ttmlTimeMs(it)?.plus(start) })
                ?: continue
            if (end <= start) continue
            val text = m.groupValues[2]
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .let { stripTags(it) }
                .trim()
            if (text.isNotBlank()) cues += Triple(start, end, text)
        }
        if (cues.isEmpty()) return null
        return cuesToSrt(cues.sortedBy { it.first })
    }

    private fun attrValue(attrs: String, name: String): String? =
        Regex("""(?i)\b$name\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)

    private fun ttmlTimeMs(value: String): Long? {
        val v = value.trim()
        Regex("""^(\d+):(\d{2}):(\d{2})[.,](\d{1,3})$""").find(v)?.let { return timeMs(it.groupValues, 1) }
        // Frame-based (`00:00:04:12`) — assume 25 fps.
        Regex("""^(\d+):(\d{2}):(\d{2}):(\d{1,3})$""").find(v)?.let {
            val base = ((it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()) * 60 + it.groupValues[3].toLong()) * 1000
            return base + it.groupValues[4].toLong() * 1000 / 25
        }
        Regex("""^(\d+(?:\.\d+)?)(ms|s|m|h)$""").find(v)?.let {
            val n = it.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (it.groupValues[2]) {
                "ms" -> 1.0
                "s" -> 1000.0
                "m" -> 60_000.0
                else -> 3_600_000.0
            }
            return (n * mult).toLong()
        }
        return null
    }

    private fun normalizeSrt(payload: String): String {
        var text = payload
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        text = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
        return text
    }

    private fun cuesToSrt(cues: List<Triple<Long, Long, String>>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1)
            append('\n')
            append(srtTime(cue.first))
            append(" --> ")
            append(srtTime(cue.second))
            append('\n')
            append(cue.third)
            append("\n\n")
        }
    }

    private fun srtTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1000
        val milli = total % 1000
        // Locale.ROOT: a Persian-locale device must not get localized digits inside SRT
        // timestamps - the parser only understands ASCII digits.
        return "%02d:%02d:%02d,%03d".format(Locale.ROOT, h, m, s, milli)
    }

    private fun timeMs(groups: List<String>, offset: Int): Long {
        val h = groups[offset].toLongOrNull() ?: 0L
        val m = groups[offset + 1].toLongOrNull() ?: 0L
        val s = groups[offset + 2].toLongOrNull() ?: 0L
        val fraction = groups[offset + 3]
        val milli = (fraction + "000").take(3).toLongOrNull() ?: 0L
        return ((h * 60 + m) * 60 + s) * 1000 + milli
    }

    private fun assTimeMs(value: String): Long? {
        val m = ASS_TIME.find(value) ?: return null
        return timeMs(m.groupValues, 1)
    }

    private fun stripTags(text: String): String = text.replace(HTML_TAG, "")

    private const val MIN_SUBTITLE_BYTES = 40
    private val JUNK_NAME = Regex(
        """(readme|promo|advert|广告|visit\s|\.url$|^url|source\s*\.?txt|thank|support|donate|sub(?:s|title)?\s*team)""",
        RegexOption.IGNORE_CASE,
    )
}
