package com.tabpdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tabpdf.ink.Brushes
import com.tabpdf.ink.INK_PAGE_WIDTH
import com.tabpdf.ink.InkTool
import com.tabpdf.ink.ToolStyle
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import com.tabpdf.MAX_INK_PRESETS
import com.tabpdf.ui.theme.Paper
import com.tabpdf.ui.theme.bar
import java.util.Locale

/**
 * Handwriting tools under the top bar while handwriting mode is on (기획서 4.1). No words: each
 * tool is drawn as itself — pen, highlighter, eraser — standing up, its tip in the colour it
 * writes with, the chosen one raised. Tapping the chosen tool again opens its colour and size panel.
 */
@Composable
fun InkToolbar(
    tool: InkTool,
    styles: Map<InkTool, ToolStyle>,
    recentColors: Map<InkTool, List<Int>>,
    presets: Map<InkTool, List<ToolStyle>>,
    onAddPreset: (InkTool, ToolStyle) -> Unit,
    onRemovePreset: (InkTool, index: Int) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onTool: (InkTool) -> Unit,
    onStyle: (InkTool, ToolStyle) -> Unit,
    onCustomColor: (InkTool, Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    /** Whether fingers draw too, when a pen is in use; null hides the switch. */
    fingerDraws: Boolean?,
    onFingerDraws: (Boolean) -> Unit,
) {
    var panelFor by remember { mutableStateOf<InkTool?>(null) }
    Surface(Modifier.barEdge(), color = MaterialTheme.colorScheme.bar, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in InkTool.entries) {
                Box {
                    ToolPictogram(
                        tool = t,
                        color = Color(styles.getValue(t).color),
                        selected = t == tool,
                        onClick = { if (t == tool) panelFor = t else onTool(t) },
                    )
                    DropdownMenu(expanded = panelFor == t, onDismissRequest = { panelFor = null }) {
                        StylePanel(
                            tool = t,
                            style = styles.getValue(t),
                            recent = recentColors[t].orEmpty(),
                            presets = presets[t].orEmpty(),
                            onStyle = { onStyle(t, it) },
                            onAddPreset = { onAddPreset(t, styles.getValue(t)) },
                            onRemovePreset = { onRemovePreset(t, it) },
                            onCustomColor = { onCustomColor(t, it) },
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            if (fingerDraws != null) {
                // Lit when on, as the handwriting button above.
                IconToggleButton(
                    checked = fingerDraws,
                    onCheckedChange = onFingerDraws,
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(FingerIcon, contentDescription = "손가락으로 그리기")
                }
            }
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

/** The tool itself, standing in the bar; the chosen one stands taller. Its name is for screen readers. */
@Composable
private fun ToolPictogram(tool: InkTool, color: Color, selected: Boolean, onClick: () -> Unit) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .width(40.dp)
            .fillMaxHeight()
            .clipToBounds()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = tool.label },
    ) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val u = size.width / 40f // drawn on a 40 × 52 grid
            val lift = if (selected) 0f else 14f
            shifted(0f, lift * u) { drawTool(tool, color.copy(alpha = 1f), body, outline, u) }
        }
    }
}

private inline fun DrawScope.shifted(x: Float, y: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(x, y)
    block()
    drawContext.transform.translate(-x, -y)
}

/** A tool drawn on a 40 × 52 grid ([u] px per unit), tip up, running off the bottom edge. */
private fun DrawScope.drawTool(tool: InkTool, ink: Color, body: Color, outline: Color, u: Float) {
    fun path(vararg points: Pair<Float, Float>) = Path().apply {
        moveTo(points[0].first * u, points[0].second * u)
        for (p in points.drop(1)) lineTo(p.first * u, p.second * u)
        close()
    }
    val line = Stroke(width = 1.2f * u)
    when (tool) {
        InkTool.Pen -> {
            val barrel = path(13f to 20f, 27f to 20f, 27f to 60f, 13f to 60f)
            val cone = path(13f to 20f, 27f to 20f, 20f to 5f)
            drawPath(barrel, body); drawPath(barrel, outline, style = line)
            drawPath(cone, body); drawPath(cone, outline, style = line)
            drawPath(path(16.8f to 12f, 23.2f to 12f, 20f to 5f), ink) // the nib, in the ink's colour
            drawRect(ink, Offset(13f * u, 26f * u), Size(14f * u, 4f * u)) // and a band on the barrel
        }
        InkTool.Highlighter -> {
            val barrel = path(11f to 22f, 29f to 22f, 29f to 60f, 11f to 60f)
            val tip = path(14f to 22f, 26f to 22f, 26f to 10f, 14f to 15f) // chisel tip
            drawPath(barrel, body); drawPath(barrel, outline, style = line)
            drawPath(tip, ink); drawPath(tip, outline, style = line)
            drawRect(ink, Offset(11f * u, 28f * u), Size(18f * u, 6f * u))
        }
        InkTool.Eraser -> {
            drawRoundRect(Color(0xFFF4A7B9), Offset(12f * u, 6f * u), Size(16f * u, 16f * u), CornerRadius(4f * u))
            drawRoundRect(outline, Offset(12f * u, 6f * u), Size(16f * u, 16f * u), CornerRadius(4f * u), style = line)
            val barrel = path(12f to 20f, 28f to 20f, 28f to 60f, 12f to 60f)
            drawPath(barrel, body); drawPath(barrel, outline, style = line)
            drawRect(outline.copy(alpha = 0.6f), Offset(12f * u, 20f * u), Size(16f * u, 5f * u))
        }
    }
}

/** Swatches in one row: the panel's inner width (288dp) holds this many. */
private const val SWATCHES_PER_ROW = 10

/** A swatch's touch area; the circle drawn in it is a little smaller (24dp). */
private val SwatchSize = 28.dp

/**
 * The tool's presets, colour swatches and the size, shown live (기획서 5.8.2). The swatches fill
 * one row: the tool's colours, then colours picked by hand for as many places as are left, then
 * the rainbow.
 */
@Composable
private fun StylePanel(
    tool: InkTool,
    style: ToolStyle,
    recent: List<Int>,
    presets: List<ToolStyle>,
    onStyle: (ToolStyle) -> Unit,
    onCustomColor: (Int) -> Unit,
    onAddPreset: () -> Unit,
    onRemovePreset: (index: Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (tool != InkTool.Eraser) {
            Row {
                presets.forEachIndexed { index, preset ->
                    PresetSwatch(
                        tool, preset, index,
                        selected = preset == style,
                        onClick = { onStyle(preset) },
                        onRemove = { onRemovePreset(index) },
                    )
                }
                val canAdd = style !in presets && presets.size < MAX_INK_PRESETS
                Box(
                    Modifier.size(SwatchSize).clip(CircleShape).clickable(enabled = canAdd, onClick = onAddPreset),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "지금 스타일을 프리셋에 추가",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canAdd) 1f else 0.38f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            val toolColors = if (tool == InkTool.Pen) Brushes.penColors else Brushes.highlighterColors
            val picked = recent.filter { it !in toolColors }.take(SWATCHES_PER_ROW - 1 - toolColors.size)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (c in toolColors + picked) {
                    Swatch(Color(c), selected = c == style.color) { onStyle(style.copy(color = c)) }
                }
                CustomColorSwatch { picking = true }
                // Fewer than a row: the empty places go at the end, so the swatches stay evenly spaced.
                repeat(SWATCHES_PER_ROW - 1 - toolColors.size - picked.size) { Spacer(Modifier.size(SwatchSize)) }
            }
            Spacer(Modifier.height(12.dp))
        }
        SizePreview(tool, style, Modifier.fillMaxWidth().height(64.dp))
        val range = Brushes.sizeRange(tool)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = style.size.coerceIn(range),
                onValueChange = { onStyle(style.copy(size = (it * 2).roundToInt() / 2f)) },
                valueRange = range,
                modifier = Modifier.weight(1f).semantics { contentDescription = "굵기" },
            )
            Text(
                sizeLabel(style.size),
                Modifier.width(40.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
            )
        }
    }
    if (picking) {
        ColorPickerDialog(
            initial = style.color,
            onPick = {
                picking = false
                val color = if (tool == InkTool.Highlighter) Brushes.highlighterAlpha(it) else it
                onStyle(style.copy(color = color))
                onCustomColor(color)
            },
            onDismiss = { picking = false },
        )
    }
}

