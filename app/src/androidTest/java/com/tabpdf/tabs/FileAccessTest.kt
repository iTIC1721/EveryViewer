package com.tabpdf.tabs

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val copy = File(opened.uri.path!!)
        assertTrue(copy.path.startsWith(appContext.filesDir.path))
        val original = testContext.assets.open(TestPdfProvider.ASSET).use { it.readBytes() }
        assertArrayEquals(original, copy.readBytes())

        FileAccess.release(appContext, PdfTab("t", null, opened.uri, opened.name, opened.isInternalCopy))
        assertFalse(copy.exists())
    }
}
