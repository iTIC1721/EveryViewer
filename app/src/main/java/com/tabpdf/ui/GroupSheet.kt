package com.tabpdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tabpdf.tabs.TabGroup

/**
 * 그룹 시트 (기획서 4.3). Tap switches group; long press shows rename / colour / close; long press
 * and drag reorders. The last group can't be closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(
    groups: List<TabGroup>,
    activeGroupId: String,
    tabCount: (groupId: String) -> Int,
    onSelect: (groupId: String) -> Unit,
    onCreate: (name: String) -> Unit,
    onRename: (groupId: String, name: String) -> Unit,
    onColor: (groupId: String, color: Int) -> Unit,
    onReorder: (ids: List<String>) -> Unit,
    onClose: (groupId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val order = remember(groups) { groups.toMutableStateList() }
    val listState = rememberLazyListState()
    val gestures = rememberItemGestureState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TabGroup?>(null) }
    var coloring by remember { mutableStateOf<TabGroup?>(null) }
    var closing by remember { mutableStateOf<TabGroup?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "탭 그룹",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .itemGestures(
                        state = gestures,
                        keyAt = { listState.keyAt(it) },
                        itemTopLeft = { key -> listState.topLeftOf(key) },
                        onTap = { onSelect(it as String) },
                        onLongPress = { menuFor = it as String },
                        onMove = { from, to ->
                            val fromIndex = order.indexOfFirst { it.id == from }
                            val toIndex = order.indexOfFirst { it.id == to }
                            if (fromIndex >= 0 && toIndex >= 0) order.add(toIndex, order.removeAt(fromIndex))
                        },
                        onDragEnd = { onReorder(order.map { it.id }) },
                    ),
            ) {
                items(order, key = { it.id }) { group ->
                    val dragging = gestures.draggingKey == group.id
                    Box(
                        (if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                            .graphicsLayer { translationY = gestures.dragTranslation(group.id).y }
                    ) {
                        // Transparent at rest so rows blend into the sheet; lifted while dragged.
                        Surface(
                            color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                            shadowElevation = if (dragging) 6.dp else 0.dp,
                        ) {
                            GroupRow(group, tabCount(group.id), isActive = group.id == activeGroupId)
                        }
                        DropdownMenu(expanded = menuFor == group.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menuFor = null; renaming = group })
                            DropdownMenuItem(text = { Text("색상 변경") }, onClick = { menuFor = null; coloring = group })
                            DropdownMenuItem(
                                text = { Text(if (groups.size > 1) "그룹 닫기" else "그룹 닫기 (마지막 그룹은 닫을 수 없음)") },
                                enabled = groups.size > 1,
                                onClick = { menuFor = null; closing = group },
                            )
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { creating = true }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("새 그룹")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (creating) {
        NameDialog(
            title = "새 그룹",
            initial = "",
            confirmLabel = "만들기",
            onConfirm = { name -> creating = false; onCreate(name) },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { group ->
        NameDialog(
            title = "이름 변경",
            initial = group.name,
            confirmLabel = "변경",
            onConfirm = { name -> renaming = null; onRename(group.id, name) },
            onDismiss = { renaming = null },
        )
    }
    coloring?.let { group ->
        ColorDialog(
            selected = group.color,
            onSelect = { color -> coloring = null; onColor(group.id, color) },
            onDismiss = { coloring = null },
        )
    }
    closing?.let { group ->
        ConfirmDialog(
            title = "그룹 닫기",
            message = "'${group.name}' 그룹과 탭 ${tabCount(group.id)}개를 닫을까요?",
            confirmLabel = "닫기",
            onConfirm = { closing = null; onClose(group.id) },
            onDismiss = { closing = null },
        )
    }
}

@Composable
private fun GroupRow(group: TabGroup, tabCount: Int, isActive: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupDot(group.color, size = 14.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            group.name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${tabCount}개 탭",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterEnd) {
            if (isActive) Icon(Icons.Default.Check, contentDescription = "현재 그룹", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun LazyListState.keyAt(position: Offset): Any? =
    layoutInfo.visibleItemsInfo.firstOrNull { position.y.toInt() in it.offset until it.offset + it.size }?.key

private fun LazyListState.topLeftOf(key: Any): Offset? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { Offset(0f, it.offset.toFloat()) }
