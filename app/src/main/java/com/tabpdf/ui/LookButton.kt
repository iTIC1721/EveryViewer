package com.tabpdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tabpdf.ui.theme.floating
import com.tabpdf.ui.theme.onFloating

/**
 * The document's look (기획서 5.10): a small round button at the top left of a document that has
 * a dark look, a sun while it is shown dark (press: light) and a moon while light (press: dark).
 * It shows with the bars, steps aside while reading on down, and comes back when scrolled up a
 * little — like a browser's address bar — so it doesn't sit on the text being read.
 */
@Composable
fun LookButton(
    dark: Boolean,
    onClick: () -> Unit,
    listState: LazyListState,
    barsVisible: Boolean,
    /** Where it is in the window while it shows; null when gone. */
    onBounds: (androidx.compose.ui.geometry.Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrolledAway by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val hideAfter = with(density) { 48.dp.toPx() }
    val showAfter = with(density) { 16.dp.toPx() }
    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        var down = 0f
        var up = 0f
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            // Within a page the offset says how far; across pages only which way.
            val delta = if (index == lastIndex) (offset - lastOffset).toFloat() else if (index > lastIndex) hideAfter else -showAfter
            lastIndex = index
            lastOffset = offset
            if (delta > 0) {
                down += delta
                up = 0f
                if (down >= hideAfter) scrolledAway = true
            } else if (delta < 0) {
                up -= delta
                down = 0f
                if (up >= showAfter) scrolledAway = false
            }
        }
    }
    // Bars shown again (a tap on the page): the button comes back with them.
    LaunchedEffect(barsVisible) { if (barsVisible) scrolledAway = false }
    val shown = barsVisible && !scrolledAway
    LaunchedEffect(shown) { if (!shown) onBounds(null) }
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.floating,
            contentColor = MaterialTheme.colorScheme.onFloating,
            shadowElevation = 2.dp,
            modifier = Modifier
                .onGloballyPositioned { if (shown) onBounds(it.boundsInRoot()) }
                .size(40.dp).semantics { contentDescription = if (dark) "밝게 보기" else "어둡게 보기" },
        ) {
            Icon(if (dark) SunIcon else MoonIcon, contentDescription = null, modifier = Modifier.padding(9.dp))
        }
    }
}

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

/** Material "dark_mode". */
private val MoonIcon = icon(
    "Moon",
    "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z",
)

/** Material "light_mode". */
private val SunIcon = icon(
    "Sun",
    "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0 .39-.39.39-1.03 0-1.41l-1.06-1.06zm1.06-10.96c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z",
)
