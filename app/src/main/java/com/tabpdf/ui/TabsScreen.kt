package com.tabpdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabpdf.tabs.PdfTab
import com.tabpdf.tabs.TabsViewModel

private val TOP_BAR_HEIGHT = 56.dp

@Composable
fun TabsScreen(viewModel: TabsViewModel, onOpenFiles: () -> Unit) {
    val tabs by viewModel.tabs.collectAsState()
    val navigateTo by viewModel.navigateTo.collectAsState()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    val pagerState = rememberPagerState { tabs.size }

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
                    initialState = viewModel.viewerState(tab.id),
                    onSaveState = { viewModel.saveViewerState(tab.id, it) },
                    onTap = { barsVisible = !barsVisible },
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
                onSelectTab = { index -> viewModel.open(listOf(tabs[index].uri to tabs[index].name)) },
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
    val current = tabs.getOrNull(pagerState.currentPage)
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT - 3.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current?.name.orEmpty(),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onOpenFiles) {
                    Icon(Icons.Default.Add, contentDescription = "PDF 열기")
                }
                TabCountButton(tabs, pagerState.currentPage, onSelectTab, onCloseTab)
            }
            TabIndicator(pagerState, tabs.size)
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

/** A thin track with a segment that follows the pager, so position reads at a glance for any tab count. */
@Composable
private fun TabIndicator(pagerState: PagerState, count: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp)) {
        if (count <= 1) return@BoxWithConstraints
        val segment = maxWidth / count
        val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, (count - 1).toFloat())
        Box(
            Modifier
                .offset(x = segment * position)
                .width(segment)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
    }
}
