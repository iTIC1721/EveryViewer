package com.tabpdf.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * "Paper on a desk": the bars, cards and panels are paper; behind the pages is a darker desk, so a
 * document reads as a sheet lying on it. The surfaces, text and outlines are one neutral grey ramp
 * (no violet tint), so the documents carry the colour. One accent, ink violet from the app icon
 * (#6A24DE), marks state only (pen on, current tab, selection, a switch that is on). Text roles
 * keep 4.5:1 or more, outlines 3:1 or more.
 *
 * Not theme colours: group colours (GroupColors), pen colours (Ink), search highlights and the
 * documents themselves keep their own colours in both themes (기획서 5.5).
 */

/** A page or thumbnail before it is drawn: documents are white paper in both themes. */
val Paper = Color.White

val LightColors = lightColorScheme(
    // Ink
    primary = Color(0xFF5B33C9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1F005F),
    inversePrimary = Color(0xFFCDBDFF),
    secondary = Color(0xFF525256),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8EA),
    onSecondaryContainer = Color(0xFF18181A),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    // Paper, graphite (text) and pencil (secondary text, outlines): neutral grey
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181A),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF18181A),
    surfaceVariant = Color(0xFFE4E4E6),
    onSurfaceVariant = Color(0xFF525256),
    surfaceTint = Color(0xFF5B33C9),
    inverseSurface = Color(0xFF2A2A2C),
    inverseOnSurface = Color(0xFFF4F4F5),
    outline = Color(0xFF76767B),
    outlineVariant = Color(0xFFD4D4D8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAFAFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F5),
    surfaceContainer = Color(0xFFEFEFF0),
    surfaceContainerHigh = Color(0xFFE9E9EB),
    surfaceContainerHighest = Color(0xFFE3E3E5),
    // Desk: behind the pages and the tab switcher's cards
    surfaceDim = Color(0xFFE2E2E4),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFCDBDFF),
    onPrimary = Color(0xFF330D8E),
    primaryContainer = Color(0xFF4A28B0),
    onPrimaryContainer = Color(0xFFE7DEFF),
    inversePrimary = Color(0xFF5B33C9),
    secondary = Color(0xFFC6C6CA),
    onSecondary = Color(0xFF2A2A2C),
    secondaryContainer = Color(0xFF38383B),
    onSecondaryContainer = Color(0xFFEDEDEE),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF1A1A1C),
    onBackground = Color(0xFFEDEDEE),
    surface = Color(0xFF1A1A1C),
    onSurface = Color(0xFFEDEDEE),
    surfaceVariant = Color(0xFF3A3A3D),
    onSurfaceVariant = Color(0xFFB4B4B9),
    surfaceTint = Color(0xFFCDBDFF),
    inverseSurface = Color(0xFFEDEDEE),
    inverseOnSurface = Color(0xFF2A2A2C),
    outline = Color(0xFF8A8A90),
    outlineVariant = Color(0xFF3A3A3D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38383B),
    surfaceContainerLowest = Color(0xFF141415),
    surfaceContainerLow = Color(0xFF1A1A1C),
    surfaceContainer = Color(0xFF1F1F21),
    surfaceContainerHigh = Color(0xFF29292B),
    surfaceContainerHighest = Color(0xFF333336),
    // The desk is darker than the bars so white pages stand out without a shadow.
    surfaceDim = Color(0xFF111112),
)
