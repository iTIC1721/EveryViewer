package com.tabpdf.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.Charset

/** Telling formats apart (기획서 5.7) and reading text in the encodings Korean files come in. */
@RunWith(AndroidJUnit4::class)
class FileFormatTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0)

    private fun detect(name: String, head: ByteArray, mime: String? = null) = FileFormats.detect(name, mime, head)

    @Test
    fun signaturesWinOverNameAndType() {
        assertEquals(FileFormat.Pdf, detect("scan.jpg", "%PDF-1.7\n".toByteArray(), "image/jpeg"))
        assertEquals(FileFormat.Image, detect("photo", png, "application/octet-stream"))
        assertEquals(FileFormat.Image, detect("photo.pdf", jpeg))
    }

    @Test
    fun extensionThenTypeThenContent() {
        assertEquals(FileFormat.Markdown, detect("README.md", "# Title".toByteArray(), "application/octet-stream"))
        assertEquals(FileFormat.Markdown, detect("notes", "# Title".toByteArray(), "text/markdown"))
        assertEquals(FileFormat.Text, detect("build.gradle.kts", "plugins {}".toByteArray()))
        assertEquals(FileFormat.Text, detect("log", "a\tb\r\nc".toByteArray(), "application/octet-stream"))
        // Zip-based documents look like nothing in particular; their name says what they are.
        assertEquals(FileFormat.Pptx, detect("slides.pptx", zip))
        assertEquals(FileFormat.Xlsx, detect("sheet.xlsx", zip))
        assertEquals(FileFormat.Unsupported, detect("old.xls", zip))
        assertEquals(FileFormat.Unsupported, detect("data.bin", byteArrayOf(1, 2, 0, 3)))
        // "BM" at the start of text is not a bitmap.
        assertEquals(FileFormat.Text, detect("notes", "BMW service notes, 2024".toByteArray()))
    }

    @Test
    fun decodesUtf8WithAndWithoutBomAndUtf16() {
        val text = "한글 텍스트 abc"
        assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
        assertEquals(text, TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun decodesKoreanLegacyEncoding() {
        val text = "가나다라마바사 아자차카타파하. 옛날 윈도우 메모장으로 저장한 한글 문서입니다. 똠방각하 펲".repeat(4)
        val bytes = text.toByteArray(Charset.forName("x-windows-949"))
        assertEquals(text, TextDecoding.decode(bytes))
    }

    @Test
    fun utf8CutInsideACharacterIsStillUtf8() {
        val bytes = "가나다".toByteArray()
        assertEquals("가나", TextDecoding.decode(bytes.copyOf(bytes.size - 1)))
    }
}
