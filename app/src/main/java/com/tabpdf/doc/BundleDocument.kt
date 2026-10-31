package com.tabpdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** One file in a [bundle][BundleDocument]: its own access, like a tab's (기획서 5.1). */
data class BundleItem(
    /** Stable across edits, so handwriting can follow the file when the order changes. */
    val id: String,
    val uri: Uri,
    val name: String,
    val format: FileFormat,
    val isInternalCopy: Boolean,
)

/**
 * The list of files a bundle tab shows, kept in a small file of its own in app storage; the tab
 * points at that file (기획서 5.1: 파일 묶음). Nothing else about the tab changes, so the session's
 * storage needs no new tables.
 */
object Bundles {
    private const val DIR = "bundles"
    private const val VERSION = 1

    fun dir(context: Context) = File(context.filesDir, DIR)

    fun read(file: File): List<BundleItem> {
        val json = try {
            JSONObject(file.readText())
        } catch (e: org.json.JSONException) {
            throw IOException("Broken bundle", e)
        }
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            BundleItem(
                id = item.getString("id"),
                uri = Uri.parse(item.getString("uri")),
                name = item.getString("name"),
                format = FileFormat.fromKey(item.getString("format")),
                isInternalCopy = item.optBoolean("copy"),
            )
        }
    }

    fun write(file: File, items: List<BundleItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("uri", item.uri.toString())
                    .put("name", item.name)
                    .put("format", item.format.key)
                    .put("copy", item.isInternalCopy)
            )
        }
        // Written aside and moved into place, so a crash never leaves half a list.
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JSONObject().put("version", VERSION).put("items", array).toString())
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Could not save bundle")
        }
    }

    /** A new bundle file listing [items]; returns its location. */
    fun create(context: Context, items: List<BundleItem>): Uri {
        val file = File(dir(context).apply { mkdirs() }, "${java.util.UUID.randomUUID()}.json")
        write(file, items)
        return Uri.fromFile(file)
    }

    fun read(uri: Uri): List<BundleItem> = read(file(uri))

    fun write(uri: Uri, items: List<BundleItem>) = write(file(uri), items)

    /**
     * The bundle file at [uri], which must be one of the app's own lists: the address comes from
     * the saved session, and a damaged or tampered one must not make the app write elsewhere.
     */
    private fun file(uri: Uri): File {
        val file = File(requireNotNull(uri.path)).canonicalFile
        if (uri.scheme != "file" || file.parentFile?.name != DIR || file.parentFile?.parentFile?.name != "files") {
            throw java.io.IOException("Not a bundle")
        }
        return file
    }
}

/** Where a bundle's pages go when its files are rearranged (handwriting and reading positions follow). */
object BundlePages {
    /**
     * [before] and [after] are the files (id and page count) in their old and new order. The result
     * gives an old page's new number, or null if its file was taken out.
     */
    fun mover(before: List<Pair<String, Int>>, after: List<Pair<String, Int>>): (Int) -> Int? {
        val newStart = mutableMapOf<String, Int>()
        var start = 0
        for ((id, count) in after) {
            newStart[id] = start
            start += count
        }
        return { page ->
            var oldStart = 0
            var result: Int? = null
            for ((id, count) in before) {
                if (page < oldStart + count) {
                    result = newStart[id]?.let { it + page - oldStart }
                    break
                }
                oldStart += count
            }
            result
        }
    }
}

/**
 * Several files shown as one document (기획서 5.1: 파일 묶음): their pages one after another, in
 * the bundle's order. Each file is opened as it would be in a tab of its own; search, selection
 * and links go to the file a page belongs to. A file that can't be opened shows as one blank
 * page with its name, so the rest of the bundle still opens.
 */
