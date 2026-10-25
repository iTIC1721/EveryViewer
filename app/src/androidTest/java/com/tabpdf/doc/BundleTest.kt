package com.tabpdf.doc

import android.graphics.PointF
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.tabs.FileAccess
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Several files shown as one document (기획서 5.1: 파일 묶음). */
@RunWith(AndroidJUnit4::class)
class BundleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "bundle-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun item(name: String, text: String?, format: FileFormat = FileFormat.Text): BundleItem {
        val file = File(dir, name)
        if (text != null) file.writeText(text)
        return BundleItem("id-$name", Uri.fromFile(file), name, format, isInternalCopy = false)
    }

    /** A text file of [lines] short lines, long enough for several pages. */
    private fun lines(prefix: String, lines: Int) = (1..lines).joinToString("\n") { "$prefix line $it" }

    @Test
    fun filesFollowOneAnother() = runBlocking {
        val first = item("first.txt", lines("first", 200))
        val second = item("second.txt", lines("second", 200))
        val alone = Documents.open(context, first.uri, first.name, FileFormat.Text).also { it.awaitComplete() }
        val firstPages = alone.pageCount
        alone.close()
        val bundle = BundleDocument.open(context, Bundles.create(context, listOf(first, second)))
        bundle.awaitComplete()
        assertEquals(firstPages * 2, bundle.pageCount)
        assertEquals(mapOf(first.id to firstPages, second.id to firstPages), bundle.pageCounts())
        // Found in the second file: its pages come after the first file's.
        val found = bundle.search("second line 1")
        assertTrue(found.isNotEmpty() && found.all { it.page >= firstPages })
        assertEquals(firstPages, found.first().page)
        // Selection goes to the file the page belongs to.
        val word = bundle.selectWord(firstPages, PointF(found.first().rects.first().centerX(), found.first().rects.first().centerY()))
        assertEquals("second", word?.text)
        assertNotNull(bundle.renderPage(bundle.pageCount - 1, 200))
        bundle.close()
    }

    @Test
    fun aMissingFileIsOneBlankPage() = runBlocking {
        val missing = item("gone.pdf", text = null, format = FileFormat.Pdf)
        val there = item("there.txt", "short")
        val bundle = BundleDocument.open(context, Bundles.create(context, listOf(missing, there)))
        bundle.awaitComplete()
        assertEquals(2, bundle.pageCount)
        assertNotNull("the blank page is drawn", bundle.renderPage(0, 200))
        assertNull(bundle.selectWord(0, PointF(0.5f, 0.5f)))
        assertEquals(1, bundle.search("short").single().page)
        bundle.close()
    }

    @Test
    fun pagesMoveWithTheirFiles() {
        // a (2 pages), b (3), c (1) → c, a; b taken out.
        val move = BundlePages.mover(listOf("a" to 2, "b" to 3, "c" to 1), listOf("c" to 1, "a" to 2))
        assertEquals(listOf(1, 2, null, null, null, 0), (0..5).map(move))
        // Unknown pages (past the end) go nowhere.
        assertNull(move(6))
    }

    @Test
    fun aBundlesFilesCountAsInUse() {
        val inBundle = item("in.txt", "x")
        val list = Bundles.create(context, listOf(inBundle))
        val other = Uri.fromFile(File(dir, "other.txt"))
        val used = FileAccess.filesInUse(listOf(list to FileFormat.Bundle, other to FileFormat.Text))
        assertEquals(setOf(list, inBundle.uri, other), used)
        assertEquals(listOf(inBundle), Bundles.read(list))
        File(list.path!!).delete()
    }
}
