package com.tabpdf.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

/**
 * Motion answers what the user did — opening, closing, picking up — and nothing moves on its own.
 * Compose scales every duration by the system's animation setting, so "remove animations" applies.
 */
object Motion {
    /** Material 3 emphasized curves: things arrive fast and settle, leave by speeding up. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** A full-screen layer (tab switcher, settings, bundle editor, about) rising over the viewer. */
    val screenEnter: EnterTransition =
        fadeIn(tween(250, easing = EmphasizedDecelerate)) +
            slideInVertically(tween(300, easing = EmphasizedDecelerate)) { it / 10 }
    val screenExit: ExitTransition =
        fadeOut(tween(150, easing = EmphasizedAccelerate)) +
            slideOutVertically(tween(200, easing = EmphasizedAccelerate)) { it / 10 }

    /** A panel opening out of the card that was tapped. */
    val panelEnter: EnterTransition =
        fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f)
    val panelExit: ExitTransition =
        fadeOut(tween(150, easing = EmphasizedAccelerate)) + scaleOut(tween(150, easing = EmphasizedAccelerate), targetScale = 0.95f)

    /** A card picked up or pushed aside while dragging: quick, without bouncing past. */
    fun <T> lift() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** A thumbnail or page appearing once drawn. */
    const val APPEAR_MS = 150
}
