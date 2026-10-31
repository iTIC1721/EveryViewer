package com.tabpdf.pdf

import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PDF pages drawn in the isolated render process (RenderService): the same pixels as drawing in the
 * app, pages still drawn after that process ends, and what it costs (logged as "RenderMeasure").
 */
@RunWith(AndroidJUnit4::class)
class RenderProcessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var file: File

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= 27)
        file = File(context.cacheDir, "render-test.pdf")
        writeBusyPdf(file, PAGES)
    }

    @After
    fun tearDown() {
        RenderProcess.enabled = true
        if (::file.isInitialized) file.delete()
    }

    @Test
    fun pagesMatchDrawingInTheApp() = runBlocking {
        RenderProcess.enabled = false
        val local = PdfDocument.open(context, Uri.fromFile(file))
        RenderProcess.enabled = true
        val remote = PdfDocument.open(context, Uri.fromFile(file))
        assertEquals(local.pageSizes, remote.pageSizes)
        for (index in 0 until 3) {
            val a = local.renderPage(index, WIDTH_PX)!!
            val b = remote.renderPage(index, WIDTH_PX)!!
            assertTrue("page $index", a.sameAs(b))
        }
        local.close()
        remote.close()
    }

    @Test
    fun textAndPagesReadTheSameAsInTheApp() = runBlocking {
        val uri = Uri.fromFile(file)
        RenderProcess.enabled = false
        val local = PdfDocument.open(context, uri)
        val localCount = PdfDocument.pageCount(context, file)
        RenderProcess.enabled = true
        val remote = PdfDocument.open(context, uri)
        assertEquals(PAGES, PdfDocument.pageCount(context, file))
        assertEquals(PAGES, localCount)
        // Text support only on Android 12+ with the system update; elsewhere both find nothing.
        val found = remote.search("fox")
        assertEquals(local.search("fox").map { it.page to it.rects }, found.map { it.page to it.rects })
        val from = android.graphics.PointF(0.05f, 0.04f)
        val to = android.graphics.PointF(0.6f, 0.1f)
        val selected = remote.selectText(0, from, to)?.text
        assertEquals(local.selectText(0, from, to)?.text, selected)
        if (PdfEngine.textSupport) {
            assertEquals("one match a line", minOf(PAGES * 70, com.tabpdf.doc.TextSearch.MAX_MATCHES), found.size)
            assertTrue(selected.orEmpty().contains("quick brown fox"))
        }
        assertEquals(local.link(0, 0.5f, 0.5f), remote.link(0, 0.5f, 0.5f))
        local.close()
        remote.close()
    }

    @Test
    fun pagesAreStillDrawnAfterTheProcessEnds() = runBlocking {
        val doc = PdfDocument.open(context, Uri.fromFile(file))
        assertNotNull(doc.renderPage(0, WIDTH_PX))
        RenderProcess.restart()
        assertNotNull("opened again in a new process", doc.renderPage(1, WIDTH_PX))
        doc.close()
    }

    @Test
    fun notAPdfFailsToOpen() = runBlocking {
        file.writeText("not a pdf")
        val failed = try {
            PdfDocument.open(context, Uri.fromFile(file)).close()
            false
        } catch (e: Exception) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun measure() = runBlocking {
        val uri = Uri.fromFile(file)
        RenderProcess.restart()
        var t = SystemClock.elapsedRealtime()
        val cold = PdfDocument.open(context, uri)
        val coldOpen = SystemClock.elapsedRealtime() - t
        t = SystemClock.elapsedRealtime()
        val warm = PdfDocument.open(context, uri)
        val warmOpen = SystemClock.elapsedRealtime() - t
        warm.close()
        RenderProcess.enabled = false
        t = SystemClock.elapsedRealtime()
        val local = PdfDocument.open(context, uri)
        val localOpen = SystemClock.elapsedRealtime() - t
        RenderProcess.enabled = true

        // Once each first so neither side pays for warming up.
        cold.renderPage(0, WIDTH_PX)
        local.renderPage(0, WIDTH_PX)
        t = SystemClock.elapsedRealtime()
        for (i in 0 until PAGES) cold.renderPage(i, WIDTH_PX)!!.recycle()
        val remotePages = SystemClock.elapsedRealtime() - t
        t = SystemClock.elapsedRealtime()
        for (i in 0 until PAGES) local.renderPage(i, WIDTH_PX)!!.recycle()
        val localPages = SystemClock.elapsedRealtime() - t

        // PSS of each of the app's processes, in kB.
        val memory = shell("ps -A -o PID,NAME").lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 2 && it[1].startsWith(context.packageName) && !it[1].endsWith(".test") }
            .joinToString { (pid, name) ->
                val total = shell("dumpsys meminfo $pid").lineSequence().map { it.trim() }
                    .firstOrNull { it.startsWith("TOTAL PSS:") || it.startsWith("TOTAL ") }
                    ?.split(Regex("\\s+"))?.firstOrNull { it.all(Char::isDigit) }
                "$name $total kB"
            }
        android.util.Log.i(
            "RenderMeasure",
            "open cold ${coldOpen}ms, warm ${warmOpen}ms, in app ${localOpen}ms; " +
                "$PAGES pages at ${WIDTH_PX}px: process ${remotePages}ms (${remotePages / PAGES}/page), " +
                "in app ${localPages}ms (${localPages / PAGES}/page); render process memory: $memory",
        )
        cold.close()
        local.close()
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    /** Pages full of text and lines, about as heavy to draw as a page of a report. */
    private fun writeBusyPdf(target: File, pages: Int) {
        val pdf = android.graphics.pdf.PdfDocument()
        val text = Paint().apply { textSize = 9f; color = Color.BLACK }
        val line = Paint().apply { strokeWidth = 0.5f; color = Color.BLUE; style = Paint.Style.STROKE }
        for (p in 0 until pages) {
            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, p + 1).create())
            val canvas = page.canvas
            for (row in 0 until 70) {
                canvas.drawText("Page ${p + 1} line $row — The quick brown fox jumps over the lazy dog 0123456789", 40f, 40f + row * 11f, text)
            }
            for (k in 0 until 200) canvas.drawCircle(300f + (k % 20) * 12f, 600f + (k / 20) * 12f, 5f, line)
            pdf.finishPage(page)
        }
        target.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    private companion object {
        const val PAGES = 30
        const val WIDTH_PX = 1440
    }
}
