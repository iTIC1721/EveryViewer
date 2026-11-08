package com.tabpdf.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tabpdf.ink.Brushes
import com.tabpdf.ink.CharStyle
import com.tabpdf.ink.INK_PAGE_WIDTH
import com.tabpdf.ink.InkText
import com.tabpdf.ink.InkTextLayout
import com.tabpdf.ink.MAX_TEXT_SIZE
import com.tabpdf.ink.MIN_TEXT_SIZE
import com.tabpdf.ink.TextAlign
import com.tabpdf.ui.theme.bar
import kotlin.math.roundToInt

/**
 * A text box being typed into in one viewer (기획서 5.8.6). [original] is how it was before (null
 * for a new one); [box] holds where it is and how wide, its text lives in [field] while it is open.
 */
class TextEditing(val original: InkText?, box: InkText, val caret: Int) {
    var box by mutableStateOf(box)
    var field: TextBoxField? = null

    /** Bumped on every change of the text, its styles or the selection. */
    var version by mutableIntStateOf(0)

    /** The editor's size on screen, in px. */
    var size by mutableStateOf(IntSize.Zero)

    /** The box as it is now. */
    fun current(): InkText = box.copy(content = field?.rich ?: box.content)
}

/** Space kept around the text inside the frame, and the handles' size. */
private val FramePad = 4.dp
private val HandleSize = 28.dp

/**
 * The open text box over the page: the text field at the box's place ([origin], viewer px, read at
 * layout time so scrolling doesn't recompose), at [scale] px per page unit, in a frame with a handle
 * to move it (top left) and one to set its width (right edge).
 */
