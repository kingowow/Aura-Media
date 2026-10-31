package org.akkirrai.hibiki.core.source.extension

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Reads `index.pb` repository indexes - the protobuf-encoded counterpart of `index.min.json`
 * that some Aniyomi extension repositories publish (e.g. the `repo` branch of Secozzi's
 * `aniyomi-extensions`). This is what made such repositories show up as "invalid".
 *
 * No `.proto` schema is assumed: none ships with the app and a remembered/guessed schema only
 * produced "invalid index" errors. Instead the bytes are walked with generic wire-format
 * parsing, extension records are located structurally (a repeated wrapper field, a bare
 * concatenation of messages, or a repeating field-number cycle), and every field number is
 * identified by *content voting* across all records:
 *
 *   - package names match `letter.segment...` per segment
 *   - APK paths end in `.apk`
 *   - languages are short lowercase tags (`en`, `multi`, ...) or well-known language codes
 *   - versions are dotted numbers
 *   - nsfw is the integer field whose values are only 0/1
 *
 * When the essential `pkg`/`apk` fields cannot be identified with confidence the decode fails
 * and the caller surfaces the same "invalid repository index" error as for malformed JSON.
 */
object ExtensionIndexProtobuf {

    private const val WIRE_VARINT = 0
    private const val WIRE_I64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_I32 = 5

    private const val MIN_SCORE = 0.6
    private const val MIN_PRESENCE = 0.6

    private val PACKAGE_VALUE =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val LANGUAGE_VALUE = Regex("^[a-z]{2,3}(-[a-z0-9]+)*$")
    private val VERSION_VALUE = Regex("^\\d{1,4}(\\.\\d{1,4}){0,3}$")
    private val IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif")

    private val KNOWN_LANGUAGES = setOf(
        "en", "ja", "zh", "cn", "ko", "ru", "uk", "fr", "de", "es", "it", "pt", "pt-br",
        "ar", "fa", "tr", "th", "vi", "id", "ms", "hi", "bn", "pl", "nl", "sv", "no", "da",
        "fi", "cs", "sk", "sl", "hr", "sr", "bg", "ro", "hu", "el", "he", "et", "lv", "lt",
        "zh-cn", "zh-tw", "zh-hans", "zh-hant", "ja-ro", "en-us", "multi", "all", "other",
    )

    private class IndexFormatException(message: String) : Exception(message)

    private data class Token(
        val field: Int,
        val wire: Int,
        val varint: Long?,
        val payload: ByteArray?,
    )

    /** Decodes a whole `index.pb` body. Throws when the layout is not understood. */
    fun decode(raw: ByteArray): List<ApkRepositoryExtension> {
        val bytes = gunzip(raw)
        if (bytes.isEmpty()) return emptyList()
        val top = runCatching { parseTokens(bytes, 0, bytes.size) }
            .getOrElse { throw IllegalArgumentException("body is not a protobuf message", it) }
        for (records in candidateRecordSets(top)) {
            val extensions = extensionsFromRecords(records)
            if (extensions.isNotEmpty()) return extensions
        }
        throw IllegalArgumentException("protobuf index layout is not recognized")
    }

    // ---------------------------------------------------------------- structure

    /** Ordered guesses at how top-level tokens group into one record per extension. */
    private fun candidateRecordSets(top: List<Token>): List<List<List<Token>>> {
        val candidates = mutableListOf<List<List<Token>>>()
        if (top.isEmpty()) return candidates

        // A single extension (the whole file is one message whose only field is the extension).
        if (top.size == 1 && top[0].wire == WIRE_LEN) {
            parsePayloads(listOf(top[0]))?.let { candidates += it }
        }

        // `repeated Extension ext = 1;` style: the same length-delimited field repeats.
        val repeated = top.asSequence()
            .filter { it.wire == WIRE_LEN }
            .groupBy { it.field }
            .values
            .filter { it.size >= 2 }
            .maxByOrNull { it.size }
        if (repeated != null) {
            parsePayloads(repeated)?.let { records ->
                candidates += records
                // One more wrapper level: each "record" is itself a single repeated field.
                if (records.all { rec -> rec.size >= 2 && rec.all { it.field == rec[0].field && it.wire == WIRE_LEN } }) {
                    val inner = mutableListOf<List<Token>>()
                    var usable = true
                    for (rec in records) {
                        val parsed = rec.mapNotNull { token ->
                            token.payload?.let { runCatching { parseTokens(it, 0, it.size) }.getOrNull() }
                        }
                        if (parsed.size != rec.size) { usable = false; break }
                        inner += parsed
                    }
                    if (usable) candidates += inner
                }
            }
        }

        // Concatenated messages / mixed top level: a repeating field-number cycle.
        cycleRecords(top)?.let { candidates += it }

        // The file is one flat message (fields 1..N of a single extension).
        if (top.size >= 3 && top.map { it.field }.distinct().size == top.size) {
            candidates += listOf(top)
        }
        return candidates
    }

