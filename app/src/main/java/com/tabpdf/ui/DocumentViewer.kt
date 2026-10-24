package com.tabpdf.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.tabpdf.doc.TextSelection
import android.widget.Toast
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
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
import com.tabpdf.doc.Document
import com.tabpdf.doc.LayoutProgress
import com.tabpdf.doc.awaitPages
import com.tabpdf.doc.SearchMatch
import androidx.compose.ui.graphics.BlendMode
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.Documents
import com.tabpdf.doc.FileFormat
import com.tabpdf.ink.INK_PAGE_WIDTH
import com.tabpdf.ink.InkBinding
import com.tabpdf.ink.InkPainter
import com.tabpdf.ink.InkStroke
import com.tabpdf.ink.InkTool
import com.tabpdf.ink.LocalInkSurface
import com.tabpdf.ink.PageHit
import com.tabpdf.ink.inkInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.tabpdf.tabs.PageJump
import com.tabpdf.tabs.ViewerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MAX_ZOOM = 5f
private val OTHER_MATCH = Color(0xFFFFEB3B)
private val CURRENT_MATCH = Color(0xFFFF9632)
private const val DOUBLE_TAP_ZOOM = 2.5f
private val PAGE_GAP = 8.dp

/** Largest bitmap we render for one page, to keep memory bounded on big zooms (~32 MB). */
private const val MAX_RENDER_PIXELS = 8_000_000f

/** Shorter documents scroll fine by hand; no fast-scroll handle for them. */
private const val FAST_SCROLL_MIN_PAGES = 8

/**
 * Vertically scrolling, zoomable view of one file, whatever its format (기획서 5.7).
 *
 * Gesture rule (기획서 5.2): a horizontal drag is left unconsumed — so the enclosing tab pager
 * turns the tab — unless the page is zoomed in *and* can still pan in that direction when the
 * drag starts. A drag that starts panning keeps panning even after it hits the edge; the reader
 * has to lift and swipe again to change tabs, like a photo gallery.
 */
@Composable
fun DocumentViewer(
    uri: Uri,
    name: String,
    format: FileFormat,
    /** Changing this reopens the file even if [uri] is the same (after "다시 찾기"). */
    revision: Int,
    initialState: ViewerState,
    onStateChange: (ViewerState) -> Unit,
    onTap: () -> Unit,
    onRelocate: () -> Unit,
    onClose: () -> Unit,
    contentPadding: PaddingValues,
    /** A "go to page" request for this viewer, handled once via [onPageJumpHandled]. */
    pageJump: PageJump?,
    onPageJumpHandled: (PageJump) -> Unit,
    onDocumentOpened: (pageCount: Int) -> Unit,
    /** The tab's handwriting, all pages. */
    strokes: List<InkStroke>,
    /** Set while handwriting mode is on (기획서 5.8). */
    ink: InkBinding?,
    /** Text to find and mark (기획서 5.5), or null when not searching this tab. */
    searchQuery: String? = null,
    /** Which match to show. */
    searchIndex: Int = 0,
    /** How many matches [searchQuery] has; null while searching. */
    onSearchResults: (Int?) -> Unit = {},
    /** This is the tab being looked at (not a neighbour kept ready for swiping). */
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<Document?>(null) }
    var error by remember { mutableStateOf<Exception?>(null) }
    val currentOnDocumentOpened by rememberUpdatedState(onDocumentOpened)

    LaunchedEffect(uri, revision, format) {
        error = null
        val doc = try {
            // Not cancellable, so a document opened just as the page leaves is still closed below.
            withContext(NonCancellable) { Documents.open(context, uri, name, format) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
            return@LaunchedEffect
        }
        try {
            // A document still being laid out is shown once it reaches the page last read.
            doc.awaitPages(initialState.page + 1)
            document = doc
            currentOnDocumentOpened(doc.pageCount)
            doc.progress?.collect { currentOnDocumentOpened(it.pages) } ?: awaitCancellation()
        } finally {
            document = null
            doc.close()
        }
    }

    // Told once per opening, when the tab is the one being looked at.
    var noticeShown by remember(document) { mutableStateOf(false) }
    LaunchedEffect(document, active) {
        val notice = document?.notice ?: return@LaunchedEffect
        if (active && !noticeShown) {
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            noticeShown = true
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
            else -> DocumentPages(
                document = doc,
                viewportWidth = constraints.maxWidth.toFloat(),
                viewportHeight = constraints.maxHeight.toFloat(),
                initialState = initialState,
                onStateChange = onStateChange,
                onTap = onTap,
                contentPadding = contentPadding,
                pageJump = pageJump,
                onPageJumpHandled = onPageJumpHandled,
                strokes = strokes,
                ink = ink,
                searchQuery = searchQuery,
                searchIndex = searchIndex,
                onSearchResults = onSearchResults,
            )
        }
    }
}

