package com.tabpdf.doc

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.doc.office.DocxReader
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.office.ZipParts
import com.tabpdf.export.PdfFile
import com.tabpdf.export.PdfSyntaxException
import com.tabpdf.tabs.TabsViewModel
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

    @Test
    fun exportNamesStayInTheExportFolder() {
        // The name comes from the app that gave the file.
        val export = File(dir, "export")
        for (name in listOf("../../files/tab-copies/x.pdf", "..", "", "a/b\\c.docx", ".hidden", "\u0000\n.pdf", "가".repeat(300) + ".txt")) {
            val file = TabsViewModel.exportFileName(name)
            assertTrue(file, file.endsWith(".pdf") && !file.contains('/') && !file.contains('\\') && !file.startsWith("."))
            assertTrue(file, file.length <= 104)
            assertEquals(file, export.canonicalPath, File(export, file).parentFile!!.canonicalPath)
        }
        assertEquals("보고서.pdf", TabsViewModel.exportFileName("보고서.docx"))
    }

    /**
     * A PDF of [objects] (number to body) with a cross-reference stream listing the [inStream]
     * objects (number to index) as inside object stream 4, the rest where they are written.
     */
    private fun pdfWithXrefStream(objects: Map<Int, String>, inStream: Map<Int, Int>): File {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.5\n")
        val offsets = HashMap<Int, Int>()
        for ((num, body) in objects) {
            offsets[num] = out.size()
            write("$num 0 obj\n$body\nendobj\n")
        }
        val size = (objects.keys + inStream.keys).max() + 2
        val xref = size - 1
        offsets[xref] = out.size()
        val rows = ByteArrayOutputStream()
        for (n in 0 until size) {
            val inside = inStream[n]
            val offset = offsets[n]
            when {
                inside != null -> { rows.write(2); rows.write(byteArrayOf(0, 0, 0, 4)); rows.write(byteArrayOf(0, inside.toByte())) }
                offset != null -> { rows.write(1); for (sh in intArrayOf(24, 16, 8, 0)) rows.write(offset shr sh and 0xFF); rows.write(byteArrayOf(0, 0)) }
                else -> { rows.write(0); rows.write(ByteArray(6)) }
            }
        }
        val data = rows.toByteArray()
        write("$xref 0 obj\n<</Type/XRef/Size $size/W[1 4 2]/Root 1 0 R/Length ${data.size}>>\nstream\n")
        out.write(data)
        write("\nendstream\nendobj\nstartxref\n${offsets[xref]}\n%%EOF\n")
        return File(dir, "crafted.pdf").apply { writeBytes(out.toByteArray()) }
    }

    @Test
    fun pdfStructuresThatPointAtThemselvesAreRefused() {
        // The catalog in object stream 4, which itself claims to be in object stream 4.
        val selfContained = pdfWithXrefStream(mapOf(2 to "<</Type/Pages/Kids[]/Count 0>>"), mapOf(1 to 0, 4 to 0))
        // Object stream 4's length is an object inside object stream 4.
        val stream = "<</Type/ObjStm/N 1/First 4/Length 5 0 R>>\nstream\n1 0 <</Type/Catalog>>\nendstream"
        val ownLength = pdfWithXrefStream(mapOf(4 to stream), mapOf(1 to 0, 5 to 1))
        for (file in listOf(selfContained, ownLength)) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                try {
                    PdfFile(raf).pages()
                    fail("a crafted structure is refused")
                } catch (_: PdfSyntaxException) {
                    // Refused, not a crash (a StackOverflowError would end the test run).
                }
            }
        }
    }
}
