package com.tabpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tabpdf.FingerDrawing
import com.tabpdf.ink.Brushes
import com.tabpdf.ink.InkTool
import com.tabpdf.ink.ToolStyle
import kotlin.math.roundToInt

/**
 * Handwriting tools under the top bar while handwriting mode is on (기획서 4.1): pen, highlighter,
 * eraser, undo, redo. Tapping the chosen tool again opens its colour and size panel.
 */
@Composable
fun InkToolbar(
    tool: InkTool,
    styles: Map<InkTool, ToolStyle>,
    canUndo: Boolean,
    canRedo: Boolean,
    onTool: (InkTool) -> Unit,
    onStyle: (InkTool, ToolStyle) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    var panelFor by remember { mutableStateOf<InkTool?>(null) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in InkTool.entries) {
                Box {
                    ToolChip(
                        tool = t,
                        style = styles.getValue(t),
                        selected = t == tool,
                        onClick = { if (t == tool) panelFor = t else onTool(t) },
                    )
                    DropdownMenu(expanded = panelFor == t, onDismissRequest = { panelFor = null }) {
                        StylePanel(t, styles.getValue(t)) { onStyle(t, it) }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(UndoIcon, contentDescription = "필기 되돌리기") }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(RedoIcon, contentDescription = "필기 다시 하기") }
        }
    }
}

private val InkTool.label get() = when (this) {
    InkTool.Pen -> "펜"
    InkTool.Highlighter -> "형광펜"
    InkTool.Eraser -> "지우개"
}

@Composable
private fun ToolChip(tool: InkTool, style: ToolStyle, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = tool.label + if (selected) " (다시 누르면 색·굵기)" else "" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tool != InkTool.Eraser) {
            ColorDot(Color(style.color), 12.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(tool.label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ColorDot(color: Color, size: androidx.compose.ui.unit.Dp, selected: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
    )
}

/** Colours (with "직접 고르기") and size for one tool (기획서 5.8.2). */
@Composable
private fun StylePanel(tool: InkTool, style: ToolStyle, onStyle: (ToolStyle) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (tool != InkTool.Eraser) {
            Text("색", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val palette = if (tool == InkTool.Pen) Brushes.penColors else Brushes.highlighterColors
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in palette) {
                    Box(Modifier.clickable { onStyle(style.copy(color = c)) }.semantics { selected = c == style.color }) {
                        ColorDot(Color(c), 28.dp, selected = c == style.color)
                    }
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color.Gray, CircleShape)
                        .clickable { picking = true }
                        .semantics { contentDescription = "색 직접 고르기" },
                    contentAlignment = Alignment.Center,
                ) { Text("+", style = MaterialTheme.typography.titleMedium) }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text("굵기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val presets = when (tool) {
            InkTool.Pen -> Brushes.penSizes
            InkTool.Highlighter -> Brushes.highlighterSizes
            InkTool.Eraser -> Brushes.eraserSizes
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEachIndexed { i, size ->
                val dot = (8 + i * 8).dp
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (style.size == size) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onStyle(style.copy(size = size)) }
                        .semantics { contentDescription = listOf("가늘게", "보통", "굵게")[i] },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(dot).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                }
            }
        }
        val range = Brushes.sizeRange(tool)
        Slider(
            value = style.size.coerceIn(range),
            onValueChange = { onStyle(style.copy(size = (it * 2).roundToInt() / 2f)) },
            valueRange = range,
        )
    }
    if (picking) {
        ColorPickerDialog(
            initial = style.color,
            onPick = {
                picking = false
                onStyle(style.copy(color = if (tool == InkTool.Highlighter) Brushes.highlighterAlpha(it) else it))
            },
            onDismiss = { picking = false },
        )
    }
}

/** Hue, saturation and brightness sliders with a preview. */
@Composable
private fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial or 0xFF000000.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색 고르기") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(color)))
                Spacer(Modifier.height(12.dp))
                Text("색상", style = MaterialTheme.typography.labelMedium)
                Slider(hue, { hue = it }, valueRange = 0f..360f)
                Text("채도", style = MaterialTheme.typography.labelMedium)
                Slider(saturation, { saturation = it })
                Text("밝기", style = MaterialTheme.typography.labelMedium)
                Slider(value, { value = it })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Handwriting settings from the ⋮ menu (기획서 5.6). */
@Composable
fun InkSettingsDialog(
    fingerDrawing: FingerDrawing,
    stylusSeen: Boolean,
    sideButtonErases: Boolean,
    onFingerDrawing: (FingerDrawing) -> Unit,
    onSideButtonErases: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("필기 설정") },
        text = {
            Column {
                Text("필기 모드에서 손가락으로 그리기", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                val options = listOf(
                    FingerDrawing.Auto to "자동 — 펜을 쓰면 펜만 그림" + if (stylusSeen) " (펜 사용 중)" else "",
                    FingerDrawing.Always to "항상 — 펜이 있어도 손가락도 그림",
                    FingerDrawing.Never to "안 함 — 손가락은 넘기기만",
                )
                for ((mode, label) in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == fingerDrawing, role = Role.RadioButton) { onFingerDrawing(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == fingerDrawing, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onSideButtonErases(!sideButtonErases) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("펜 측면 버튼으로 지우기", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "버튼을 누른 채 그리면 지우개",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sideButtonErases, onCheckedChange = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

// Material Symbols "undo" and "redo" (Apache 2.0); material-icons-core doesn't include them.
private val UndoIcon: ImageVector = materialIcon(name = "Undo") {
    materialPath {
        moveTo(12.5f, 8f)
        curveToRelative(-2.65f, 0f, -5.05f, 0.99f, -6.9f, 2.6f)
        lineTo(2f, 7f)
        verticalLineToRelative(9f)
        horizontalLineToRelative(9f)
        lineToRelative(-3.62f, -3.62f)
        curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
        curveToRelative(3.54f, 0f, 6.55f, 2.31f, 7.6f, 5.5f)
        lineToRelative(2.37f, -0.78f)
        curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f)
        close()
    }
}

private val RedoIcon: ImageVector = materialIcon(name = "Redo") {
    materialPath {
        moveTo(18.4f, 10.6f)
        curveTo(16.55f, 8.99f, 14.15f, 8f, 11.5f, 8f)
        curveToRelative(-4.65f, 0f, -8.58f, 3.03f, -9.96f, 7.22f)
        lineTo(3.9f, 16f)
        curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
        curveToRelative(1.95f, 0f, 3.73f, 0.72f, 5.12f, 1.88f)
        lineTo(13f, 16f)
        horizontalLineToRelative(9f)
        verticalLineTo(7f)
        lineToRelative(-3.6f, 3.6f)
        close()
    }
}
