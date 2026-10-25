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

    private fun epub(
        chapters: List<String>,
        extra: Map<String, ByteArray> = emptyMap(),
        navHref: String? = null,
        ncxHref: String? = null,
    ): ByteArray {
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
            val items = chapters.indices.joinToString("") { """<item id="c$it" href="text/ch$it.xhtml" media-type="application/xhtml+xml"/>""" } +
                (navHref?.let { """<item id="nav" href="$it" properties="nav" media-type="application/xhtml+xml"/>""" } ?: "") +
                (ncxHref?.let { """<item id="ncx" href="$it" media-type="application/x-dtbncx+xml"/>""" } ?: "")
            // The spine lists chapters in reverse of the manifest: the spine is the reading order.
            val spine = chapters.indices.reversed().joinToString("") { """<itemref idref="c$it"/>""" }
            val toc = if (ncxHref != null) """ toc="ncx"""" else ""
            put("OPS/book.opf", """<package xmlns="http://www.idpf.org/2007/opf"><manifest>$items</manifest><spine$toc>$spine</spine></package>""".toByteArray())
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
    fun theBooksTableOfContentsFromItsNavOrNcx() = runBlocking {
        val long = (1..150).joinToString("") { "<p>문단 $it 입니다. 문장을 길게 써서 여러 줄을 만듭니다.</p>" }
        // ch0 is the body, ch1 the preface; the spine reads ch1 first.
        val chapters = listOf("<h1>본문</h1>$long<h2 id='late'>뒤쪽 절</h2><p>끝</p>", "<h1>머리말</h1><p>짧음</p>")
        val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="landmarks"><ol><li><a href="ch1.xhtml">X</a></li></ol></nav>
            <nav epub:type="toc"><ol><li><a href="ch1.xhtml">머리말</a></li>
            <li><a href="ch0.xhtml">본문</a><ol><li><a href="ch0.xhtml#late">뒤쪽 절</a></li></ol></li></ol></nav></body></html>"""
        // The nav sits next to the chapters.
        val withNav = epub(chapters, mapOf("OPS/text/nav.xhtml" to nav.toByteArray()), navHref = "text/nav.xhtml")
        val doc = open("nav.epub", withNav)
        val toc = doc.tableOfContents()
        assertEquals(listOf("머리말", "본문"), toc.map { it.title })
        val late = toc[1].children.single()
        assertEquals("뒤쪽 절", late.title)
        assertEquals("the section is where its heading is", doc.search("뒤쪽 절").single().page, late.page)
        assertTrue(late.page > toc[1].page)
        doc.close()

        val ncx = """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint id="a"><navLabel><text>첫째</text></navLabel><content src="text/ch1.xhtml"/>
              <navPoint id="b"><navLabel><text>둘째</text></navLabel><content src="text/ch0.xhtml#late"/></navPoint></navPoint>
            </navMap></ncx>"""
        val withNcx = open("ncx.epub", epub(chapters, mapOf("OPS/toc.ncx" to ncx.toByteArray()), ncxHref = "toc.ncx"))
        val ncxToc = withNcx.tableOfContents()
        assertEquals("첫째", ncxToc.single().title)
        assertEquals("둘째", ncxToc.single().children.single().title)
        withNcx.close()

        // Without either: the chapters' headings.
        val bare = open("bare.epub", epub(chapters))
        assertEquals(listOf("머리말", "본문"), bare.tableOfContents().map { it.title })
        bare.close()
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