/** A size as the panel shows it: "5", or "2.5" (sizes go in halves). */
internal fun sizeLabel(size: Float): String =
    if (size % 1f == 0f) size.toInt().toString() else String.format(Locale.ROOT, "%.1f", size)

/**
 * A saved colour and size: a dot in that colour on paper white, bigger the bigger the size is
 * within the tool's range. Holding it offers to delete it.
 */
@Composable
private fun PresetSwatch(
    tool: InkTool,
    preset: ToolStyle,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    val range = Brushes.sizeRange(tool)
    val share = ((preset.size - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Box {
        Box(
            Modifier
                .size(SwatchSize)
                .clip(CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .semantics {
                    contentDescription = "프리셋 ${index + 1}: 굵기 ${sizeLabel(preset.size)}"
                    this.selected = selected
                },
        ) {
            val swatchEdge = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            Canvas(Modifier.size(SwatchSize)) {
                val r = 12.dp.toPx()
                if (selected) drawCircle(ring, r, style = Stroke(width = 2.dp.toPx()))
                val inner = if (selected) r - 4.dp.toPx() else r - 1.dp.toPx()
                drawCircle(Paper, inner)
                drawCircle(swatchEdge, inner, style = Stroke(width = 1.dp.toPx()))
                val dot = 2.dp.toPx() + share * (inner - 4.dp.toPx())
                drawCircle(Color(preset.color), dot)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("삭제") }, onClick = { menu = false; onRemove() })
        }
    }
}

/** A colour to pick, on white so see-through highlighter colours look as they will on paper. */
@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    val swatchEdge = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(
        Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(SwatchSize)) {
            val r = 12.dp.toPx()
            if (selected) drawCircle(ring, r, style = Stroke(width = 2.dp.toPx()))
            val inner = if (selected) r - 4.dp.toPx() else r - 1.dp.toPx()
            drawCircle(Paper, inner)
            drawCircle(color, inner)
            drawCircle(swatchEdge, inner, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

/** The usual "any colour" button: a rainbow ring. */
@Composable
private fun CustomColorSwatch(onClick: () -> Unit) {
    Box(
        Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "색 직접 고르기" },
    ) {
        Canvas(Modifier.size(SwatchSize)) {
            val r = 12.dp.toPx()
            drawCircle(Brush.sweepGradient(HueColors), r)
            drawCircle(Paper, r - 5.dp.toPx())
        }
    }
}

private val HueColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/**
 * The tool's mark at its real size on screen (at normal zoom): a curve in the pen's or
 * highlighter's colour and width, or the eraser's reach as a circle.
 */
@Composable
private fun SizePreview(tool: InkTool, style: ToolStyle, modifier: Modifier) {
    val density = LocalDensity.current
    // Page units to pixels at normal zoom, where a page is as wide as the screen.
    val unit = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() } / INK_PAGE_WIDTH
    val edge = MaterialTheme.colorScheme.outlineVariant
    val reach = MaterialTheme.colorScheme.outline
    val shape = MaterialTheme.shapes.small
    Canvas(modifier.clip(shape).background(Paper).border(1.dp, edge, shape)) {
        val width = style.size * unit
        if (tool == InkTool.Eraser) {
            drawCircle(reach, width / 2, center, style = Stroke(width = 1.5.dp.toPx()))
            return@Canvas
        }
        val pad = 24.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height
        val curve = Path().apply {
            moveTo(pad, h * 0.62f)
            cubicTo(pad + w * 0.25f, h * 0.05f, pad + w * 0.45f, h * 0.05f, pad + w * 0.5f, h * 0.5f)
            cubicTo(pad + w * 0.55f, h * 0.95f, pad + w * 0.75f, h * 0.95f, pad + w, h * 0.38f)
        }
        drawPath(
            curve,
            Color(style.color),
            style = Stroke(width = width, cap = if (tool == InkTool.Highlighter) StrokeCap.Butt else StrokeCap.Round),
        )
    }
}

/**
 * Picking any colour, as drawing apps do: a saturation × brightness square for the chosen hue and
 * a hue strip under it, with the old and new colour side by side. Icons only.
 */
@Composable
private fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial or 0xFF000000.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val picked = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.width(300.dp).padding(20.dp)) {
                SaturationValueSquare(hue, saturation, value) { s, v ->
                    saturation = s
                    value = v
                }
                Spacer(Modifier.height(16.dp))
                HueStrip(hue) { hue = it }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Before → after
                    Row(Modifier.height(36.dp).width(96.dp).clip(CircleShape)) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(initial or 0xFF000000.toInt())))
                        Box(Modifier.weight(1f).fillMaxHeight().background(picked))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "취소") }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { onPick(picked.toArgb()) }) {
                        Icon(Icons.Default.Check, contentDescription = "이 색 쓰기")
                    }
                }
            }
        }
    }
}

