package com.tabpdf.tabs

/**
 * Where the reader is in a document. Offsets are stored as fractions rather than pixels so the
 * position survives rotation and a different screen width.
 */
data class ViewerState(
    val page: Int = 0,
    /** Scroll offset into [page], as a fraction of the page height. */
    val pageOffset: Float = 0f,
    val zoom: Float = 1f,
    /** Horizontal pan, as a fraction of the viewport width. */
    val panX: Float = 0f,
)
