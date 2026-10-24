package com.tabpdf.doc

import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.pdf.PdfDocument
import com.tabpdf.doc.office.DocxReader
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.office.ZipParts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Text search (기획서 5.5) in the documents this app lays out itself. */
@RunWith(AndroidJUnit4::class)
class SearchTest {
    private fun docx(body: String): ZipParts {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body" +
                    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>" +
                    "</w:body></w:document>").toByteArray()
            )
            z.closeEntry()
        }
        return ZipParts.read(ByteArrayInputStream(out.toByteArray()))
    }

    private fun p(text: String) = "<w:p><w:r><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

    @Test
    fun findsKoreanWordsCaseInsensitivelyOnTheRightPage() = runBlocking {
        val lines = (1..200).joinToString("") { p("줄 $it 내용") }
        val doc = OfficeLayout.layOut(
            DocxReader.read(docx(p("실험 계획서 Memory Map") + lines + p("<w:br w:type=\"page\"/>마지막 memory 메모리")))
        )
        assertTrue(doc.pageCount >= 3)

        // Korean words get invisible joiners between their letters for line breaking; search sees through them.
        val plan = doc.search("계획서")
        assertEquals(1, plan.size)
        assertEquals(0, plan[0].page)
        val r = plan[0].rects.single()
        assertTrue("on the page, near the top", r.left > 0f && r.right < 1f && r.top > 0f && r.bottom < 0.2f && r.width() > 0f)

        // Case is ignored; the last match is on the last page.
        val memory = doc.search("MEMORY")
        assertEquals(2, memory.size)
        assertEquals(0, memory[0].page)
        assertEquals(doc.pageCount - 1, memory[1].page)

        assertTrue(doc.search("없는 말").isEmpty())
        assertTrue(doc.search("").isEmpty())
        doc.close()
    }

    @Test
    fun aMatchOverALineBreakCoversBothLines() = runBlocking {
        val paint = flowPaint(34f)
        // Narrow enough that "beta" wraps under "alpha".
        val layout = staticLayout("alpha beta", paint, paint.measureText("alpha b"))
        assertEquals(2, layout.lineCount)
        val builder = FlowBuilder().apply { add(TextBlock(layout)) }
        val doc = builder.build(maxPageHeight = 1000f, pageGaps = false)
        val found = doc.search("Alpha Beta")
        assertEquals(1, found.size)
        val (first, second) = found.single().rects
        assertTrue(second.top >= first.bottom - 0.001f)
        doc.close()
    }

    @Test
    fun flowTextIsSearchedPerLine() = runBlocking {
        val builder = FlowBuilder()
        val paint = flowPaint(34f)
        builder.add(TextBlock(staticLayout("first line with a word\nsecond line with a WORD", paint, FLOW_WIDTH)))
        val doc = builder.build(maxPageHeight = 60f, pageGaps = false)
        val found = doc.search("word")
        assertEquals(2, found.size)
        assertTrue("one line per page here", found[1].page > found[0].page)
        doc.close()
    }

    /** Needs Android 15, or Android 12–14 with the PDF system update; finds nothing elsewhere. */
    @Test
    fun pdfTextIsFoundWhereThePlatformCanReadIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "search.pdf")
        instrumentation.context.assets.open("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val doc = PdfDocument.open(instrumentation.targetContext, Uri.fromFile(file))
        val found = doc.search("vocab")
        val canSearch = Build.VERSION.SDK_INT >= 35 ||
            Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        if (canSearch) {
            assertEquals("every page has the word", (0 until doc.pageCount).toSet(), found.map { it.page }.toSet())
            for (r in found.flatMap { it.rects }) assertTrue(r.left >= 0f && r.right <= 1f && r.top >= 0f && r.bottom <= 1f && r.width() > 0f)
        } else {
            assertTrue(found.isEmpty())
        }
        doc.close()
        file.delete()
        Unit
    }

    @Test
    fun documentsWithoutTextFindNothing() = runBlocking {
        val image = object : Document {
            override val pageSizes = listOf(PageSize(100f, 100f))
            override suspend fun renderPage(index: Int, widthPx: Int) = null
            override suspend fun close() = Unit
        }
        assertTrue(image.search("anything").isEmpty())
    }
}
