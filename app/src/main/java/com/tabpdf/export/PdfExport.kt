package com.tabpdf.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tabpdf.doc.BundleDocument
import com.tabpdf.doc.Document
import com.tabpdf.doc.awaitComplete
import com.tabpdf.ink.InkPainter
import com.tabpdf.ink.InkStroke
import com.tabpdf.pdf.PdfDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * A tab with its handwriting as one PDF file (기획서 5.9), made the way that keeps the most:
 * - a PDF: the original file with the strokes added after it ([PdfAppend]), so its text, links and
 *   bookmarks stay; where its structure can't be followed, its pages as pictures;
 * - laid-out formats (text, Markdown, HTML, e-books, Word, Hangul, slides, sheets): drawn as text
 *   and shapes with Android's PDF writer, so the text can be found and copied in other apps;
 * - images and bundles: pages as JPEG pictures ([JpegPdfWriter]).
 * The strokes are always shapes over the page. A PDF opened with a password comes out without one.
 */
object PdfExport {
    enum class Method { Appended, Drawn, Pictures }

    /** Print resolution for pages written as pictures. */
    private const val PICTURE_DPI = 200f
    private const val MAX_PICTURE_SIDE = 4000
    private const val JPEG_QUALITY = 85

    /** Under the cache folder, outside the one shared with other apps: the source while it is exported. */
    const val WORK_DIR = "export-work"

    /**
     * Writes [document] (opened from [uri]) with [strokes] to [target]. [onProgress] gets pages
     * done and the total. Returns how it was made. On failure or cancellation [target] is deleted.
     */
    suspend fun export(
        context: Context,
        uri: Uri,
        document: Document,
        strokes: List<InkStroke>,
        target: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Method = withContext(Dispatchers.IO) {
        try {
            document.awaitComplete()
            val byPage = strokes.groupBy { it.page }
            when {
                document is PdfDocument -> appended(context, uri, document, byPage, target, onProgress)
                    ?: pictures(document, byPage, target, onProgress)
                document !is BundleDocument && drawn(document, byPage, target, onProgress) -> Method.Drawn
                else -> pictures(document, byPage, target, onProgress)
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    /** The PDF with the strokes added after it, or null if its structure can't be followed. */
    private suspend fun appended(
        context: Context,
        uri: Uri,
        document: PdfDocument,
        strokes: Map<Int, List<InkStroke>>,
        target: File,
        onProgress: (Int, Int) -> Unit,
    ): Method? {
        // Not in the shared export folder: a locked PDF's copy here has no password (writeUnlocked).
        val work = File(File(context.cacheDir, WORK_DIR).apply { mkdirs() }, "source.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> work.outputStream().use { input.copyTo(it) } }
                ?: throw FileNotFoundException()
            coroutineContext.ensureActive()
            val targets = strokes.mapNotNull { (page, list) ->
                if (page !in 0 until document.pageCount) null
                else PdfAppend.Target(page, PdfInk(list), document.pageSizes[page].width)
            }
            fun append() {
                target.outputStream().buffered().use { PdfAppend.write(work, document.pageCount, targets, it) }
            }
            try {
                append()
            } catch (e: PdfSyntaxException) {
                // Encrypted (a password, or only limits on printing and copying): Android writes it
                // without the encryption, and the strokes go after that copy.
                if (e.message != "Encrypted") throw e
                ParcelFileDescriptor.open(work, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use { fd ->
                    if (!document.writeUnlocked(fd)) throw e
                }
                append()
            }
            check(context, target, document.pageCount)
            onProgress(document.pageCount, document.pageCount)
            return Method.Appended
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the kind: messages may hold the file's address (and so its name).
            android.util.Log.w("EveryViewer", "PDF not appended to (${e.javaClass.simpleName}); exporting pictures")
            target.delete()
            return null
        } catch (e: OutOfMemoryError) {
            // A crafted structure (huge or endlessly nested): the pages as pictures still work.
            target.delete()
            return null
        } catch (e: StackOverflowError) {
            target.delete()
            return null
        } finally {
            work.delete()
        }
    }

    /** Android's own reading of the result: it must open with every page, or it isn't kept. */
    private suspend fun check(context: Context, file: File, pages: Int) {
        val read = PdfDocument.pageCount(context, file)
        if (read != pages) throw IOException("Wrote $read pages, expected $pages")
    }

    /** Text and shapes with Android's PDF writer; false (nothing written) if a page only comes as a picture. */
    private suspend fun drawn(
        document: Document,
        strokes: Map<Int, List<InkStroke>>,
        target: File,
        onProgress: (Int, Int) -> Unit,
    ): Boolean {
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            for (i in 0 until document.pageCount) {
                coroutineContext.ensureActive()
                val size = document.pageSizes[i]
                val width = document.paperWidth(i)
                val height = width * size.aspect
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1), i + 1).create())
                val canvas = page.canvas
                canvas.save()
                canvas.scale(width / size.width, width / size.width)
                val ok = document.drawPage(i, canvas)
                canvas.restore()
                if (!ok) {
                    pdf.finishPage(page)
                    return false
                }
                strokes[i]?.let { InkPainter.draw(canvas, it, width) }
                pdf.finishPage(page)
                onProgress(i + 1, document.pageCount)
            }
            target.outputStream().buffered().use { pdf.writeTo(it) }
            return true
        } finally {
            pdf.close()
        }
    }

    /** Each page as a JPEG picture with the strokes over it. */
    private suspend fun pictures(
        document: Document,
        strokes: Map<Int, List<InkStroke>>,
        target: File,
        onProgress: (Int, Int) -> Unit,
    ): Method {
        target.outputStream().buffered().use { out ->
            val writer = JpegPdfWriter(out)
            for (i in 0 until document.pageCount) {
                coroutineContext.ensureActive()
                val size = document.pageSizes[i]
                val width = document.paperWidth(i)
                val height = width * size.aspect
                val px = (width * PICTURE_DPI / 72f).roundToInt()
                    .coerceAtMost((MAX_PICTURE_SIDE / maxOf(1f, size.aspect)).roundToInt())
                    .coerceIn(1, MAX_PICTURE_SIDE)
                val bitmap = document.renderPage(i, px) ?: throw IOException("Page ${i + 1} could not be drawn")
                val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                writer.addPage(width, height, jpeg, bitmap.width, bitmap.height, strokes[i]?.let { PdfInk(it) })
                bitmap.recycle()
                onProgress(i + 1, document.pageCount)
            }
            writer.finish()
        }
        return Method.Pictures
    }
}
