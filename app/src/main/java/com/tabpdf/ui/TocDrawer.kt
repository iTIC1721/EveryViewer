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

/**
 * The table of contents (기획서 5.5) in a panel from the left, as e-book readers show it: entries
 * nested by level, the ones under an entry folded until its arrow is pressed, the entry being read
 * marked and its path unfolded. Pressing an entry goes there and closes the panel.
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
) {
    BackHandler(enabled = open, onBack = onClose)
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        )
    }
    AnimatedVisibility(open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
        BoxWithConstraints {
            Surface(
                Modifier
                    .fillMaxHeight()
                    .width(minOf(maxWidth * 0.85f, 360.dp))
                    // Taps inside don't reach the scrim.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                    Text("목차", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp))
                    when {
                        entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        entries.isEmpty() -> Text(
                            "이 문서에는 목차가 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
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