    private fun cycleRecords(top: List<Token>): List<List<Token>>? {
        val size = top.size
        if (size < 2) return null
        for (period in 1..minOf(32, size / 2)) {
            if (size % period != 0) continue
            val matches = (period until size).all { top[it].field == top[it - period].field }
            if (!matches) continue
            val count = size / period
            if (count < 2) continue
            return (0 until count).map { k -> top.subList(k * period, (k + 1) * period) }
        }
        return null
    }

    private fun parsePayloads(tokens: List<Token>): List<List<Token>>? {
        if (tokens.isEmpty()) return null
        val out = ArrayList<List<Token>>(tokens.size)
        for (token in tokens) {
            val payload = token.payload ?: return null
            out += runCatching { parseTokens(payload, 0, payload.size) }.getOrNull() ?: return null
        }
        return out
    }

    // ---------------------------------------------------------------- wire format

    private fun parseTokens(data: ByteArray, start: Int, end: Int): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = start
        while (pos < end) {
            var shift = 0
            var tag = 0L
            while (true) {
                if (pos >= end) throw IndexFormatException("truncated field tag")
                val byte = data[pos].toInt() and 0xFF
                pos++
                tag = tag or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 63) throw IndexFormatException("malformed field tag")
            }
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field <= 0) throw IndexFormatException("invalid field number")
            when (wire) {
                WIRE_VARINT -> {
                    var value = 0L
                    var varintShift = 0
                    while (true) {
                        if (pos >= end) throw IndexFormatException("truncated varint")
                        val byte = data[pos].toInt() and 0xFF
                        pos++
                        value = value or ((byte and 0x7F).toLong() shl varintShift)
                        if (byte and 0x80 == 0) break
                        varintShift += 7
                        if (varintShift > 63) throw IndexFormatException("malformed varint")
                    }
                    tokens += Token(field, WIRE_VARINT, value, null)
                }
                WIRE_I64 -> {
                    if (pos + 8 > end) throw IndexFormatException("truncated 64-bit field")
                    tokens += Token(field, WIRE_I64, null, data.copyOfRange(pos, pos + 8))
                    pos += 8
                }
                WIRE_I32 -> {
                    if (pos + 4 > end) throw IndexFormatException("truncated 32-bit field")
                    tokens += Token(field, WIRE_I32, null, data.copyOfRange(pos, pos + 4))
                    pos += 4
                }
                WIRE_LEN -> {
                    var length = 0L
                    var lenShift = 0
                    while (true) {
                        if (pos >= end) throw IndexFormatException("truncated length")
                        val byte = data[pos].toInt() and 0xFF
                        pos++
                        length = length or ((byte and 0x7F).toLong() shl lenShift)
                        if (byte and 0x80 == 0) break
                        lenShift += 7
                        if (lenShift > 63) throw IndexFormatException("malformed length")
                    }
                    if (length < 0 || length > end - pos) throw IndexFormatException("payload out of bounds")
                    tokens += Token(field, WIRE_LEN, null, data.copyOfRange(pos, pos + length.toInt()))
                    pos += length.toInt()
                }
                else -> throw IndexFormatException("unsupported wire type $wire")
            }
        }
        return tokens
    }

    // ---------------------------------------------------------------- content voting

    private fun extensionsFromRecords(records: List<List<Token>>): List<ApkRepositoryExtension> {
        if (records.isEmpty()) return emptyList()
        val total = records.size
        val strings = mutableMapOf<Int, MutableList<String>>()
        val varints = mutableMapOf<Int, MutableList<Long>>()

        for (record in records) {
            for (token in record) {
                val payload = token.payload
                when (token.wire) {
                    WIRE_VARINT -> varints.getOrPut(token.field) { mutableListOf() } += token.varint ?: 0L
                    WIRE_LEN -> {
                        if (payload == null) continue
                        if (isSingleFlagBlob(payload)) {
                            varints.getOrPut(token.field) { mutableListOf() } += 0L
                        } else {
                            strings.getOrPut(token.field) { mutableListOf() } += String(payload, Charsets.UTF_8)
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (strings.isEmpty()) return emptyList()

        fun presence(field: Int): Double = (strings[field]?.size ?: 0).toDouble() / total
        fun values(field: Int): List<String> = strings[field].orEmpty()
        fun frac(field: Int, predicate: (String) -> Boolean): Double {
            val list = values(field)
            if (list.isEmpty()) return 0.0
            return list.count(predicate).toDouble() / list.size
        }
        fun avgLen(field: Int): Double =
            values(field).filter { it.isNotBlank() }.map { it.length }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        fun printable(field: Int): Double =
            frac(field) { v -> v.isNotBlank() && v.all { c -> c.code >= 32 && c != '\uFFFD' } }

        val eligible = strings.keys.filter { presence(it) >= MIN_PRESENCE }

        val apkField = eligible
            .maxByOrNull { frac(it) { v -> v.endsWith(".apk", ignoreCase = true) } }
            ?.takeIf { frac(it) { v -> v.endsWith(".apk", ignoreCase = true) } >= MIN_SCORE }
            ?: return emptyList()

        val pkgField = (eligible - apkField)
            .maxByOrNull { frac(it) { v -> PACKAGE_VALUE.matches(v) } }
            ?.takeIf { frac(it) { v -> PACKAGE_VALUE.matches(v) } >= MIN_SCORE }
            ?: return emptyList()

        val langField = (eligible - setOf(apkField, pkgField))
            .map { it to frac(it) { v -> LANGUAGE_VALUE.matches(v) || v.lowercase() in KNOWN_LANGUAGES } }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { pair -> pair.second * 1000.0 - avgLen(pair.first) }
            ?.first

        val versionField = (eligible - setOf(apkField, pkgField) - setOfNotNull(langField))
            .map { it to frac(it) { v -> VERSION_VALUE.matches(v) } }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { pair ->
                pair.second * 1000.0 + (if (frac(pair.first) { v -> v.contains('.') } >= 0.5) 50.0 else 0.0) -
                    avgLen(pair.first)
            }
            ?.first

        val nameField = (eligible - setOf(apkField, pkgField) - setOfNotNull(langField, versionField))
            .filter { field ->
                frac(field) { v -> v.isBlank() || !looksLikeAuxiliary(v) } >= 0.5 && printable(field) >= 0.6
            }
            .maxByOrNull { avgLen(it) }

        val flagField = varints.keys.sorted().firstOrNull { field ->
            varints[field].orEmpty().isNotEmpty() && varints[field]!!.all { it == 0L || it == 1L }
        }

        fun stringOf(field: Int?, record: List<Token>): String =
            field?.let { wanted ->
                record.firstOrNull { it.field == wanted && it.wire == WIRE_LEN }
                    ?.payload?.let { String(it, Charsets.UTF_8) }
            } ?: ""

        val extensions = mutableListOf<ApkRepositoryExtension>()
        for (record in records) {
            val apk = stringOf(apkField, record)
            val pkg = stringOf(pkgField, record)
            if (apk.isBlank() || pkg.isBlank()) continue
            val flagValue = flagField?.let { wanted ->
                record.firstOrNull { it.field == wanted && it.wire == WIRE_VARINT }?.varint
            } ?: 0L
            extensions += ApkRepositoryExtension(
                name = stringOf(nameField, record).ifBlank { pkg },
                pkg = pkg,
                apk = apk,
                lang = stringOf(langField, record),
                version = stringOf(versionField, record),
                nsfw = if (flagValue == 1L) 1 else 0,
            )
        }
        return extensions
    }

    /** Icon paths, absolute URLs and the like - never the display name. */
    private fun looksLikeAuxiliary(value: String): Boolean {
        val lower = value.lowercase()
        if (IMAGE_SUFFIXES.any { lower.endsWith(it) }) return true
        if (lower.contains("://")) return true
        return false
    }

    /**
     * A lone 0/1 byte (or a short run of them) is a packed boolean flag such as `nsfw`; real
     * text like "en" parses as varints too, so the value range is what keeps it a string.
     */
    private fun isSingleFlagBlob(payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > 4) return false
        var pos = 0
        var count = 0
        while (pos < payload.size) {
            var value = 0L
            var shift = 0
            while (true) {
                if (pos >= payload.size) return false
                val byte = payload[pos].toInt() and 0xFF
                pos++
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 63) return false
            }
            if (value != 0L && value != 1L) return false
            count++
        }
        return count > 0
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2) return bytes
        if ((bytes[0].toInt() and 0xFF) != 0x1F || (bytes[1].toInt() and 0xFF) != 0x8B) return bytes
        return runCatching { GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } }
            .getOrElse { throw IllegalArgumentException("gzipped index could not be read", it) }
    }
}
