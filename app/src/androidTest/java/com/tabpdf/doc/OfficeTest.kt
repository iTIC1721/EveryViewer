package com.tabpdf.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabpdf.doc.office.Align
import com.tabpdf.doc.office.DocxReader
import com.tabpdf.doc.office.HwpxReader
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.office.Paragraph
import com.tabpdf.doc.office.Run
import com.tabpdf.doc.office.Table
import com.tabpdf.doc.office.ZipParts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** docx and hwpx reading and layout (기획서 5.7), on small documents built here. */
@RunWith(AndroidJUnit4::class)
class OfficeTest {
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

    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private fun docx(body: String) = zip(
        "word/document.xml" to "<w:document $w><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\"/></w:sectPr></w:body></w:document>",
    )

    @Test
    fun docxTableWithMergedCellsBordersAndShading() {
        val doc = DocxReader.read(
            docx(
                """<w:tbl><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="8"/><w:insideH w:val="single" w:sz="4"/></w:tblBorders></w:tblPr>
                <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid>
                <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:shd w:fill="D9D9D9"/></w:tcPr><w:p><w:r><w:t>wide</w:t></w:r></w:p></w:tc>
                      <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>tall</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc></w:tr>
                </w:tbl>"""
            )
        )
        val table = doc.body.single() as Table
        assertEquals(listOf(100f, 100f, 100f), table.columns)
        val wide = table.cells.first { it.row == 0 && it.column == 0 }
        assertEquals(2, wide.columnSpan)
        assertEquals(0xFFD9D9D9.toInt(), wide.fill)
        assertEquals(1f, wide.borders.top!!.width) // 8 eighths of a point
        assertEquals(0.5f, wide.borders.bottom!!.width) // inside line
        val tall = table.cells.first { it.column == 2 }
        assertEquals(2, tall.rowSpan)
        assertEquals(4, table.cells.size) // the continued merge is not a cell of its own
    }

    /**
     * A table's style sets its paragraphs between the document's defaults and their own style:
     * Word's "Table Grid" takes away the defaults' space after each paragraph (a reader's form
     * whose table ran onto the next page because every row was 8 pt too tall).
     */
    @Test
    fun docxTableStyleSetsItsParagraphs() {
        val doc = DocxReader.read(
            zip(
                "word/document.xml" to "<w:document $w><w:body>" +
                    "<w:tbl><w:tblPr><w:tblStyle w:val=\"grid\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
                    "<w:tr><w:tc><w:p><w:r><w:t>in a cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
                    "<w:p><w:r><w:t>outside</w:t></w:r></w:p></w:body></w:document>",
                "word/styles.xml" to "<w:styles $w><w:docDefaults><w:pPrDefault><w:pPr><w:spacing w:after=\"160\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
                    "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"normal\"><w:name w:val=\"Normal\"/></w:style>" +
                    "<w:style w:type=\"table\" w:styleId=\"grid\"><w:pPr><w:spacing w:after=\"0\"/></w:pPr></w:style></w:styles>",
            )
        )
        val cell = (doc.body[0] as Table).cells.single().blocks.single() as Paragraph
        assertEquals(0f, cell.spaceAfter)
        assertEquals(8f, (doc.body[1] as Paragraph).spaceAfter)
    }

    @Test
    fun docxRunsStylesListsAndPageBreaks() {
        val doc = DocxReader.read(
            zip(
                "word/document.xml" to "<w:document $w><w:body>" +
                    "<w:p><w:pPr><w:jc w:val=\"center\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>" +
                    "<w:r><w:rPr><w:b/><w:color w:val=\"FF0000\"/><w:sz w:val=\"28\"/></w:rPr><w:t>Title</w:t></w:r>" +
                    "<w:r><w:br w:type=\"page\"/><w:t>next page</w:t></w:r></w:p></w:body></w:document>",
                "word/numbering.xml" to "<w:numbering $w><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">" +
                    "<w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum>" +
                    "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>",
            )
        )
        val first = doc.body[0] as Paragraph
        val second = doc.body[1] as Paragraph
        assertEquals(Align.Center, first.align)
        assertEquals("1.", (first.label as Run.Text).text)
        val title = first.runs.single() as Run.Text
        assertTrue(title.style.bold)
        assertEquals(14f, title.style.size)
        assertEquals(0xFFFF0000.toInt(), title.style.color)
        assertTrue(second.pageBreakBefore)
        assertEquals("next page", (second.runs.single() as Run.Text).text)
        assertEquals(2, OfficeLayout.layOut(doc).pageCount)
    }

    private val hp = "xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\""

