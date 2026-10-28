package com.tabpdf.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Composable
fun EveryViewerTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Behind the pages and the tab switcher: the desk the paper lies on. */
val ColorScheme.desk: Color get() = surfaceDim

/** Dims what is behind a panel or drawer; one strength everywhere. */
val ColorScheme.dimmed: Color get() = scrim.copy(alpha = 0.32f)

/** Small floating labels and badges over a page (page number, close): the inverse of the paper. */
val ColorScheme.floating: Color get() = inverseSurface.copy(alpha = 0.88f)
val ColorScheme.onFloating: Color get() = inverseOnSurface

/** Bars over the document: paper, letting a little of the page show through. */
val ColorScheme.bar: Color get() = surface.copy(alpha = 0.94f)

/** A card on the desk (tab switcher, bundle editor): paper in light, a raised grey in dark. */
val ColorScheme.card: Color get() = if (surface.luminance() > 0.5f) surfaceContainerLowest else surfaceContainerHigh
