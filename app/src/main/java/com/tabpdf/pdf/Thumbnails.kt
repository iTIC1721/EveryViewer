package com.tabpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small page images for the tab overview (기획서 4.2). Kept in memory and in the cache dir; both can
 * be dropped at any time and are rebuilt by rendering the page again.
 */
object Thumbnails {
    private const val WIDTH_PX = 360
    private const val DIR = "thumbs"

    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Opening a PDF is the expensive part; don't open many at once while the grid fills in. */
    private val renders = Semaphore(2)

    /** Thumbnail of [page] of the file at [uri], or null if the file can't be read. */
    suspend fun load(context: Context, tabId: String, uri: Uri, page: Int): Bitmap? {
        // The URI is part of the key so a tab pointed at another file ("다시 찾기") gets a new image.
        val key = "$tabId-${uri.toString().hashCode().toUInt()}-$page"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, "$key.png")
            val bitmap = file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                ?: render(context, uri, page)?.also { rendered ->
                    // One file per tab: drop thumbnails of pages it no longer shows.
                    dir.listFiles { f -> f.name.startsWith("$tabId-") }?.forEach { it.delete() }
                    file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            bitmap?.also { memory.put(key, it) }
        }
    }

    private suspend fun render(context: Context, uri: Uri, page: Int): Bitmap? = renders.withPermit {
        try {
            val document = PdfDocument.open(context, uri)
            try {
                document.renderPage(page.coerceIn(0, document.pageCount - 1), WIDTH_PX)
            } finally {
                document.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
