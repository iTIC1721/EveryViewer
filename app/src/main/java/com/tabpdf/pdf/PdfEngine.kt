package com.tabpdf.pdf

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.content.PdfPageGotoLinkContent
import android.graphics.pdf.content.PdfPageLinkContent
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import com.tabpdf.doc.PageSize

/**
 * Everything done with Android's PDF support (PDFium) for one file: in [RenderService]'s isolated
 * process, or in the app where that isn't available (Android 8.0). Not thread-safe; one call at a
 * time. Positions are in PDF points from the page's top left; callers check what comes back.
 */
class PdfEngine private constructor(
    /**
     * Android 15's [PdfRenderer]; on Android 12–14 with the system update that brings text support,
     * the newer [PdfRendererPreV] it adds (it also takes passwords); else the old [PdfRenderer].
     */
    private val renderer: AutoCloseable,
) : AutoCloseable {
    /** Reading every page size costs ~0.4 ms a page (1000 pages ≈ 0.4 s on an emulator). */
    val pageSizes: List<PageSize> = try {
        when (renderer) {
            is PdfRenderer -> (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
            }
            else -> pageSizesPreV()
        }
    } catch (e: Exception) {
        renderer.close()
        throw e
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun pageSizesPreV(): List<PageSize> = (renderer as PdfRendererPreV).let { r ->
        (0 until r.pageCount).map { i -> r.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) } }
    }

    /** Draws page [index] over what is in [bitmap]. */
    fun render(index: Int, bitmap: Bitmap) {
        if (renderer is PdfRenderer) {
            renderer.openPage(index).use { page ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } else {
            renderPreV(index, bitmap)
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun renderPreV(index: Int, bitmap: Bitmap) {
        (renderer as PdfRendererPreV).openPage(index).use { page ->
            page.render(bitmap, null, null, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
        }
    }

    /** Where [query] is on page [index]: each match as its boxes (one per line it spans). */
    fun search(index: Int, query: String): List<List<RectF>> = when {
        Build.VERSION.SDK_INT >= 35 -> platform.openPage(index).use { page -> page.searchText(query).map { it.bounds } }
        textSupport -> searchPreV(index, query)
        else -> emptyList()
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun searchPreV(index: Int, query: String): List<List<RectF>> =
        (renderer as PdfRendererPreV).openPage(index).use { page -> page.searchText(query).map { it.bounds } }

    /** The text from [start] to [stop] on page [index], in pieces with their boxes; null if none. */
    fun select(index: Int, start: Point, stop: Point): List<Pair<String, List<RectF>>>? = when {
        Build.VERSION.SDK_INT >= 35 -> platform.openPage(index).use { page ->
            page.selectContent(SelectionBoundary(start), SelectionBoundary(stop))?.selectedTextContents?.map { it.text to it.bounds }
        }
        textSupport -> selectPreV(index, start, stop)
        else -> null
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun selectPreV(index: Int, start: Point, stop: Point): List<Pair<String, List<RectF>>>? =
        (renderer as PdfRendererPreV).openPage(index).use { page ->
            page.selectContent(SelectionBoundary(start), SelectionBoundary(stop))?.selectedTextContents?.map { it.text to it.bounds }
        }

    /** A link to a place in the document: page and, if given, how far down (points). */
    class Goto(val bounds: List<RectF>, val page: Int, val y: Float)

    /** Page [index]'s links: web addresses as written (not yet checked), and places in the document. */
    fun links(index: Int): Pair<List<Pair<List<RectF>, String>>, List<Goto>> = when {
        Build.VERSION.SDK_INT >= 35 -> platform.openPage(index).use { page -> linksOf(page.linkContents, page.gotoLinks) }
        textSupport -> linksPreV(index)
        else -> emptyList<Pair<List<RectF>, String>>() to emptyList()
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun linksPreV(index: Int) =
        (renderer as PdfRendererPreV).openPage(index).use { page -> linksOf(page.linkContents, page.gotoLinks) }

    private fun linksOf(web: List<PdfPageLinkContent>, goto: List<PdfPageGotoLinkContent>) =
        web.map { it.bounds to it.uri.toString() } to
            goto.map { Goto(it.bounds, it.destination.pageNumber, it.destination.yCoordinate) }

    /** Writes the file to [target] without its encryption (for export); false where Android can't. */
    fun write(target: ParcelFileDescriptor): Boolean = when {
        Build.VERSION.SDK_INT >= 35 -> {
            platform.write(target, true)
            true
        }
        textSupport -> {
            writePreV(target)
            true
        }
        else -> false
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun writePreV(target: ParcelFileDescriptor) = (renderer as PdfRendererPreV).write(target, true)

    /** On Android 15 the renderer is always [PdfRenderer]. */
    @get:RequiresApi(35)
    private val platform: PdfRenderer get() = renderer as PdfRenderer

    override fun close() = renderer.close()

    companion object {
        /** Whether Android here has PDF text support (search, selection, links, passwords, writing). */
        @get:ChecksSdkIntAtLeast(api = 31)
        val textSupport: Boolean by lazy {
            Build.VERSION.SDK_INT >= 35 ||
                Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        }

        /**
         * Opens [fd] (the caller closes it after this). Throws [SecurityException] if the file needs a
         * password, or the one given doesn't open it; passwords are only taken with [textSupport].
         */
        fun open(fd: ParcelFileDescriptor, password: String?): PdfEngine = PdfEngine(
            when {
                Build.VERSION.SDK_INT >= 35 -> if (password == null) PdfRenderer(fd) else PdfRenderer(fd, loadParams(password))
                textSupport -> openPreV(fd, password)
                else -> PdfRenderer(fd)
            },
        )

        @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
        // A file locked only against printing or copying opens with the empty password, which
        // [write] then takes off as well.
        private fun openPreV(fd: ParcelFileDescriptor, password: String?): PdfRendererPreV =
            PdfRendererPreV(fd, loadParams(password ?: ""))

        @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
        private fun loadParams(password: String): LoadParams = LoadParams.Builder().setPassword(password).build()

        /** Pages in [fd] once each has been opened (an export is read back like this); throws if it doesn't open. */
        fun countPages(fd: ParcelFileDescriptor): Int = PdfRenderer(fd).use { r ->
            for (i in 0 until r.pageCount) r.openPage(i).close()
            r.pageCount
        }
    }
}
