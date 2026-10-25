package com.tabpdf.doc

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.doc.office.NumberFormat
import com.tabpdf.doc.office.XlsxReader
import com.tabpdf.doc.office.ZipParts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Excel workbooks (기획서 5.7): sheet.xlsx was made with openpyxl and saved again by Excel, so it
 * holds what Excel writes (shared strings, saved formula results, built-in formats). Its first
 * sheet "매출" has a title merged over A1:E1, a table A3:E8 with dates, ₩ prices and formulas,
 * a sum in E10, formats in row 12, overflowing text in A14, a link in B17 and a picture at G3; then
 * "넓은 표" (40 × 100) and "메모".
 */
@RunWith(AndroidJUnit4::class)
class XlsxTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun open(name: String = "sheet.xlsx", bytes: ByteArray? = null): Document = runBlocking {
        val file = File(instrumentation.targetContext.cacheDir, name)
        if (bytes != null) file.writeBytes(bytes)
        else instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        Documents.open(instrumentation.targetContext, Uri.fromFile(file), name, FileFormat.Xlsx)
    }

    @Test
    fun numbersShowAsExcelShowsThem() {
        fun f(v: Double, code: String) = NumberFormat.format(v, code, date1904 = false).text
        assertEquals("1,234,568", f(1234567.89, "#,##0"))
        assertEquals("-1,235", f(-1234.5, NumberFormat.builtIn(38)))
        assertEquals("45.7%", f(0.4567, "0.0%"))
        assertEquals("₩1,200.00", f(1200.0, "\"₩\"#,##0.00"))
        assertEquals("1.23E+04", f(12345.0, "0.00E+00"))
        assertEquals("2026-07-04", f(46207.0, "yyyy\\-mm\\-dd"))
        assertEquals("2026-10-25 2:30 PM", f(46320.604166666657, "yyyy\\-mm\\-dd\\ h:mm\\ AM/PM"))
        assertEquals("2026년 7월 4일", f(46207.0, NumberFormat.builtIn(31)))
        assertEquals("26:30:00", f(1.1041666666, "[h]:mm:ss"))
        assertEquals("0.333333333", NumberFormat.general(1.0 / 3))
        assertEquals("1.23457E+12", NumberFormat.general(1234567890123.0))
        assertEquals("12", NumberFormat.general(12.0))
        // Thousands dropped by a trailing comma; text in quotes; zero section.
        assertEquals("1,235천", f(1234567.0, "#,##0,\"천\""))
        assertEquals("-", f(0.0, "#,##0;-#,##0;\"-\""))
        assertEquals("Red", NumberFormat.format(-1.0, "0;[Red]0", false).color?.let { if (it == 0xFFFF0000.toInt()) "Red" else "?" })
    }

    @Test
    fun sheetsBecomePagesWithTheirText() = runBlocking {
        val doc = open()
        assertTrue("three sheets, the wide one over several pages", doc.pageCount >= 4)
        for (text in listOf("2026년 3분기 매출 보고", "2026-07-04", "₩3,400.50", "1,118,040", "45.7%", "-1,235", "TRUE", "넓은 표", "좁은 시트")) {
            assertTrue(text, doc.search(text).isNotEmpty())
        }
        // The first page is the first sheet; its pages are wider than tall pages of the wide sheet's ratio.
        assertEquals(0, doc.search("사과").first().page)
        assertTrue(doc.search("열40").first().page > 0)
        val word = doc.search("포도").single()
        val r = word.rects.first()
        assertEquals("포도", doc.selectWord(word.page, android.graphics.PointF(r.centerX(), r.centerY()))?.text)
        assertNotNull(doc.renderPage(0, 400))
        assertNotNull(doc.renderPage(doc.pageCount - 1, 400))
        doc.close()
    }

    @Test
    fun linksAndOverflowingText() = runBlocking {
        val doc = open()
        val link = doc.search("예제 사이트").single()
        val r = link.rects.first()
        assertEquals("https://example.com/", doc.linkAt(link.page, r.centerX(), r.centerY()))
        // Long text spills into the empty cells to its right and can be found whole.
        val long = doc.search("끝까지 보이나요").single().rects.first()
        assertTrue("past column A", long.left > 0.4f)
        doc.close()
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun minimal(sheet: String) = zip(
        "xl/workbook.xml" to """<workbook xmlns:r="r"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/worksheets/sheet1.xml" to sheet,
    )

    @Test
    fun craftedSheetsDontCrash() {
        // A cell at Excel's last row and column, a merge over the whole sheet, a bad shared string index.
        val far = minimal(
            """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>처음</t></is></c><c r="B1" t="s"><v>999</v></c></row>
            <row r="1048576"><c r="XFD1048576"><v>1</v></c></row></sheetData>
            <mergeCells><mergeCell ref="A1:XFD1048576"/></mergeCells></worksheet>"""
        )
        val doc = open("far.xlsx", far)
        assertTrue(runBlocking { doc.search("처음") }.isNotEmpty())
        runBlocking { doc.close() }
        // Rows past Excel's limit are a broken file: an error, not a crash.
        val bad = minimal("""<worksheet><sheetData><row r="99999999"><c><v>1</v></c></row></sheetData></worksheet>""")
        assertTrue(runCatching { open("bad.xlsx", bad) }.isFailure)
        assertEquals(10L shl 20 or 3L, XlsxReader.key(10, 3))
        assertNotNull(ZipParts.read(far.inputStream()))
    }
}
