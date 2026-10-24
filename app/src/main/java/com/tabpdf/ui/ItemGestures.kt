package com.tabpdf.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Which card is being dragged or swiped, for drawing it under the finger. */
@Stable
class ItemGestureState {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var swipingKey by mutableStateOf<Any?>(null)
        internal set
    var swipeOffset by mutableFloatStateOf(0f)
        internal set
    internal var pointer by mutableStateOf(Offset.Zero)
    internal var grab = Offset.Zero
    internal var itemRect: (Any) -> Rect? = { null }

    /** How far to shift [key] from its layout position so the dragged card follows the finger. */
    fun dragTranslation(key: Any): Offset {
        if (key != draggingKey) return Offset.Zero
        val topLeft = itemRect(key)?.topLeft ?: return Offset.Zero
        return pointer - grab - topLeft
    }
}

@Composable
fun rememberItemGestureState() = remember { ItemGestureState() }

private enum class EarlyEnd { Tap, Horizontal, Other }

/**
 * One detector for all card gestures in a lazy list or grid, so they don't compete:
 * - tap → [onTap]
 * - long press, release in place → [onLongPress] (context menu)
 * - long press, then drag → [onDragMove] on every move (with the card under the finger and where
 *   in it, 0..1), [onDrop] on release
 * - horizontal swipe → card slides away and [onSwipeAway] (if given)
 * - vertical drag → left to the list's own scrolling
 *
 * [keyAt] and [itemRect] map between pointer positions and items, in this element's coordinates.
 * The list must not use top/left content padding, so its item offsets match those coordinates.
 */
@Composable
fun Modifier.itemGestures(
    state: ItemGestureState,
    keyAt: (Offset) -> Any?,
    itemRect: (Any) -> Rect?,
    onTap: (Any) -> Unit,
    onLongPress: (Any) -> Unit,
    onDragMove: (dragged: Any, target: Any?, positionInTarget: Offset) -> Unit,
    onDrop: (dragged: Any) -> Unit,
    onSwipeAway: ((Any) -> Unit)? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentKeyAt by rememberUpdatedState(keyAt)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnSwipeAway by rememberUpdatedState(onSwipeAway)
    state.itemRect = itemRect

    return pointerInput(state) {
        coroutineScope {
            val scope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val key = currentKeyAt(down.position) ?: return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    awaitEarlyEnd(down, slop)
                }
                when (early) {
                    EarlyEnd.Tap -> currentOnTap(key)
                    EarlyEnd.Other -> Unit
                    EarlyEnd.Horizontal -> {
                        val onSwipe = currentOnSwipeAway ?: return@awaitEachGesture
                        state.swipingKey = key
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            state.swipeOffset = change.position.x - down.position.x
                        }
                        val offset = state.swipeOffset
                        val away = abs(offset) > size.width * 0.25f
                        scope.launch {
                            animate(offset, if (away) sign(offset) * size.width else 0f) { v, _ -> state.swipeOffset = v }
                            if (away) onSwipe(key)
                            state.swipingKey = null
                            state.swipeOffset = 0f
                        }
                    }
                    null -> { // Long press
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.grab = down.position - (state.itemRect(key)?.topLeft ?: down.position)
                        state.pointer = down.position
                        state.draggingKey = key
                        var dragged = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            state.pointer = change.position
                            if ((change.position - down.position).getDistance() > slop) dragged = true
                            if (dragged) {
                                val target = currentKeyAt(change.position)
                                val rect = target?.let { state.itemRect(it) }
                                val inTarget = if (rect == null) Offset.Zero else Offset(
                                    (change.position.x - rect.left) / rect.width,
                                    (change.position.y - rect.top) / rect.height,
                                )
                                currentOnDragMove(key, target, inTarget)
                            }
                        }
                        state.draggingKey = null
                        if (dragged) currentOnDrop(key) else currentOnLongPress(key)
                    }
                }
            }
        }
    }
}

/** Waits until the first finger lifts or moves past [slop]; the caller times this out as a long press. */
private suspend fun AwaitPointerEventScope.awaitEarlyEnd(down: PointerInputChange, slop: Float): EarlyEnd {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return EarlyEnd.Other
        // A consumed release means a child (e.g. the close button) already handled the tap.
        if (!change.pressed) return if (change.isConsumed) EarlyEnd.Other else EarlyEnd.Tap
        val moved = change.position - down.position
        if (moved.getDistance() > slop) {
            return if (abs(moved.x) > abs(moved.y)) EarlyEnd.Horizontal else EarlyEnd.Other
        }
    }
}

/**
 * Turns drag moves into reorders and merges, the way a launcher makes folders: holding the dragged
 * card over the middle of a card that [canMerge] accepts marks it as a merge target (dropped there,
 * the two combine); resting near a card's edge for a moment moves the dragged card into its place.
 * Cards that can't be merged with are reordered immediately.
 */
@Stable
class ReorderController internal constructor() {
    internal data class Hover(val dragged: Any, val target: Any, val center: Boolean)

    internal var hover by mutableStateOf<Hover?>(null)
    internal var onMove: (Any, Any) -> Unit = { _, _ -> }
    internal var canMerge: (Any, Any) -> Boolean = { _, _ -> false }

    /** The card just swapped with: ignored until the layout catches up, or it would swap straight back. */
    private var lastSwapped: Any? = null

    /** Card the dragged one would merge with if dropped now. */
    val mergeTarget: Any?
        get() = hover?.takeIf { it.center && canMerge(it.dragged, it.target) }?.target

    fun dragMove(dragged: Any, target: Any?, positionInTarget: Offset) {
        if (target == null || target == dragged) {
            if (target == dragged) lastSwapped = null
            hover = null
            return
        }
        if (target == lastSwapped) return
        val center = positionInTarget.x in 0.25f..0.75f && positionInTarget.y in 0.25f..0.75f
        hover = Hover(dragged, target, center)
    }

    /** Ends the drag; returns the merge target if the card was dropped onto one. */
    fun drop(): Any? {
        val target = mergeTarget
        hover = null
        lastSwapped = null
        return target
    }

    internal fun swapped(target: Any) {
        lastSwapped = target
        hover = null
    }
}

@Composable
fun rememberReorderController(
    onMove: (from: Any, to: Any) -> Unit,
    canMerge: (dragged: Any, target: Any) -> Boolean = { _, _ -> false },
): ReorderController {
    val controller = remember { ReorderController() }
    controller.onMove = onMove
    controller.canMerge = canMerge
    LaunchedEffect(controller.hover) {
        val h = controller.hover ?: return@LaunchedEffect
        val mergeable = controller.canMerge(h.dragged, h.target)
        if (h.center && mergeable) return@LaunchedEffect
        // Near a mergeable card, wait a little so passing over its edge on the way to its middle
        // doesn't reorder.
        if (mergeable) delay(REORDER_DELAY_MS)
        controller.onMove(h.dragged, h.target)
        controller.swapped(h.target)
    }
    return controller
}

private const val REORDER_DELAY_MS = 300L
