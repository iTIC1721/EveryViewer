package com.tabpdf.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.SessionState
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.TabsViewModel
import com.tabpdf.tabs.displayName
import kotlin.math.abs
import kotlin.math.roundToInt

private val TOP_BAR_HEIGHT = 56.dp

/**
 * Main screen: the viewer pager with its top bar, and the tab switcher over it.
 * [onOpenFiles] opens the file picker for a group, or for ungrouped tabs (null).
 */
@Composable
fun TabsScreen(viewModel: TabsViewModel, onOpenFiles: (groupId: String?) -> Unit, onRelocate: (DocTab) -> Unit) {
    // Loading the saved session takes a few ms; show just the background until then, so the pager
    // can start on the saved tab instead of jumping to it.
    val session = viewModel.state.collectAsState().value ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsState()
    val undoOffer by viewModel.undoOffer.collectAsState()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    var openGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val keepScreenOn by viewModel.settings.keepScreenOn.collectAsState()
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Swiping stays within the current tab's group, or among ungrouped tabs (기획서 5.2).
        // A fresh pager per context, starting on the current tab.
        val contextId = session.currentTab?.groupId
        key(contextId) {
            ContextPager(
                session = session,
                groupId = contextId,
                viewModel = viewModel,
                barsVisible = barsVisible,
                onToggleBars = { barsVisible = !barsVisible },
                onOpenFiles = onOpenFiles,
                onRelocate = onRelocate,
                onShowSwitcher = { groupId ->
                    openGroupId = groupId
                    showSwitcher = true
                },
            )
        }

        AnimatedVisibility(
            visible = showSwitcher,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
        ) {
            TabSwitcher(
                state = session,
                viewModel = viewModel,
                openGroupId = openGroupId,
                onOpenGroup = { openGroupId = it },
                onOpenFiles = { groupId ->
                    openGroupId = null
                    showSwitcher = false
                    onOpenFiles(groupId)
                },
                onDismiss = {
                    openGroupId = null
                    showSwitcher = false
                },
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
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

/** The tabs of one swipe context — a group, or all ungrouped tabs — with the top bar over them. */
@Composable
private fun ContextPager(
    session: SessionState,
    groupId: String?,
    viewModel: TabsViewModel,
    barsVisible: Boolean,
    onToggleBars: () -> Unit,
    onOpenFiles: (groupId: String?) -> Unit,
    onRelocate: (DocTab) -> Unit,
    onShowSwitcher: (openGroupId: String?) -> Unit,
) {
    val tabs = session.context(groupId)
    val group = groupId?.let { session.group(it) }
    val pageJump by viewModel.pageJump.collectAsState()
    var dialog by remember { mutableStateOf<ViewerDialog?>(null) }
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOfFirst { it.id == session.currentTabId }.coerceAtLeast(0)
    ) { tabs.size }
    val currentTabs by rememberUpdatedState(tabs)

    // Swiping picks the current tab…
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentTabs.getOrNull(page)?.let { viewModel.onTabShown(it.id) }
        }
    }
    // …and a current tab chosen elsewhere (switcher, undo, a newly opened file) moves the pager.
    LaunchedEffect(session.currentTabId, tabs) {
        val index = tabs.indexOfFirst { it.id == session.currentTabId }
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }

    Box(Modifier.fillMaxSize()) {
        if (tabs.isEmpty()) {
            Button(onClick = { onOpenFiles(groupId) }, modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("파일 열기", Modifier.padding(start = 8.dp))
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
                DocumentViewer(
                    uri = tab.uri,
                    name = tab.name,
                    format = tab.format,
                    revision = tab.revision,
                    initialState = viewModel.viewerState(tab.id),
                    onStateChange = { viewModel.updateViewerState(tab.id, it) },
                    onTap = onToggleBars,
                    onRelocate = { onRelocate(tab) },
                    onClose = { viewModel.close(tab.id) },
                    contentPadding = contentPadding,
                    pageJump = pageJump?.takeIf { it.tabId == tab.id },
                    onPageJumpHandled = viewModel::onPageJumpHandled,
                    onDocumentOpened = { viewModel.onDocumentOpened(tab.id, it) },
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
                group = group,
                groupTabCount = tabs.size,
                totalTabCount = session.allTabs.size,
                tabs = tabs,
                pagerState = pagerState,
                onGroupClick = { onShowSwitcher(groupId) },
                onOpenFiles = { onOpenFiles(groupId) },
                onTabCountClick = { onShowSwitcher(null) },
                menu = { dismiss ->
                    val canJump = session.currentTab?.let { viewModel.pageCount(it.id) } != null
                    ViewerMenu(canJump, viewModel, open = { dialog = it }, dismiss = dismiss)
                },
            )
        }
    }

    val current = session.currentTab
    when (dialog) {
        ViewerDialog.PageJump -> {
            val pageCount = current?.let { viewModel.pageCount(it.id) }
            if (current != null && pageCount != null) {
                PageJumpDialog(
                    current = viewModel.viewerState(current.id).page,
                    pageCount = pageCount,
                    onJump = { page ->
                        dialog = null
                        viewModel.jumpToPage(current.id, page)
                    },
                    onDismiss = { dialog = null },
                )
            }
        }
        ViewerDialog.About -> AboutDialog(onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun TopBar(
    group: TabGroup?,
    groupTabCount: Int,
    totalTabCount: Int,
    tabs: List<DocTab>,
    pagerState: PagerState,
    onGroupClick: () -> Unit,
    onOpenFiles: () -> Unit,
    onTabCountClick: () -> Unit,
    /** Items of the ⋮ menu; call the given function to close it. */
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT)
                .padding(start = if (group != null) 8.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group != null) {
                GroupChip(group, groupTabCount, onGroupClick)
                Spacer(Modifier.width(8.dp))
            }
            TitleStrip(tabs, pagerState, Modifier.weight(1f))
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Default.Add, contentDescription = "파일 열기")
            }
            TabCountButton(totalTabCount, onTabCountClick)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "메뉴")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu { menuOpen = false }
                }
            }
        }
    }
}

