package com.tabpdf.doc

import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabpdf.doc.office.DocxReader
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.office.ZipParts
import com.tabpdf.ui.SelectionAnchors
import com.tabpdf.ui.SelectionEnd
import com.tabpdf.ui.joinSelection
import com.tabpdf.ui.selectRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Selecting text to copy (기획서 5.5): words, ranges, and ranges over several pages. */
@RunWith(AndroidJUnit4::class)
class SelectionTest {
    private fun flow(text: String, pageHeight: Float): FlowDocument {
        val builder = FlowBuilder()
        builder.add(TextBlock(staticLayout(text, flowPaint(34f), FLOW_WIDTH)))
        return builder.build(pageHeight, pageGaps = false)
    }

    /** The middle of [text]'s first occurrence on page [page], as a point on the page. */
    private suspend fun pointOf(doc: Document, page: Int, text: String): PointF {
        val r = doc.search(text).first { it.page == page }.rects.first()
        return PointF(r.centerX(), r.centerY())
    }

    @Test
    fun aLongPressPicksTheWord() = runBlocking {
        val doc = flow("alpha beta gamma", 1000f)
        assertEquals("beta", doc.selectWord(0, pointOf(doc, 0, "eta"))!!.text)
        // Off the text: nothing.
        assertNull(doc.selectWord(0, PointF(0.5f, 0.99f)))
        doc.close()
    }

    @Test
    fun aRangeRunsBetweenTwoPointsEitherWay() = runBlocking {
        val doc = flow("one two three four five", 1000f)
        val two = doc.search("two").single().rects.single()
        val four = doc.search("four").single().rects.single()
        val from = PointF(two.left + 0.001f, two.centerY())
        val to = PointF(four.right - 0.001f, four.centerY())
        assertEquals("two three four", doc.selectText(0, from, to)!!.text)
        assertEquals("two three four", doc.selectText(0, to, from)!!.text)
        doc.close()
    }

    @Test
    fun koreanWordsComeOutWithoutTheirHiddenJoiners() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">실험 계획서 초안</w:t></w:r></w:p>" +
                    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>" +
                    "</w:body></w:document>").toByteArray()
            )
            z.closeEntry()
        }
        val doc = OfficeLayout.layOut(DocxReader.read(ZipParts.read(ByteArrayInputStream(out.toByteArray()))))
        assertEquals("계획서", doc.selectWord(0, pointOf(doc, 0, "획"))!!.text)
        doc.close()
    }

    @Test
    fun aSelectionOverPagesJoinsThem() = runBlocking {
        // Short pages: each line on its own page.
        val doc = flow("first line\nsecond line\nthird line", 50f)
        assertEquals(3, doc.pageCount)
        val line = doc.search("line").first { it.page == 0 }.rects.single()
        val third = doc.search("third").single().rects.single()
        // Given end first: the order the reader dragged in doesn't matter.
        val anchors = SelectionAnchors(
            SelectionEnd(2, Offset(third.right - 0.001f, third.centerY())),
            SelectionEnd(0, Offset(line.left + 0.001f, line.centerY())),
        )
        val parts = selectRange(doc, anchors)
        assertEquals(listOf(0, 1, 2), parts.map { it.first })
        assertEquals("line\nsecond line\nthird", joinSelection(parts))
        assertNotNull(parts.first().second.rects.firstOrNull())
        doc.close()
    }
}
