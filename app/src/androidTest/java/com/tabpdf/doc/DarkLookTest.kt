package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabpdf.doc.html.HtmlDocument
import com.tabpdf.ink.InkOnDark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Documents' dark look (기획서 5.10): which have one, its colours, and pages that stay as they are. */
@RunWith(AndroidJUnit4::class)
class DarkLookTest {
    private val darkPage = """
        <html><head><style>
        :root { --bg: #ffffff; --fg: #111111; --box: #eeeeee; }
        @media (prefers-color-scheme: dark) {
          :root { --bg: #101010; --fg: #f0f0f0; --box: #303030; }
          h1 { font-size: 90px; margin: 200px 0 }
          p { line-height: 4 }
        }
        body { background: var(--bg); color: var(--fg); margin: 0 }
        .box { background: var(--box); height: 200px }
        </style></head><body>
        <h1>제목</h1>
        ${"<p>여러 줄로 이어지는 문단입니다. 쪽이 여러 개가 되도록 길게 씁니다.</p>".repeat(80)}
        <div class="box">상자</div>
        </body></html>
    """.trimIndent()

    private suspend fun Document.page(index: Int, dark: Boolean): Bitmap {
        this.dark = dark
        return renderPage(index, 200)!!
    }

    /** The colour most of the page is. */
    private fun Bitmap.ground(): Int = (0 until height step 7).flatMap { y -> (0 until width step 7).map { x -> getPixel(x, y) } }
        .groupingBy { it }.eachCount().maxBy { it.value }.key

    @Test
    fun pageWithDarkCssHasALookTakingOnlyItsColours() = runBlocking {
        val doc = HtmlDocument.render(darkPage)
        assertTrue(doc.hasDarkLook)
        // One layout for both looks: the dark rules' sizes and spacing are not used.
        val light = HtmlDocument.render(darkPage.replace(Regex("""@media \(prefers-color-scheme: dark\) \{.*?\n\s*\}\n""", RegexOption.DOT_MATCHES_ALL), ""))
        assertFalse(light.hasDarkLook)
        assertEquals(light.pageSizes, doc.pageSizes)
        assertEquals(0xFFFFFFFF.toInt(), doc.page(0, dark = false).ground())
        assertEquals(0xFF101010.toInt(), doc.page(0, dark = true).ground())
        // Back again: only drawing changes.
        assertEquals(0xFFFFFFFF.toInt(), doc.page(0, dark = false).ground())
        doc.close()
        light.close()
    }

    @Test
    fun backgroundGivenByACustomPropertyIsDrawn() = runBlocking {
        // `background: var(--x)` is read once the property is put in (it used to be dropped).
        val doc = HtmlDocument.render("""<style>:root{--x:#ff0000} div{background:var(--x);padding:40px}</style><body style="margin:0"><div>글</div></body>""")
        val page = doc.renderPage(0, 200)!!
        assertEquals(Color.RED, page.getPixel(195, 2))
        doc.close()
    }

    @Test
    fun pageSayingItSuitsDarkScreensGetsABrowsersDarkColours() = runBlocking {
        val doc = HtmlDocument.render("""<html><head><meta name="color-scheme" content="light dark"></head><body><p>글</p></body></html>""")
        assertTrue(doc.hasDarkLook)
        assertEquals(DocLook.CANVAS, doc.page(0, dark = true).ground())
        assertEquals(Color.WHITE, doc.page(0, dark = false).ground())
        doc.close()
        val only = HtmlDocument.render("""<html><head><meta name="color-scheme" content="only light"></head><body><p>글</p></body></html>""")
        assertFalse(only.hasDarkLook)
        only.close()
    }

    @Test
    fun plainPagesHaveNoDarkLook() = runBlocking {
        val doc = HtmlDocument.render("""<body style="background:#fafafa"><p>그냥 문서</p></body>""")
        assertFalse(doc.hasDarkLook)
        // Asked for anyway, it stays as it is.
        doc.dark = true
        assertFalse(doc.dark)
        doc.close()
    }

    @Test
    fun textAndMarkdownHaveTheViewersDarkLook() = runBlocking {
        val md = MarkdownDocument.render("# 제목\n\n본문 `코드` [링크](https://example.com)\n\n```\n블록\n```")
        assertTrue(md.hasDarkLook)
        assertEquals(DocLook.PAPER, md.page(0, dark = true).ground())
        assertEquals(Color.WHITE, md.page(0, dark = false).ground())
        md.close()
        val text = TextDocument.layOut("한 줄\n두 줄", prose = true)
        assertTrue(text.hasDarkLook)
        val dark = text.page(0, dark = true)
        assertEquals(DocLook.PAPER, dark.ground())
        // Its letters are light there.
        val letters = (0 until dark.height).flatMap { y -> (0 until dark.width).map { x -> dark.getPixel(x, y) } }
        assertTrue(letters.any { Color.red(it) > 150 })
        text.close()
    }

    @Test
    fun exportDrawsTheUsualLook() = runBlocking {
        val doc = MarkdownDocument.render("본문")
        doc.dark = true
        val bitmap = Bitmap.createBitmap(500, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(0.5f, 0.5f)
        doc.drawPage(0, canvas)
        val pixels = (0 until 300).flatMap { y -> (0 until 500).map { x -> bitmap.getPixel(x, y) } }
        // Dark text on the page, as in the usual look.
        assertTrue(pixels.any { Color.red(it) < 100 })
        doc.close()
    }

    @Test
    fun blackAndWhiteInkSwapOnDarkPages() {
        assertEquals(0xFFFFFFFF.toInt(), InkOnDark.color(0xFF000000.toInt(), true))
        assertEquals(0xFF000000.toInt(), InkOnDark.color(0xFFFFFFFF.toInt(), true))
        assertEquals(0x66FFFFFF, InkOnDark.color(0x66000000, true))
        assertEquals(0xFFD93025.toInt(), InkOnDark.color(0xFFD93025.toInt(), true))
        assertEquals(0xFF000000.toInt(), InkOnDark.color(0xFF000000.toInt(), false))
    }
}
