package com.tabpdf.doc

import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.pdf.PdfDocument
import com.tabpdf.pdf.PdfPasswords
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Locked PDFs (기획서 5.5): locked.pdf (made with PyMuPDF, AES-256) has two A4 pages saying
 * "Locked page N secret text" and opens with the user password 1234.
 */
@RunWith(AndroidJUnit4::class)
class PdfPasswordTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = File(instrumentation.targetContext.cacheDir, "locked.pdf").apply {
        instrumentation.context.assets.open("locked.pdf").use { input -> outputStream().use { input.copyTo(it) } }
    }
    private val uri = Uri.fromFile(file)

    @After
    fun tearDown() {
        PdfPasswords.remove(uri)
        file.delete()
    }

    private fun failure(): DocumentOpenException.Reason? = runBlocking {
        runCatching { PdfDocument.open(instrumentation.targetContext, uri) }.exceptionOrNull()
            .let { (it as? DocumentOpenException)?.reason }
    }

    @Test
    fun opensWithItsPasswordWhereThePlatformTakesOne() = runBlocking {
        assertEquals("asks for a password", DocumentOpenException.Reason.PasswordProtected, failure())
        if (!PdfDocument.takesPasswords) {
            // Older devices: a typed password isn't even tried.
            PdfPasswords[uri] = "1234"
            assertEquals(DocumentOpenException.Reason.PasswordProtected, failure())
            return@runBlocking
        }

        PdfPasswords[uri] = "wrong"
        assertEquals(DocumentOpenException.Reason.WrongPassword, failure())
        assertNull("a wrong password is forgotten", PdfPasswords[uri])

        PdfPasswords[uri] = "1234"
        val doc = PdfDocument.open(instrumentation.targetContext, uri)
        assertTrue(doc.locked)
        assertEquals(2, doc.pageCount)
        assertEquals(842f / 595f, doc.pageSizes[0].aspect, 0.01f)
        val page = doc.renderPage(0, 300)
        assertNotNull(page)
        // Text was drawn: some pixel isn't white.
        val pixels = IntArray(page!!.width * page.height).also { page.getPixels(it, 0, page.width, 0, 0, page.width, page.height) }
        assertTrue("the page has its text", pixels.any { it != Color.WHITE })
        // Search and selection use the same password (Android 12–14 use the newer renderer for all).
        assertEquals(listOf(1), doc.search("Locked page 2").map { it.page })
        doc.close()
    }

    @Test
    fun unlockedFilesAreNotLocked() = runBlocking {
        // owner-only.pdf is encrypted with only an owner password (permissions), as many shared PDFs are.
        for (name in listOf("sample.pdf", "owner-only.pdf")) {
            val plain = File(instrumentation.targetContext.cacheDir, name)
            instrumentation.context.assets.open(name).use { input -> plain.outputStream().use { input.copyTo(it) } }
            val doc = PdfDocument.open(instrumentation.targetContext, Uri.fromFile(plain))
            assertFalse(name, doc.locked)
            assertNotNull(name, doc.renderPage(0, 200))
            doc.close()
            plain.delete()
        }
    }
}
