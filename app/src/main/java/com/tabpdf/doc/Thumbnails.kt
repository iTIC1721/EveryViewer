package com.tabpdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.tabpdf.ink.InkPainter
import com.tabpdf.ink.InkStroke
import com.tabpdf.tabs.DocTab
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

    /** Opening a file is the expensive part; don't open many at once while the grid fills in. */
    private val renders = Semaphore(2)

    /**
     * Thumbnail of [page] of [tab]'s file with the tab's handwriting on it (기획서 5.8.5), or null if
     * the file can't be read. Only the page itself is cached; the strokes are drawn over a copy.
     */
    suspend fun load(context: Context, tab: DocTab, page: Int, strokes: List<InkStroke>): Bitmap? {
        val base = loadPage(context, tab, page) ?: return null
        val onPage = strokes.filter { it.page == page }
        if (onPage.isEmpty()) return base
        return withContext(Dispatchers.Default) {
            base.copy(Bitmap.Config.ARGB_8888, true)?.also { InkPainter.draw(Canvas(it), onPage, it.width.toFloat()) } ?: base
        }
    }

    private suspend fun loadPage(context: Context, tab: DocTab, page: Int): Bitmap? {
        val tabId = tab.id
        val uri = tab.uri
        // The URI is part of the key so a tab pointed at another file ("다시 찾기") gets a new image.
        val key = "$tabId-${uri.toString().hashCode().toUInt()}-$page"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, "$key.png")
            val bitmap = file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                ?: render(context, tab, page)?.let { (rendered, locked) ->
                    // One file per tab: drop thumbnails of pages it no longer shows.
                    dir.listFiles { f -> f.name.startsWith("$tabId-") }?.forEach { it.delete() }
                    // A file opened with a password stays off the disk, its pictures too.
                    if (!locked) file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    rendered
                }
            bitmap?.also { memory.put(key, it) }
        }
    }

    /** The page, and whether its file was opened with a password. */
    private suspend fun render(context: Context, tab: DocTab, page: Int): Pair<Bitmap, Boolean>? = renders.withPermit {
        try {
            val document = Documents.open(context, tab.uri, tab.name, tab.format)
            try {
                document.awaitPages(page + 1)
                document.renderPage(page.coerceIn(0, document.pageCount - 1), WIDTH_PX)?.let { it to document.locked }
            } finally {
                document.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            // Drawing a crafted page, not only opening it, can run out (Documents.open covers only that).
            null
        } catch (e: StackOverflowError) {
            null
        }
    }
}
