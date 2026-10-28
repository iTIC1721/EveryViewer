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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.tabpdf.doc.Link
import com.tabpdf.doc.isWebLink
import com.tabpdf.doc.LayoutProgress
import com.tabpdf.doc.awaitPages
import com.tabpdf.doc.guarded
import com.tabpdf.doc.MAX_DRAWN_BYTES
import com.tabpdf.doc.SearchMatch
import androidx.compose.ui.graphics.BlendMode
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.Documents
import com.tabpdf.doc.FileFormat
import com.tabpdf.pdf.PdfDocument
import com.tabpdf.pdf.PdfPasswords
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

/** Pages may be shrunk to half the screen width, centred with room on both sides (기획서 5.2). */
private const val MIN_ZOOM = 0.5f
private val OTHER_MATCH = Color(0xFFFFEB3B)
private val CURRENT_MATCH = Color(0xFFFF9632)
private const val DOUBLE_TAP_ZOOM = 2.5f
private val PAGE_GAP = 8.dp

/**
 * Tallest a page may be on screen, in px. Compose can't lay out a child taller than 262 142 px
 * beside one narrower than 8 191 px (its Constraints), and throws while measuring — a crash that
 * would come back on every start, as the zoom is saved. Very tall pages zoom less far instead.
 */
private const val MAX_PAGE_HEIGHT_PX = 200_000f

