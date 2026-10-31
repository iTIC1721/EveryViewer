package com.tabpdf.doc

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.tabpdf.tabs.FileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * An image file as a one-page document (기획서 5.7). Decoded again at the width the viewer asks
 * for (the viewer caps it), so a 50-megapixel photo never sits in memory at full size.
 */
class ImageDocument private constructor(
    /** What every read uses: the tab's own file, or a copy made when it was opened ([TEMP_DIR]). */
    private val file: File,
    private val temporary: Boolean,
    /** Size as stored in the file, before [orientation] is applied. */
    private val storedWidth: Int,
    private val storedHeight: Int,
    private val orientation: Int,
) : Document {
    private val swapsSides = orientation in setOf(
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
    )

    override val pageSizes = listOf(
        if (swapsSides) PageSize(storedHeight.toFloat(), storedWidth.toFloat())
        else PageSize(storedWidth.toFloat(), storedHeight.toFloat())
    )

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val shownWidth = if (swapsSides) storedHeight else storedWidth
        // Largest power-of-two reduction that still leaves at least widthPx.
        var sample = 1
        while (shownWidth / (sample * 2) >= widthPx) sample *= 2
        // Rounding down to a power of two can leave up to 4× the pixels asked for, and Android
        // won't draw a bitmap over 100 MB (the app would crash, again on every start at that zoom).
        while (storedWidth.toLong() * storedHeight / (sample.toLong() * sample) > MAX_DECODED_PIXELS) sample *= 2
        val decoded = try {
            Pictures.decode(file, Size(storedWidth, storedHeight), sample)
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("EveryViewer", "No memory for image at 1/$sample", e)
            null
        } ?: return@withContext null
        onPaper(oriented(decoded))
    }

    /** Transparent parts show white, like the paper behind every other format. */
    private fun onPaper(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val paper = try {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return bitmap
        }
        paper.eraseColor(android.graphics.Color.WHITE)
        android.graphics.Canvas(paper).drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        return paper
    }

    private fun oriented(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (temporary) file.delete()
        Unit
    }

    companion object {
        /** Most pixels an image is decoded at (64 MB): twice what the viewer asks for at most. */
        const val MAX_DECODED_PIXELS = 16_000_000L

        /**
         * Under the cache folder: copies of images from other apps, for as long as they are open.
         * Ones left by an app that ended meanwhile are deleted at the next start (TabsViewModel).
         */
        const val TEMP_DIR = "images"

        suspend fun open(context: Context, uri: Uri): ImageDocument = withContext(Dispatchers.IO) {
            // Another app's provider can give other bytes each time the file is read: a picture
            // checked as a small PNG could be decoded as something else, or far larger. So its
            // bytes are copied once, and the format check, the size and every decoding use that copy.
            val temporary = uri.scheme != ContentResolver.SCHEME_FILE
            val file = if (temporary) {
                File(File(context.cacheDir, TEMP_DIR).apply { mkdirs() }, UUID.randomUUID().toString()).also {
                    FileAccess.copy(context, uri, it)
                }
            } else {
                File(requireNotNull(uri.path))
            }
            try {
                val head = file.inputStream().use { it.readUpTo(FileFormats.HEAD_SIZE) }
                // Only the formats the app shows reach Android's decoder (FileFormats.isImage).
                if (!FileFormats.isImage(head)) throw DocumentOpenException(DocumentOpenException.Reason.Unsupported)
                // Null if not an image this device can decode (e.g. HEIF before Android 9) or a broken file.
                val size = Pictures.size(file) ?: throw IOException("Can't decode image")
                val orientation = try {
                    ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }
                ImageDocument(file, temporary, size.width, size.height, orientation)
            } catch (e: Throwable) {
                if (temporary) file.delete()
                throw e
            }
        }
    }
}
