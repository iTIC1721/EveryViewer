package com.tabpdf.doc

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.doc.html.HtmlDocument
import com.tabpdf.doc.office.DocxReader
import com.tabpdf.doc.office.HwpxReader
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.office.ZipParts
import com.tabpdf.pdf.PdfDocument
import com.tabpdf.pdf.PdfPasswords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tables of contents (기획서 5.5). outline.pdf (made with PyMuPDF, 6 A4 pages): "첫 장" → page 1 top,
 * with "1.1 절" → page 2 halfway down and "1.2 절" → page 3; "Second chapter" → page 4 through the
 * named destination "chap2". outline-locked.pdf is the same with the password abcd.
 */
@RunWith(AndroidJUnit4::class)
class TocTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun asset(name: String) = File(context.cacheDir, name).apply {
        instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }

    /** Titles with their pages (1-based), nested entries indented by two spaces. */
    private fun outline(entries: List<TocEntry>, depth: Int = 0): List<String> =
        entries.flatMap { listOf("  ".repeat(depth) + "${it.title} ${it.page + 1}") + outline(it.children, depth + 1) }

    private val pdfOutline = listOf("첫 장 1", "  1.1 절 2", "  1.2 절 3", "Second chapter 4")

    @Test
    fun pdfBookmarksWithTheirPlaces() = runBlocking {
        val doc = PdfDocument.open(context, Uri.fromFile(asset("outline.pdf")))
        val toc = doc.tableOfContents()
        assertEquals(pdfOutline, outline(toc))
        assertEquals(0f, toc[0].y!!, 0.01f)
        assertEquals(0.5f, toc[0].children[0].y!!, 0.01f)
        doc.close()
        // Files without bookmarks have none.
        val plain = PdfDocument.open(context, Uri.fromFile(asset("sample.pdf")))
        assertTrue(plain.tableOfContents().isEmpty())
        plain.close()
    }

    @Test
    fun lockedPdfBookmarksWithThePassword() = runBlocking {
        if (!PdfDocument.takesPasswords) return@runBlocking
        val uri = Uri.fromFile(asset("outline-locked.pdf"))
        PdfPasswords[uri] = "abcd"
        val doc = PdfDocument.open(context, uri)
        assertEquals(pdfOutline, outline(doc.tableOfContents()))
        doc.close()
        PdfPasswords.remove(uri)
    }

    @Test
    fun markdownAndHtmlHeadings() = runBlocking {
        val md = File(context.cacheDir, "toc.md").apply {
            writeText("# 하나\n\n본문\n\n## 하나의 둘\n\n" + (1..200).joinToString("\n\n") { "문단 $it" } + "\n\n# 셋\n")
        }
        val markdown = Documents.open(context, Uri.fromFile(md), "toc.md", FileFormat.Markdown)
        val mdToc = markdown.tableOfContents()
        assertEquals(listOf("하나 1", "  하나의 둘 1"), outline(mdToc).take(2))
        assertEquals("셋", mdToc[1].title)
        assertTrue("the last heading is further on", mdToc[1].page > 0)
        markdown.close()

        val html = HtmlDocument.render(
            """<h1 id="top">제목</h1><p>글</p><div style="border:1px solid #000"><h2>상자 안 제목</h2></div>
               <h2>둘째</h2><h3>셋째 <span id="inner">안쪽</span></h3><p id="last">끝</p>"""
        )
        assertEquals(listOf("제목 1", "  상자 안 제목 1", "  둘째 1", "    셋째 안쪽 1"), outline(html.tableOfContents()))
        assertEquals(0, html.anchor("top")!!.first)
        assertTrue(html.anchor("last")!!.second > html.anchor("top")!!.second)
        assertNull(html.anchor("nowhere"))
        html.close()
    }

    private fun zip(vararg parts: Pair<String, String>): ZipParts {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return ZipParts.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun wordAndHangulOutlineParagraphs() = runBlocking {
        val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        fun p(style: String?, text: String, extra: String = "") =
            "<w:p><w:pPr>${style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: ""}$extra</w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"
        val docx = DocxReader.read(
            zip(
                "word/document.xml" to "<w:document $w><w:body>" +
                    p("H1", "장 하나") + p(null, "본문") + p("Sub", "절 하나") +
                    "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" +
                    p(null, "직접 지정", "<w:outlineLvl w:val=\"0\"/>") + p("H1", "") +
                    "</w:body></w:document>",
                // One style with the level, one known only by Word's built-in name (based on another).
                "word/styles.xml" to "<w:styles $w>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"H1\"><w:name w:val=\"My heading\"/><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr></w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Base2\"><w:name w:val=\"heading 2\"/></w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Sub\"><w:name w:val=\"Sub\"/><w:basedOn w:val=\"Base2\"/></w:style>" +
                    "</w:styles>",
            )
        )
        val sheet = OfficeLayout.layOut(docx)
        assertEquals(listOf("장 하나 1", "  절 하나 1", "직접 지정 2"), outline(sheet.tableOfContents()))
        // Below the top margin on the sheet.
        assertTrue(sheet.tableOfContents()[0].y!! > 0.05f)

        val hwpx = HwpxReader.read(
            zip(
                "Contents/header.xml" to """<hh:head xmlns:hh="h" xmlns:hc="c"><hh:refList><hh:paraProperties>
                    <hh:paraPr id="0"><hh:heading type="OUTLINE" idRef="0" level="0"/></hh:paraPr>
                    <hh:paraPr id="1"><hh:heading type="OUTLINE" idRef="0" level="1"/></hh:paraPr>
                    <hh:paraPr id="2"><hh:heading type="NONE" idRef="0" level="0"/></hh:paraPr>
                    </hh:paraProperties></hh:refList></hh:head>""",
                "Contents/section0.xml" to """<hs:sec xmlns:hs="s" xmlns:hp="p">
                    <hp:p paraPrIDRef="0"><hp:run><hp:t>개요 1</hp:t></hp:run></hp:p>
                    <hp:p paraPrIDRef="2"><hp:run><hp:t>본문</hp:t></hp:run></hp:p>
                    <hp:p paraPrIDRef="1"><hp:run><hp:t>개요 2</hp:t></hp:run></hp:p></hs:sec>""",
            )
        )
        assertEquals(listOf("개요 1 1", "  개요 2 1"), outline(OfficeLayout.layOut(hwpx).tableOfContents()))
    }

    @Test
    fun sheetsOfAWorkbook() = runBlocking {
        val file = asset("sheet.xlsx")
        val doc = Documents.open(context, Uri.fromFile(file), "sheet.xlsx", FileFormat.Xlsx)
        val toc = doc.tableOfContents()
        assertEquals(listOf("매출", "넓은 표", "메모"), toc.map { it.title })
        assertEquals(0, toc[0].page)
        assertTrue(toc[2].page > toc[1].page)
        doc.close()
    }
}