class BundleDocument private constructor(
    private val parts: List<Part>,
) : Document {
    /** A file of the bundle, or null where it couldn't be opened. */
    private class Part(val item: BundleItem, val document: Document?)

    override val locked: Boolean get() = parts.any { it.document?.locked == true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow(snapshot())
    override val progress: StateFlow<LayoutProgress> = _progress.asStateFlow()

    init {
        // A file still being laid out (text) holds back the pages after it, never the ones before.
        val growing = parts.mapNotNull { it.document?.progress }
        if (growing.isNotEmpty()) scope.launch { combine(growing) { snapshot() }.collect { _progress.value = it } }
    }

    private fun pagesOf(part: Part): Int = part.document?.pageCount ?: 1

    private fun completeOf(part: Part): Boolean = part.document?.progress?.value?.complete ?: true

    /** Pages shown so far: every file up to and including the first one still being laid out. */
    private fun snapshot(): LayoutProgress {
        var pages = 0
        for (part in parts) {
            pages += pagesOf(part)
            if (!completeOf(part)) return LayoutProgress(pages, complete = false)
        }
        return LayoutProgress(pages, complete = true)
    }

    /** Page [index] of the bundle as (part, page in that part's file). */
    private fun locate(index: Int): Pair<Part, Int> {
        var start = 0
        for (part in parts) {
            val count = pagesOf(part)
            if (index < start + count) return part to index - start
            start += count
            if (!completeOf(part)) break
        }
        throw IndexOutOfBoundsException("Page $index")
    }

    /** Where [part]'s pages start in the bundle. */
    private fun startOf(part: Part): Int = parts.takeWhile { it !== part }.sumOf { pagesOf(it) }

    override val pageSizes: List<PageSize> = object : AbstractList<PageSize>() {
        override val size: Int get() = snapshot().pages
        override fun get(index: Int): PageSize {
            val (part, page) = locate(index)
            return part.document?.pageSizes?.get(page) ?: MISSING_PAGE
        }
    }

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? {
        val (part, page) = locate(index)
        return part.document?.renderPage(page, widthPx) ?: missingPage(part.item, widthPx)
    }

    /** Each file by its name, with its own contents under it. */
    override suspend fun tableOfContents(): List<TocEntry> = parts.map { part ->
        val start = startOf(part)
        TocEntry(part.item.name, start, null, TocEntry.shift(part.document?.tableOfContents().orEmpty(), start))
    }

    // A bundle is exported as pictures (mostly photos of pages), so no drawPage; each page keeps its paper.
    override fun paperWidth(index: Int): Float {
        val (part, page) = locate(index)
        return part.document?.paperWidth(page) ?: A4_WIDTH
    }

    override fun linkAt(index: Int, x: Float, y: Float): String? {
        val (part, page) = locate(index)
        return part.document?.linkAt(page, x, y)
    }

    override suspend fun link(index: Int, x: Float, y: Float): Link? {
        val (part, page) = locate(index)
        return when (val link = part.document?.link(page, x, y)) {
            // A place in the file is that place in the bundle.
            is Link.Page -> link.copy(page = startOf(part) + link.page)
            else -> link
        }
    }

    override suspend fun search(query: String): List<SearchMatch> {
        val result = mutableListOf<SearchMatch>()
        for (part in parts) {
            val document = part.document ?: continue
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val start = startOf(part)
            for (match in document.search(query)) {
                result += SearchMatch(start + match.page, match.rects)
                if (result.size >= TextSearch.MAX_MATCHES) return result
            }
        }
        return result
    }

    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? {
        val (part, page) = locate(index)
        return part.document?.selectText(page, from, to)
    }

    override suspend fun selectWord(index: Int, at: PointF): TextSelection? {
        val (part, page) = locate(index)
        return part.document?.selectWord(page, at)
    }

    /**
     * Each file's page count by [BundleItem.id], once all are laid out: where every file's pages
     * are, for moving handwriting when the bundle is edited.
     */
    suspend fun pageCounts(): Map<String, Int> {
        awaitComplete()
        return parts.associate { it.item.id to pagesOf(it) }
    }

    override suspend fun close() = withContext(NonCancellable) {
        scope.cancel()
        for (part in parts) part.document?.close()
    }

    companion object {
        /** A file that can't be opened stands as one A4 page. */
        private val MISSING_PAGE = PageSize(595f, 842f)

        /** Opens the bundle at [uri] (see [Bundles]), its files one by one. */
        suspend fun open(context: Context, uri: Uri): BundleDocument = withContext(Dispatchers.IO) {
            val items = Bundles.read(uri)
            if (items.isEmpty()) throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
            val parts = mutableListOf<Part>()
            try {
                for (item in items) {
                    val document = try {
                        // Bundles don't nest.
                        if (item.format == FileFormat.Bundle) null
                        else Documents.open(context, item.uri, item.name, item.format)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    parts += Part(item, document)
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { parts.forEach { it.document?.close() } }
                throw e
            }
            BundleDocument(parts)
        }

        /** The page shown for a file that can't be opened: blank, with its name. */
        private fun missingPage(item: BundleItem, widthPx: Int): Bitmap? {
            val heightPx = (widthPx * MISSING_PAGE.aspect).roundToInt()
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return null
            }
            bitmap.eraseColor(Color.rgb(0xEE, 0xEE, 0xEE))
            val canvas = Canvas(bitmap)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = widthPx / 24f
                textAlign = Paint.Align.CENTER
            }
            val name = TextUtils.ellipsize(item.name, paint, widthPx * 0.8f, TextUtils.TruncateAt.MIDDLE).toString()
            canvas.drawText("열 수 없는 파일", widthPx / 2f, heightPx / 2f - paint.textSize, paint)
            canvas.drawText(name, widthPx / 2f, heightPx / 2f + paint.textSize, paint)
            return bitmap
        }
    }
}
