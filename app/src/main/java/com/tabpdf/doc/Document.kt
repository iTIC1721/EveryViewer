package com.tabpdf.doc

import android.graphics.Bitmap
import java.io.IOException

/** A file that was read fine but can't be shown. */
class DocumentOpenException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { PasswordProtected, NoPages, Unsupported, TooLarge }
}

/** Page size in the document's own units (PDF points for PDFs); the viewer only uses the ratio. */
data class PageSize(val width: Float, val height: Float) {
    val aspect: Float get() = height / width
}

/**
 * Any opened file, as the viewer sees it (기획서 5.7): pages of fixed size stacked vertically.
 * Every format is turned into this, so the viewer, thumbnails and reading positions never deal
 * with formats.
 */
interface Document {
    val pageSizes: List<PageSize>

    val pageCount: Int get() = pageSizes.size

    /**
     * Whether pages are shown apart, like sheets of paper. Text that just flows on is cut into
     * pages only for rendering and is shown without gaps.
     */
    val pageGaps: Boolean get() = true

    /**
     * Renders page [index] at [widthPx] wide (height follows the page's aspect ratio). Returns null
     * if the document was closed meanwhile or there wasn't memory for the bitmap.
     */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap?

    /**
     * Web link at ([x], [y]) on page [index], both as fractions of the page's width and height,
     * or null if nothing there is a link.
     */
    fun linkAt(index: Int, x: Float, y: Float): String? = null

    suspend fun close()
}
