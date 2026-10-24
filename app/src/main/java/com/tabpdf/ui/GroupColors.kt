package com.tabpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tabpdf.tabs.TabsViewModel

/** Fixed group palette (기획서 5.4); a group stores an index into it. Mid tones read on light and dark. */
val GroupColors = listOf(
    Color(0xFF5B7FDB), // blue
    Color(0xFFE0675A), // red
    Color(0xFF3FA66B), // green
    Color(0xFFE59A3B), // orange
    Color(0xFF9B6BD6), // purple
    Color(0xFF2EA3A3), // teal
    Color(0xFFD9619C), // pink
    Color(0xFFB59A2E), // olive
).also { check(it.size == TabsViewModel.GROUP_COLOR_COUNT) }

fun groupColor(index: Int): Color = GroupColors[index.mod(GroupColors.size)]

@Composable
fun GroupDot(color: Int, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).background(groupColor(color), CircleShape))
}
