package com.tabpdf.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toRect
import androidx.compose.ui.zIndex
import com.tabpdf.doc.FileFormat
import com.tabpdf.doc.Thumbnails
import com.tabpdf.ink.LocalInkStore
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.SessionState
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.TabsViewModel
import com.tabpdf.tabs.TopItem
import com.tabpdf.tabs.displayName
import kotlin.math.abs

/**
 * Tab switcher, Chrome-style (기획서 4.2): ungrouped tabs and group cards in one grid.
 * - Tab card: tap opens it; ✕ or a sideways swipe closes it; long press shows a menu.
 * - Long press and drag reorders; dropping a tab onto the middle of another tab makes a group of
 *   the two, onto a group card adds it to that group.
 * - Group card: tap opens the group panel ([GroupPanel]) over the switcher.
 */
@Composable
fun TabSwitcher(
    state: SessionState,
    viewModel: TabsViewModel,
    openGroupId: String?,
    onOpenGroup: (groupId: String?) -> Unit,
    onOpenFiles: (groupId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val order = remember(state.top) { state.top.toMutableStateList() }
    val gridState = rememberLazyGridState()
    val gestures = rememberItemGestureState()
    val reorder = rememberReorderController(
        onMove = { from, to -> order.move(from, to) },
        canMerge = { dragged, target -> order.byKey(dragged) is DocTab && order.byKey(target) != null },
    )
    var menuFor by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<SwitcherDialog?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
                Text(
                    "탭 ${state.allTabs.size}개",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { onOpenFiles(null) }) {
                    Icon(Icons.Default.Add, contentDescription = "파일 열기")
                }
            }

            if (order.isEmpty()) {
                EmptyTabs(onOpen = { onOpenFiles(null) })
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
                            itemRect = { gridState.rectOf(it) },
                            onTap = { key ->
                                when (val item = order.byKey(key)) {
                                    is DocTab -> {
                                        viewModel.select(item.id)
                                        onDismiss()
                                    }
                                    is TabGroup -> onOpenGroup(item.id)
                                    null -> Unit
                                }
                            },
                            onLongPress = { menuFor = it as String },
                            onDragMove = reorder::dragMove,
                            onDrop = { dragged ->
                                when (val target = order.byKey(reorder.drop())) {
                                    is DocTab -> viewModel.groupTogether(dragged as String, target.id)
                                    is TabGroup -> viewModel.addToGroup(dragged as String, target.id)
                                    null -> viewModel.reorderTop(order.map { it.id })
                                }
                            },
                            onSwipeAway = { key -> closeItem(order.byKey(key), viewModel) },
                            scrollBy = { gridState.scrollBy(it) },
                        ),
                    // Top padding would shift item offsets away from pointer coordinates; see itemGestures.
                    contentPadding = PaddingValues(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(order, key = { it.id }) { item ->
                        DraggableCard(item.id, gestures, isMergeTarget = reorder.mergeTarget == item.id) {
                            when (item) {
                                is DocTab -> TabCard(
                                    tab = item,
                                    page = viewModel.viewerState(item.id).page,
                                    isCurrent = item.id == state.currentTabId,
                                    onClose = { viewModel.close(item.id) },
                                )
                                is TabGroup -> GroupCard(
                                    group = item,
                                    tabs = state.tabsOf(item.id),
                                    pageOf = { viewModel.viewerState(it).page },
                                    containsCurrent = state.currentTab?.groupId == item.id,
                                    onClose = { viewModel.closeGroup(item.id) },
                                )
                            }
                            DropdownMenu(expanded = menuFor == item.id, onDismissRequest = { menuFor = null }) {
                                when (item) {
                                    is DocTab -> {
                                        MenuItem("그룹에 추가") { menuFor = null; dialog = SwitcherDialog.AddToGroup(item) }
                                        MenuItem("파일 정보") { menuFor = null; dialog = SwitcherDialog.Info(item) }
                                        MenuItem("닫기") { menuFor = null; viewModel.close(item.id) }
                                    }
                                    is TabGroup -> {
                                        MenuItem("이름 변경") { menuFor = null; dialog = SwitcherDialog.Rename(item) }
                                        MenuItem("색상 변경") { menuFor = null; dialog = SwitcherDialog.Color(item) }
                                        MenuItem("그룹 해제") { menuFor = null; viewModel.ungroup(item.id) }
                                        MenuItem("그룹 닫기") { menuFor = null; viewModel.closeGroup(item.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val openGroup = openGroupId?.let { state.group(it) }
    // The group can disappear while its panel is open (closed, ungrouped, emptied).
    LaunchedEffect(openGroupId, openGroup) { if (openGroupId != null && openGroup == null) onOpenGroup(null) }
    // Keep showing the last group while the panel animates out.
    val lastGroup = remember { arrayOfNulls<TabGroup>(1) }
    if (openGroup != null) lastGroup[0] = openGroup
    AnimatedVisibility(openGroup != null, enter = fadeIn() + scaleIn(initialScale = 0.95f), exit = fadeOut() + scaleOut(targetScale = 0.95f)) {
        val group = lastGroup[0] ?: return@AnimatedVisibility
        GroupPanel(
            group = group,
            tabs = state.tabsOf(group.id),
            currentTabId = state.currentTabId,
            viewModel = viewModel,
            onOpenTab = { id ->
                viewModel.select(id)
                onOpenGroup(null)
                onDismiss()
            },
            onOpenFiles = { onOpenFiles(group.id) },
            onDismiss = { onOpenGroup(null) },
        )
    }
    BackHandler { if (openGroupId != null) onOpenGroup(null) else onDismiss() }

    SwitcherDialogs(dialog, state, viewModel, onDone = { dialog = null })
}

/**
 * A group's tabs over the switcher (Chrome's tab group dialog): rename and recolour from the
 * header, reorder by long press and drag, take tabs out of the group from their menu.
 */
@Composable
private fun GroupPanel(
    group: TabGroup,
    tabs: List<DocTab>,
    currentTabId: String?,
    viewModel: TabsViewModel,
    onOpenTab: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val order = remember(tabs) { tabs.toMutableStateList() }
    val gridState = rememberLazyGridState()
    val gestures = rememberItemGestureState()
    val reorder = rememberReorderController(onMove = { from, to -> order.move(from, to) })
    var menuFor by remember { mutableStateOf<String?>(null) }
    var headerMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SwitcherDialog?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss, indication = null, interactionSource = null),
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
                // Swallow taps so they don't reach the scrim behind the panel.
                .clickable(onClick = {}, indication = null, interactionSource = null),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 3.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { dialog = SwitcherDialog.Color(group) }) {
                        GroupDot(group.color, size = 16.dp)
                    }
                    Text(
                        group.displayName(tabs.size),
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { dialog = SwitcherDialog.Rename(group) }
                            .padding(vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Default.Add, contentDescription = "이 그룹에 파일 열기")
                    }
                    Box {
                        IconButton(onClick = { headerMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "그룹 메뉴")
                        }
                        DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                            MenuItem("이름 변경") { headerMenu = false; dialog = SwitcherDialog.Rename(group) }
                            MenuItem("색상 변경") { headerMenu = false; dialog = SwitcherDialog.Color(group) }
                            MenuItem("그룹 해제") { headerMenu = false; viewModel.ungroup(group.id) }
                            MenuItem("그룹 닫기") { headerMenu = false; viewModel.closeGroup(group.id) }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
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
                            onTap = { onOpenTab(it as String) },
                            onLongPress = { menuFor = it as String },
                            onDragMove = reorder::dragMove,
                            onDrop = {
                                reorder.drop()
                                viewModel.reorderGroup(group.id, order.map { it.id })
                            },
                            onSwipeAway = { viewModel.close(it as String) },
                            scrollBy = { gridState.scrollBy(it) },
                        ),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(order, key = { it.id }) { tab ->
                        DraggableCard(tab.id, gestures, isMergeTarget = false) {
                            TabCard(
                                tab = tab,
                                page = viewModel.viewerState(tab.id).page,
                                isCurrent = tab.id == currentTabId,
                                onClose = { viewModel.close(tab.id) },
                            )
                            DropdownMenu(expanded = menuFor == tab.id, onDismissRequest = { menuFor = null }) {
                                MenuItem("그룹에서 빼기") { menuFor = null; viewModel.removeFromGroup(tab.id) }
                                MenuItem("파일 정보") { menuFor = null; dialog = SwitcherDialog.Info(tab) }
                                MenuItem("닫기") { menuFor = null; viewModel.close(tab.id) }
                            }
                        }
                    }
                }
            }
        }
    }
    SwitcherDialogs(dialog, null, viewModel, onDone = { dialog = null })
}

private sealed interface SwitcherDialog {
    data class AddToGroup(val tab: DocTab) : SwitcherDialog
    data class NewGroupFor(val tab: DocTab) : SwitcherDialog
    data class Info(val tab: DocTab) : SwitcherDialog
    data class Rename(val group: TabGroup) : SwitcherDialog
    data class Color(val group: TabGroup) : SwitcherDialog
}

@Composable
private fun SwitcherDialogs(dialog: SwitcherDialog?, state: SessionState?, viewModel: TabsViewModel, onDone: () -> Unit) {
    var next by remember { mutableStateOf<SwitcherDialog?>(null) }
    when (val d = next ?: dialog) {
        is SwitcherDialog.AddToGroup -> AddToGroupDialog(
            groups = state?.groups.orEmpty(),
            tabCount = { state?.tabsOf(it)?.size ?: 0 },
            onPick = { groupId -> onDone(); viewModel.addToGroup(d.tab.id, groupId) },
            onNewGroup = { next = SwitcherDialog.NewGroupFor(d.tab) },
            onDismiss = onDone,
        )
        is SwitcherDialog.NewGroupFor -> NameDialog(
            title = "새 그룹",
            initial = "",
            confirmLabel = "만들기",
            onConfirm = { name -> next = null; onDone(); viewModel.addToNewGroup(d.tab.id, name) },
            onDismiss = { next = null; onDone() },
        )
        is SwitcherDialog.Info -> FileInfoDialog(d.tab, viewModel.viewerState(d.tab.id).page, onDismiss = onDone)
        is SwitcherDialog.Rename -> NameDialog(
            title = "그룹 이름",
            initial = d.group.name,
            confirmLabel = "변경",
            onConfirm = { name -> onDone(); viewModel.renameGroup(d.group.id, name) },
            onDismiss = onDone,
            allowBlank = true,
        )
        is SwitcherDialog.Color -> ColorDialog(
            selected = d.group.color,
            onSelect = { color -> onDone(); viewModel.setGroupColor(d.group.id, color) },
            onDismiss = onDone,
        )
        null -> Unit
    }
}

private fun closeItem(item: TopItem?, viewModel: TabsViewModel) {
    when (item) {
        is DocTab -> viewModel.close(item.id)
        is TabGroup -> viewModel.closeGroup(item.id)
        null -> Unit
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun EmptyTabs(onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("열린 탭이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpen) { Text("파일 열기") }
        }
    }
}

/**
 * Wraps a grid card so it follows the finger while dragged and slides with a swipe. Other cards
 * animate into their new places; the dragged one is placed directly under the finger instead.
 */
@Composable
private fun LazyGridItemScope.DraggableCard(
    key: String,
    gestures: ItemGestureState,
    isMergeTarget: Boolean,
    content: @Composable () -> Unit,
) {
    val dragging = gestures.draggingKey == key
    Box(
        (if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
            .graphicsLayer {
                val drag = gestures.dragTranslation(key)
                val swipe = if (gestures.swipingKey == key) gestures.swipeOffset else 0f
                translationX = drag.x + swipe
                translationY = drag.y
                // The dragged card is see-through so the merge highlight under it shows.
                alpha = if (dragging) 0.75f else 1f - (abs(swipe) / size.width).coerceIn(0f, 0.7f)
                val scale = when {
                    dragging -> 1.05f
                    isMergeTarget -> 0.92f
                    else -> 1f
                }
                scaleX = scale
                scaleY = scale
            }
    ) {
        content()
        if (isMergeTarget) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            )
        }
    }
}

private fun <T : TopItem> List<T>.byKey(key: Any?): T? = key?.let { k -> firstOrNull { it.id == k } }

private fun <T : TopItem> MutableList<T>.move(from: Any, to: Any) {
    val fromIndex = indexOfFirst { it.id == from }
    val toIndex = indexOfFirst { it.id == to }
    if (fromIndex >= 0 && toIndex >= 0) add(toIndex, removeAt(fromIndex))
}

private fun LazyGridState.keyAt(position: Offset): Any? =
    layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(position.round()) }?.key

private fun LazyGridState.rectOf(key: Any): Rect? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { IntRect(it.offset, it.size).toRect() }

// ---- Cards ----

private val CardShape = RoundedCornerShape(12.dp)

@Composable
private fun TabCard(tab: DocTab, page: Int, isCurrent: Boolean, onClose: () -> Unit) {
    Surface(
        shape = CardShape,
        tonalElevation = 2.dp,
        border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color.White)) {
                Thumbnail(tab, page, Modifier.fillMaxSize())
                CloseBadge("탭 닫기", onClose, Modifier.align(Alignment.TopEnd))
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

/** A group in the switcher: up to four of its pages in a 2×2 collage, tinted with its colour. */
@Composable
private fun GroupCard(
    group: TabGroup,
    tabs: List<DocTab>,
    pageOf: (String) -> Int,
    containsCurrent: Boolean,
    onClose: () -> Unit,
) {
    val color = groupColor(group.color)
    Surface(
        shape = CardShape,
        tonalElevation = 2.dp,
        border = if (containsCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(color.copy(alpha = 0.25f)).padding(6.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0..1) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0..1) {
                                val tab = tabs.getOrNull(row * 2 + col)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (tab != null) Color.White else Color.Transparent)
                                ) {
                                    if (tab != null) Thumbnail(tab, pageOf(tab.id), Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
                CloseBadge("그룹 닫기", onClose, Modifier.align(Alignment.TopEnd))
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GroupDot(group.color)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        group.displayName(tabs.size),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // An unnamed group's title already is "탭 n개".
                    if (group.name.isNotBlank()) {
                        Text(
                            "탭 ${tabs.size}개",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A plain clickable circle: IconButton would force a 48dp box that overflows the card corner. */
@Composable
private fun CloseBadge(description: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .padding(6.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, contentDescription = description, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Thumbnail(tab: DocTab, page: Int, modifier: Modifier) {
    val context = LocalContext.current
    // null while loading; a failed load (missing or unreadable file) gives Result.failure.
    val inkStore = LocalInkStore.current
    val strokes by remember(tab.id, inkStore) { inkStore?.strokes(tab.id) ?: MutableStateFlow(emptyList()) }.collectAsState()
    val result by produceState<Result<Bitmap>?>(null, tab.id, tab.uri, page, strokes) {
        val bitmap = Thumbnails.load(context.applicationContext, tab, page, strokes)
        value = if (bitmap != null) Result.success(bitmap) else Result.failure(IllegalStateException())
    }
    val image = remember(result) { result?.getOrNull()?.asImageBitmap() }
    when {
        // Text is read from the top at full width, however short its page; other pages fill the card.
        image != null && (tab.format == FileFormat.Text || tab.format == FileFormat.Markdown) -> Image(
            image, contentDescription = null, modifier.background(Color.White),
            contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter,
        )
        image != null ->
            Image(image, contentDescription = null, modifier, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        result?.isFailure == true -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("열 수 없음", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        }
    }
}