@Composable
private fun SaturationValueSquare(hue: Float, saturation: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.3f)
            .pointerInput(Unit) {
                fun set(p: Offset) = onChange(
                    (p.x / size.width).coerceIn(0f, 1f),
                    1f - (p.y / size.height).coerceIn(0f, 1f),
                )
                awaitEachGesture {
                    val down = awaitFirstDown()
                    set(down.position)
                    drag(down.id) { set(it.position); it.consume() }
                }
            }
            .semantics { contentDescription = "채도와 밝기" },
    ) {
        // Rounded by drawing, not clipping, so the thumb stays whole at the edges.
        val corner = CornerRadius(12.dp.toPx())
        drawRoundRect(Brush.horizontalGradient(listOf(Color.White, pure)), cornerRadius = corner)
        drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), cornerRadius = corner)
        thumb(Offset(saturation * size.width, (1f - value) * size.height))
    }
}

@Composable
private fun HueStrip(hue: Float, onChange: (Float) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                fun set(p: Offset) = onChange((p.x / size.width).coerceIn(0f, 1f) * 360f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    set(down.position)
                    drag(down.id) { set(it.position); it.consume() }
                }
            }
            .semantics { contentDescription = "색상" },
    ) {
        drawRoundRect(Brush.horizontalGradient(HueColors), cornerRadius = CornerRadius(size.height / 2))
        thumb(Offset(hue / 360f * size.width, size.height / 2))
    }
}

