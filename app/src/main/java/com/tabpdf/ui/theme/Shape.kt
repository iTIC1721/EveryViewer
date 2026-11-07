package com.tabpdf.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii by size of the thing, instead of one radius everywhere:
 * extraSmall — handles; small — buttons, small controls and thumbnails inside a card; medium — cards;
 * large — drawers; extraLarge — panels and dialogs. Nothing is a pill except round icon buttons.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
