package com.tabpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tabpdf.tabs.PdfTab
import com.tabpdf.tabs.SessionState
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.TabsViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private val TOP_BAR_HEIGHT = 56.dp

@Composable
fun TabsScreen(viewModel: TabsViewModel, onOpenFiles: () -> Unit, onRelocate: (PdfTab) -> Unit) {
    // Loading the saved session takes a few ms; show just the background until then, so the pager
    // can start on the saved tab instead of jumping to it.
    val session = viewModel.state.collectAsState().value ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsState()
    val undoOffer by viewModel.undoOffer.collectAsState()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var showOverview by rememberSaveable { mutableStateOf(false) }
    var showGroups by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        // A fresh pager per group, starting on the tab last seen in that group.
        key(session.activeGroupId) {
            GroupPager(
                session = session,
                viewModel = viewModel,
                barsVisible = barsVisible,
                onToggleBars = { barsVisible = !barsVisible },
                onOpenFiles = onOpenFiles,
                onRelocate = onRelocate,
                onShowOverview = { showOverview = true },
                onShowGroups = { showGroups = true },
            )
        }

        AnimatedVisibility(
            visible = showOverview,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
        ) {
            TabOverview(
                group = session.activeGroup,
                tabs = session.activeTabs,
                groups = session.groups,
                currentTabId = session.activeGroup.activeTabId,
                pageOf = { viewModel.viewerState(it).page },
                onSelect = { id ->
                    viewModel.select(id)
                    showOverview = false
                },
                onClose = viewModel::close,
                onReorder = viewModel::reorderTabs,
                onMoveToGroup = viewModel::moveTab,
                onMoveToNewGroup = viewModel::moveTabToNewGroup,
                onOpenFiles = {
                    showOverview = false
                    onOpenFiles()
                },
                onBack = { showOverview = false },
            )
        }
        BackHandler(enabled = showOverview) { showOverview = false }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (showGroups) {
        GroupSheet(
            groups = session.groups,
            activeGroupId = session.activeGroupId,
            tabCount = { session.tabsOf(it).size },
            onSelect = { id ->
                viewModel.switchGroup(id)
                showGroups = false
            },
            onCreate = { name ->
                viewModel.createGroup(name)
                showGroups = false
            },
            onRename = viewModel::renameGroup,
            onColor = viewModel::setGroupColor,
            onReorder = viewModel::reorderGroups,
            onClose = { id ->
                viewModel.closeGroup(id)
                // Close the sheet so the undo snackbar underneath can be seen and tapped.
                showGroups = false
            },
            onDismiss = { showGroups = false },
        )
    }

    duplicatePrompt?.let { prompt ->
        DuplicateDialog(
            prompt,
            onGoThere = { viewModel.resolveDuplicate(openHere = false) },
            onOpenHere = { viewModel.resolveDuplicate(openHere = true) },
            onCancel = { viewModel.resolveDuplicate(openHere = null) },
        )
    }

    // Closing a tab or group offers undo; once the snackbar goes away, the close is final.
    LaunchedEffect(undoOffer?.id) {
        val offer = undoOffer ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(offer.message, actionLabel = "실행 취소", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.commitUndo()
    }
}

/** The active group's tabs: a horizontal pager of viewers with the top bar over it. */
@Composable
private fun GroupPager(
    session: SessionState,
    viewModel: TabsViewModel,
    barsVisible: Boolean,
    onToggleBars: () -> Unit,
    onOpenFiles: () -> Unit,
    onRelocate: (PdfTab) -> Unit,
    onShowOverview: () -> Unit,
    onShowGroups: () -> Unit,
) {
    val tabs = session.activeTabs
    val navigateTo by viewModel.navigateTo.collectAsState()
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOfFirst { it.id == session.activeGroup.activeTabId }.coerceAtLeast(0)
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
        if (index < 0) return@LaunchedEffect // Not in this group (yet); the group switch will handle it.
        pagerState.scrollToPage(index)
        viewModel.onNavigated()
    }

    Box(Modifier.fillMaxSize()) {
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
                    onTap = onToggleBars,
                    onRelocate = { onRelocate(tab) },
                    onClose = { viewModel.close(tab.id) },
                    contentPadding = contentPadding,
                )
            }
        }

        AnimatedVisibility(
            visible = barsVisible || tabs.isEmpty(),
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TopBar(
                group = session.activeGroup,
                showGroupName = session.groups.size > 1,
                tabs = tabs,
                pagerState = pagerState,
                onGroupClick = onShowGroups,
                onOpenFiles = onOpenFiles,
                onTabCountClick = onShowOverview,
            )
        }
    }
}

@Composable
private fun TopBar(
    group: TabGroup,
    showGroupName: Boolean,
    tabs: List<PdfTab>,
    pagerState: PagerState,
    onGroupClick: () -> Unit,
    onOpenFiles: () -> Unit,
    onTabCountClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupChip(group, showGroupName, onGroupClick)
            Spacer(Modifier.width(8.dp))
            TitleStrip(tabs, pagerState, Modifier.weight(1f))
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Default.Add, contentDescription = "PDF 열기")
            }
            TabCountButton(tabs.size, onTabCountClick)
        }
    }
}

/**
 * Current group: colour dot and name, opening the group sheet. With a single group only the dot
 * and arrow show, so people who never use groups see a plain tab viewer (기획서 3장).
 */
@Composable
private fun GroupChip(group: TabGroup, showName: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupDot(group.color)
        if (showName) {
            Spacer(Modifier.width(6.dp))
            Text(
                group.name,
                Modifier.widthIn(max = 96.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Default.ArrowDropDown, contentDescription = "탭 그룹", Modifier.size(20.dp))
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

/** Tab count like a mobile browser's tab button; opens the tab overview. */
@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(24.dp)
                .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) "99+" else count.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
