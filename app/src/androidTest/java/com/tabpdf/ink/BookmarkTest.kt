package com.tabpdf.ink

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.TabPdfApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Bookmarks (기획서 5.5): one per page, named or not, following their pages, gone with the tab. */
@RunWith(AndroidJUnit4::class)
class BookmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as TabPdfApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tabId = "bookmark-test-" + UUID.randomUUID()
    private lateinit var store: InkStore

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { app.inkDao.deleteTabs(listOf(tabId)) }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun shown() = onMain { store.bookmarks(tabId).value.map { it.page to it.title } }

    /** What is saved, once the write queue has caught up with [expected]. */
    private fun assertSaved(expected: List<Pair<Int, String?>>) {
        val end = SystemClock.uptimeMillis() + 3_000
        var saved: List<Pair<Int, String?>>
        do {
            saved = runBlocking { app.inkDao.bookmarksOf(tabId).map { it.page to it.name } }
            if (saved == expected) return
            Thread.sleep(50)
        } while (SystemClock.uptimeMillis() < end)
        assertEquals(expected, saved)
    }

    @Test
    fun putTakenAwayNamedMovedAndForgotten() {
        onMain { store = InkStore(app, scope) }
        onMain { store.toggleBookmark(tabId, 4, 0.3f) }
        onMain { store.toggleBookmark(tabId, 1, 0.5f) }
        assertEquals("in page order, named after their pages", listOf(1 to "2페이지", 4 to "5페이지"), shown())
        assertSaved(listOf(1 to null, 4 to null))

        onMain { store.toggleBookmark(tabId, 1, 0.9f) }
        assertEquals("the same button takes it away again", listOf(4 to "5페이지"), shown())
        onMain { store.toggleBookmark(tabId, 1, 0.9f) }
        assertEquals(0.9f, onMain { store.bookmarks(tabId).value.first().y }, 0.001f)

        val id = onMain { store.bookmarks(tabId).value.last().id }
        onMain { store.renameBookmark(tabId, id, "  공식 정리 ") }
        assertEquals(listOf(1 to "2페이지", 4 to "공식 정리"), shown())
        assertSaved(listOf(1 to null, 4 to "공식 정리"))
        onMain { store.renameBookmark(tabId, id, " ") }
        assertNull("a blank name goes back to the page's", onMain { store.bookmarks(tabId).value.last().name })

        // A bundle edited: page 1's file taken out, the rest moved up front.
        onMain { store.movePages(tabId) { page -> if (page == 1) null else page - 2 } }
        assertEquals(listOf(2 to "3페이지"), shown())
        assertSaved(listOf(2 to null))

        // Loaded again, as at the next start.
        onMain { store = InkStore(app, scope) }
        onMain { store.bookmarks(tabId) }
        val end = SystemClock.uptimeMillis() + 3_000
        while (shown().isEmpty() && SystemClock.uptimeMillis() < end) Thread.sleep(50)
        assertEquals(listOf(2 to "3페이지"), shown())

        onMain { store.forget(listOf(tabId)) }
        assertSaved(emptyList())
    }
}
