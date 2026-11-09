package com.tabpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabpdf.doc.TocEntry
import com.tabpdf.ink.Bookmark
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tabpdf.ui.theme.Motion
import com.tabpdf.ui.theme.dimmed

/**
 * The table of contents and the bookmarks (기획서 5.5) in a panel from the left, as e-book readers
 * show them, one list at a time under two tabs. Contents: entries nested by level, the ones under an
 * entry folded until its arrow is pressed, the entry being read marked and its path unfolded.
 * Bookmarks: in page order; a long press renames or deletes one. Pressing an entry or a bookmark
 * goes there and closes the panel.
 */
@Composable
fun TocDrawer(
    open: Boolean,
    /** Null while it is being read. */
    entries: List<TocEntry>?,
    /** Where the reader is: page, and how far down it. */
    page: Int,
    pageOffset: Float,
    onJump: (TocEntry) -> Unit,
    onClose: () -> Unit,
    showBookmarks: Boolean,
    onShowBookmarks: (Boolean) -> Unit,
    bookmarks: List<Bookmark>,
    /** Pages laid out so far, if known: bookmarks past them wait (shown dimmed). */
    pageCount: Int?,
    onBookmark: (Bookmark) -> Unit,
    onRenameBookmark: (Bookmark, String) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
) {
    BackHandler(enabled = open, onBack = onClose)
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.dimmed)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        )
    }
    AnimatedVisibility(
        open,
        enter = slideInHorizontally(tween(300, easing = Motion.EmphasizedDecelerate)) { -it },
        exit = slideOutHorizontally(tween(200, easing = Motion.EmphasizedAccelerate)) { -it },
    ) {
        BoxWithConstraints {
            Surface(
                Modifier
                    .fillMaxHeight()
                    .width(minOf(maxWidth * 0.85f, 360.dp))
                    // Taps inside don't reach the scrim.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                // Rounded where it ends, as Material's navigation drawer.
                shape = DrawerShape,
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                    SecondaryTabRow(
                        selectedTabIndex = if (showBookmarks) 1 else 0,
                        containerColor = Color.Transparent,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Tab(selected = !showBookmarks, onClick = { onShowBookmarks(false) }, text = { Text("목차") })
                        Tab(selected = showBookmarks, onClick = { onShowBookmarks(true) }, text = { Text("북마크") })
                    }
                    if (showBookmarks) {
                        BookmarkList(bookmarks, pageCount, onBookmark, onRenameBookmark, onDeleteBookmark)
                    } else when {
                        entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        entries.isEmpty() -> Text(
                            "이 문서에는 목차가 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                        else -> TocList(entries, page, pageOffset, onJump)
                    }
                }
            }
        }
    }
}

/** An entry as a row: where it sits in the tree ([key]), how deep. */
private class TocRow(val entry: TocEntry, val key: String, val depth: Int)

@Composable
private fun TocList(entries: List<TocEntry>, page: Int, pageOffset: Float, onJump: (TocEntry) -> Unit) {
    // The entry being read: the last one (in reading order) at or before the reader's place.
    val currentPath = remember(entries, page, pageOffset) {
        var best: List<Int>? = null
        fun walk(list: List<TocEntry>, path: List<Int>) {
            for ((i, e) in list.withIndex()) {
                val at = path + i
                if (e.page < page || (e.page == page && (e.y ?: 0f) <= pageOffset + 0.05f)) best = at
                walk(e.children, at)
            }
        }
        walk(entries, emptyList())
        best
    }
    val currentKey = currentPath?.joinToString("/")
    var expanded by remember(entries) {
        mutableStateOf(buildSet {
            // Unfolded: the path to the current entry, and a lone top entry (a book's title).
            currentPath?.let { p -> for (n in 1 until p.size) add(p.take(n).joinToString("/")) }
            if (entries.size == 1) add("0")
        })
    }
    val rows = remember(entries, expanded) {
        buildList {
            fun walk(list: List<TocEntry>, prefix: String, depth: Int) {
                for ((i, e) in list.withIndex()) {
                    val key = if (prefix.isEmpty()) "$i" else "$prefix/$i"
                    add(TocRow(e, key, depth))
                    if (key in expanded) walk(e.children, key, depth + 1)
                }
            }
            walk(entries, "", 0)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = rows.indexOfFirst { it.key == currentKey }
        if (index > 2) listState.scrollToItem(index - 2)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { row ->
            val current = row.key == currentKey
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onJump(row.entry) }
                    .padding(start = 12.dp + 16.dp * row.depth.coerceAtMost(6), end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.entry.children.isNotEmpty()) {
                    val open = row.key in expanded
                    IconButton(onClick = { expanded = if (open) expanded - row.key else expanded + row.key }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (open) "접기" else "펼치기",
                        )
                    }
                } else {
                    Spacer(Modifier.size(40.dp))
                }
                Text(
                    row.entry.title,
                    style = if (row.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                Text(
                    "${row.entry.page + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    pageCount: Int?,
    onBookmark: (Bookmark) -> Unit,
    onRename: (Bookmark, String) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(BookmarkIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Text(
                "북마크가 없습니다",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        return
    }
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(bookmarks, key = { it.id }) { mark ->
            // A large text file lays out its pages while shown: one further on can't be gone to yet.
            val ready = pageCount == null || mark.page < pageCount
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .combinedClickable(onClick = { if (ready) onBookmark(mark) }, onLongClick = { menu = true })
                        .padding(start = 20.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        BookmarkedIcon, contentDescription = null,
                        tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        mark.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (ready) Color.Unspecified else MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                    )
                    Text(
                        "${mark.page + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menu = false; renaming = mark })
                    DropdownMenuItem(text = { Text("삭제") }, onClick = { menu = false; onDelete(mark) })
                }
            }
        }
    }
    renaming?.let { mark ->
        NameDialog(
            title = "북마크 이름 변경",
            initial = mark.name.orEmpty(),
            confirmLabel = "변경",
            onConfirm = { name ->
                onRename(mark, name)
                renaming = null
            },
            onDismiss = { renaming = null },
            // Blank: named after its page again.
            allowBlank = true,
            placeholder = "${mark.page + 1}페이지",
        )
    }
}

private val DrawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
