package com.tabpdf.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * "Paper on a desk": the bars, cards and panels are paper; behind the pages is a darker desk, so a
 * document reads as a sheet lying on it. One accent, ink violet from the app icon (#6A24DE), marks
 * state (pen on, current tab, selection). Text roles keep 4.5:1 or more, outlines 3:1 or more.
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
    secondary = Color(0xFF615B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7DFF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    // Paper, graphite (text) and pencil (secondary text, outlines)
    background = Color(0xFFFCFBFE),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFCFBFE),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE6E1EC),
    onSurfaceVariant = Color(0xFF5D5A66),
    surfaceTint = Color(0xFF5B33C9),
    inverseSurface = Color(0xFF313036),
    inverseOnSurface = Color(0xFFF4EFF7),
    outline = Color(0xFF8E8A97),
    outlineVariant = Color(0xFFCAC5D2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFBFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F5F9),
    surfaceContainer = Color(0xFFF0EFF4),
    surfaceContainerHigh = Color(0xFFEAE9EE),
    surfaceContainerHighest = Color(0xFFE4E3E9),
    // Desk: behind the pages and the tab switcher's cards
    surfaceDim = Color(0xFFE3E2E8),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFCDBDFF),
    onPrimary = Color(0xFF330D8E),
    primaryContainer = Color(0xFF4A28B0),
    onPrimaryContainer = Color(0xFFE7DEFF),
    inversePrimary = Color(0xFF5B33C9),
    secondary = Color(0xFFCBC3DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE7DFF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF1C1B20),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF1C1B20),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF48454E),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceTint = Color(0xFFCDBDFF),
    inverseSurface = Color(0xFFE6E1E9),
    inverseOnSurface = Color(0xFF313036),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A383F),
    surfaceContainerLowest = Color(0xFF161519),
    surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF212026),
    surfaceContainerHigh = Color(0xFF2B2A30),
    surfaceContainerHighest = Color(0xFF36353B),
    // The desk is darker than the bars so white pages stand out without a shadow.
    surfaceDim = Color(0xFF131217),
)
