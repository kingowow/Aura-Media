package org.akkirrai.hibiki.core.source.extension

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * `index.pb` support: the decoder must find extension records and identify fields without a
 * `.proto` schema, so every fixture below uses a *different*, invented schema.
 */
class ExtensionIndexProtobufTest {

    // ---- minimal protobuf wire encoder (test-only) -------------------------

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                out.write(byte)
                return out.toByteArray()
            }
            out.write(byte or 0x80)
        }
    }

    private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

    private fun lenDelimited(field: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 2))
        out.write(varint(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun str(field: Int, value: String): ByteArray = lenDelimited(field, value.toByteArray())

    private fun varintField(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 0))
        out.write(varint(value))
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    // ---- fixtures ----------------------------------------------------------

    private data class Fixture(
        val name: String,
        val pkg: String,
        val apk: String,
        val lang: String,
        val version: String,
        val nsfw: Int,
    )

    private val fixtures = listOf(
        Fixture(
            name = "Aniyomi: AnimeOnsen",
            pkg = "eu.kanade.tachiyomi.animeextension.all.animeonsen",
            apk = "aniyomi-all.animeonsen-v14.10.apk",
            lang = "en",
            version = "14.10",
            nsfw = 0,
        ),
        Fixture(
            name = "Gogoanime",
            pkg = "eu.kanade.tachiyomi.animeextension.en.gogoanime",
            apk = "aniyomi-en.gogoanime-v14.9.apk",
            lang = "multi",
            version = "14.9",
            nsfw = 1,
        ),
    )

    /** Classic layout: `message Index { repeated Ext ext = 1; }`, fields in JSON-like order. */
    private fun classicWrappedIndex(): ByteArray {
        val records = fixtures.map { f ->
            concat(
                str(1, f.name),
                str(2, f.pkg),
                str(3, f.apk),
                str(4, f.lang),
                str(5, f.version),
                varintField(6, f.nsfw.toLong()),
            )
        }
        return concat(*records.map { lenDelimited(1, it) }.toTypedArray())
    }

    /** Invented layout: bare concatenated messages, reordered fields, different numbers. */
    private fun concatenatedOddSchemaIndex(): ByteArray {
        val records = fixtures.map { f ->
            concat(
                str(7, f.apk),   // apk
                str(3, f.pkg),   // pkg
                str(9, f.lang),  // lang
                str(1, f.version), // version
                str(5, f.name),  // name
                varintField(2, f.nsfw.toLong()), // nsfw
            )
        }
        return concat(*records.toTypedArray())
    }

    private fun assertFixtureShape(index: List<ApkRepositoryExtension>) {
        assertEquals(2, index.size)
        index.forEach { extension ->
            val expected = fixtures.first { it.pkg == extension.pkg }
            assertEquals(expected.name, extension.name)
            assertEquals(expected.apk, extension.apk)
            assertEquals(expected.lang, extension.lang)
            assertEquals(expected.version, extension.version)
            assertEquals(expected.nsfw, extension.nsfw)
        }
    }

    // ---- decoder -----------------------------------------------------------

    @Test
    fun `decodes a wrapped repeated-field index`() {
        assertFixtureShape(ExtensionIndexProtobuf.decode(classicWrappedIndex()))
    }

    @Test
    fun `decodes bare concatenated messages with an unrelated schema`() {
        assertFixtureShape(ExtensionIndexProtobuf.decode(concatenatedOddSchemaIndex()))
    }

    @Test
    fun `rejects bytes that are not protobuf`() {
        try {
            ExtensionIndexProtobuf.decode("<html>nope</html>".toByteArray())
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `empty body decodes to an empty index`() {
        assertTrue(ExtensionIndexProtobuf.decode(ByteArray(0)).isEmpty())
    }

    // ---- end to end through the marketplace client -------------------------

    @Test
    fun `fetchCatalog decodes an index.pb response and resolves apk and icon urls`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = classicWrappedIndex(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/octet-stream"),
            )
        }
        val client = ExtensionMarketplaceClient(HttpClient(engine), "https://example.com/repo/index.pb")
        val extensions = client.fetchCatalog()

        assertEquals(2, extensions.size)
        val extension = extensions.first { it.pkg == fixtures[0].pkg }
        assertEquals("https://example.com/repo/apk/${fixtures[0].apk}", extension.downloadUrl)
        assertEquals("https://example.com/repo/icon/${fixtures[0].pkg}.png", extension.iconUrl)
        client.close()
    }

    @Test
    fun `a protobuf index that cannot be interpreted reports the standard invalid error`() = runBlocking {
        // A structurally valid protobuf message whose fields do not look like an extension index.
        val bytes = concat(str(1, "hello"), str(2, "world"), varintField(3, 42))
        val engine = MockEngine {
            respond(content = bytes, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "text/plain"))
        }
        val client = ExtensionMarketplaceClient(HttpClient(engine), "https://example.com/repo/index.pb")
        try {
            client.fetchCatalog()
            fail("Expected ExtensionMarketplaceException")
        } catch (expected: ExtensionMarketplaceException) {
            assertTrue(expected.message!!.contains("invalid"))
        }
        client.close()
    }
}
