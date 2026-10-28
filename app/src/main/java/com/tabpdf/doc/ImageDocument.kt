package com.tabpdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * An image file as a one-page document (기획서 5.7). Decoded again at the width the viewer asks
 * for (the viewer caps it), so a 50-megapixel photo never sits in memory at full size.
 */
class ImageDocument private constructor(
    private val context: Context,
    private val uri: Uri,
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
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("EveryViewer", "No memory for image at 1/$sample", e)
            null
        } catch (e: IOException) {
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

    override suspend fun close() = Unit

    companion object {
        /** Most pixels an image is decoded at (64 MB): twice what the viewer asks for at most. */
        const val MAX_DECODED_PIXELS = 16_000_000L

        suspend fun open(context: Context, uri: Uri): ImageDocument = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Decoding just the bounds always returns null; a missing stream is what means "not found".
            val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                // Not an image this device can decode (e.g. HEIF before Android 9) or a broken file.
                throw IOException("Can't decode image $uri")
            }
            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (_: Exception) {
                null
            } ?: ExifInterface.ORIENTATION_NORMAL
            ImageDocument(context.applicationContext, uri, bounds.outWidth, bounds.outHeight, orientation)
        }
    }
}
