package com.tabpdf.doc

import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.pdf.PdfDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PDF links (기획서 5.7): links.pdf (612 × 792 pt) has a web link, a file: link and a link to y = 142
 * from the top of page 3, as boxes on page 1 at y 62–102, 212–252 and 512–552 from the top.
 */
@RunWith(AndroidJUnit4::class)
class PdfLinkTest {
    @Test
    fun linksWorkWhereThePlatformCanReadThem() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "links.pdf")
        instrumentation.context.assets.open("links.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val doc = PdfDocument.open(instrumentation.targetContext, Uri.fromFile(file))
        fun at(yPt: Float) = runBlocking { doc.link(0, 180f / 612f, yPt / 792f) }
        val canRead = Build.VERSION.SDK_INT >= 35 ||
            Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        if (canRead) {
            assertEquals(Link.Web("https://example.com/"), at(82f))
            assertNull("file: links are not opened", at(232f))
            val goto = at(532f) as Link.Page
            assertEquals(2, goto.page)
            assertEquals(142f / 792f, goto.y!!, 0.01f)
            assertNull("nothing between the links", at(400f))
        } else {
            for (y in listOf(82f, 232f, 532f)) assertNull(at(y))
        }
        doc.close()
        file.delete()
        Unit
    }
}