private fun DrawScope.thumb(at: Offset) {
    drawCircle(Color.White, 10.dp.toPx(), at, style = Stroke(width = 3.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.4f), 11.5.dp.toPx(), at, style = Stroke(width = 1.dp.toPx()))
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

/** A finger touching the screen (Material "touch app"). */
private val FingerIcon: ImageVector = ImageVector.Builder("Finger", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes(
        "M18.19 12.44l-3.24-1.62c1.29-1 2.12-2.56 2.12-4.32 0-3.03-2.47-5.5-5.5-5.5s-5.5 2.47-5.5 5.5c0 2.13 1.22 3.98 3 4.89v3.26" +
            "c-2.15-.46-2.02-.44-2.26-.44-.53 0-1.03.21-1.41.59L4 16.22l5.09 5.09c.43.44 1.03.69 1.65.69h6.3c.98 0 1.81-.7 1.97-1.67" +
            "l.8-4.71c.22-1.3-.43-2.58-1.62-3.18zM17.32 15.3l-.8 4.7H10.74c-.09 0-.17-.04-.24-.1l-3.68-3.68 4.25.89V6.5" +
            "c0-.28.22-.5.5-.5s.5.22.5.5v6h1.76l3.46 1.73c.4.2.62.63.53 1.07zM8.07 6.5c0-1.93 1.57-3.5 3.5-3.5s3.5 1.57 3.5 3.5" +
            "c0 .95-.38 1.81-1 2.44V6.5c0-1.38-1.12-2.5-2.5-2.5s-2.5 1.12-2.5 2.5v2.44c-.62-.63-1-1.49-1-2.44z"
    ),
    fill = SolidColor(Color.Black),
).build()
