package com.tabpdf.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabpdf.doc.html.HtmlDocument
import com.tabpdf.doc.html.Stylesheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** HTML read and laid out by the app (기획서 5.7): what shows, what doesn't, and that crafted pages can't hurt. */
@RunWith(AndroidJUnit4::class)
class HtmlTest {
    private fun render(html: String) = HtmlDocument.render(html)

    private suspend fun Document.finds(text: String) = search(text).isNotEmpty()

    @Test
    fun textShowsHiddenAndScriptsDont() = runBlocking {
        val doc = render(
            """
            <html><head><title>제목은 안 보임</title><style>.gone { display: none } @media print { p { display: none } }</style></head>
            <body><p>보이는 문단</p><p class="gone">숨긴 문단</p><p hidden>속성으로 숨김</p>
            <script>document.write("스크립트 결과")</script><noscript>스크립트 없을 때</noscript></body></html>
            """
        )
        assertTrue(doc.finds("보이는 문단"))
        assertTrue(doc.finds("스크립트 없을 때"))
        for (hidden in listOf("제목은 안 보임", "숨긴 문단", "속성으로 숨김", "스크립트 결과", "document.write")) {
            assertTrue(hidden, !doc.finds(hidden))
        }
        doc.close()
    }

    @Test
    fun webLinksOnlyAreLinks() = runBlocking {
        val doc = render(
            """<body style="margin:0"><p style="margin:0;font-size:40px"><a href="https://example.com/a">웹링크</a></p>
            <p style="margin:0;font-size:40px"><a href="javascript:alert(1)">스크립트링크</a></p>
            <p style="margin:0;font-size:40px"><a href="file:///data/x">파일링크</a></p></body>"""
        )
        fun linkOf(text: String): String? = runBlocking {
            val match = doc.search(text).single()
            val r = match.rects.first()
            doc.linkAt(match.page, r.centerX(), r.centerY())
        }
        assertEquals("https://example.com/a", linkOf("웹링크"))
        assertEquals(null, linkOf("스크립트링크"))
        assertEquals(null, linkOf("파일링크"))
        doc.close()
    }

    @Test
    fun tablesAndListsKeepTheirText() = runBlocking {
        val doc = render(
            """<table border=1><tr><td rowspan=2>병합</td><td>오른쪽 위</td></tr><tr><td>오른쪽 아래</td></tr>
            <tr><td colspan=2>두 칸</td></tr></table><ol start=5><li>다섯</li></ol>"""
        )
        for (text in listOf("병합", "오른쪽 위", "오른쪽 아래", "두 칸", "다섯")) assertTrue(text, doc.finds(text))
        doc.close()
    }

    @Test
    fun declaredKoreanEncodingIsRead() {
        val html = """<meta charset="euc-kr"><p>똠방각하 펲시</p>"""
        assertEquals(true, HtmlDocument.decode(html.toByteArray(charset("x-windows-949"))).contains("똠방각하 펲시"))
        // Without a declaration, the bytes decide (UTF-8 here).
        assertTrue(HtmlDocument.decode("<p>한글</p>".toByteArray()).contains("한글"))
    }

    @Test
    fun formatIsDetected() {
        assertEquals(FileFormat.Html, FileFormats.detect("page.htm", null, ByteArray(0)))
        assertEquals(FileFormat.Html, FileFormats.detect("download", "application/octet-stream", "\n <!DOCTYPE html><html>".toByteArray()))
        assertEquals(FileFormat.Text, FileFormats.detect("notes.txt", null, "<html>".toByteArray()))
    }

    @Test
    fun cssCascade() {
        assertTrue(Stylesheet.mediaMatches("screen and (max-width: 600px)"))
        assertTrue(!Stylesheet.mediaMatches("(min-width: 900px)"))
        assertTrue(!Stylesheet.mediaMatches("print"))
        val doc = org.jsoup.Jsoup.parse("""<div id="a" class="x"><p class="y">t</p></div>""")
        val sheet = Stylesheet().apply { add("p { color: red } .x > .y { color: blue } #a p { color: green } a:hover p { color: pink }") }
        val p = doc.selectFirst("p")!!
        assertEquals(listOf("red", "blue", "green"), sheet.declarationsFor(p, emptyList()).map { it.value })
    }

    @Test
    fun craftedPagesDontCrash() = runBlocking {
        // Nested far deeper than real pages; a huge colspan; a broken data image.
        val deep = "<div>".repeat(50_000) + "깊은 글" + "</div>".repeat(50_000)
        render(deep).let { assertTrue(it.finds("깊은 글")); it.close() }
        // Boxes in boxes are drawn by recursion too.
        val boxes = "<div style='border:1px solid red;padding:1px'>".repeat(300) + "상자 안" + "</div>".repeat(300)
        render(boxes).let {
            assertTrue(it.finds("상자 안"))
            assertNotNull(it.renderPage(0, 200))
            it.selectWord(0, android.graphics.PointF(0.5f, 0.1f))
            it.close()
        }
        render("""<table><tr><td colspan=99999999 rowspan=99999999>넓은 칸</td></tr></table><img src="data:image/png;base64,!!!!">""").let {
            assertTrue(it.finds("넓은 칸"))
            assertNotNull(it.renderPage(0, 200))
            it.close()
        }
    }
}