    private fun hwpx(section: String) = zip(
        "Contents/header.xml" to """<hh:head xmlns:hh="h" xmlns:hc="c"><hh:refList>
            <hh:charProperties><hh:charPr id="0" height="1000" textColor="#0000FF"><hh:bold/><hh:ratio hangul="90"/><hh:spacing hangul="-5"/></hh:charPr></hh:charProperties>
            <hh:paraProperties><hh:paraPr id="0"><hh:align horizontal="JUSTIFY"/><hh:margin><hc:intent value="-1000"/><hc:left value="2000"/><hc:right value="0"/><hc:prev value="0"/><hc:next value="0"/></hh:margin><hh:lineSpacing type="PERCENT" value="160"/></hh:paraPr></hh:paraProperties>
            <hh:borderFills><hh:borderFill id="1"><hh:leftBorder type="SOLID" width="0.12 mm" color="#000000"/><hh:topBorder type="NONE" width="0.1 mm" color="#000000"/><hc:fillBrush><hc:winBrush faceColor="#F2F2F2"/></hc:fillBrush></hh:borderFill></hh:borderFills>
            </hh:refList></hh:head>""",
        "Contents/section0.xml" to "<hs:sec xmlns:hs=\"s\" $hp>$section</hs:sec>",
    )

    @Test
    fun hwpxShapesColoursAndPage() {
        val doc = HwpxReader.read(
            hwpx(
                """<hp:p paraPrIDRef="0"><hp:run charPrIDRef="0"><hp:secPr><hp:pagePr width="59528" height="84188" landscape="WIDELY">
                <hp:margin header="4252" footer="2836" gutter="0" left="8504" right="8504" top="2836" bottom="2836"/></hp:pagePr></hp:secPr>
                <hp:t>가<hp:tab/>나<hp:lineBreak/>다</hp:t></hp:run></hp:p>"""
            )
        )
        // Body starts below the header area, as in Hangul.
        assertEquals(28.36f + 42.52f, doc.page.bodyTop, 0.01f)
        val p = doc.body.single() as Paragraph
        assertEquals(Align.Justify, p.align)
        // Hanging indent: the first line stays at the left margin, the rest go 10pt further in.
        assertEquals(30f, p.indentLeft, 0.01f)
        assertEquals(-10f, p.firstLine, 0.01f)
        val run = p.runs.single() as Run.Text
        assertEquals("가\t나\n다", run.text)
        assertEquals(0xFFFF0000.toInt(), run.style.color) // stored BGR: "#0000FF" is red
        assertEquals(0.9f, run.style.scaleX, 0.001f)
        assertEquals(-0.05f, run.style.letterSpacing, 0.001f)
    }

    @Test
    fun hwpxTableFromCellAddresses() {
        val cell = { col: Int, row: Int, cs: Int, rs: Int, width: Int, text: String ->
            """<hp:tc borderFillIDRef="1"><hp:subList vertAlign="CENTER"><hp:p paraPrIDRef="0"><hp:run charPrIDRef="0"><hp:t>$text</hp:t></hp:run></hp:p></hp:subList>
            <hp:cellAddr colAddr="$col" rowAddr="$row"/><hp:cellSpan colSpan="$cs" rowSpan="$rs"/><hp:cellSz width="$width" height="1000"/></hp:tc>"""
        }
        val doc = HwpxReader.read(
            hwpx(
                """<hp:p paraPrIDRef="0"><hp:run charPrIDRef="0"><hp:tbl rowCnt="2" colCnt="2" borderFillIDRef="1">
                <hp:tr>${cell(0, 0, 2, 1, 6000, "top")}</hp:tr>
                <hp:tr>${cell(0, 1, 1, 1, 2000, "a")}${cell(1, 1, 1, 1, 4000, "b")}</hp:tr>
                </hp:tbl></hp:run></hp:p>"""
            )
        )
        val table = doc.body.single() as Table
        assertEquals(listOf(20f, 40f), table.columns)
        val top = table.cells.first { it.row == 0 }
        assertEquals(2, top.columnSpan)
        assertEquals(0xFFF2F2F2.toInt(), top.fill)
        assertNotNull(top.borders.left)
        assertEquals(null, top.borders.top)
        assertTrue(OfficeLayout.layOut(doc).pageCount == 1)
    }

    @Test
    fun aTallRowIsSplitAcrossPagesBetweenLines() = runBlocking {
        val lines = (1..120).joinToString("") { "<w:p><w:r><w:t>line $it</w:t></w:r></w:p>" }
        val doc = DocxReader.read(
            docx("<w:tbl><w:tblGrid><w:gridCol w:w=\"5000\"/></w:tblGrid><w:tr><w:tc>$lines</w:tc></w:tr></w:tbl>")
        )
        val sheets = OfficeLayout.layOut(doc)
        assertTrue("one row of 120 lines goes over several pages", sheets.pageCount >= 3)
        for (page in 0 until sheets.pageCount) assertNotNull(sheets.renderPage(page, 300))
        sheets.close()
    }

    @Test
    fun officeFormatsAreDetected() {
        val zipHead = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        assertEquals(FileFormat.Docx, FileFormats.detect("report.docx", null, zipHead))
        assertEquals(FileFormat.Hwpx, FileFormats.detect("계획서.hwpx", "application/octet-stream", zipHead))
        assertEquals(FileFormat.Hwpx, FileFormats.detect("download", "application/hwp+zip", zipHead))
        assertEquals(FileFormat.Hwp, FileFormats.detect("old.hwp", null, zipHead))
        assertEquals(FileFormat.Unsupported, FileFormats.detect("old.doc", null, zipHead))
    }
}
