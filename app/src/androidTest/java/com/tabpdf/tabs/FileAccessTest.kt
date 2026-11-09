package com.tabpdf.tabs

import android.net.Uri
import com.tabpdf.doc.FileFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A PDF handed over by another app ([TestPdfProvider] plays that app) comes with no persistable
 * grant, so FileAccess must copy it into app storage — and delete the copy on release (기획서 5.3).
 */
@RunWith(AndroidJUnit4::class)
class FileAccessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val testContext = instrumentation.context

    @Test
    fun fileWithoutPersistableGrantIsCopiedAndTheCopyRemovedOnRelease() = runBlocking {
        val uri = Uri.parse("content://${TestPdfProvider.AUTHORITY}/shared.pdf")

        val opened = FileAccess.prepare(appContext, uri)

        assertTrue(opened.isInternalCopy)
        assertEquals("shared.pdf", opened.name)
        assertEquals(FileFormat.Pdf, opened.format)
        val copy = File(opened.uri.path!!)
        assertTrue(copy.path.startsWith(appContext.filesDir.path))
        val original = testContext.assets.open(TestPdfProvider.ASSET).use { it.readBytes() }
        assertArrayEquals(original, copy.readBytes())

        FileAccess.release(appContext, DocTab("t", null, opened.uri, opened.name, opened.isInternalCopy, opened.format))
        assertFalse(copy.exists())
    }

    // ---- Knowing a file opened again (기획서 5.1) ----

    private val made = mutableListOf<File>()

    @After
    fun removeFiles() {
        made.forEach { it.delete() }
    }

    /** A file in the app's files folder, where the debug hook's files lie (FileAccess takes file: addresses only there). */
    private fun file(name: String, content: String): Uri {
        val f = File(appContext.filesDir, name)
        f.writeText(content)
        made += f
        return Uri.fromFile(f)
    }

    private fun tab(id: String, uri: Uri, copy: Boolean, source: Uri? = null) =
        DocTab(id, null, uri, "$id.txt", copy, FileFormat.Text, source = source)

    @Test
    fun tabReadingTheFileWhereItLiesIsTheSameFile() = runBlocking {
        val shared = file("same-direct.txt", "hello")
        val tab = tab("a", shared, copy = false)
        assertSame(tab, FileAccess.findSame(appContext, shared, listOf(tab)))
    }

    @Test
    fun copyIsTheSameFileOnlyWhileTheFileIsUnchanged() = runBlocking {
        val shared = file("same-shared.txt", "hello")
        val tab = tab("a", file("same-copy.txt", "hello"), copy = true, source = shared)
        assertSame(tab, FileAccess.findSame(appContext, shared, listOf(tab)))
        // Edited since: as large as before, but another file now.
        File(shared.path!!).writeText("jello")
        assertNull(FileAccess.findSame(appContext, shared, listOf(tab)))
        File(shared.path!!).writeText("hello!")
        assertNull(FileAccess.findSame(appContext, shared, listOf(tab)))
    }

    @Test
    fun sameFileFromAnotherAddressIsFoundByItsContent() = runBlocking {
        val shared = file("same-other.txt", "hello")
        val lookAlike = tab("a", file("same-alike.txt", "world"), copy = true)
        val same = tab("b", file("same-copy2.txt", "hello"), copy = true, source = Uri.parse("content://elsewhere/1"))
        val later = tab("c", file("same-copy3.txt", "hello"), copy = true)
        assertSame(same, FileAccess.findSame(appContext, shared, listOf(lookAlike, same, later)))
        assertNull(FileAccess.findSame(appContext, shared, listOf(lookAlike)))
    }

    @Test
    fun fileSharedAgainFromAnotherAppIsKnown() = runBlocking {
        val uri = Uri.parse("content://${TestPdfProvider.AUTHORITY}/shared.pdf")
        val opened = FileAccess.prepare(appContext, uri)
        made += File(opened.uri.path!!)
        assertEquals(uri, opened.source)
        val tab = DocTab("t", null, opened.uri, opened.name, opened.isInternalCopy, opened.format, source = opened.source)
        assertSame(tab, FileAccess.findSame(appContext, uri, listOf(tab)))
        // A tab from before addresses were kept: known by size and content.
        val old = tab.copy(source = null)
        assertSame(old, FileAccess.findSame(appContext, uri, listOf(old)))
    }
}