private enum class ViewerDialog { PageJump, About }

/** ⋮ menu items: go to page, keep the screen on, app info (기획서 4.1, 5.6). */
@Composable
private fun ViewerMenu(canJump: Boolean, viewModel: TabsViewModel, open: (ViewerDialog) -> Unit, dismiss: () -> Unit) {
    val keepScreenOn by viewModel.settings.keepScreenOn.collectAsState()
    DropdownMenuItem(
        text = { Text("페이지 이동") },
        enabled = canJump,
        onClick = { dismiss(); open(ViewerDialog.PageJump) },
    )
    DropdownMenuItem(
        text = { Text("화면 켜짐 유지") },
        trailingIcon = { Checkbox(checked = keepScreenOn, onCheckedChange = null) },
        onClick = { viewModel.settings.setKeepScreenOn(!keepScreenOn) },
    )
    DropdownMenuItem(text = { Text("앱 정보") }, onClick = { dismiss(); open(ViewerDialog.About) })
}

/** Shown only while viewing a grouped tab: the group's colour and name; opens its panel. */
@Composable
private fun GroupChip(group: TabGroup, tabCount: Int, onClick: () -> Unit) {
    val color = groupColor(group.color)
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupDot(group.color)
        Spacer(Modifier.width(6.dp))
        Text(
            group.displayName(tabCount),
            Modifier.widthIn(max = 96.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * File names of the current tab and its neighbours, sliding with the pager: while swiping, the
 * current name moves out with the finger and the next one comes in, fading by distance.
 */
@Composable
private fun TitleStrip(tabs: List<DocTab>, pagerState: PagerState, modifier: Modifier) {
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

/** Total tab count, like a mobile browser's tab button; opens the tab switcher. */
@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "탭 ${count}개 보기" }) {
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