@Composable
private fun DocumentPages(
    document: Document,
    viewportWidth: Float,
    viewportHeight: Float,
    initialState: ViewerState,
    onStateChange: (ViewerState) -> Unit,
    onTap: () -> Unit,
    contentPadding: PaddingValues,
    pageJump: PageJump?,
    onPageJumpHandled: (PageJump) -> Unit,
    strokes: List<InkStroke>,
    ink: InkBinding?,
    searchQuery: String?,
    searchIndex: Int,
    onSearchResults: (Int?) -> Unit,
) {
    val density = LocalDensity.current
    // Pages laid out so far: a document laid out in the background grows (기획서 5.7).
    val progress by (document.progress ?: remember(document) { MutableStateFlow(LayoutProgress(document.pageCount, complete = true)) })
        .collectAsState()
    val strokesByPage = remember(strokes) { strokes.groupBy { it.page } }
    val currentInk by rememberUpdatedState(ink)
    var hover by remember { mutableStateOf<Offset?>(null) }
    // Handwriting has the current gesture: no panning, zooming or tapping for it. Read by the
    // gesture handlers below, so a plain holder rather than state.
    val inkClaimed = remember { booleanArrayOf(false) }
    val scope = rememberCoroutineScope()
    var fastScrolling by remember { mutableStateOf(false) }

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
    val context = LocalContext.current

    // Text selection (기획서 5.5): two ends, each a point on a page; what lies between, page by page.
    val selectionAnchors = remember(document) { mutableStateOf<SelectionAnchors?>(null) }
    var selection by remember(document) { mutableStateOf<List<Pair<Int, TextSelection>>>(emptyList()) }
    LaunchedEffect(selectionAnchors.value) {
        selection = selectionAnchors.value?.let { selectRange(document, it) }.orEmpty()
    }
    LaunchedEffect(ink != null) { if (ink != null) selectionAnchors.value = null }
    BackHandler(enabled = selectionAnchors.value != null) { selectionAnchors.value = null }
    // A long press took the current gesture for selecting: no tap or pan for it.
    val selectionClaimed = remember { booleanArrayOf(false) }
    // Where the selection handles and toolbar are, so touches on them are left to them.
    val handleBounds = remember { mutableMapOf<String, Rect>() }
    fun onHandle(position: Offset) = selectionAnchors.value != null && handleBounds.values.any { it.contains(position) }
    val haptics = LocalHapticFeedback.current

    /** The page under [position] (viewer coordinates), if any. */
    fun pageAt(position: Offset): PageHit? {
        val info = listState.layoutInfo
        // Item offsets are measured from the end of the top content padding, where viewportStartOffset is negative.
        val item = info.visibleItemsInfo.firstOrNull {
            val top = it.offset - info.viewportStartOffset
            position.y >= top && position.y < top + it.size
        } ?: return null
        val pageWidth = viewportWidth * zoom
        if (position.x + panX !in 0f..pageWidth) return null
        return PageHit(item.index, left = -panX, top = (item.offset - info.viewportStartOffset).toFloat(), scale = INK_PAGE_WIDTH / pageWidth)
    }

    /** [position] (viewer coordinates) as a selection end: its page and where on it, in fractions. */
    fun selectionEndAt(position: Offset): SelectionEnd? {
        val hit = pageAt(position) ?: return null
        val inPage = hit.toPage(position)
        val aspect = document.pageSizes[hit.index].aspect
        return SelectionEnd(hit.index, Offset((inPage.x / INK_PAGE_WIDTH).coerceIn(0f, 1f), (inPage.y / (INK_PAGE_WIDTH * aspect)).coerceIn(0f, 1f)))
    }

    /** A point on page [page] (fractions) in viewer coordinates, if that page is on screen. */
    fun viewerPoint(page: Int, x: Float, y: Float): Offset? {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == page } ?: return null
        val top = (item.offset - info.viewportStartOffset).toFloat()
        return Offset(x * viewportWidth * zoom - panX, top + y * pageHeight(page, zoom))
    }

    /** The web link under [position] (viewer coordinates), if the document has one there. */
    fun linkAt(position: Offset): String? {
        val hit = pageAt(position) ?: return null
        val inPage = hit.toPage(position)
        return document.linkAt(hit.index, inPage.x / INK_PAGE_WIDTH, inPage.y / (INK_PAGE_WIDTH * document.pageSizes[hit.index].aspect))
    }

    /** A tap on a link opens it in the browser (기획서 5.7); any other tap shows or hides the bars. */
    fun tap(position: Offset) {
        if (selectionAnchors.value != null) {
            selectionAnchors.value = null
            return
        }
        val url = linkAt(position) ?: return currentOnTap()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "링크를 열 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

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

    LaunchedEffect(pageJump) {
        val jump = pageJump ?: return@LaunchedEffect
        listState.scrollToItem(jump.page.coerceIn(0, document.pageCount - 1))
        onPageJumpHandled(jump)
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

    val gapPx = if (document.pageGaps) with(density) { PAGE_GAP.toPx() } else 0f

    // Text search (기획서 5.5): find as the query changes, mark every match, bring the current one into view.
    var matches by remember(document) { mutableStateOf<List<SearchMatch>>(emptyList()) }
    val currentOnSearchResults by rememberUpdatedState(onSearchResults)
    LaunchedEffect(document, searchQuery) {
        if (searchQuery.isNullOrEmpty()) {
            matches = emptyList()
            if (searchQuery != null) currentOnSearchResults(0)
            return@LaunchedEffect
        }
        currentOnSearchResults(null)
        delay(250) // typing
        val found = document.search(searchQuery)
        matches = found
        currentOnSearchResults(found.size)
    }
    val matchesByPage = remember(matches) { matches.withIndex().groupBy { it.value.page } }
    val topPaddingPx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val bottomPaddingPx = with(density) { contentPadding.calculateBottomPadding().toPx() }
    LaunchedEffect(matches, searchIndex) {
        val match = matches.getOrNull(searchIndex) ?: return@LaunchedEffect
        val rect = match.rects.first()
        val height = pageHeight(match.page, zoom)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == match.page }
        val shown = item != null && (item.offset - info.viewportStartOffset).let { top ->
            top + rect.top * height >= topPaddingPx && top + rect.bottom * height <= viewportHeight - bottomPaddingPx
        }
        if (!shown) {
            // A third of the way down the screen, as browsers put a found word.
            val target = (rect.top * height - (viewportHeight - topPaddingPx) / 3).roundToInt()
            listState.scrollToItem(match.page, target.coerceAtLeast(0))
            if (target < 0) listState.scrollBy(target.toFloat())
        }
        if (zoom > 1.01f) {
            val pageWidth = viewportWidth * zoom
            val left = rect.left * pageWidth
            val right = rect.right * pageWidth
            if (left < panX || right > panX + viewportWidth) panX = ((left + right - viewportWidth) / 2).coerceIn(0f, maxPan())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Handwriting first: a pointer that draws is consumed before anything below sees it.
            .inkInput(LocalInkSurface.current, { currentInk }, ::pageAt, onClaim = { inkClaimed[0] = it }) { hover = it }
            // Pinch: watched on the Initial pass so two-finger moves never reach the list or pager.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2 && !inkClaimed[0]) {
                            val pan = event.calculatePan()
                            zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = true))
                            panBy(-pan.x)
                            listState.dispatchRawDelta(-pan.y)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Long press: select the word there; dragging on extends the selection. On the Initial
            // pass, so once selecting, moves never reach the list or the pager. Not while drawing.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    selectionClaimed[0] = false
                    if (currentInk != null || onHandle(down.position)) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val letGo = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || event.changes.count { it.pressed } > 1 ||
                                (change.position - down.position).getDistance() > slop
                            ) break
                        }
                        true
                    }
                    if (letGo != null || inkClaimed[0]) return@awaitEachGesture
                    val start = selectionEndAt(down.position) ?: return@awaitEachGesture
                    selectionClaimed[0] = true
                    scope.launch {
                        val word = document.selectWord(start.page, PointF(start.point.x, start.point.y)) ?: return@launch
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectionAnchors.value = SelectionAnchors.around(start.page, word)
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        val end = selectionEndAt(change.position) ?: continue
                        selectionAnchors.value?.let { selectionAnchors.value = it.copy(end = end) }
                    }
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
                        // The selection handles move themselves.
                        if (onHandle(down.position)) return@awaitEachGesture
                        var total = Offset.Zero
                        var decided = false
                        var panning = false
                        var multiTouch = false
                        var released = false
                        // Drawn on: neither a pan nor a tap.
                        var inked = inkClaimed[0]
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) multiTouch = true
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (inkClaimed[0]) inked = true
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
                            if (panning && !multiTouch && !inked) {
                                panBy(-delta.x)
                                change.consume()
                            }
                        }
                        if (released && !decided && !multiTouch && !inked && !selectionClaimed[0]) {
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
                                    tap(position)
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
            items(progress.pages) { index ->
                val aspect = document.pageSizes[index].aspect
                PageImage(
                    document = document,
                    index = index,
                    pageWidth = pageWidth,
                    height = with(density) { (pageWidth * aspect).toDp() },
                    renderWidth = renderWidthFor(viewportWidth, zoom, aspect),
                    panX = { panX },
                    strokes = strokesByPage[index].orEmpty(),
                    found = matchesByPage[index].orEmpty(),
                    selected = selection.firstOrNull { it.first == index }?.second?.rects.orEmpty(),
                    selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    currentMatch = searchIndex,
                )
            }
        }
        FastScroller(
            listState = listState,
            pageCount = progress.pages,
            contentPadding = contentPadding,
            onDraggingChange = { fastScrolling = it },
            modifier = Modifier.align(Alignment.TopEnd),
        )
        PageIndicator(listState, progress.pages, progress.complete, active = fastScrolling, Modifier.align(Alignment.BottomEnd))
        if (selection.isNotEmpty()) {
            val first = selection.first()
            val last = selection.last()
            val startRect = first.second.rects.first()
            val endRect = last.second.rects.last()
            val startTip = viewerPoint(first.first, startRect.left, startRect.bottom)
            val endTip = viewerPoint(last.first, endRect.right, endRect.bottom)
            /** Moves one end of the selection to the text under [tip] (a handle's point, under the line). */
            fun moveEnd(isStart: Boolean, tip: Offset, lift: Float) {
                val end = selectionEndAt(tip - Offset(0f, lift)) ?: return
                val (s, e) = selectionAnchors.value?.ordered() ?: return
                selectionAnchors.value = if (isStart) SelectionAnchors(end, e) else SelectionAnchors(s, end)
            }
            val lineHeight = { r: RectF, page: Int -> r.height() * pageHeight(page, zoom) / 2 }
            startTip?.let { tip ->
                SelectionHandle(tip, isStart = true, onBounds = { handleBounds["start"] = it }) { at -> moveEnd(true, at, lineHeight(startRect, first.first)) }
            } ?: handleBounds.remove("start")
            endTip?.let { tip ->
                SelectionHandle(tip, isStart = false, onBounds = { handleBounds["end"] = it }) { at -> moveEnd(false, at, lineHeight(endRect, last.first)) }
            } ?: handleBounds.remove("end")
            val topPx = with(density) { contentPadding.calculateTopPadding().toPx() }
            val above = viewerPoint(first.first, startRect.left, startRect.top)?.y?.minus(with(density) { 64.dp.toPx() })
            val below = endTip?.y?.plus(with(density) { 36.dp.toPx() })
            val toolbarY = when {
                above != null && above >= topPx -> above
                below != null && below < viewportHeight - with(density) { 120.dp.toPx() } -> below
                else -> topPx + with(density) { 8.dp.toPx() }
            }
            SelectionToolbar(
                text = { joinSelection(selection) },
                onDone = { selectionAnchors.value = null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, toolbarY.roundToInt()) }
                    .onGloballyPositioned { handleBounds["toolbar"] = it.boundsInParent() },
            )
        } else {
            handleBounds.clear()
        }
        // Where a hovering pen would draw or erase, and how wide (기획서 5.8.4).
        val hoverAt = hover
        val hoverInk = ink
        if (hoverAt != null && hoverInk != null) {
            val size = if (hoverInk.tool == InkTool.Eraser) hoverInk.eraserSize else hoverInk.style.size
            val radius = (size / 2 * viewportWidth * zoom / INK_PAGE_WIDTH).coerceAtLeast(3f)
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.Black.copy(alpha = 0.5f), radius, hoverAt, style = Stroke(width = 2f))
                drawCircle(Color.White.copy(alpha = 0.7f), radius + 2f, hoverAt, style = Stroke(width = 1.5f))
            }
        }
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
private fun PageImage(
    document: Document,
    index: Int,
    pageWidth: Float,
    height: Dp,
    renderWidth: Int,
    panX: () -> Float,
    strokes: List<InkStroke>,
    /** Search matches on this page, with their number among all matches. */
    found: List<IndexedValue<SearchMatch>>,
    currentMatch: Int,
    /** Selected text on this page. */
    selected: List<RectF>,
    selectionColor: Color,
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
        for (r in selected) {
            drawRect(
                selectionColor,
                topLeft = Offset(left + r.left * pageWidth, r.top * size.height),
                size = androidx.compose.ui.geometry.Size(r.width() * pageWidth, r.height() * size.height),
            )
        }
        // Found text in yellow, the current match in orange, as browsers mark it; under the handwriting.
        val pad = 1.dp.toPx()
        for ((i, match) in found) {
            val color = if (i == currentMatch) CURRENT_MATCH else OTHER_MATCH
            for (r in match.rects) {
                val topLeft = Offset(left + r.left * pageWidth - pad, r.top * size.height - pad)
                val box = androidx.compose.ui.geometry.Size(r.width() * pageWidth + 2 * pad, r.height() * size.height + 2 * pad)
                drawRect(color, topLeft, box, blendMode = BlendMode.Multiply)
                // Multiplying vanishes on dark or coloured backgrounds; outline the current match too.
                if (i == currentMatch) drawRect(CURRENT_MATCH, topLeft, box, style = Stroke(width = 2 * pad))
            }
        }
        if (strokes.isNotEmpty()) drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            // A stroke that ran off the page is cut at its edge.
            native.clipRect(left, 0f, left + pageWidth, size.height)
            native.translate(left, 0f)
            InkPainter.draw(native, strokes, pageWidth)
            native.restore()
        }
    }
}

