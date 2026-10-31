package com.tabpdf.doc

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.doc.html.HtmlDocument
import com.tabpdf.doc.office.DocxReader
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.office.ZipParts
import com.tabpdf.export.PdfFile
import com.tabpdf.export.PdfSyntaxException
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.TabsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
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

    /** An xlsx of one sheet: [rows] in its sheetData, [tail] after that; style 1 shows [format] (or 0.00). */
    private fun xlsx(rows: String, format: String? = null, tail: String = "", sheetRels: String? = null): ByteArray {
        val main = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val numFmts = format?.let { "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"$it\"/></numFmts>" }.orEmpty()
        val parts = mapOf(
            "xl/workbook.xml" to "<workbook $main><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$rel/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/styles.xml" to "<styleSheet $main>$numFmts<cellXfs count=\"2\"><xf numFmtId=\"0\"/>" +
                "<xf numFmtId=\"${if (format != null) 164 else 2}\"/></cellXfs></styleSheet>",
            "xl/worksheets/sheet1.xml" to "<worksheet $main><sheetData>$rows</sheetData>$tail</worksheet>",
        )
        return docx(parts + (sheetRels?.let { mapOf("xl/worksheets/_rels/sheet1.xml.rels" to it) } ?: emptyMap()))
    }

    /** An EPUB with one chapter, which [spine] (itemref elements) lists. */
    private fun epub(spine: String): ByteArray = docx(
        mapOf(
            "META-INF/container.xml" to "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles>" +
                "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
            "OEBPS/content.opf" to "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><manifest>" +
                "<item id=\"c\" href=\"ch.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine>$spine</spine></package>",
            "OEBPS/ch.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" + "<p>${"가나다 ".repeat(200)}</p>".repeat(5) + "</body></html>",
        )
    )

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
    fun cellsThatCannotBeRoundedStillDraw() = runBlocking {
        // NaN is read as a number, and enough percent signs take zero to NaN. Rounding NaN throws, and
        // drawing the page used to throw with it: the app ended on every start, as the tab was reopened.
        val cases = listOf("NaN" to null, "0" to "0" + "%".repeat(155), "1" to "0." + "0".repeat(400))
        for ((value, format) in cases) {
            val doc = openFile("cell.xlsx", xlsx("<row r=\"1\"><c r=\"A1\" s=\"1\"><v>$value</v></c></row>", format), FileFormat.Xlsx)
            try {
                assertNotNull(doc.renderPage(0, 400))
            } finally {
                doc.close()
            }
        }
    }

    @Test
    fun repeatedLinksAndMergesOpenQuickly() = runBlocking {
        // Two cells make the used range 1000 × 1000. Every link and merge over it used to be spread over
        // its cells, one after another: hours of work for a file of a few dozen KB.
        val rows = "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row><row r=\"1000\"><c r=\"ALL1000\"><v>1</v></c></row>"
        val tail = "<mergeCells>" + "<mergeCell ref=\"A2:CV1000\"/>".repeat(100_000) + "</mergeCells>" +
            "<hyperlinks>" + "<hyperlink ref=\"A1:ALL1000\" r:id=\"rId1\"/>".repeat(100_000) + "</hyperlinks>"
        val rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"h\" Target=\"https://example.com/\" TargetMode=\"External\"/></Relationships>"
        val started = SystemClock.elapsedRealtime()
        openFile("links.xlsx", xlsx(rows, tail = tail, sheetRels = rels), FileFormat.Xlsx).close()
        assertTrue(SystemClock.elapsedRealtime() - started < 10_000)
    }

    @Test
    fun aChapterRepeatedInTheSpineIsLaidOutOnce() = runBlocking {
        // Laying the same chapter out again and again in the background used to run the app out of memory.
        val once = openFile("once.epub", epub("<itemref idref=\"c\"/>"), FileFormat.Epub)
        val pages = once.pageCount
        once.close()
        val repeated = openFile("repeated.epub", epub("<itemref idref=\"c\"/>".repeat(100_000)), FileFormat.Epub)
        try {
            repeated.awaitComplete()
            assertEquals(pages, repeated.pageCount)
        } finally {
            repeated.close()
        }
    }

    @Test
    fun onlyWebAndMailLinksAreKept() = runBlocking {
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="web" Type="h" Target="https://example.com/" TargetMode="External"/>""" +
            """<Relationship Id="file" Type="h" Target="file:///data/data/io.github.itic1721.everyviewer/databases/tabs.db" TargetMode="External"/>""" +
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

    /** A black greyscale PNG of [width] × [height], written row by row: no bitmap that size is made. */
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(body.size)
            out.write(name)
            out.write(body)
            data.writeInt(CRC32().apply { update(name); update(body) }.value.toInt())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(0).put(0).put(0).put(0).array())
        val pixels = ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use { z ->
            val row = ByteArray(width + 1) // filter byte, then the row
            repeat(height) { z.write(row) }
        }
        chunk("IDAT", pixels.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun pagesTooTallToLayOutAreRefused() = runBlocking {
        // Taller than Compose can lay out: the app ended on opening it, and on every start after.
        try {
            openFile("tall.png", png(10, 3000), FileFormat.Image).close()
            fail("opened")
        } catch (e: DocumentOpenException) {
            assertEquals(DocumentOpenException.Reason.PageTooTall, e.reason)
        }
        // Paper of no width made every length endless; margins taller than the paper, pages that never end.
        val text = "<w:p><w:r><w:t>x</w:t></w:r></w:p>"
        for (section in listOf("<w:pgSz w:w=\"0\" w:h=\"16838\"/>", "<w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"9000\" w:bottom=\"9000\"/>")) {
            try {
                openFile("paper.docx", docx(mapOf("word/document.xml" to body("$text<w:sectPr>$section</w:sectPr>"))), FileFormat.Docx).close()
                fail("opened")
            } catch (e: IOException) {
                // Refused.
            }
        }
        // Long, but not too long, still opens.
        openFile("long.png", png(100, 5000), FileFormat.Image).close()
    }

    @Test
    fun imagesAreNeverDecodedPastTheLimit() = runBlocking {
        // Asked for 2500 wide, a 4999-wide image was decoded whole: 20 million pixels. Android won't draw a
        // bitmap over 100 MB at all, and the app ended (a 108-megapixel photo at 3× zoom did that).
        val doc = openFile("big.png", png(4999, 4000), FileFormat.Image)
        try {
            val bitmap = doc.renderPage(0, 2500)!!
            assertTrue(bitmap.width.toLong() * bitmap.height <= ImageDocument.MAX_DECODED_PIXELS)
        } finally {
            doc.close()
        }
    }

    @Test
    fun thumbnailsOfTallPagesStaySmallAndGoWithTheirTab() = runBlocking {
        val file = File(dir, "tall.pdf")
        android.graphics.pdf.PdfDocument().apply {
            finishPage(startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(10, 900, 1).create()))
            file.outputStream().use { writeTo(it) }
            close()
        }
        val tab = DocTab("security-test-tab", null, Uri.fromFile(file), "tall.pdf", isInternalCopy = false, FileFormat.Pdf)
        // 360 wide, it was 360 × 32 400 pixels: 47 MB for a picture on a card.
        val thumbnail = Thumbnails.load(context, tab, 0, emptyList())!!
        assertTrue(thumbnail.width.toLong() * thumbnail.height <= 1_000_000)
        // Closed, its pictures leave the disk too.
        Thumbnails.forget(context, listOf(tab.id))
        assertTrue(File(context.cacheDir, "thumbs").listFiles().orEmpty().none { it.name.startsWith("${tab.id}-") })
    }

    @Test
    fun longDescendantSelectorsMatchQuickly() = runBlocking {
        // Each descendant combinator tried every chain of ancestors: C(59, 12) ≈ 2×10¹² steps here.
        val html = "<style>x" + " div".repeat(12) + " p { color: red }</style>" + "<div>".repeat(60) + "<p>글</p>" + "</div>".repeat(60)
        val started = SystemClock.elapsedRealtime()
        HtmlDocument.render(html).close()
        assertTrue(SystemClock.elapsedRealtime() - started < 5_000)
    }

    @Test
    fun crossReferenceRowsOfNoBytesAreRefused() {
        // /W [0 0 0]: rows of no bytes, so nothing bounded the loop over /Index (≈ 10¹¹ rounds here).
        val index = "0 10000000 ".repeat(10_000)
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.5\n")
        val at = out.size()
        write("1 0 obj\n<</Type/XRef/Size 2/W[0 0 0]/Index[$index]/Length 0>>\nstream\n\nendstream\nendobj\nstartxref\n$at\n%%EOF\n")
        val file = File(dir, "rows.pdf").apply { writeBytes(out.toByteArray()) }
        val started = SystemClock.elapsedRealtime()
        java.io.RandomAccessFile(file, "r").use { raf ->
            try {
                PdfFile(raf)
                fail("a crafted structure is refused")
            } catch (_: PdfSyntaxException) {
                // Refused.
            }
        }
        assertTrue(SystemClock.elapsedRealtime() - started < 5_000)
    }

    /** A 1×1 WBMP: a format Android decodes, but not one the app shows. */
    private val wbmp = byteArrayOf(0, 0, 1, 1, 0)

    @Test
    fun picturesInFormatsTheAppDoesNotShowAreNotDecoded() = runBlocking {
        // Android picks the decoder by content, whatever the name or "data:image/png" says, and has
        // more of them than the formats the app shows (camera raw like DNG: the way in of the 2025
        // attacks with a single picture). Only those formats reach it.
        assertNotNull(BitmapFactory.decodeByteArray(wbmp, 0, wbmp.size))
        assertFalse(FileFormats.isImage(wbmp))
        assertFalse(FileFormats.isImage(byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0))) // TIFF, DNG
        assertFalse(FileFormats.isImage(byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8)))
        assertTrue(FileFormats.isImage(png(1, 1)))
        try {
            openFile("photo.jpg", wbmp, FileFormat.Image).close()
            fail("opened")
        } catch (e: DocumentOpenException) {
            assertEquals(DocumentOpenException.Reason.Unsupported, e.reason)
        }
        // In a page, its description shows instead, as for a picture that isn't in the file.
        fun page(bytes: ByteArray) = HtmlDocument.render(
            "<p><img src=\"data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}\" alt=\"그림설명\"></p>"
        )
        page(wbmp).let { doc -> assertTrue(doc.search("그림설명").isNotEmpty()); doc.close() }
        page(png(1, 1)).let { doc -> assertTrue(doc.search("그림설명").isEmpty()); doc.close() }
    }

    @Test
    fun anImageIsDecodedFromTheBytesThatWereChecked() = runBlocking {
        // Its provider gives a 1×1 picture first and a 4000×4000 one after that. Read again to be
        // decoded, the file would have got past the checks made on the first (format, size limit).
        val uri = Uri.parse("content://${ChangingImageProvider.AUTHORITY}/${UUID.randomUUID()}.png")
        val copies = File(context.cacheDir, ImageDocument.TEMP_DIR)
        val before = copies.listFiles().orEmpty().size
        val doc = Documents.open(context, uri, "changing.png", FileFormat.Image)
        try {
            assertEquals(1f, doc.pageSizes[0].width)
            assertEquals(1, doc.renderPage(0, 100)!!.width)
        } finally {
            doc.close()
        }
        // The copy goes with the document.
        assertEquals(before, copies.listFiles().orEmpty().size)
    }

    @Test
    fun entitiesTheFileDeclaresAreNotExpanded() = runBlocking {
        // "Billion laughs": ten levels of ten references each, a few hundred bytes making 10¹⁰ characters.
        val entities = (1..10).joinToString("") { i -> "<!ENTITY e$i \"" + "&e${i - 1};".repeat(10) + "\">" }
        val xml = "<!DOCTYPE w:document [<!ENTITY e0 \"하하하하하하하하하하\">$entities]>" +
            body("<w:p><w:r><w:t>&e10;</w:t></w:r></w:p>")
        val started = SystemClock.elapsedRealtime()
        try {
            openFile("laughs.docx", docx(mapOf("word/document.xml" to xml)), FileFormat.Docx).close()
            fail("opened")
        } catch (e: Exception) {
            // Refused for the reference, not after running out of memory expanding it.
            assertFalse((e as? DocumentOpenException)?.reason == DocumentOpenException.Reason.TooLarge)
        }
        assertTrue(SystemClock.elapsedRealtime() - started < 5_000)
    }

    @Test
    fun sizesThatAreNoNumbersOrHugeOpenQuickly() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        val cases = listOf(
            "<w:p><w:r><w:rPr><w:sz w:val=\"Infinity\"/></w:rPr><w:t>x</w:t></w:r></w:p>" +
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"NaN\" w:bottom=\"NaN\" w:left=\"NaN\" w:right=\"Infinity\"/></w:sectPr>",
            "<w:tbl><w:tr><w:trPr><w:gridBefore w:val=\"2000000000\"/></w:trPr>" +
                "<w:tc><w:tcPr><w:gridSpan w:val=\"2000000000\"/></w:tcPr><w:p/></w:tc></w:tr></w:tbl><w:p/>",
        )
        for (xml in cases) {
            try {
                openFile("sizes.docx", docx(mapOf("word/document.xml" to body(xml))), FileFormat.Docx).close()
            } catch (_: Exception) {
                // Refusing is fine; ending the app or never finishing is not.
            }
        }
        val sheet = xlsx("<row r=\"1\" ht=\"NaN\" customHeight=\"1\"><c r=\"A1\"><v>1</v></c></row>")
        openFile("sizes.xlsx", sheet, FileFormat.Xlsx).close()
        assertTrue(SystemClock.elapsedRealtime() - started < 10_000)
    }

    @Test
    fun longNumbersInStylesAreReadQuickly() = runBlocking {
        // A run of digits made the length pattern try every way to split it: for ever, at this length.
        val started = SystemClock.elapsedRealtime()
        HtmlDocument.render("<p style=\"width: ${"1".repeat(200_000)}x!; margin-left: 1e99px\">글</p>").close()
        assertTrue(SystemClock.elapsedRealtime() - started < 5_000)
    }

    @Test
    fun markdownPicturesStayWithinTheirMemory() = runBlocking {
        // A few KB each, 16 MB each once decoded: 500 of them would make Android end the app.
        val picture = "![그림](data:image/png;base64,${Base64.encodeToString(png(2048, 2048), Base64.NO_WRAP)})\n\n"
        val doc = openFile("pictures.md", picture.repeat(500).toByteArray(), FileFormat.Markdown)
        try {
            // The first are drawn, the rest named as inline pictures are.
            assertTrue(doc.search("이미지: 그림").size in 400..500)
        } finally {
            doc.close()
        }
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
