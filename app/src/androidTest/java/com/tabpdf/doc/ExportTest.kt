package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.export.PdfExport
import com.tabpdf.ink.Brushes
import com.tabpdf.ink.InkStroke
import com.tabpdf.ink.InkTool
import com.tabpdf.ink.ToolStyle
import com.tabpdf.pdf.PdfDocument
import com.tabpdf.pdf.PdfPasswords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PDF export (기획서 5.9): a red stroke across ink coordinates (200–300, 300) on every page must come
 * out where the viewer shows it, for each way of writing the file. Assets: turned.pdf (pages turned
 * 90° with a crop box, 270°, and plain with a crop box), packed.pdf (cross-reference and object
 * streams), locked.pdf (password 1234), owner-only.pdf (encrypted without a password).
 */
@RunWith(AndroidJUnit4::class)
class ExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "export-test").apply { deleteRecursively(); mkdirs() }

    private fun asset(name: String) = File(dir, name).apply {
        instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }

    private fun stroke(page: Int, color: Int = 0xFFFF0000.toInt()): InkStroke {
        val inputs = MutableStrokeInputBatch()
        for (i in 0..20) inputs.add(StrokeInput.create(200f + i * 5f, 300f, i * 10L, InputToolType.STYLUS, pressure = 0.8f))
        return InkStroke("s$page", page, page.toLong(), InkTool.Pen, Stroke(Brushes.forTool(InkTool.Pen, ToolStyle(color, 10f)), inputs.toImmutable()))
    }

    private fun export(doc: Document, uri: Uri, strokes: List<InkStroke>): Pair<File, PdfExport.Method> = runBlocking {
        val out = File(dir, "out.pdf")
        val method = PdfExport.export(context, uri, doc, strokes, out) { _, _ -> }
        out to method
    }

    /** Page [index] of [file] drawn [width] pixels wide. */
    private fun render(file: File, index: Int, width: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(index).use { p ->
                    val bmp = Bitmap.createBitmap(width, (width.toFloat() * p.height / p.width).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

    private fun pageCount(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }

    private fun isRed(c: Int) = Color.red(c) > 180 && Color.green(c) < 90 && Color.blue(c) < 90

    /** The stroke is drawn at ink (250, 300) and not far off it (1000 pixels = the page width). */
    private fun assertStrokeWhereShown(bmp: Bitmap, what: String) {
        assertEquals(1000, bmp.width)
        assertTrue("$what: stroke at its place", isRed(bmp.getPixel(250, 300)))
        assertTrue("$what: stroke ends near 300", isRed(bmp.getPixel(290, 300)) && !isRed(bmp.getPixel(330, 300)))
        assertTrue("$what: nothing below", !isRed(bmp.getPixel(250, 340)))
    }

    @Test
    fun pdfKeepsItsPagesAndGetsTheStrokesWhereTheyShow() = runBlocking {
        for (name in listOf("turned.pdf", "packed.pdf", "sample.pdf", "links.pdf")) {
            val file = asset(name)
            val doc = PdfDocument.open(context, Uri.fromFile(file))
            val (out, method) = export(doc, Uri.fromFile(file), (0 until doc.pageCount).map { stroke(it) })
            assertEquals(name, PdfExport.Method.Appended, method)
            assertEquals(name, doc.pageCount, pageCount(out))
            assertTrue("$name: the original is kept whole", out.length() > file.length())
            for (i in 0 until doc.pageCount) assertStrokeWhereShown(render(out, i, 1000), "$name page ${i + 1}")
            doc.close()
        }
        // The text is still text.
        if (Build.VERSION.SDK_INT >= 35) {
            val file = asset("turned.pdf")
            val doc = PdfDocument.open(context, Uri.fromFile(file))
            val (out, _) = export(doc, Uri.fromFile(file), listOf(stroke(0)))
            doc.close()
            val again = PdfDocument.open(context, Uri.fromFile(File(dir, "copy.pdf").also { out.copyTo(it, true) }))
            assertEquals(listOf(1), again.search("Turned page 2").map { it.page })
            again.close()
        }
    }

    @Test
    fun passwordPdfsComeOutWithoutItLimitedOnesKeepTheirLimits() = runBlocking {
        if (PdfDocument.takesPasswords) {
            val locked = asset("locked.pdf")
            val uri = Uri.fromFile(locked)
            PdfPasswords[uri] = "1234"
            val opened = PdfDocument.open(context, uri)
            val (lockedOut, lockedMethod) = export(opened, uri, listOf(stroke(1)))
            assertEquals(PdfExport.Method.Appended, lockedMethod)
            // Opens with no password.
            assertEquals(2, pageCount(lockedOut))
            assertStrokeWhereShown(render(lockedOut, 1, 1000), "locked")
            opened.close()
            PdfPasswords.remove(uri)
        }
        // Encrypted only to limit printing and copying (AES-128, RC4 and AES-256, the last two with
        // pages in object streams): the strokes are encrypted alike and the limits stay.
        for (name in listOf("owner-only.pdf", "owner-rc4.pdf", "owner-aes256.pdf")) {
            val owner = asset(name)
            val doc = PdfDocument.open(context, Uri.fromFile(owner))
            val (out, method) = export(doc, Uri.fromFile(owner), (0 until doc.pageCount).map { stroke(it) })
            assertEquals(name, PdfExport.Method.Appended, method)
            assertTrue("$name is still encrypted", String(out.readBytes(), Charsets.ISO_8859_1).contains("/Encrypt"))
            for (i in 0 until doc.pageCount) assertStrokeWhereShown(render(out, i, 1000), "$name page ${i + 1}")
            doc.close()
        }
    }

    @Test
    fun laidOutFormatsAreWrittenAsText() = runBlocking {
        val text = File(dir, "note.txt").apply { writeText((1..300).joinToString("\n") { "내보내기 시험 줄 $it" }) }
        val doc = Documents.open(context, Uri.fromFile(text), "note.txt", FileFormat.Text)
        doc.awaitComplete()
        val (out, method) = export(doc, Uri.fromFile(text), listOf(stroke(0)))
        assertEquals(PdfExport.Method.Drawn, method)
        assertEquals(doc.pageCount, pageCount(out))
        assertStrokeWhereShown(render(out, 0, 1000), "text")
        if (Build.VERSION.SDK_INT >= 35) {
            val back = PdfDocument.open(context, Uri.fromFile(out))
            assertTrue("text can be found in the PDF", back.search("시험 줄 250").isNotEmpty())
            back.close()
        }
        doc.close()
    }

    @Test
    fun imagesBecomePictures() = runBlocking {
        val png = File(dir, "photo.png")
        Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 220, 255)) }
            .let { b -> png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val doc = Documents.open(context, Uri.fromFile(png), "photo.png", FileFormat.Image)
        val (out, method) = export(doc, Uri.fromFile(png), listOf(stroke(0)))
        assertEquals(PdfExport.Method.Pictures, method)
        assertEquals(1, pageCount(out))
        val page = render(out, 0, 1000)
        assertStrokeWhereShown(page, "image")
        val c = page.getPixel(600, 500)
        assertTrue("the picture is there", Color.blue(c) > 230 && Color.red(c) in 180..220)
        doc.close()
    }
}