private fun IntSize.toSize() = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())

/** Missing or unreadable file: say so and offer to find it again or close the tab (기획서 5.3). */
@Composable
private fun OpenFailure(error: Exception, onRelocate: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    // A SecurityException here comes from opening the file (access lost), not from PdfRenderer —
    // PdfDocument turns the renderer's one into PasswordProtected.
    val missing = error is FileNotFoundException || error is SecurityException
    val reason = (error as? DocumentOpenException)?.reason
    val (title, detail) = when {
        missing -> "파일을 찾을 수 없습니다" to "이동되었거나 삭제되었을 수 있습니다."
        reason == DocumentOpenException.Reason.PasswordProtected ->
            "암호가 걸린 PDF입니다" to "암호 PDF는 아직 지원하지 않습니다."
        reason == DocumentOpenException.Reason.NoPages -> "빈 PDF입니다" to "페이지가 하나도 없습니다."
        reason == DocumentOpenException.Reason.Unsupported -> "이 형식은 열 수 없습니다" to "아직 지원하지 않는 파일 형식입니다."
        reason == DocumentOpenException.Reason.TooLarge -> "파일이 너무 큽니다" to "텍스트 파일은 16MB까지 열 수 있습니다."
        else -> "파일을 열 수 없습니다" to "손상되었거나 지원하지 않는 파일입니다."
    }
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text("탭 닫기") }
            // Finding the file again only helps when it went missing.
            if (missing) Button(onClick = onRelocate) { Text("다시 찾기") }
        }
    }
}