@Composable
fun TextBoxEditor(
    editing: TextEditing,
    origin: () -> Offset,
    scale: Float,
    onMoveBy: (Offset) -> Unit,
    onWidthBy: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val box = editing.box
    val frame = MaterialTheme.colorScheme.primary
    val pad = with(density) { FramePad.toPx() }
    val handle = with(density) { HandleSize.toPx() }
    val widthModifier = with(density) {
        if (box.width > 0f) Modifier.width((box.width * scale).toDp())
        else Modifier.widthIn(min = 2.dp, max = (InkTextLayout.availableWidth(box) * scale).toDp())
    }
    // The frame, just outside the text.
    Canvas(
        Modifier
            .offset { origin().let { IntOffset((it.x - pad).roundToInt(), (it.y - pad).roundToInt()) } }
            .size(with(density) { (editing.size.width + 2 * pad).toDp() }, with(density) { (editing.size.height + 2 * pad).toDp() })
    ) {
        drawRect(frame, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    AndroidView(
        factory = { ctx ->
            TextBoxField(ctx, box.content, scale).also { f ->
                editing.field = f
                f.onChange = { editing.version++ }
                f.placeCursor(editing.caret)
                // Once in the window: focus taken before that doesn't hold.
                f.post {
                    f.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(f, 0)
                    editing.version++
                }
            }
        },
        update = { it.rescale(scale) },
        modifier = Modifier
            .offset { origin().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
            .then(widthModifier)
            .onSizeChanged { editing.size = it },
    )
    // Move: a round handle just above the top-left corner, clear of the text.
    Box(
        Modifier
            .offset { origin().let { IntOffset((it.x - pad).roundToInt(), (it.y - pad - handle).roundToInt()) } }
            .size(HandleSize)
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); onMoveBy(drag) } }
            .semantics { contentDescription = "텍스트 상자 이동" },
    ) {
        Canvas(Modifier.size(HandleSize)) {
            drawCircle(frame, size.minDimension / 2 - 2.dp.toPx())
        }
        Icon(MoveIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.Center).size(16.dp))
    }
    // Width: an upright bar at the middle of the right edge.
    Box(
        Modifier
            .offset {
                origin().let { IntOffset((it.x + editing.size.width + pad - handle / 2).roundToInt(), (it.y + editing.size.height / 2f - handle / 2).roundToInt()) }
            }
            .size(HandleSize)
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); onWidthBy(drag.x) } }
            .semantics { contentDescription = "텍스트 상자 너비" },
    ) {
        Canvas(Modifier.size(HandleSize)) {
            val w = 6.dp.toPx()
            val h = 20.dp.toPx()
            drawRoundRect(
                frame, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
    }
}

/** Where the open editor and its handles are (viewer px), so touches there are left to them. */
fun TextEditing.chrome(origin: Offset, density: Float): List<Rect> {
    val pad = 4f * density
    val handle = 28f * density
    val frame = Rect(origin.x - pad, origin.y - pad, origin.x + size.width + pad, origin.y + size.height + pad)
    return listOf(
        frame,
        Rect(Offset(frame.left, frame.top - handle), Size(handle, handle)),
        Rect(Offset(frame.right - handle / 2, origin.y + size.height / 2f - handle / 2), Size(handle, handle)),
    )
}

/**
 * Styles for the open text box, just above the keyboard (기획서 5.8.6): bold, italic, underline,
 * strike-through, alignment, size, colour, delete, done. A style is lit when all the selected
 * characters have it.
 */
@Composable
fun TextFormatBar(
    editing: TextEditing,
    recentColors: List<Int>,
    onCustomColor: (Int) -> Unit,
    /** Size or colour chosen: new boxes start with it too. */
    onDefault: (CharStyle) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    /** Told whether the bar is on screen (it goes away with the keyboard). */
    onShown: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var alignMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // Above the keyboard, so it goes when the keyboard is put away (as keyboard toolbars do), but
    // stays while one of its menus is open (they take the keyboard away) and a moment after, while
    // the keyboard comes back. With a hardware keyboard no keyboard shows on screen: it always stays.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val hardKeyboard = config.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
        config.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val wanted = keyboard || hardKeyboard || alignMenu || sizeMenu || colorMenu || picking
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(wanted) {
        if (!wanted) delay(KEYBOARD_GRACE_MS)
        shown = wanted
    }
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(shown) { currentOnShown(shown) }
    if (!shown) return

    @Suppress("UNUSED_VARIABLE") val changed = editing.version // read so the bar follows the selection
    val field = editing.field
    val styles = field?.selectedStyles().orEmpty()
    val first = styles.firstOrNull() ?: editing.box.content.base
    fun all(test: (CharStyle) -> Boolean) = styles.isNotEmpty() && styles.all(test)
    fun style(change: (CharStyle) -> CharStyle) {
        field?.style(change)
    }
    val toggle = IconButtonDefaults.iconToggleButtonColors(
        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Surface(modifier, color = MaterialTheme.colorScheme.bar, contentColor = MaterialTheme.colorScheme.onSurface) {
        // Done stays in reach at the right; the styles scroll on narrow screens.
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            // Swipes on the bar stay on the bar: its own scrolling takes the drag (even with nothing to
            // scroll), and what is left over at either end is kept here, not passed on to the pager.
            // (Consuming the touches instead would also stop the bar's own scrolling.)
            Modifier.weight(1f).nestedScroll(KeepScroll).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bold = all { it.bold }
            IconToggleButton(bold, { on -> style { it.copy(bold = on) } }, colors = toggle) { Icon(BoldIcon, contentDescription = "굵게") }
            val italic = all { it.italic }
            IconToggleButton(italic, { on -> style { it.copy(italic = on) } }, colors = toggle) { Icon(ItalicIcon, contentDescription = "기울임꼴") }
            val underline = all { it.underline }
            IconToggleButton(underline, { on -> style { it.copy(underline = on) } }, colors = toggle) { Icon(UnderlineIcon, contentDescription = "밑줄") }
            val strike = all { it.strike }
            IconToggleButton(strike, { on -> style { it.copy(strike = on) } }, colors = toggle) { Icon(StrikeIcon, contentDescription = "취소선") }

            val align = field?.selectedAlign() ?: TextAlign.Left
            Box {
                IconButton(onClick = { alignMenu = true }) { Icon(align.icon, contentDescription = "정렬") }
                DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false; field?.showKeyboard() }) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        for (a in TextAlign.entries) {
                            IconToggleButton(a == align, { field?.align(a); alignMenu = false; field?.showKeyboard() }, colors = toggle) {
                                Icon(a.icon, contentDescription = a.label)
                            }
                        }
                    }
                }
            }

            val sameSize = styles.map { it.size }.distinct().singleOrNull()
            Box {
                TextButton(onClick = { sizeMenu = true }, modifier = Modifier.semantics { contentDescription = "글자 크기" }) {
                    Text(sameSize?.let(::sizeLabel) ?: "–", style = MaterialTheme.typography.titleSmall)
                }
                DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false; field?.showKeyboard() }) {
                    Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            for (s in TEXT_SIZE_STEPS) {
                                TextButton(
                                    onClick = { style { it.copy(size = s) }; onDefault(first.copy(size = s)) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                ) {
                                    Text(sizeLabel(s), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = (sameSize ?: first.size).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE),
                                onValueChange = { v -> val s = v.roundToInt().toFloat(); style { it.copy(size = s) } },
                                onValueChangeFinished = { onDefault(first) },
                                valueRange = MIN_TEXT_SIZE..MAX_TEXT_SIZE,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "글자 크기" },
                            )
                            Text(sizeLabel(sameSize ?: first.size), Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            val sameColor = styles.map { it.color }.distinct().singleOrNull()
            Box {
                IconButton(onClick = { colorMenu = true }, modifier = Modifier.semantics { contentDescription = "글자 색상" }) {
                    Canvas(Modifier.size(22.dp)) {
                        drawCircle(Color(sameColor ?: first.color))
                        drawCircle(Color.Black.copy(alpha = 0.25f), style = Stroke(width = 1.dp.toPx()))
                    }
                }
                DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false; field?.showKeyboard() }) {
                    val picked = recentColors.filter { it !in Brushes.penColors }.take(TEXT_SWATCHES - 1 - Brushes.penColors.size)
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        for (c in Brushes.penColors + picked) {
                            Swatch(Color(c), selected = c == sameColor) {
                                style { it.copy(color = c) }
                                onDefault(first.copy(color = c))
                                colorMenu = false
                                field?.showKeyboard()
                            }
                        }
                        CustomColorSwatch { picking = true; colorMenu = false }
                    }
                }
            }
            if (picking) {
                ColorPickerDialog(
                    initial = sameColor ?: first.color,
                    onPick = { c ->
                        picking = false
                        style { it.copy(color = c) }
                        onDefault(first.copy(color = c))
                        onCustomColor(c)
                        field?.showKeyboard()
                    },
                    onDismiss = { picking = false; field?.showKeyboard() },
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "텍스트 상자 삭제") }
        }
        IconButton(onClick = onDone) { Icon(Icons.Default.Check, contentDescription = "완료") }
        }
    }
}

