package com.tabpdf.doc

import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

/**
 * Text files read and laid out a chunk at a time (기획서 5.7): the pages must come out exactly as
 * laying out the whole text at once makes them (handwriting already on them stays put), whatever
 * the encoding, and files of any size open.
 */
@RunWith(AndroidJUnit4::class)
class LargeTextTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "large-text-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray): Uri = Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })

    private suspend fun open(uri: Uri, prose: Boolean): Document =
        StreamedTextDocument.open(context, uri, prose, notice = null).also { it.awaitComplete() }

    /** Same pages, and the same text on a few of them, as the whole text laid out at once. */
    private suspend fun assertSameAsWhole(text: String, bytes: ByteArray, prose: Boolean) {
        val whole = TextDocument.layOut(text, prose)
        val streamed = open(file("t.txt", bytes), prose)
        assertEquals(whole.pageSizes, streamed.pageSizes)
        val all = PointF(0f, 0f) to PointF(1f, 1f)
        for (page in listOf(0, whole.pageCount / 2, whole.pageCount - 1)) {
            assertEquals(
                whole.selectText(page, all.first, all.second)?.text,
                streamed.selectText(page, all.first, all.second)?.text,
            )
        }
        whole.close()
        streamed.close()
    }

    private val mixed = (1..6000).joinToString("\n") { "줄 $it: 한글과 English가 섞인 텍스트, 조금 더 긴 줄은 접혀서 두 줄이 됩니다 ".repeat(it % 3 + 1) }

    @Test
    fun utf8PagesAreTheSameAsLayingOutWhole() = runBlocking {
        assertSameAsWhole(mixed, mixed.toByteArray(), prose = true)
        assertSameAsWhole(mixed, mixed.toByteArray(), prose = false)
    }

    @Test
    fun lineEndsAndLongLinesAreCutTheSame() = runBlocking {
        // CR LF and lone CR, lines far longer than a chunk, an emoji right at a cut, no final newline.
        val text = buildString {
            repeat(300) { append("windows line $it\r\n") }
            repeat(50) { append("old mac line $it\r") }
            append("x".repeat(TextDocument.CHUNK_CHARS - 1)).append("😀").append("y".repeat(30_000)).append('\n')
            append("가".repeat(50_000))
        }
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        assertSameAsWhole(normalized, text.toByteArray(), prose = false)
    }

    @Test
    fun koreanCp949AfterALongAsciiStartIsDecoded() = runBlocking {
        // The encoding is only settled at the first non-ASCII byte, far into the file.
        val text = (1..3000).joinToString("\n") { "ascii line $it" } + "\n" + mixed
        assertSameAsWhole(text, text.toByteArray(Charset.forName("x-windows-949")), prose = true)
    }

    @Test
    fun utf16WithByteOrderMarkIsDecoded() = runBlocking {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertSameAsWhole(mixed, bom + mixed.toByteArray(Charsets.UTF_16LE), prose = true)
    }

    @Test
    fun emptyFileIsOnePage() = runBlocking {
        val doc = open(file("empty.txt", ByteArray(0)), prose = true)
        assertEquals(TextDocument.layOut("", prose = true).pageSizes, doc.pageSizes)
        doc.close()
    }

    @Test
    fun searchFindsWhatLayingOutWholeFinds() = runBlocking {
        val whole = TextDocument.layOut(mixed, prose = true)
        val streamed = open(file("s.txt", mixed.toByteArray()), prose = true)
        for (query in listOf("줄 5999", "english", "없는 말")) {
            val a = whole.search(query)
            val b = streamed.search(query)
            assertEquals(a.map { it.page }, b.map { it.page })
            // Laid out whole, positions far down are offset in float before being made relative.
            for ((r, s) in a.flatMap { it.rects }.zip(b.flatMap { it.rects })) {
                assertEquals(r.left, s.left, 1e-4f)
                assertEquals(r.top, s.top, 1e-4f)
                assertEquals(r.bottom, s.bottom, 1e-4f)
            }
        }
        whole.close()
        streamed.close()
    }

    @Test
    fun aFileFarOverTheOldLimitOpensAtOnce() = runBlocking {
        // 40 MB, over the old 16 MB limit: the first page comes before the rest is laid out.
        val line = "로그 2026-10-25 12:00:00 INFO 큰 텍스트 파일 한 줄입니다 0123456789\n".toByteArray()
        val f = File(dir, "big.log")
        f.outputStream().buffered().use { out -> repeat(40 * 1024 * 1024 / line.size) { out.write(line) } }
        val started = SystemClock.elapsedRealtime()
        val doc = StreamedTextDocument.open(context, Uri.fromFile(f), prose = false, notice = null)
        val firstPage = SystemClock.elapsedRealtime() - started
        assertNotNull(doc.renderPage(0, 400))
        doc.awaitComplete()
        val all = SystemClock.elapsedRealtime() - started
        android.util.Log.i("LargeTextTest", "40 MB: first page ${firstPage} ms, all ${all} ms, ${doc.pageCount} pages")
        assertTrue("first page took $firstPage ms", firstPage < 1000)
        assertNotNull(doc.renderPage(doc.pageCount - 1, 400))
        assertTrue(doc.selectText(doc.pageCount / 2, PointF(0f, 0f), PointF(1f, 1f))!!.text.contains("큰 텍스트 파일"))
        doc.close()
    }

    @Test
    fun markdownTooBigForPreviewOpensAsText() = runBlocking {
        val md = "# 제목\n\n본문 줄입니다. **굵게**\n".repeat(4 * 1024 * 1024 / 40 + 1000)
        val uri = file("big.md", md.toByteArray())
        val doc = Documents.open(context, uri, "big.md", FileFormat.Markdown)
        assertNotNull(doc.notice)
        doc.awaitComplete()
        assertTrue(doc.search("**굵게**").isNotEmpty()) // the source, not rendered
        doc.close()
        val small = Documents.open(context, file("small.md", "# 제목\n".toByteArray()), "small.md", FileFormat.Markdown)
        assertEquals(null, small.notice)
        small.close()
    }
}
