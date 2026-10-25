package com.tabpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabpdf.doc.BundleItem
import com.tabpdf.tabs.BundleEdit
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.TabsViewModel

/**
 * Editing a bundle (기획서 5.1: 파일 묶음), laid out like the tab switcher: a card per file with its
 * first page; long press and drag to reorder, ✕ or a sideways swipe to take a file out, ＋ to add
 * files, the name to rename. Applied when the editor is left.
 */
@Composable
fun BundleEditor(edit: BundleEdit, viewModel: TabsViewModel, onAddFiles: () -> Unit) {
    val gridState = rememberLazyGridState()
    val gestures = rememberItemGestureState()
    val reorder = rememberReorderController(onMove = { from, to -> viewModel.moveBundleItem(from as String, to as String) })
    var renaming by remember { mutableStateOf(false) }
    BackHandler { viewModel.finishBundleEdit() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::finishBundleEdit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
                Text(
                    edit.name,
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { renaming = true }
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onAddFiles) {
                    Icon(Icons.Default.Add, contentDescription = "파일 추가")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .itemGestures(
                        state = gestures,
                        keyAt = { gridState.keyAt(it) },
                        itemRect = { gridState.rectOf(it) },
                        onTap = {},
                        onLongPress = {},
                        onDragMove = reorder::dragMove,
                        onDrop = { reorder.drop() },
                        onSwipeAway = if (edit.items.size > 1) ({ viewModel.removeBundleItem(it as String) }) else null,
                        scrollBy = { gridState.scrollBy(it) },
                    ),
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(edit.items, key = { it.id }) { item ->
                    DraggableCard(item.id, gestures, isMergeTarget = false) {
                        ItemCard(
                            item = item,
                            position = edit.items.indexOf(item) + 1,
                            // A bundle keeps at least one file.
                            onRemove = if (edit.items.size > 1) ({ viewModel.removeBundleItem(item.id) }) else null,
                        )
                    }
                }
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = "묶음 이름",
            initial = edit.name,
            confirmLabel = "변경",
            onConfirm = { name -> renaming = false; viewModel.renameBundle(name) },
            onDismiss = { renaming = false },
        )
    }
}

/** One file of the bundle: its first page, its place in the order, its name. */
@Composable
private fun ItemCard(item: BundleItem, position: Int, onRemove: (() -> Unit)?) {
    // The thumbnail cache is keyed by tab; each file of a bundle gets a key of its own.
    val asTab = remember(item) { DocTab("bundle-${item.id}", null, item.uri, item.name, item.isInternalCopy, item.format) }
    Surface(shape = CardShape, tonalElevation = 2.dp) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.White)) {
                Thumbnail(asTab, 0, Modifier.fillMaxSize())
                if (onRemove != null) CloseBadge("묶음에서 빼기", onRemove, Modifier.align(Alignment.TopEnd))
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$position",
                    Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
