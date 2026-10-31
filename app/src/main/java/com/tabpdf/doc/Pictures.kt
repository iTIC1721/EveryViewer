package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import com.tabpdf.pdf.RenderProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap

/**
 * Android's image decoders for pictures from files (an image file, or one inside a document), in
 * the isolated render process ([RenderProcess]) like PDFs: a picture that takes over a decoder there
 * finds nothing of the app's. Only formats the app shows get here ([FileFormats.isImage]); on Android
 * 8.0 they are decoded in the app as before.
 *
 * HEIF is the exception: Android decodes it through its media service ([FileFormats.isHeif]), which an
 * isolated process can't reach. The file is read and decoded there, in the system's own sandboxed
 * media processes, and only the pixels come back; so it is asked for from the app.
 *
 * The plain functions block: they are called while laying out and drawing pages, never on the main
 * thread. A picture that failed once (or ended the process) isn't sent again.
 */
object Pictures {
    private val failed: MutableSet<ByteArray> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val renderProcess: Boolean get() = RenderProcess.available && RenderProcess.app != null

    private fun isolated(head: ByteArray): Boolean = renderProcess && !FileFormats.isHeif(head)

    private fun head(file: File): ByteArray = file.inputStream().use { it.readUpTo(FileFormats.HEAD_SIZE) }

    /** [bytes]' picture size as stored, or null if it can't be read. */
    fun size(bytes: ByteArray): Size? {
        if (!FileFormats.isImage(bytes) || bytes in failed) return null
        if (!isolated(bytes)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return if (bounds.outWidth > 0 && bounds.outHeight > 0) Size(bounds.outWidth, bounds.outHeight) else null
        }
        return remotely(bytes) { RenderProcess.pictureSize(it, RenderProcess.Source.Bytes(bytes)) }
    }

    /** [bytes]' picture, of [size] ([Pictures.size]), decoded at 1/[sample]; null if it can't be. */
    fun decode(bytes: ByteArray, size: Size, sample: Int): Bitmap? {
        if (!FileFormats.isImage(bytes) || bytes in failed) return null
        if (!isolated(bytes)) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        return remotely(bytes) { RenderProcess.picture(it, RenderProcess.Source.Bytes(bytes), size, sample) }
    }

    /** As [size], for an image file the app owns (a copy: what the format was checked on, [FileFormats.isImage]). */
    suspend fun size(file: File): Size? {
        if (!isolated(head(file))) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            return if (bounds.outWidth > 0 && bounds.outHeight > 0) Size(bounds.outWidth, bounds.outHeight) else null
        }
        return attempt { RenderProcess.pictureSize(it, RenderProcess.Source.Path(file)) }
    }

    /** As [decode], for an image file the app owns. */
    suspend fun decode(file: File, size: Size, sample: Int): Bitmap? {
        if (!isolated(head(file))) return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        return attempt { RenderProcess.picture(it, RenderProcess.Source.Path(file), size, sample) }
    }

    private fun <T> remotely(bytes: ByteArray, block: suspend (android.content.Context) -> T?): T? =
        runBlocking { attempt(block) }.also { if (it == null) failed += bytes }

    private suspend fun <T> attempt(block: suspend (android.content.Context) -> T?): T? {
        if (Build.VERSION.SDK_INT < 27) return null
        val app = RenderProcess.app ?: return null
        return try {
            block(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            android.util.Log.w("EveryViewer", "Picture not decoded: ${e.javaClass.simpleName}")
            null
        }
    }
}
