package com.tabpdf.ink

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection
import androidx.ink.strokes.Stroke
import kotlin.math.atan2
import kotlin.math.hypot
import com.tabpdf.SideButton

/**
 * Ink's low-latency view for strokes being drawn (front-buffered, 기획서 5.8.5). It sits over the
 * whole window and takes no touches itself: the viewer feeds it the pointer it draws with. Once a
 * stroke is finished it goes to the page that drew it, which draws it from then on.
 */
class InkSurface(context: Context) {
    val view = InProgressStrokesView(context)
    private val targets = mutableMapOf<InProgressStrokeId, (Stroke) -> Unit>()

    init {
        view.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                for ((id, stroke) in strokes) targets.remove(id)?.invoke(stroke)
                // The page draws them from its next frame on; keep them here until it has.
                val ids = strokes.keys.toSet()
                view.postOnAnimation { view.postOnAnimation { view.removeFinishedStrokes(ids) } }
            }
        })
    }

    fun start(event: MotionEvent, pointerId: Int, brush: Brush, motionToPage: Matrix, onFinished: (Stroke) -> Unit): InProgressStrokeId =
        view.startStroke(event, pointerId, brush, motionToPage, Matrix()).also { targets[it] = onFinished }

    fun add(event: MotionEvent, pointerId: Int, id: InProgressStrokeId) = view.addToStroke(event, pointerId, id, null)

    fun finish(event: MotionEvent, pointerId: Int, id: InProgressStrokeId) = view.finishStroke(event, pointerId, id)

    fun cancel(id: InProgressStrokeId, event: MotionEvent?) {
        targets.remove(id)
        view.cancelStroke(id, event)
    }
}

val LocalInkSurface = staticCompositionLocalOf<InkSurface?> { null }

/** Where screens get tabs' handwriting from (e.g. for thumbnails). */
val LocalInkStore = staticCompositionLocalOf<InkStore?> { null }

/** What a viewer needs to take handwriting (기획서 5.8.3). */
class InkBinding(
    val tool: InkTool,
    val style: ToolStyle,
    val eraserSize: Float,
    /** Whether a finger draws now (no pen in use, or the user wants fingers to draw anyway). */
    val fingerDraws: Boolean,
    val sideButton: SideButton,
    val onUndo: () -> Unit,
    val strokes: List<InkStroke>,
    val onStylusSeen: () -> Unit,
    /** A finished line: its parts on the pages it crossed (one, unless it went on to other pages). */
    val onStroke: (parts: List<Pair<Int, Stroke>>, tool: InkTool) -> Unit,
    val onErase: (ids: Set<String>, gesture: Long) -> Unit,
)

/** Where a point falls in the document: page [index], whose top-left is at ([left], [top]) in viewer pixels. */
class PageHit(val index: Int, val left: Float, val top: Float, /** Page units per pixel. */ val scale: Float) {
    fun toPage(p: Offset) = Offset((p.x - left) * scale, (p.y - top) * scale)

    /** MotionEvent coordinates are the window's; [motionOffset] is where the viewer's origin is in them. */
    fun motionToPage(motionOffset: Offset) = Matrix().apply {
        setTranslate(-motionOffset.x - left, -motionOffset.y - top)
        postScale(scale, scale)
    }
}

private val STYLUS_SIDE_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY

private val PointerInputChange.isPen get() = type == PointerType.Stylus || type == PointerType.Eraser

/**
 * Handwriting input, on the Initial pass so a pointer that draws never scrolls the page or turns
 * the tab (the viewer's other gesture handlers skip consumed changes). Rules (기획서 5.8.3):
 * - a pen always draws; a finger draws only when [InkBinding.fingerDraws];
 * - a second finger cancels a finger stroke and becomes two-finger scrolling and zooming;
 * - once a pen draws, nothing else in that gesture (a resting palm) moves the page;
 * - the eraser tool, a pen's eraser end, or the held side button erase whole strokes;
 * - or, set to undo, pressing the side button while the pen hovers undoes once.
 * [binding] is null when handwriting mode is off; then nothing here touches the events.
 */
