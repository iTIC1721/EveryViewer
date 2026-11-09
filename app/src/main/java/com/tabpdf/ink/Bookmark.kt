package com.tabpdf.ink

import com.tabpdf.data.BookmarkEntity

/**
 * A bookmark (기획서 5.5 북마크): page [page] of a tab, at [y] down it (a fraction of its height),
 * at most one per page. [name] is null until the reader names it; it is then shown by its page.
 */
data class Bookmark(val id: String, val page: Int, val y: Float, val name: String?, val createdAt: Long) {
    /** What the list shows. */
    val title: String get() = name ?: "${page + 1}페이지"
}

fun BookmarkEntity.toBookmark() = Bookmark(id, page, y.coerceIn(0f, 1f), name, createdAt)

fun Bookmark.toEntity(tabId: String) = BookmarkEntity(id, tabId, page, y, name, createdAt)
