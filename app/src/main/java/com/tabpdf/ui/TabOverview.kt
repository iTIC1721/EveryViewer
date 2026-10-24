package com.tabpdf.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import com.tabpdf.pdf.Thumbnails
import com.tabpdf.tabs.PdfTab
import com.tabpdf.tabs.TabGroup
import kotlin.math.abs

/**
 * 탭 개요 (기획서 4.2): the active group's tabs as a grid of page thumbnails.
 * Tap opens a tab, ✕ or a sideways swipe closes it, long press shows a menu, long press and drag
 * reorders.
 */
@Composable
fun TabOverview(
    group: TabGroup,
    tabs: List<PdfTab>,
    groups: List<TabGroup>,
    currentTabId: String?,
    pageOf: (tabId: String) -> Int,
    onSelect: (tabId: String) -> Unit,
    onClose: (tabId: String) -> Unit,
    onReorder: (ids: List<String>) -> Unit,
    onMoveToGroup: (tabId: String, groupId: String) -> Unit,
    onMoveToNewGroup: (tabId: String, name: String) -> Unit,
    onOpenFiles: () -> Unit,
    onBack: () -> Unit,
) {
    // Local copy so a drag can reorder live; reset whenever the real list changes.
    val order = remember(tabs) { tabs.toMutableStateList() }
    val gridState = rememberLazyGridState()
    val gestures = rememberItemGestureState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var movingTab by remember { mutableStateOf<PdfTab?>(null) }
    var newGroupFor by remember { mutableStateOf<PdfTab?>(null) }
    var infoFor by remember { mutableStateOf<PdfTab?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
                GroupDot(group.color)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${group.name} (${tabs.size})",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onOpenFiles) {
                    Icon(Icons.Default.Add, contentDescription = "PDF 열기")
                }
            }

            if (tabs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("이 그룹에는 탭이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onOpenFiles) { Text("PDF 열기") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .itemGestures(
                            state = gestures,
                            keyAt = { gridState.keyAt(it) },
                            itemTopLeft = { key -> gridState.topLeftOf(key) },
                            onTap = { onSelect(it as String) },
                            onLongPress = { menuFor = it as String },
                            onMove = { from, to ->
                                val fromIndex = order.indexOfFirst { it.id == from }
                                val toIndex = order.indexOfFirst { it.id == to }
                                if (fromIndex >= 0 && toIndex >= 0) order.add(toIndex, order.removeAt(fromIndex))
                            },
                            onDragEnd = { onReorder(order.map { it.id }) },
                            onSwipeAway = { onClose(it as String) },
                        ),
                    // Top padding would shift item offsets away from pointer coordinates; see itemGestures.
                    contentPadding = PaddingValues(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(order, key = { it.id }) { tab ->
                        val dragging = gestures.draggingKey == tab.id
                        Box(
                            (if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                                .graphicsLayer {
                                    val drag = gestures.dragTranslation(tab.id)
                                    val swipe = if (gestures.swipingKey == tab.id) gestures.swipeOffset else 0f
                                    translationX = drag.x + swipe
                                    translationY = drag.y
                                    alpha = 1f - (abs(swipe) / size.width).coerceIn(0f, 0.7f)
                                    if (dragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                }
                        ) {
                            TabCard(
                                tab = tab,
                                page = pageOf(tab.id),
                                isCurrent = tab.id == currentTabId,
                                onClose = { onClose(tab.id) },
                            )
                            DropdownMenu(expanded = menuFor == tab.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(
                                    text = { Text("다른 그룹으로 이동") },
                                    onClick = { menuFor = null; movingTab = tab },
                                )
                                DropdownMenuItem(
                                    text = { Text("파일 정보") },
                                    onClick = { menuFor = null; infoFor = tab },
                                )
                                DropdownMenuItem(
                                    text = { Text("닫기") },
                                    onClick = { menuFor = null; onClose(tab.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    movingTab?.let { tab ->
        MoveToGroupDialog(
            groups = groups,
            currentGroupId = tab.groupId,
            onMove = { groupId -> movingTab = null; onMoveToGroup(tab.id, groupId) },
            onNewGroup = { movingTab = null; newGroupFor = tab },
            onDismiss = { movingTab = null },
        )
    }
    newGroupFor?.let { tab ->
        NameDialog(
            title = "새 그룹으로 이동",
            initial = "",
            confirmLabel = "이동",
            onConfirm = { name -> newGroupFor = null; onMoveToNewGroup(tab.id, name) },
            onDismiss = { newGroupFor = null },
        )
    }
    infoFor?.let { tab -> FileInfoDialog(tab, pageOf(tab.id), onDismiss = { infoFor = null }) }
}

private fun LazyGridState.keyAt(position: Offset): Any? =
    layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(position.round()) }?.key

private fun LazyGridState.topLeftOf(key: Any): Offset? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toOffset()

@Composable
private fun TabCard(tab: PdfTab, page: Int, isCurrent: Boolean, onClose: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        tonalElevation = 2.dp,
        border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.White)) {
                Thumbnail(tab, page, Modifier.fillMaxSize())
                // A plain clickable circle: IconButton would force a 48dp box that overflows the card corner.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, contentDescription = "탭 닫기", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(tab.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "p.${page + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(tab: PdfTab, page: Int, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, tab.id, tab.uri, page) {
        value = Thumbnails.load(context.applicationContext, tab.id, tab.uri, page)
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(image, contentDescription = null, modifier, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
    }
}
