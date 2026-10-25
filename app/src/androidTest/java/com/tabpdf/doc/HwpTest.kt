package com.tabpdf.doc

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker
import kr.dogfoot.hwplib.writer.HWPWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Binary Hangul documents (기획서 5.7): files are made with hwplib's own writer, so no sample
 * document is kept. Layout fidelity was checked against hwpx saved by Hangul from the same file
 * (the two render identically).
 */
@RunWith(AndroidJUnit4::class)
class HwpTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun open(name: String, bytes: ByteArray): Document = runBlocking {
        val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
        Documents.open(context, Uri.fromFile(file), name, FileFormats.detect(name, null, bytes.copyOf(minOf(bytes.size, 512))))
    }

    @Test
    fun textOfAWrittenFileIsShown() = runBlocking {
        val hwp = BlankFileMaker.make()
        val paragraph = hwp.bodyText.sectionList[0].getParagraph(0)
        paragraph.text.addString("한글 바이너리 문서 시험")
        paragraph.header.characterCount = paragraph.text.charSize.toLong()
        val out = ByteArrayOutputStream()
        HWPWriter.toStream(hwp, out)
        val doc = open("written.hwp", out.toByteArray())
        assertEquals(1, doc.pageCount)
        // An A4 sheet, as the blank file sets up.
        assertEquals(842f / 595f, doc.pageSizes[0].aspect, 0.02f)
        assertTrue(doc.search("바이너리 문서").isNotEmpty())
        assertNotNull(doc.renderPage(0, 300))
        doc.close()
    }

    @Test
    fun outlineParagraphsMakeTheTableOfContents() = runBlocking {
        val hwp = BlankFileMaker.make()
        val paragraph = hwp.bodyText.sectionList[0].getParagraph(0)
        paragraph.text.addString("개요 문단")
        paragraph.header.characterCount = paragraph.text.charSize.toLong()
        // The paragraph's shape is an outline level 2 (개요 2).
        val shape = hwp.docInfo.paraShapeList[paragraph.header.paraShapeId]
        shape.property1.paraHeadShape = kr.dogfoot.hwplib.`object`.docinfo.parashape.ParaHeadShape.Outline
        shape.property1.paraLevel = 1
        val out = ByteArrayOutputStream()
        HWPWriter.toStream(hwp, out)
        val doc = open("outline.hwp", out.toByteArray())
        val toc = doc.tableOfContents()
        assertEquals("개요 문단", toc.single().title)
        assertEquals(0, toc.single().page)
        doc.close()
    }

    @Test
    fun brokenFilesAreAnErrorNotACrash() {
        val notHwp = "이것은 hwp가 아닙니다".toByteArray()
        assertTrue(runCatching { open("broken.hwp", notHwp) }.isFailure)
        // A compound file header with nothing behind it.
        val truncated = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(600)
        assertTrue(runCatching { open("truncated.hwp", truncated) }.isFailure)
    }
}