/**
 * Draggable handle on the right edge for jumping through long documents (기획서 5.5). Appears
 * while scrolling, like Android's fast scroller, so it never sits on top of the page otherwise.
 */
@Composable
private fun FastScroller(
    listState: LazyListState,
    pageCount: Int,
    contentPadding: PaddingValues,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    if (pageCount < FAST_SCROLL_MIN_PAGES) return
    // Read live by the position and the drag handler: a document laid out in the background grows.
    val pages by rememberUpdatedState(pageCount)
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress, dragging) {
        if (listState.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(1500)
            visible = false
        }
    }
    // How far through the document, 0..1, from the first visible page and how much of it is scrolled.
    val progress by remember {
        derivedStateOf {
            if (!listState.canScrollForward) return@derivedStateOf 1f
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
            val within = listState.firstVisibleItemScrollOffset.toFloat() / first.size.coerceAtLeast(1)
            ((first.index + within) / (pages - 1)).coerceIn(0f, 1f)
        }
    }
    AnimatedVisibility(
        visible,
        modifier
            .fillMaxHeight()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        BoxWithConstraints(Modifier.fillMaxHeight().width(36.dp)) {
            val trackHeight = constraints.maxHeight.toFloat()
            val thumbHeight = with(LocalDensity.current) { 48.dp.toPx() }
            fun jumpTo(y: Float) {
                val fraction = ((y - thumbHeight / 2) / (trackHeight - thumbHeight)).coerceIn(0f, 1f)
                scope.launch { listState.scrollToItem((fraction * (pages - 1)).roundToInt()) }
            }
            fun setDragging(value: Boolean) {
                dragging = value
                onDraggingChange(value)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { setDragging(true); jumpTo(it.y) },
                            onDragEnd = { setDragging(false) },
                            onDragCancel = { setDragging(false) },
                        ) { change, _ ->
                            change.consume()
                            jumpTo(change.position.y)
                        }
                    }
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (progress * (trackHeight - thumbHeight)).roundToInt()) }
                    .padding(end = 4.dp)
                    .size(width = if (dragging) 8.dp else 6.dp, height = 48.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dragging) 0.9f else 0.6f),
                        RoundedCornerShape(4.dp),
                    )
            )
        }
    }
}

/** "12 / 240", shown while scrolling (or [active]) and briefly after; "240+" while more pages are being laid out. */
@Composable
private fun PageIndicator(listState: LazyListState, pageCount: Int, complete: Boolean, active: Boolean, modifier: Modifier) {
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
    LaunchedEffect(listState.isScrollInProgress, active, currentPage) {
        if (listState.isScrollInProgress || active) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }
    // Extra end padding keeps it clear of the fast-scroll handle.
    AnimatedVisibility(visible, modifier.navigationBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 44.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            "$currentPage / $pageCount" + if (complete) "" else "+",
            Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