/** Takes whatever scrolling is left over, so none of it goes on to the pager. */
private val KeepScroll = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}

/** How long the bar waits for the keyboard to come back before it goes too. */
private const val KEYBOARD_GRACE_MS = 400L

private const val TEXT_SWATCHES = 10
private val TEXT_SIZE_STEPS = listOf(12f, 16f, 24f, 32f, 48f)

/** Keeps a box on its page: [x], [y] (page units) for a box [w] × [h] on a page [pageHeight] tall. */
fun clampBox(x: Float, y: Float, w: Float, h: Float, pageHeight: Float) = Offset(
    x.coerceIn(0f, (INK_PAGE_WIDTH - w.coerceAtMost(INK_PAGE_WIDTH)).coerceAtLeast(0f)),
    y.coerceIn(0f, (pageHeight - h).coerceAtLeast(0f)),
)

private val TextAlign.label get() = when (this) {
    TextAlign.Left -> "왼쪽 정렬"
    TextAlign.Center -> "가운데 정렬"
    TextAlign.Right -> "오른쪽 정렬"
}

private val TextAlign.icon get() = when (this) {
    TextAlign.Left -> AlignLeftIcon
    TextAlign.Center -> AlignCenterIcon
    TextAlign.Right -> AlignRightIcon
}

// Material icons (Apache 2.0) that material-icons-core doesn't include.
private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

private val BoldIcon = icon(
    "Bold",
    "M15.6 10.79c.97-.67 1.65-1.77 1.65-2.79 0-2.26-1.75-4-4-4H7v14h7.04c2.09 0 3.71-1.7 3.71-3.79 0-1.52-.86-2.82-2.15-3.42z" +
        "M10 6.5h3c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5h-3v-3zm3.5 9H10v-3h3.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5z",
)
private val ItalicIcon = icon("Italic", "M10 4v3h2.21l-3.42 8H6v3h8v-3h-2.21l3.42-8H18V4z")
private val UnderlineIcon = icon(
    "Underline",
    "M12 17c3.31 0 6-2.69 6-6V3h-2.5v8c0 1.93-1.57 3.5-3.5 3.5S8.5 12.93 8.5 11V3H6v8c0 3.31 2.69 6 6 6zm-7 2v2h14v-2H5z",
)
private val StrikeIcon = icon("Strikethrough", "M10 19h4v-3h-4v3zM5 4v3h5v3h4V7h5V4H5zM3 14h18v-2H3v2z")
private val AlignLeftIcon = icon("AlignLeft", "M15 15H3v2h12v-2zm0-8H3v2h12V7zM3 13h18v-2H3v2zm0 8h18v-2H3v2zM3 3v2h18V3H3z")
private val AlignCenterIcon = icon("AlignCenter", "M7 15v2h10v-2H7zm-4 6h18v-2H3v2zm0-8h18v-2H3v2zm4-6v2h10V7H7zM3 3v2h18V3H3z")
private val AlignRightIcon = icon("AlignRight", "M3 21h18v-2H3v2zm6-4h12v-2H9v2zm-6-4h18v-2H3v2zm6-4h12V7H9v2zM3 3v2h18V3H3z")
private val MoveIcon = icon(
    "Move",
    "M10 9h4V6h3l-5-5-5 5h3v3zm-1 1H6V7l-5 5 5 5v-3h3v-4zm14 2l-5-5v3h-3v4h3v3l5-5zm-9 3h-4v3H7l5 5 5-5h-3v-3z",
)
