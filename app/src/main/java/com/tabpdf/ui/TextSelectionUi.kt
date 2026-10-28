package com.tabpdf.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PointF
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tabpdf.doc.Document
import com.tabpdf.doc.TextSelection
import com.tabpdf.doc.guarded
import kotlin.math.roundToInt

/** One end of a text selection: a page, and a point on it in fractions of its width and height. */
data class SelectionEnd(val page: Int, val point: Offset) : Comparable<SelectionEnd> {
    /** Reading order: page, then line, then along the line. Points this close in height are on one line. */
    override fun compareTo(other: SelectionEnd): Int = when {
        page != other.page -> page.compareTo(other.page)
        kotlin.math.abs(point.y - other.point.y) > SAME_LINE -> point.y.compareTo(other.point.y)
        else -> point.x.compareTo(other.point.x)
    }

    private companion object {
        /** A fraction of the page height well under one line of small print. */
        const val SAME_LINE = 0.006f
    }
}

/** The two ends of a selection, as the reader placed them (either may come first). */
data class SelectionAnchors(val start: SelectionEnd, val end: SelectionEnd) {
    fun ordered(): Pair<SelectionEnd, SelectionEnd> = if (start <= end) start to end else end to start

    companion object {
        /** Ends just inside the first and last letters of [selection] on [page] (a word just picked). */
        fun around(page: Int, selection: TextSelection): SelectionAnchors {
            val first = selection.rects.first()
            val last = selection.rects.last()
            return SelectionAnchors(
                SelectionEnd(page, Offset(first.left + first.width() * 0.01f, first.centerY())),
                SelectionEnd(page, Offset(last.right - last.width() * 0.01f, last.centerY())),
            )
        }
    }
}

/** Longest stretch of pages one selection runs over; beyond that the end page is cut short. */
private const val MAX_SELECTION_PAGES = 200

/** The text between [anchors], page by page: the first and last pages from their ends, the rest whole. */
suspend fun selectRange(document: Document, anchors: SelectionAnchors): List<Pair<Int, TextSelection>> {
    val (s, e) = anchors.ordered()
    val lastPage = minOf(e.page, s.page + MAX_SELECTION_PAGES)
    return (s.page..lastPage).mapNotNull { page ->
        val from = if (page == s.page) s.point else Offset.Zero
        val to = if (page == e.page) e.point else Offset(1f, 1f)
        guarded(null) { document.selectText(page, PointF(from.x, from.y), PointF(to.x, to.y)) }?.let { page to it }
    }
}

/** The selected text of all pages; pages that don't end in a line break get one. */
fun joinSelection(selection: List<Pair<Int, TextSelection>>): String = buildString {
    for ((_, part) in selection) {
        if (isNotEmpty() && !last().isWhitespace() && part.text.firstOrNull()?.isWhitespace() == false) append('\n')
        append(part.text)
    }
}.trim()

/**
 * A selection handle as Android draws them: a drop whose point sits at [tip] (under the line's
 * start or end), hanging left for the start and right for the end. Dragging it calls [onMove] with
 * where its point now is.
 */
@Composable
fun SelectionHandle(tip: Offset, isStart: Boolean, onBounds: (Rect) -> Unit, onMove: (Offset) -> Unit) {
    val density = LocalDensity.current
    val radius = with(density) { 11.dp.toPx() }
    // A larger box than the drop, for the finger.
    val touch = with(density) { 40.dp.toPx() }
    val color = MaterialTheme.colorScheme.primary
    val currentTip by rememberUpdatedState(tip)
    val currentOnMove by rememberUpdatedState(onMove)
    val left = if (isStart) tip.x - touch + (touch - 2 * radius) / 2 else tip.x - (touch - 2 * radius) / 2
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), tip.y.roundToInt()) }
            .size(with(density) { touch.toDp() })
            .onGloballyPositioned { onBounds(it.boundsInParent()) }
            .semantics { contentDescription = if (isStart) "선택 시작" else "선택 끝" }
            .pointerInput(isStart) {
                var at = Offset.Zero
                detectDragGestures(onDragStart = { at = currentTip }) { change, amount ->
                    change.consume()
                    at += amount
                    currentOnMove(at)
                }
            }
    ) {
        Canvas(Modifier.size(with(density) { touch.toDp() })) {
            // The drop's point is at the box's top edge, on the side towards the text.
            val pointX = if (isStart) size.width - (touch - 2 * radius) / 2 else (touch - 2 * radius) / 2
            val centerX = if (isStart) pointX - radius else pointX + radius
            drawCircle(color, radius, Offset(centerX, radius))
            drawRect(color, topLeft = Offset(minOf(pointX, centerX), 0f), size = Size(radius, radius))
        }
    }
}

/** What to do with the selection, as Android's text toolbar offers: copy and share. */
@Composable
fun SelectionToolbar(text: () -> String, onDone: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp) {
        Row {
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("EveryViewer", text()))
                // Android 13+ shows its own confirmation.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "복사했습니다", Toast.LENGTH_SHORT).show()
                onDone()
            }) { Text("복사") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text())
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onDone()
            }) { Text("공유") }
        }
    }
}
