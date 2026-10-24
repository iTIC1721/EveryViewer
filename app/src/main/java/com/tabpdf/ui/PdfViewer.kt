package com.tabpdf.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tabpdf.pdf.PdfDocument
import com.tabpdf.tabs.ViewerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private val PAGE_GAP = 8.dp

/** Largest bitmap we render for one page, to keep memory bounded on big zooms (~32 MB). */
private const val MAX_RENDER_PIXELS = 8_000_000f

/**
 * Vertically scrolling, zoomable view of one PDF.
 *
 * Gesture rule (기획서 5.2): a horizontal drag is left unconsumed — so the enclosing tab pager
 * turns the tab — unless the page is zoomed in *and* can still pan in that direction when the
 * drag starts. A drag that starts panning keeps panning even after it hits the edge; the reader
 * has to lift and swipe again to change tabs, like a photo gallery.
 */
@Composable
fun PdfViewer(
    uri: Uri,
    /** Changing this reopens the file even if [uri] is the same (after "다시 찾기"). */
    revision: Int,
    initialState: ViewerState,
    onStateChange: (ViewerState) -> Unit,
    onTap: () -> Unit,
    onRelocate: () -> Unit,
    onClose: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<PdfDocument?>(null) }
    var error by remember { mutableStateOf<Exception?>(null) }

    LaunchedEffect(uri, revision) {
        error = null
        val doc = try {
            // Not cancellable, so a document opened just as the page leaves is still closed below.
            withContext(NonCancellable) { PdfDocument.open(context, uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
            return@LaunchedEffect
        }
        document = doc
        try {
            awaitCancellation()
        } finally {
            document = null
            doc.close()
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Zoomed pages are drawn wider than the viewport; keep them off the neighbouring tab.
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val doc = document
        val failure = error
        when {
            failure != null -> OpenFailure(failure, onRelocate, onClose, Modifier.align(Alignment.Center))
            doc == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> PdfPages(
                document = doc,
                viewportWidth = constraints.maxWidth.toFloat(),
                initialState = initialState,
                onStateChange = onStateChange,
                onTap = onTap,
                contentPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun PdfPages(
    document: PdfDocument,
    viewportWidth: Float,
    initialState: ViewerState,
    onStateChange: (ViewerState) -> Unit,
    onTap: () -> Unit,
    contentPadding: PaddingValues,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    /** Height of page [index] at [zoom], in px — the unit the saved fractional offset is relative to. */
    fun pageHeight(index: Int, zoom: Float) = viewportWidth * zoom * document.pageSizes[index].aspect

    // The file may have fewer pages than when the position was saved.
    val initialPage = initialState.page.coerceIn(0, document.pageCount - 1)
    val initialZoom = initialState.zoom.coerceIn(1f, MAX_ZOOM)
    val listState = rememberLazyListState(
        initialPage,
        (initialState.pageOffset * pageHeight(initialPage, initialZoom)).roundToInt(),
    )
    var zoom by remember { mutableFloatStateOf(initialZoom) }
    var panX by remember {
        mutableFloatStateOf((initialState.panX * viewportWidth).coerceIn(0f, viewportWidth * (initialZoom - 1f)))
    }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnStateChange by rememberUpdatedState(onStateChange)

    fun currentState(): ViewerState {
        val page = listState.firstVisibleItemIndex
        return ViewerState(
            page = page,
            pageOffset = listState.firstVisibleItemScrollOffset / pageHeight(page, zoom),
            zoom = zoom,
            panX = panX / viewportWidth,
        )
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentState() }.distinctUntilChanged().collect { currentOnStateChange(it) }
    }

    fun maxPan() = viewportWidth * (zoom - 1f)

    fun panBy(dx: Float) {
        panX = (panX + dx).coerceIn(0f, maxPan())
    }

    /**
     * Zooms by [factor] keeping the content under [focus] (viewport coordinates) in place.
     * Pages and the gaps between them all scale together, so the content offset from the first
     * visible item's top scales exactly by the zoom ratio.
     */
    fun zoomBy(factor: Float, focus: Offset) {
        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val ratio = newZoom / zoom
        if (ratio == 1f) return
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        zoom = newZoom
        panX = ((panX + focus.x) * ratio - focus.x).coerceIn(0f, maxPan())
        val newOffset = ((offset + focus.y) * ratio - focus.y).roundToInt().coerceAtLeast(0)
        // Undispatched so the scroll position updates before the next pointer event reads it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { listState.scrollToItem(index, newOffset) }
    }

    fun animateZoomTo(target: Float, focus: Offset) {
        scope.launch {
            animate(zoom, target, animationSpec = tween(200)) { value, _ -> zoomBy(value / zoom, focus) }
        }
    }

    val gapPx = with(density) { PAGE_GAP.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            // Pinch: watched on the Initial pass so two-finger moves never reach the list or pager.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val pan = event.calculatePan()
                            zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = true))
                            panBy(-pan.x)
                            listState.dispatchRawDelta(-pan.y)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // One finger: horizontal pan while zoomed, taps. Main pass, after the list has seen
            // the event (so vertical scrolling and fling stay the list's) and before the pager.
            .pointerInput(Unit) {
                coroutineScope {
                    var lastTapTime = 0L
                    var lastTapPosition = Offset.Zero
                    var pendingTap: Job? = null
                    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                    val slop = viewConfiguration.touchSlop

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var total = Offset.Zero
                        var decided = false
                        var panning = false
                        var multiTouch = false
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) multiTouch = true
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            // Raw movement: the list may already have consumed the vertical part.
                            val delta = change.position - change.previousPosition
                            total += delta
                            if (!decided && !multiTouch && total.getDistance() > slop) {
                                decided = true
                                val dir = -total.x // content moves opposite to the finger
                                panning = zoom > 1.01f && when {
                                    abs(total.x) < slop / 2 -> true // mostly vertical: keep 2D panning
                                    dir > 0 -> panX < maxPan() - 0.5f
                                    else -> panX > 0.5f
                                }
                            }
                            if (panning && !multiTouch) {
                                panBy(-delta.x)
                                change.consume()
                            }
                        }
                        if (released && !decided && !multiTouch) {
                            val now = down.uptimeMillis
                            val position = down.position
                            if (now - lastTapTime < doubleTapTimeout &&
                                (position - lastTapPosition).getDistance() < slop * 4
                            ) {
                                pendingTap?.cancel()
                                lastTapTime = 0L
                                animateZoomTo(if (zoom > 1.01f) 1f else DOUBLE_TAP_ZOOM, position)
                            } else {
                                lastTapTime = now
                                lastTapPosition = position
                                pendingTap = launch {
                                    delay(doubleTapTimeout)
                                    currentOnTap()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val pageWidth = viewportWidth * zoom
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(with(density) { (gapPx * zoom).toDp() }),
        ) {
            items(document.pageCount) { index ->
                val aspect = document.pageSizes[index].aspect
                PdfPage(
                    document = document,
                    index = index,
                    pageWidth = pageWidth,
                    height = with(density) { (pageWidth * aspect).toDp() },
                    renderWidth = renderWidthFor(viewportWidth, zoom, aspect),
                    panX = { panX },
                )
            }
        }
        PageIndicator(listState, document.pageCount, Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * Bitmap width for a page: re-rendered only at a few zoom steps (1×, 2×, 3×) so pinching does not
 * trigger a render per frame, capped by [MAX_RENDER_PIXELS].
 */
private fun renderWidthFor(viewportWidth: Float, zoom: Float, aspect: Float): Int {
    val step = when {
        zoom < 1.5f -> 1f
        zoom < 2.5f -> 2f
        else -> 3f
    }
    val maxWidth = sqrt(MAX_RENDER_PIXELS / aspect)
    return (viewportWidth * step).coerceAtMost(maxWidth).roundToInt().coerceAtLeast(1)
}

@Composable
private fun PdfPage(
    document: PdfDocument,
    index: Int,
    pageWidth: Float,
    height: Dp,
    renderWidth: Int,
    panX: () -> Float,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderWidth) {
        // Keep showing the previous (blurrier) bitmap while a sharper one renders.
        if (bitmap != null) delay(150)
        document.renderPage(index, renderWidth)?.let { bitmap = it }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val left = -panX()
        val dstSize = IntSize(pageWidth.roundToInt(), size.height.roundToInt())
        if (image == null) {
            drawRect(Color.White, topLeft = Offset(left, 0f), size = dstSize.toSize())
        } else {
            drawImage(image, dstOffset = IntOffset(left.roundToInt(), 0), dstSize = dstSize)
        }
    }
}

private fun IntSize.toSize() = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())

/** Missing or unreadable file: say so and offer to find it again or close the tab (기획서 5.3). */
@Composable
private fun OpenFailure(error: Exception, onRelocate: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val missing = error is FileNotFoundException || error is SecurityException
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (missing) "파일을 찾을 수 없습니다" else "PDF를 열 수 없습니다",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (missing) "이동되었거나 삭제되었을 수 있습니다." else "손상되었거나 지원하지 않는 파일입니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text("탭 닫기") }
            Button(onClick = onRelocate) { Text("다시 찾기") }
        }
    }
}

/** "12 / 240", shown while scrolling and briefly after. */
@Composable
private fun PageIndicator(listState: LazyListState, pageCount: Int, modifier: Modifier) {
    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val item = info.visibleItemsInfo.firstOrNull { it.offset + it.size > center }
                ?: info.visibleItemsInfo.lastOrNull()
            (item?.index ?: 0) + 1
        }
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress, currentPage) {
        if (listState.isScrollInProgress) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }
    AnimatedVisibility(visible, modifier.navigationBarsPadding().padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            "$currentPage / $pageCount",
            Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
