package com.tabpdf.ui

import com.tabpdf.doc.FileFormat
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabpdf.tabs.DocTab
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

/** Go to a page by number (기획서 5.5). [current] and the result are 0-based. */
@Composable
fun PageJumpDialog(current: Int, pageCount: Int, onJump: (page: Int) -> Unit, onDismiss: () -> Unit) {
    val initial = (current + 1).toString()
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val number = value.text.toIntOrNull()
    val valid = number != null && number in 1..pageCount
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("페이지 이동") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { new -> value = new.copy(text = new.text.filter(Char::isDigit).take(6)) },
                singleLine = true,
                label = { Text("1 – $pageCount") },
                isError = value.text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onJump(number!! - 1) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onJump(number!! - 1) }, enabled = valid) { Text("이동") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("EveryViewer $version") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("어떤 파일이든 탭으로 열어 두고 넘겨 보는 뷰어")
                Text(
                    "오픈소스: Kotlin, AndroidX (Jetpack Compose, Room 등) — Apache License 2.0 · " +
                        "commonmark-java — BSD 2-Clause · juniversalchardet — MPL 1.1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
fun FileInfoDialog(tab: DocTab, page: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tab.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("형식", tab.format.label)
                InfoRow("읽던 위치", "${page + 1}페이지")
                when {
                    // A bundle's own file is just its list; where its files are shows in its editor.
                    tab.format == FileFormat.Bundle -> InfoRow("저장 방식", "파일 목록만 앱에 저장 (각 파일은 따로 열린 것과 같음)")
                    else -> {
                        InfoRow(
                            "저장 방식",
                            if (tab.isInternalCopy) "앱에 복사본 저장 (탭을 닫으면 삭제)" else "원본 파일을 직접 엶",
                        )
                        InfoRow("위치", Uri.decode(tab.uri.path ?: tab.uri.toString()))
                    }
                }
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

/** 기획서 5.1: several files picked at once — a tab each, or one bundle. */
@Composable
fun OpenChoiceDialog(count: Int, onBundle: () -> Unit, onSeparate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("파일 ${count}개 열기") },
        text = { Text("하나로 묶으면 한 탭에서 쪽이 이어지는 한 문서처럼 봅니다.") },
        confirmButton = { TextButton(onClick = onBundle) { Text("하나로 묶어 열기") } },
        dismissButton = { TextButton(onClick = onSeparate) { Text("따로 열기") } },
    )
}

/** What a format is called on screen. */
val FileFormat.label: String
    get() = when (this) {
        FileFormat.Pdf -> "PDF"
        FileFormat.Text -> "텍스트"
        FileFormat.Markdown -> "마크다운"
        FileFormat.Html -> "HTML 웹 페이지"
        FileFormat.Image -> "이미지"
        FileFormat.Docx -> "Word 문서"
        FileFormat.Hwpx -> "한글 문서"
        FileFormat.Pptx -> "PowerPoint 프레젠테이션"
        FileFormat.Xlsx -> "Excel 통합 문서"
        FileFormat.Bundle -> "파일 묶음"
        FileFormat.Unsupported -> "지원하지 않는 형식"
    }
