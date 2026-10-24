package com.tabpdf.ui

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabpdf.tabs.DuplicatePrompt
import com.tabpdf.tabs.PdfTab
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.displayName

/**
 * Single text field dialog, used for new group and rename. With [allowBlank] an empty name is
 * accepted (an unnamed group shows as "탭 n개").
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text("그룹 이름") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = allowBlank || value.text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ColorDialog(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("그룹 색상") },
        text = {
            LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(GroupColors.size) { index ->
                    val ring = if (index == selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else Modifier
                    Row(horizontalArrangement = Arrangement.Center) {
                        GroupDot(
                            index,
                            ring.padding(4.dp).clickable { onSelect(index) },
                            size = 36.dp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun FileInfoDialog(tab: PdfTab, page: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tab.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("읽던 위치", "${page + 1}페이지")
                InfoRow(
                    "저장 방식",
                    if (tab.isInternalCopy) "앱에 복사본 저장 (탭을 닫으면 삭제)" else "원본 파일을 직접 엶",
                )
                InfoRow("위치", Uri.decode(tab.uri.path ?: tab.uri.toString()))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Pick a group to add a tab to, or start a new one. */
@Composable
fun AddToGroupDialog(
    groups: List<TabGroup>,
    tabCount: (groupId: String) -> Int,
    onPick: (groupId: String) -> Unit,
    onNewGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("그룹에 추가") },
        text = {
            Column {
                groups.forEach { group ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(group.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupDot(group.color, size = 14.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(group.displayName(tabCount(group.id)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onNewGroup).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("새 그룹")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 기획서 5.1: the file is already open somewhere else — go there, or open it here too. */
@Composable
fun DuplicateDialog(prompt: DuplicatePrompt, onGoThere: () -> Unit, onOpenHere: () -> Unit, onCancel: () -> Unit) {
    val where = prompt.existingPlace?.let { "'$it' 그룹에" } ?: "그룹 밖 탭으로"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("이미 열려 있는 파일") },
        text = { Text("'${prompt.file.name}' 파일이 $where 이미 열려 있습니다.") },
        confirmButton = { TextButton(onClick = onGoThere) { Text("그 탭으로 이동") } },
        dismissButton = { TextButton(onClick = onOpenHere) { Text("여기에도 열기") } },
    )
}
