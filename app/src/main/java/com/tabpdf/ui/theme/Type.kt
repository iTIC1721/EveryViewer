package com.tabpdf.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.tabpdf.R

/**
 * UI type: Pretendard Std (KS X 1001 hangul and latin; other syllables fall back to the system
 * font glyph by glyph). Only the app's own UI uses it — documents are laid out with their own fonts.
 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)

/** Material 3's type scale in Pretendard: titles SemiBold, labels Medium, hangul slightly tighter. */
val AppTypography: Typography = Typography().run {
    fun TextStyle.set(weight: FontWeight? = null, tracking: Double = -0.005) =
        copy(fontFamily = Pretendard, fontWeight = weight ?: fontWeight, letterSpacing = tracking.em)
    Typography(
        displayLarge = displayLarge.set(tracking = -0.02),
        displayMedium = displayMedium.set(tracking = -0.02),
        displaySmall = displaySmall.set(tracking = -0.015),
        headlineLarge = headlineLarge.set(FontWeight.SemiBold, -0.015),
        headlineMedium = headlineMedium.set(FontWeight.SemiBold, -0.015),
        headlineSmall = headlineSmall.set(FontWeight.SemiBold, -0.01),
        titleLarge = titleLarge.set(FontWeight.SemiBold, -0.01),
        titleMedium = titleMedium.set(FontWeight.SemiBold, -0.01),
        titleSmall = titleSmall.set(FontWeight.Medium),
        bodyLarge = bodyLarge.set(),
        bodyMedium = bodyMedium.set(),
        bodySmall = bodySmall.set(tracking = 0.0),
        labelLarge = labelLarge.set(FontWeight.Medium, 0.0),
        labelMedium = labelMedium.set(FontWeight.Medium, 0.0),
        labelSmall = labelSmall.set(FontWeight.Medium, 0.0),
    )
}

/** Digits of equal width, so a changing number (page 9 → 10) doesn't make its neighbours jump. */
fun TextStyle.tabularNumbers() = copy(fontFeatureSettings = "tnum")