/** The largest height / width among the first [measured] pages of a document. */
private class TallestPage {
    var aspect = 0f
    var measured = 0
}

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
    /** The document while it is shown, then null once it is let go of. */
    onDocument: (Document?) -> Unit = {},
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
    val currentOnDocument by rememberUpdatedState(onDocument)

    // Raised when a password is typed for a locked PDF: open it again with that.
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(uri, revision, format, attempt) {
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
            currentOnDocument(doc)
            currentOnDocumentOpened(doc.pageCount)
            doc.progress?.collect { currentOnDocumentOpened(it.pages) } ?: awaitCancellation()
        } finally {
            document = null
            currentOnDocument(null)
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
            failure != null -> OpenFailure(
                failure, onRelocate, onClose,
                pdf = format == FileFormat.Pdf,
                onPassword = if (format == FileFormat.Pdf && PdfDocument.takesPasswords) {
                    { password ->
                        PdfPasswords[uri] = password
                        attempt++
                    }
                } else null,
                Modifier.align(Alignment.Center),
            )
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

    // Very tall pages zoom less far (MAX_PAGE_HEIGHT_PX); Documents.open refuses those that can't even fit at MIN_ZOOM.
    // Pages are measured once each as they are laid out (a large text file grows to many thousands).
    val tallest = remember(document) { TallestPage() }
    val maxZoom = remember(document, progress.pages, viewportWidth) {
        for (i in tallest.measured until progress.pages) tallest.aspect = maxOf(tallest.aspect, document.pageSizes[i].aspect)
        tallest.measured = progress.pages
        if (tallest.aspect <= 0f) MAX_ZOOM else (MAX_PAGE_HEIGHT_PX / (viewportWidth * tallest.aspect)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
    // Read by the gesture handlers, which keep the first composition's functions.
    val currentMaxZoom by rememberUpdatedState(maxZoom)

    // The file may have fewer pages than when the position was saved.
    val initialPage = initialState.page.coerceIn(0, document.pageCount - 1)
    val initialZoom = initialState.zoom.coerceIn(MIN_ZOOM, maxZoom)
    val listState = rememberLazyListState(
        initialPage,
        (initialState.pageOffset * pageHeight(initialPage, initialZoom)).roundToInt(),
    )
    var zoom by remember { mutableFloatStateOf(initialZoom) }
    var panX by remember {
        mutableFloatStateOf((initialState.panX * viewportWidth).coerceIn(panRange(viewportWidth, initialZoom)))
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

    /** The link under [position] (viewer coordinates), if the document has one there. */
    suspend fun linkAt(position: Offset): Link? {
        val hit = pageAt(position) ?: return null
        val inPage = hit.toPage(position)
        return guarded(null) { document.link(hit.index, inPage.x / INK_PAGE_WIDTH, inPage.y / (INK_PAGE_WIDTH * document.pageSizes[hit.index].aspect)) }
    }

    /**
     * A tap on a link opens it in the browser (기획서 5.7), or goes to the place in the document it
     * points to; any other tap shows or hides the bars.
     */
    suspend fun tap(position: Offset) {
        if (selectionAnchors.value != null) {
            selectionAnchors.value = null
            return
        }
        when (val link = linkAt(position)) {
            is Link.Page -> {
                // The place just below the top bar, as a reader would scroll to it.
                val offset = ((link.y ?: 0f) * pageHeight(link.page, zoom) - with(density) { 16.dp.toPx() }).roundToInt()
                listState.scrollToItem(link.page, offset.coerceAtLeast(0))
            }
            // Checked again here: whatever a document's reader let through, only web and mail links open.
            is Link.Web -> if (isWebLink(link.url)) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "링크를 열 앱이 없습니다", Toast.LENGTH_SHORT).show()
                } catch (_: RuntimeException) {
                    Toast.makeText(context, "링크를 열 수 없습니다", Toast.LENGTH_SHORT).show()
                }
            } else currentOnTap()
            null -> currentOnTap()
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
        val page = jump.page.coerceIn(0, document.pageCount - 1)
        // Like a link: the place just below the top bar.
        val offset = jump.y?.let { (it * pageHeight(page, zoom) - with(density) { 16.dp.toPx() }).roundToInt() } ?: 0
        listState.scrollToItem(page, offset.coerceAtLeast(0))
        onPageJumpHandled(jump)
    }

    fun maxPan() = panRange(viewportWidth, zoom).endInclusive

    fun panBy(dx: Float) {
        panX = (panX + dx).coerceIn(panRange(viewportWidth, zoom))
    }

    /**
     * Zooms by [factor] keeping the content under [focus] (viewport coordinates) in place.
     * Pages and the gaps between them all scale together, so the content offset from the first
     * visible item's top scales exactly by the zoom ratio.
     */
    fun zoomBy(factor: Float, focus: Offset) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, currentMaxZoom)
        val ratio = newZoom / zoom
        if (ratio == 1f) return
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        zoom = newZoom
        panX = ((panX + focus.x) * ratio - focus.x).coerceIn(panRange(viewportWidth, newZoom))
        val newOffset = ((offset + focus.y) * ratio - focus.y).roundToInt().coerceAtLeast(0)
        // Undispatched so the scroll position updates before the next pointer event reads it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { listState.scrollToItem(index, newOffset) }
    }

    fun animateZoomTo(target: Float, focus: Offset) {
        scope.launch {
            animate(zoom, target, animationSpec = tween(200)) { value, _ -> zoomBy(value / zoom, focus) }
        }
    }

    // Pages laid out later may be taller than the first ones: zoom out to what they allow.
    LaunchedEffect(maxZoom) { if (zoom > maxZoom) zoomBy(maxZoom / zoom, Offset.Zero) }

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
        val found = guarded(emptyList()) { document.search(searchQuery) }
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
                        val word = guarded(null) { document.selectWord(start.page, PointF(start.point.x, start.point.y)) } ?: return@launch
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
                                // Back to the screen's width from any zoom, in or out; from there, in.
                                animateZoomTo(if (abs(zoom - 1f) > 0.01f) 1f else DOUBLE_TAP_ZOOM, position)
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
 * Where the page's left edge may be ([panX], in px of the page at [zoom]): from 0 to the part that
 * doesn't fit when zoomed in; when shrunk, fixed so the page sits in the middle of the screen.
 */
