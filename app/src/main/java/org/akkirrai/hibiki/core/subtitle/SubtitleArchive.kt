package org.akkirrai.hibiki.core.subtitle

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** One file inside a downloaded subtitle archive. */
data class SubtitleArchiveEntry(
    val name: String,
    val size: Long,
)

/**
 * Reads the contents of subtitle archives (the ZIP files most Persian subtitle sites ship).
 *
 * The player shows the archive's inner files as a list so the viewer can pick the exact track
 * (a season pack holds one file per episode, and a movie pack often carries a stray `.txt` next
 * to the real subtitle). Listing is therefore separate from conversion: [listEntries] never
 * decodes a file, [extract] returns the bytes of one chosen entry.
 */
object SubtitleArchive {

    private val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "vtt")
    private val IGNORED_EXT = setOf("txt", "nfo", "url", "mht", "htm", "html", "jpg", "png", "gif", "sfv", "md5")
    private val JUNK_NAME = Regex(
        """(readme|promo|advert|advertis|visit\s|\.url$|^url$|source\s*\.?txt|thank|support|donate|__macosx|(^|[\\/])\.)""",
        RegexOption.IGNORE_CASE,
    )

    /** True when the payload is a ZIP archive (the `PK\x03\x04` local file header). */
    fun isArchive(bytes: ByteArray): Boolean =
        bytes.size > 3 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte()

    /**
     * The subtitle files inside the archive, best candidates first. Junk entries (readme files,
     * hidden files, macOS resource forks) and non-subtitle files are dropped; the order is by
     * "looks like a real subtitle" so the most plausible file leads the list.
     */
    fun listEntries(bytes: ByteArray): List<SubtitleArchiveEntry> {
        val entries = mutableListOf<SubtitleArchiveEntry>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && isUsableEntry(name)) {
                        // The size is not always written in the local header, so the real length
                        // is measured by reading the entry (the archives here are small).
                        val data = runCatching { zip.readBytes() }.getOrDefault(ByteArray(0))
                        entries += SubtitleArchiveEntry(name, data.size.toLong())
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return entries.sortedByDescending { entryScore(it.name) }
    }

    /** The bytes of one entry of the archive, or null when the name is not in it. */
    fun extract(bytes: ByteArray, entryName: String): ByteArray? {
        val wanted = entryName.substringAfterLast('/')
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.substringAfterLast('/') == wanted) {
                        return zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun isUsableEntry(name: String): Boolean {
        if (name.endsWith("/")) return false
        if (JUNK_NAME.containsMatchIn(name)) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in SUBTITLE_EXT) return true
        return ext !in IGNORED_EXT && ext.isNotEmpty()
    }

    /** Higher is better: a real subtitle extension first, then the bigger file. */
    private fun entryScore(name: String): Double {
        val low = name.lowercase()
        var score = when (low.substringAfterLast('.', "")) {
            "srt" -> 20.0
            "ass", "ssa" -> 16.0
            "vtt" -> 10.0
            else -> 2.0
        }
        if (low.contains("persian") || low.contains("farsi") || low.contains("فارسی")) score += 12.0
        return score
    }
}
