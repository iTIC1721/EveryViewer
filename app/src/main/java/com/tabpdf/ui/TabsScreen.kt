package com.tabpdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tabpdf.tabs.PdfTab
import com.tabpdf.tabs.TabsViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private val TOP_BAR_HEIGHT = 56.dp

@Composable
fun TabsScreen(viewModel: TabsViewModel, onOpenFiles: () -> Unit, onRelocate: (PdfTab) -> Unit) {
    val session by viewModel.session.collectAsState()
    // Loading the saved session takes a few ms; show just the background until then, so the pager
    // can start on the saved tab instead of jumping to it.
    val loaded = session ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val tabs by viewModel.tabs.collectAsState()
    val navigateTo by viewModel.navigateTo.collectAsState()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOfFirst { it.id == loaded.initialTabId }.coerceAtLeast(0)
    ) { tabs.size }
    val currentTabs by rememberUpdatedState(tabs)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentTabs.getOrNull(page)?.let { viewModel.onTabShown(it.id) }
        }
    }

    LaunchedEffect(navigateTo, tabs) {
        val id = navigateTo ?: return@LaunchedEffect
        val index = tabs.indexOfFirst { it.id == id }
        if (index >= 0) pagerState.scrollToPage(index)
        viewModel.onNavigated()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (tabs.isEmpty()) {
            Button(onClick = onOpenFiles, modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("PDF 열기", Modifier.padding(start = 8.dp))
            }
        } else {
            // Pages sit under the bar, so the first page starts below it either way.
            val contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_HEIGHT + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp,
            )
            HorizontalPager(
                state = pagerState,
                key = { tabs[it].id },
                // Keeps the neighbouring tabs open so a swipe shows them immediately.
                beyondViewportPageCount = 1,
                pageSpacing = 8.dp,
            ) { page ->
                val tab = tabs[page]
                PdfViewer(
                    uri = tab.uri,
                    revision = tab.revision,
                    initialState = viewModel.viewerState(tab.id),
                    onStateChange = { viewModel.updateViewerState(tab.id, it) },
                    onTap = { barsVisible = !barsVisible },
                    onRelocate = { onRelocate(tab) },
                    onClose = { viewModel.close(tab.id) },
                    contentPadding = contentPadding,
                )
            }
        }

        AnimatedVisibility(
            visible = barsVisible && tabs.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TopBar(
                tabs = tabs,
                pagerState = pagerState,
                onOpenFiles = onOpenFiles,
                onSelectTab = { index -> viewModel.select(tabs[index].id) },
                onCloseTab = { viewModel.close(it.id) },
            )
        }
    }
}

@Composable
private fun TopBar(
    tabs: List<PdfTab>,
    pagerState: PagerState,
    onOpenFiles: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (PdfTab) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleStrip(tabs, pagerState, Modifier.weight(1f))
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Default.Add, contentDescription = "PDF 열기")
            }
            TabCountButton(tabs, pagerState.currentPage, onSelectTab, onCloseTab)
        }
    }
}

/**
 * File names of the current tab and its neighbours, sliding with the pager: while swiping, the
 * current name moves out with the finger and the next one comes in, fading by distance.
 */
@Composable
private fun TitleStrip(tabs: List<PdfTab>, pagerState: PagerState, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
        val width = constraints.maxWidth.toFloat()
        val center = pagerState.currentPage
        for (page in (center - 1)..(center + 1)) {
            val tab = tabs.getOrNull(page) ?: continue
            // Distance from the pager's live position, in pages: 0 = settled here, ±1 = a full swipe away.
            fun distance() = page - (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            key(tab.id) {
                Text(
                    tab.name,
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset((distance() * width).roundToInt(), 0) }
                        .graphicsLayer { alpha = (1f - abs(distance())).coerceIn(0f, 1f) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Tab count like a mobile browser's tab button; opens a plain list until 탭 개요 lands (3단계). */
@Composable
private fun TabCountButton(
    tabs: List<PdfTab>,
    currentIndex: Int,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (PdfTab) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Box(
                Modifier
                    .size(24.dp)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (tabs.size > 99) "99+" else tabs.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tabs.forEachIndexed { index, tab ->
                DropdownMenuItem(
                    text = { Text(tab.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelectTab(index)
                    },
                    leadingIcon = {
                        if (index == currentIndex) Icon(Icons.Default.Check, contentDescription = "현재 탭")
                    },
                    trailingIcon = {
                        IconButton(onClick = { onCloseTab(tab) }) {
                            Icon(Icons.Default.Close, contentDescription = "탭 닫기")
                        }
                    },
                )
            }
        }
    }
}