fun Modifier.inkInput(
    surface: InkSurface?,
    binding: () -> InkBinding?,
    pageAt: (Offset) -> PageHit?,
    /** How the pages stack, for a line or an eraser that goes on from one page to the next. */
    pages: () -> PageLayout,
    /** Told whether handwriting has the current gesture, so other handlers can stay out of it. */
    onClaim: (Boolean) -> Unit,
    onHover: (Offset?) -> Unit,
): Modifier = this
    .pointerInput(surface) {
        if (surface == null) return@pointerInput
        var gestures = 0L
        awaitPointerEventScope {
            var active: Active? = null
            var claimed = false // this gesture belongs to handwriting until every pointer is up
            fun claim(value: Boolean) {
                if (claimed != value) onClaim(value)
                claimed = value
            }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val ink = binding()
                val motion = event.motionEvent
                if (ink == null || motion == null) {
                    active?.let { if (it.strokeId != null) surface.cancel(it.strokeId, motion) }
                    active = null
                    claim(false)
                    continue
                }
                if (event.changes.any { it.isPen }) ink.onStylusSeen()

                if (active == null) {
                    val fingersDown = event.changes.count { it.pressed && !it.isPen }
                    val down = event.changes.firstOrNull { change ->
                        change.changedToDownIgnoreConsumed() &&
                            (change.isPen || (ink.fingerDraws && fingersDown == 1 && !claimed))
                    }
                    val hit = down?.let { pageAt(it.position) }
                    if (down != null && hit != null) {
                        val pointerId = motion.getPointerId(motion.actionIndex)
                        val erasing = ink.tool == InkTool.Eraser || down.type == PointerType.Eraser ||
                            (down.isPen && ink.sideButton == SideButton.Eraser && motion.buttonState and STYLUS_SIDE_BUTTONS != 0)
                        val motionOffset = Offset(motion.getX(motion.actionIndex), motion.getY(motion.actionIndex)) - down.position
                        active = if (erasing) {
                            Active(down.id, pointerId, down.isPen, null, hit, down.position, ++gestures).also {
                                erase(ink, pages(), hit, down.position, down.position, gestures)
                            }
                        } else {
                            val brush = Brushes.forTool(ink.tool, ink.style)
                            val tool = ink.tool
                            val page = hit.index
                            val id = surface.start(motion, pointerId, brush, hit.motionToPage(motionOffset)) { stroke ->
                                ink.onStroke(splitAcrossPages(stroke, page, pages()), tool)
                            }
                            Active(down.id, pointerId, down.isPen, id, hit, down.position, 0)
                        }
                        claim(true)
                    }
                }

                val a = active
                if (a != null) {
                    val change = event.changes.firstOrNull { it.id == a.composeId }
                    val fingers = event.changes.count { it.pressed && !it.isPen }
                    when {
                        // A second finger: this was the start of scrolling or zooming, not a line.
                        !a.pen && fingers >= 2 -> {
                            a.strokeId?.let { surface.cancel(it, motion) }
                            active = null
                            claim(false)
                        }
                        // The system took the touch back (e.g. it decided it was a palm): drop the stroke.
                        motion.actionMasked == MotionEvent.ACTION_CANCEL || change == null -> {
                            a.strokeId?.let { surface.cancel(it, motion) }
                            active = null
                        }
                        !change.pressed -> {
                            a.strokeId?.let { surface.finish(motion, a.pointerId, it) }
                            active = null
                        }
                        a.strokeId != null -> surface.add(motion, a.pointerId, a.strokeId)
                        else -> {
                            erase(ink, pages(), a.hit, a.last, change.position, a.gesture)
                            a.last = change.position
                        }
                    }
                }
                if (claimed) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) claim(false)
            }
        }
    }
    .pointerInput(Unit) {
        // Pen hovering over the screen: show where and how big the tool is (기획서 5.8.4).
        awaitPointerEventScope {
            var buttonHeld = false
            while (true) {
                val event = awaitPointerEvent()
                val pen = event.changes.firstOrNull { it.isPen }
                // The side button going down while the pen is in the air: undo once. Held from a
                // stroke on into the air, it does nothing.
                val motion = event.motionEvent
                if (pen != null && motion != null) {
                    val held = motion.buttonState and STYLUS_SIDE_BUTTONS != 0
                    val ink = binding()
                    if (held && !buttonHeld && !pen.pressed && ink?.sideButton == SideButton.Undo) ink.onUndo()
                    buttonHeld = held
                }
                onHover(
                    when {
                        binding() == null || pen == null || event.type == PointerEventType.Exit -> null
                        else -> pen.position
                    }
                )
            }
        }
    }

private class Active(
    val composeId: PointerId,
    /** The pointer's id in the MotionEvent (not the same as Compose's). */
    val pointerId: Int,
    val pen: Boolean,
    /** Null while erasing. */
    val strokeId: InProgressStrokeId?,
    val hit: PageHit,
    var last: Offset,
    val gesture: Long,
)

/**
 * Erases every stroke that the eraser touched moving from [from] to [to]: on [hit]'s page, and on
 * the pages next to it that the eraser went on to.
 */
private fun erase(ink: InkBinding, pages: PageLayout, hit: PageHit, from: Offset, to: Offset, gesture: Long) {
    val a = hit.toPage(from)
    val b = hit.toPage(to)
    val radius = ink.eraserSize / 2
    val length = hypot(b.x - a.x, b.y - a.y)
    val origin = if (hit.index in pages.heights.indices) pages.top(hit.index) else 0f
    val touched = if (hit.index in pages.heights.indices) {
        pages.pagesBetween(origin + minOf(a.y, b.y) - radius, origin + maxOf(a.y, b.y) + radius)
    } else hit.index..hit.index
    val hits = mutableSetOf<String>()
    for (page in touched) {
        val pa = pages.move(a, hit.index, page)
        val pb = pages.move(b, hit.index, page)
        val area = ImmutableParallelogram.fromCenterDimensionsAndRotationInDegrees(
            ImmutableVec((pa.x + pb.x) / 2, (pa.y + pb.y) / 2),
            length + 2 * radius,
            2 * radius,
            Math.toDegrees(atan2(pb.y - pa.y, pb.x - pa.x).toDouble()).toFloat(),
        )
        ink.strokes
            .filter { it.page == page && with(Intersection) { it.stroke.shape.intersects(area, AffineTransform.IDENTITY) } }
            .mapTo(hits) { it.id }
    }
    if (hits.isNotEmpty()) ink.onErase(hits, gesture)
}
