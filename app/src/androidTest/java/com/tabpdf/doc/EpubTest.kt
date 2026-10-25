package com.tabpdf.doc

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.doc.html.EpubDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** E-books (기획서 5.7): small books written here, chapters in folders as real books keep them. */
@RunWith(AndroidJUnit4::class)
class EpubTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun epub(chapters: List<String>, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            // "mimetype" first and stored, as the format asks (format detection reads it).
            val type = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = type.size.toLong()
                crc = CRC32().apply { update(type) }.value
            })
            z.write(type)
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
            }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""".toByteArray())
            val items = chapters.indices.joinToString("") { """<item id="c$it" href="text/ch$it.xhtml" media-type="application/xhtml+xml"/>""" }
            // The spine lists chapters in reverse of the manifest: the spine is the reading order.
            val spine = chapters.indices.reversed().joinToString("") { """<itemref idref="c$it"/>""" }
            put("OPS/book.opf", """<package xmlns="http://www.idpf.org/2007/opf"><manifest>$items</manifest><spine>$spine</spine></package>""".toByteArray())
            chapters.forEachIndexed { i, body ->
                put("OPS/text/ch$i.xhtml", """<html><head><link rel="stylesheet" href="../style/s.css"/></head><body>$body</body></html>""".toByteArray())
            }
            put("OPS/style/s.css", ".hide { display: none }".toByteArray())
            for ((name, bytes) in extra) put(name, bytes)
        }
        return out.toByteArray()
    }

    private fun open(name: String, bytes: ByteArray): Document = runBlocking {
        val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
        val format = FileFormats.detect("download", "application/octet-stream", bytes.copyOf(minOf(bytes.size, 512)))
        assertEquals(FileFormat.Epub, format)
        Documents.open(context, Uri.fromFile(file), name, format)
    }

    @Test
    fun chaptersInReadingOrderWithTheBooksStyles() = runBlocking {
        val long = (1..200).joinToString("") { "<p>둘째 장 문단 $it 입니다. 문장을 길게 써서 여러 줄을 만듭니다.</p>" }
        val doc = open("book.epub", epub(listOf("<p>둘째 장</p>$long", "<p>첫째 장</p><p class='hide'>숨김</p>")))
        doc.awaitComplete()
        // ch1 comes first in the spine.
        val first = doc.search("첫째 장").single().page
        val second = doc.search("둘째 장").first().page
        assertTrue(first < second)
        assertTrue("several pages", doc.pageCount > 2)
        assertTrue("the book's style sheet is used", doc.search("숨김").isEmpty())
        assertNotNull(doc.renderPage(doc.pageCount - 1, 300))
        doc.close()
    }

    @Test
    fun pathsAreResolvedLikeTheBookWritesThem() {
        assertEquals("OPS/images/a b.png", EpubDocument.resolve("OPS/text/ch1.xhtml", "../images/a%20b.png#x"))
        assertEquals("OPS/text/n.xhtml", EpubDocument.resolve("OPS/text/ch1.xhtml", "./n.xhtml"))
        assertEquals("root.css", EpubDocument.resolve("OPS/text/ch1.xhtml", "/root.css"))
    }

    @Test
    fun protectedBooksAreSaidToBe() {
        val drm = """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>"""
        val error = runCatching { open("drm.epub", epub(listOf("<p>x</p>"), mapOf("META-INF/encryption.xml" to drm.toByteArray()))) }.exceptionOrNull()
        assertEquals(DocumentOpenException.Reason.Protected, (error as? DocumentOpenException)?.reason)
        // Fonts hidden by the standard obfuscation are not DRM.
        val fonts = """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/></EncryptedData></encryption>"""
        val doc = open("fonts.epub", epub(listOf("<p>글꼴만 감춤</p>"), mapOf("META-INF/encryption.xml" to fonts.toByteArray())))
        assertTrue(runBlocking { doc.search("글꼴만") }.isNotEmpty())
        runBlocking { doc.close() }
    }
}