private fun panRange(viewportWidth: Float, zoom: Float): ClosedFloatingPointRange<Float> {
    val extra = viewportWidth * (zoom - 1f)
    return if (extra < 0f) (extra / 2).let { it..it } else 0f..extra
}

/**
 * Bitmap width for a page: re-rendered only at a few zoom steps (½×, 1×, 2×, 3×) so pinching does not
 * trigger a render per frame, capped by [MAX_RENDER_PIXELS].
 */
private fun renderWidthFor(viewportWidth: Float, zoom: Float, aspect: Float): Int {
    val step = when {
        // Shrunk: more pages show at once, each drawn smaller.
        zoom < 0.75f -> 0.5f
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
        // A page that can't be drawn stays blank; the rest of the document is still readable.
        guarded(null) { document.renderPage(index, renderWidth) }?.takeIf { it.allocationByteCount <= MAX_DRAWN_BYTES }?.let { bitmap = it }
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

/**
 * Missing or unreadable file: say so and offer to find it again or close the tab (기획서 5.3).
 * A locked PDF asks for its password where the device can take one ([onPassword]).
 */
@Composable
private fun OpenFailure(
    error: Exception,
    onRelocate: () -> Unit,
    onClose: () -> Unit,
    pdf: Boolean,
    onPassword: ((String) -> Unit)?,
    modifier: Modifier,
) {
    // A SecurityException here comes from opening the file (access lost), not from PdfRenderer —
    // PdfDocument turns the renderer's one into PasswordProtected.
    val missing = error is FileNotFoundException || error is SecurityException
    val reason = (error as? DocumentOpenException)?.reason
    val wrongPassword = reason == DocumentOpenException.Reason.WrongPassword
    val asksPassword = onPassword != null && (reason == DocumentOpenException.Reason.PasswordProtected || wrongPassword)
    val (title, detail) = when {
        missing -> "파일을 찾을 수 없습니다" to "이동되었거나 삭제되었을 수 있습니다."
        asksPassword -> "암호가 걸린 문서입니다" to "문서를 열려면 암호를 입력하세요."
        reason == DocumentOpenException.Reason.PasswordProtected || wrongPassword ->
            "암호가 걸린 문서입니다" to if (pdf) "이 기기에서는 암호 PDF를 열 수 없습니다." else "암호 문서는 아직 지원하지 않습니다."
        reason == DocumentOpenException.Reason.Protected -> "보호된 문서입니다" to "DRM 등으로 보호된 문서는 열 수 없습니다."
        reason == DocumentOpenException.Reason.NoPages -> "빈 PDF입니다" to "페이지가 하나도 없습니다."
        reason == DocumentOpenException.Reason.Unsupported -> "이 형식은 열 수 없습니다" to "아직 지원하지 않는 파일 형식입니다."
        reason == DocumentOpenException.Reason.TooLarge -> "파일이 너무 큽니다" to "이 기기에서 열 수 있는 크기를 넘습니다."
        reason == DocumentOpenException.Reason.PageTooTall -> "페이지가 너무 깁니다" to "폭에 비해 세로로 아주 긴 페이지는 열 수 없습니다."
        else -> "파일을 열 수 없습니다" to "손상되었거나 지원하지 않는 파일입니다."
    }
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        // Not saved with the screen's state: a password stays in memory only (기획서 5.5).
        var password by remember { mutableStateOf("") }
        val submit = { if (password.isNotEmpty()) onPassword?.invoke(password) }
        if (asksPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("암호") },
                singleLine = true,
                isError = wrongPassword,
                supportingText = if (wrongPassword) {
                    { Text("암호가 맞지 않습니다.") }
                } else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text("탭 닫기") }
            // Finding the file again only helps when it went missing.
            if (missing) Button(onClick = onRelocate) { Text("다시 찾기") }
            if (asksPassword) Button(onClick = submit, enabled = password.isNotEmpty()) { Text("열기") }
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
