package com.tabpdf.doc

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.doc.office.DocxReader
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.office.ZipParts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crafted files (기획서 10장): they must fail to open, never crash the app — a crash would repeat on
 * every start, as the tab is reopened — and their links must not reach other apps.
 */
@RunWith(AndroidJUnit4::class)
class SecurityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "security-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    /** A docx (zip) of the given parts; [zeros] adds parts of that many zero bytes each (they compress to almost nothing). */
    private fun docx(parts: Map<String, String>, zeros: List<Long> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
            val block = ByteArray(1024 * 1024)
            for ((i, size) in zeros.withIndex()) {
                z.putNextEntry(ZipEntry("word/media/pad$i.bin"))
                var left = size
                while (left > 0) {
                    val n = minOf(left, block.size.toLong()).toInt()
                    z.write(block, 0, n)
                    left -= n
                }
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun body(xml: String) = "<w:document $ns><w:body>$xml</w:body></w:document>"

    private suspend fun openFile(name: String, bytes: ByteArray, format: FileFormat): Document {
        val file = File(dir, name).apply { writeBytes(bytes) }
        return Documents.open(context, Uri.fromFile(file), name, format)
    }

    @Test
    fun zipBombIsRefusedWithoutRunningOutOfMemory() = runBlocking {
        // 190 MB unpacked from a few hundred KB, each part within the old 64 MB limit.
        val bomb = docx(mapOf("word/document.xml" to body("<w:p/>")), zeros = List(3) { 63L * 1024 * 1024 })
        assertTrue(bomb.size < 1024 * 1024)
        try {
            openFile("bomb.docx", bomb, FileFormat.Docx).close()
            fail("opened")
        } catch (e: DocumentOpenException) {
            assertEquals(DocumentOpenException.Reason.TooLarge, e.reason)
        }
    }

    @Test
    fun deeplyNestedXmlIsRefusedQuickly() = runBlocking {
        val depth = 200_000
        val deep = body("<w:p>" + "<w:hyperlink>".repeat(depth) + "<w:r><w:t>x</w:t></w:r>" + "</w:hyperlink>".repeat(depth) + "</w:p>")
        val started = SystemClock.elapsedRealtime()
        try {
            openFile("deep.docx", docx(mapOf("word/document.xml" to deep)), FileFormat.Docx).close()
            fail("opened")
        } catch (e: Exception) {
            // An exception, not a StackOverflowError, and long before it would have come.
        }
        assertTrue(SystemClock.elapsedRealtime() - started < 5_000)
        // Nesting real documents use still opens.
        val ok = body("<w:p>" + "<w:hyperlink>".repeat(50) + "<w:r><w:t>x</w:t></w:r>" + "</w:hyperlink>".repeat(50) + "</w:p>")
        openFile("ok.docx", docx(mapOf("word/document.xml" to ok)), FileFormat.Docx).close()
    }

    @Test
    fun onlyWebAndMailLinksAreKept() = runBlocking {
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="web" Type="h" Target="https://example.com/" TargetMode="External"/>""" +
            """<Relationship Id="file" Type="h" Target="file:///data/data/com.tabpdf/databases/tabs.db" TargetMode="External"/>""" +
            """<Relationship Id="intent" Type="h" Target="intent://x#Intent;scheme=x;end" TargetMode="External"/>""" +
            "</Relationships>"
        val paragraphs = listOf("web", "file", "intent").joinToString("") {
            """<w:p><w:hyperlink r:id="$it"><w:r><w:t>link$it</w:t></w:r></w:hyperlink></w:p>"""
        }
        val parts = ZipParts.read(ByteArrayInputStream(docx(mapOf("word/document.xml" to body(paragraphs), "word/_rels/document.xml.rels" to rels))))
        val doc = OfficeLayout.layOut(DocxReader.read(parts))
        fun linkOn(word: String): String? {
            val match = runBlocking { doc.search(word) }.single()
            val r = match.rects.first()
            return doc.linkAt(match.page, r.centerX(), r.centerY())
        }
        assertEquals("https://example.com/", linkOn("linkweb"))
        assertNull(linkOn("linkfile"))
        assertNull(linkOn("linkintent"))
        doc.close()
        assertTrue(isWebLink("HTTPS://a.b") && isWebLink("mailto:a@b"))
        assertFalse(isWebLink("file:///x") || isWebLink("content://x/y") || isWebLink("intent://x") || isWebLink("javascript:x"))
    }
}
