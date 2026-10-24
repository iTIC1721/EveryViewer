package com.tabpdf.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.coroutineScope
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
    internal var itemTopLeft: (Any) -> Offset? = { null }

    /** How far to shift [key] from its layout position so the dragged card follows the finger. */
    fun dragTranslation(key: Any): Offset {
        if (key != draggingKey) return Offset.Zero
        val topLeft = itemTopLeft(key) ?: return Offset.Zero
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
 * - long press, then drag → live reorder via [onMove], committed by [onDragEnd]
 * - horizontal swipe → card slides away and [onSwipeAway] (if given)
 * - vertical drag → left to the list's own scrolling
 *
 * [keyAt] and [itemTopLeft] map between pointer positions and items, in this element's coordinates.
 * The list must not use top/left content padding, so its item offsets match those coordinates.
 */
@Composable
fun Modifier.itemGestures(
    state: ItemGestureState,
    keyAt: (Offset) -> Any?,
    itemTopLeft: (Any) -> Offset?,
    onTap: (Any) -> Unit,
    onLongPress: (Any) -> Unit,
    onMove: (from: Any, to: Any) -> Unit,
    onDragEnd: () -> Unit,
    onSwipeAway: ((Any) -> Unit)? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentKeyAt by rememberUpdatedState(keyAt)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnSwipeAway by rememberUpdatedState(onSwipeAway)
    state.itemTopLeft = itemTopLeft

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
                        state.grab = down.position - (state.itemTopLeft(key) ?: down.position)
                        state.pointer = down.position
                        state.draggingKey = key
                        var dragged = false
                        // The item just swapped with: skip it until the layout has caught up, or
                        // the pointer (still over its old slot) would swap it straight back.
                        var lastTarget: Any? = null
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            state.pointer = change.position
                            if ((change.position - down.position).getDistance() > slop) dragged = true
                            when (val target = currentKeyAt(change.position)) {
                                null -> Unit
                                key -> lastTarget = null
                                lastTarget -> Unit
                                else -> {
                                    currentOnMove(key, target)
                                    lastTarget = target
                                }
                            }
                        }
                        state.draggingKey = null
                        if (dragged) currentOnDragEnd() else currentOnLongPress(key)
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
