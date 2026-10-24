package com.tabpdf.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Cutting laid-out text into pages (기획서 5.7). */
@RunWith(AndroidJUnit4::class)
class FlowTest {
    @Test
    fun pagesEndAtTheLastBreakInReach() {
        val breaks = floatArrayOf(30f, 60f, 90f, 120f, 150f)
        assertArrayEquals(floatArrayOf(0f, 90f, 180f), paginate(180f, breaks, maxPage = 100f), 0f)
    }

    @Test
    fun contentWithoutBreaksIsCutAtTheLimit() {
        assertArrayEquals(floatArrayOf(0f, 100f, 200f, 250f), paginate(250f, FloatArray(0), maxPage = 100f), 0f)
    }

    @Test
    fun aBreakTooEarlyIsUsedOnlyWhenThereIsNothingLater() {
        // 10 would leave a nearly empty page; with nothing between 50 and 100 it is still better than a cut.
        assertArrayEquals(floatArrayOf(0f, 10f, 110f, 150f), paginate(150f, floatArrayOf(10f), maxPage = 100f), 0f)
    }

    @Test
    fun emptyContentIsOnePage() {
        assertEquals(2, paginate(0f, FloatArray(0), maxPage = 100f).size)
    }

    @Test
    fun chunksKeepEveryLineOnce() {
        val text = (1..5000).joinToString("\n") { "line $it with some text" }
        val chunks = TextDocument.chunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun longTextPagesCoverTheWholeFlowWithoutCuttingLines() = runBlocking {
        val doc = TextDocument.layOut((1..2000).joinToString("\n") { "줄 $it: 한글과 English가 섞인 텍스트" }, prose = true)
        assertTrue(doc.pageCount > 10)
        assertTrue(doc.pageSizes.all { it.width == FLOW_WIDTH && it.height <= TextDocument.MAX_PAGE_HEIGHT })
        assertTrue(doc.renderPage(doc.pageCount - 1, 200) != null)
        doc.close()
    }
}
