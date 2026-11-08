package com.tabpdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.tabpdf.ink.InkPainter
import com.tabpdf.ink.InkStroke
import com.tabpdf.ink.InkText
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.OpenGuard
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Small page images for the tab overview (기획서 4.2). Kept in memory and in the cache dir; both can
 * be dropped at any time and are rebuilt by rendering the page again.
 */
object Thumbnails {
    private const val WIDTH_PX = 360

    /** A tall page is drawn narrower: a thumbnail never needs more pixels than this (4 MB). */
    private const val MAX_PIXELS = 1_000_000f
    private const val DIR = "thumbs"

    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Opening a file is the expensive part; don't open many at once while the grid fills in. */
    private val renders = Semaphore(2)

    /**
     * Thumbnail of [page] of [tab]'s file with the tab's handwriting on it (기획서 5.8.5), or null if
     * the file can't be read; in the document's dark look if [dark] and it has one (기획서 5.10).
     * Only the page itself is cached; the strokes are drawn over a copy.
     */
    suspend fun load(context: Context, tab: DocTab, page: Int, strokes: List<InkStroke>, texts: List<InkText> = emptyList(), dark: Boolean = false): Bitmap? {
        val (base, look) = loadPage(context, tab, page, dark) ?: return null
        val onPage = strokes.filter { it.page == page }
        val textsOnPage = texts.filter { it.page == page }
        if (onPage.isEmpty() && textsOnPage.isEmpty()) return base
        return withContext(Dispatchers.Default) {
            base.copy(Bitmap.Config.ARGB_8888, true)?.also { InkPainter.draw(Canvas(it), onPage, textsOnPage, it.width.toFloat(), dark = look == DARK) } ?: base
        }
    }

    /**
     * Which look a cached page is in, the end of its name: [DARK] or [LIGHT] for a document with a
     * dark look, [PLAIN] (either look) for one without.
     */
    private const val DARK = "dark"
    private const val LIGHT = "light"
    private const val PLAIN = "plain"

    private suspend fun loadPage(context: Context, tab: DocTab, page: Int, dark: Boolean): Pair<Bitmap, String>? {
        val tabId = tab.id
        val uri = tab.uri
        // The URI is part of the key so a tab pointed at another file ("파일 다시 선택") gets a new image.
        val base = "$tabId-${uri.toString().hashCode().toUInt()}-$page"
        val looks = listOf(if (dark) DARK else LIGHT, PLAIN)
        for (look in looks) memory.get("$base-$look")?.let { return it to look }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val cached = looks.firstNotNullOfOrNull { look ->
                File(dir, "$base-$look.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.let { it to look }
            }
            val found = cached ?: render(context, tab, page, dark)?.let { (rendered, locked, look) ->
                // One file per tab: drop thumbnails of pages (or looks) it no longer shows.
                dir.listFiles { f -> f.name.startsWith("$tabId-") }?.forEach { it.delete() }
                // A file opened with a password stays off the disk, its pictures too.
                if (!locked) File(dir, "$base-$look.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                rendered to look
            }
            // One cached by an older version can be larger than Android will draw.
            found?.takeIf { it.first.allocationByteCount <= MAX_DRAWN_BYTES }?.also { memory.put("$base-${it.second}", it.first) }
        }
    }

    /**
     * Drops the thumbnails of closed tabs [tabIds]: pictures of a document the user closed shouldn't
     * stay on the disk until Android clears the cache.
     */
    suspend fun forget(context: Context, tabIds: Collection<String>) = keepOnly(context) { name -> tabIds.none { name.startsWith("$it-") } }

    /** Drops thumbnails of tabs not in [tabIds] (closed while the app was ending, before [forget] ran). */
    suspend fun keepOnly(context: Context, tabIds: Collection<String>) = keepOnly(context) { name -> tabIds.any { name.startsWith("$it-") } }

    private suspend fun keepOnly(context: Context, keep: (String) -> Boolean) = withContext(Dispatchers.IO) {
        memory.snapshot().keys.filterNot(keep).forEach { memory.remove(it) }
        File(context.cacheDir, DIR).listFiles()?.forEach { if (!keep(it.name)) it.delete() }
    }

    /** The page, whether its file was opened with a password, and the look it is in. */
    private suspend fun render(context: Context, tab: DocTab, page: Int, dark: Boolean): Triple<Bitmap, Boolean, String>? = renders.withPermit {
        // Not a file that ended the app while opening last time: it waits for the reader. This
        // opening is marked as well, since a crash here would come back with each overview (OpenGuard).
        if (tab.id in OpenGuard.held.value) return@withPermit null
        OpenGuard.begin(context, tab.id)
        try {
            val document = Documents.open(context, tab.uri, tab.name, tab.format)
            try {
                document.awaitPages(page + 1)
                val index = page.coerceIn(0, document.pageCount - 1)
                val width = minOf(WIDTH_PX.toFloat(), sqrt(MAX_PIXELS / document.pageSizes[index].aspect)).roundToInt().coerceAtLeast(1)
                val look = if (!document.hasDarkLook) PLAIN else if (dark) DARK else LIGHT
                document.dark = dark
                document.renderPage(index, width)?.let { Triple(it, document.locked, look) }
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
        } finally {
            withContext(NonCancellable) { OpenGuard.end(context, tab.id) }
        }
    }
}
