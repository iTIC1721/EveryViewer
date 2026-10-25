package com.tabpdf.ui

import com.tabpdf.FingerDrawing
import com.tabpdf.ink.InkBinding
import com.tabpdf.ink.LocalInkStore
import androidx.compose.runtime.CompositionLocalProvider
import com.tabpdf.ink.InkTool
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import com.tabpdf.doc.FileFormat
import com.tabpdf.doc.TextSearch
import androidx.compose.material3.Button
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
import kotlin.math.abs
import kotlin.math.roundToInt

private val TOP_BAR_HEIGHT = 56.dp

/**
 * Main screen: the viewer pager with its top bar, and the tab switcher over it.
 * [onOpenFiles] opens the file picker for a group, or for ungrouped tabs (null).
 */
@Composable
fun TabsScreen(
    viewModel: TabsViewModel,
    onOpenFiles: (groupId: String?) -> Unit,
    onRelocate: (DocTab) -> Unit,
    /** Opens the file picker for files to add to the bundle being edited. */
    onAddToBundle: () -> Unit,
) {
    // Loading the saved session takes a few ms; show just the background until then, so the pager
    // can start on the saved tab instead of jumping to it.
    val session = viewModel.state.collectAsState().value ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val undoOffer by viewModel.undoOffer.collectAsState()
    val openChoice by viewModel.openChoice.collectAsState()
    val bundleEdit by viewModel.bundleEdit.collectAsState()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    var openGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val keepScreenOn by viewModel.settings.keepScreenOn.collectAsState()
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    CompositionLocalProvider(LocalInkStore provides viewModel.ink) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Swiping stays within the current tab's group, or among ungrouped tabs (기획서 5.2) —
        // unless set to go on across groups, through every tab in the switcher's order.
        // A fresh pager per context, starting on the current tab.
        val crossGroups by viewModel.settings.crossGroupSwipe.collectAsState()
        val contextId = session.currentTab?.groupId
        key(crossGroups, if (crossGroups) null else contextId) {
            ContextPager(
                session = session,
                groupId = contextId,
                crossGroups = crossGroups,
                viewModel = viewModel,
                barsVisible = barsVisible,
                onToggleBars = { barsVisible = !barsVisible },
                onOpenFiles = onOpenFiles,
                onRelocate = onRelocate,
                onShowSwitcher = { groupId ->
                    openGroupId = groupId
                    showSwitcher = true
                },
                onShowSettings = { showSettings = true },
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

        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
        ) {
            SettingsScreen(viewModel.settings, onDismiss = { showSettings = false })
        }

        bundleEdit?.let { edit -> BundleEditor(edit, viewModel, onAddFiles = onAddToBundle) }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    }

    openChoice?.let { choice ->
        OpenChoiceDialog(
            count = choice.files.size,
            onBundle = { viewModel.resolveOpenChoice(bundle = true) },
            onSeparate = { viewModel.resolveOpenChoice(bundle = false) },
            onDismiss = { viewModel.resolveOpenChoice(bundle = null) },
        )
    }

    // Closing a tab or group offers undo; once the snackbar goes away, the close is final.
    LaunchedEffect(undoOffer?.id) {
        val offer = undoOffer ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(offer.message, actionLabel = "실행 취소", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.commitUndo()
    }
}

/**
 * The tabs of one swipe context — a group, or all ungrouped tabs, or every tab if [crossGroups] —
 * with the top bar over them. [groupId] is the current tab's group.
 */
@Composable
private fun ContextPager(
    session: SessionState,
    groupId: String?,
    crossGroups: Boolean,
    viewModel: TabsViewModel,
    barsVisible: Boolean,
    onToggleBars: () -> Unit,
    onOpenFiles: (groupId: String?) -> Unit,
    onRelocate: (DocTab) -> Unit,
    onShowSwitcher: (openGroupId: String?) -> Unit,
    onShowSettings: () -> Unit,
) {
    val tabs = if (crossGroups) session.allTabs else session.context(groupId)
    val group = groupId?.let { session.group(it) }
    val pageJump by viewModel.pageJump.collectAsState()
    var dialog by remember { mutableStateOf<ViewerDialog?>(null) }
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOfFirst { it.id == session.currentTabId }.coerceAtLeast(0)
    ) { tabs.size }
    val currentTabs by rememberUpdatedState(tabs)

    // Handwriting (기획서 5.8)
    val inkMode by viewModel.inkMode.collectAsState()
    val inkTool by viewModel.settings.inkTool.collectAsState()
    val toolStyles by viewModel.settings.toolStyles.collectAsState()
    val fingerDrawing by viewModel.settings.fingerDrawing.collectAsState()
    val stylusSeen by viewModel.settings.stylusSeen.collectAsState()
    val sideButtonErases by viewModel.settings.sideButtonErases.collectAsState()
    val inkHistory by viewModel.ink.history.collectAsState()
    val recentColors by viewModel.settings.recentColors.collectAsState()
    val fingerDraws = when (fingerDrawing) {
        FingerDrawing.Always -> true
        FingerDrawing.Never -> false
        FingerDrawing.Auto -> !stylusSeen
    }

    // Text search (기획서 5.5): runs on the current tab; the query stays while swiping to another.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchIndex by remember { mutableIntStateOf(0) }
    var searchCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(searchQuery, session.currentTabId) { searchIndex = 0 }
    fun step(by: Int) {
        val count = searchCount ?: return
        if (count > 0) searchIndex = (searchIndex + by + count) % count
    }
    BackHandler(enabled = searchOpen) { searchOpen = false }

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
                val strokes by remember(tab.id) { viewModel.ink.strokes(tab.id) }.collectAsState()
                val ink = if (!inkMode) null else InkBinding(
                    tool = inkTool,
                    style = toolStyles.getValue(inkTool),
                    eraserSize = toolStyles.getValue(InkTool.Eraser).size,
                    fingerDraws = fingerDraws,
                    sideButtonErases = sideButtonErases,
                    strokes = strokes,
                    onStylusSeen = viewModel.settings::markStylusSeen,
                    onStroke = { p, tool, stroke -> viewModel.addStroke(tab.id, p, tool, stroke) },
                    onErase = { ids, gesture -> viewModel.eraseStrokes(tab.id, ids, gesture) },
                )
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
                    onDocument = { viewModel.onDocument(tab.id, it) },
                    strokes = strokes,
                    ink = ink,
                    searchQuery = searchQuery.takeIf { searchOpen && tab.id == session.currentTabId },
                    searchIndex = searchIndex,
                    onSearchResults = { searchCount = it },
                    active = tab.id == session.currentTabId,
                )
            }
        }

        AnimatedVisibility(
            visible = barsVisible || tabs.isEmpty() || searchOpen,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column {
            if (searchOpen && tabs.isNotEmpty()) SearchBar(
                query = searchQuery,
                onQuery = { searchQuery = it },
                count = searchCount,
                index = searchIndex,
                onPrevious = { step(-1) },
                onNext = { step(1) },
                onClose = { searchOpen = false },
            ) else TopBar(
                group = group,
                totalTabCount = session.allTabs.size,
                tabs = tabs,
                pagerState = pagerState,
                onOpenFiles = { onOpenFiles(groupId) },
                onTabCountClick = { onShowSwitcher(null) },
                inkMode = inkMode,
                onInkMode = { viewModel.setInkMode(!inkMode) },
                menu = { dismiss ->
                    val canJump = session.currentTab?.let { viewModel.pageCount(it.id) } != null
                    val bundle = session.currentTab?.takeIf { it.format == FileFormat.Bundle }
                    ViewerMenu(
                        canJump, open = { dialog = it }, onSearch = { searchOpen = true }, onSettings = onShowSettings,
                        onEditBundle = bundle?.let { { viewModel.startBundleEdit(it.id) } }, dismiss = dismiss,
                    )
                },
            )
            if (inkMode && tabs.isNotEmpty()) {
                val currentId = session.currentTabId
                // inkHistory is read here so undo/redo availability updates with every edit.
                InkToolbar(
                    tool = inkTool,
                    styles = toolStyles,
                    recentColors = recentColors,
                    canUndo = inkHistory >= 0 && currentId != null && viewModel.ink.canUndo(currentId),
                    canRedo = inkHistory >= 0 && currentId != null && viewModel.ink.canRedo(currentId),
                    onTool = viewModel.settings::setInkTool,
                    onStyle = viewModel.settings::setToolStyle,
                    onCustomColor = viewModel.settings::addRecentColor,
                    onUndo = { viewModel.undoInk() },
                    onRedo = { viewModel.redoInk() },
                )
            }
            }
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
        null -> Unit
    }
}

@Composable
private fun TopBar(
    /** The current tab's group, shown only as the tab button's colour so the file name keeps the room. */
    group: TabGroup?,
    totalTabCount: Int,
    tabs: List<DocTab>,
    pagerState: PagerState,
    onOpenFiles: () -> Unit,
    onTabCountClick: () -> Unit,
    inkMode: Boolean,
    onInkMode: () -> Unit,
    /** Items of the ⋮ menu; call the given function to close it. */
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleStrip(tabs, pagerState, Modifier.weight(1f))
            if (tabs.isNotEmpty()) {
                IconButton(
                    onClick = onInkMode,
                    modifier = Modifier.semantics { contentDescription = if (inkMode) "필기 끄기" else "필기" },
                    colors = if (inkMode) {
                        IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    },
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                }
            }
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Default.Add, contentDescription = "파일 열기")
            }
            TabCountButton(totalTabCount, group, onTabCountClick)
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

private enum class ViewerDialog { PageJump }

/**
 * Find in the document, in place of the top bar (기획서 5.5), laid out like a browser's find bar:
 * the text, which match of how many, previous and next, close.
 */
@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    /** Matches found; null while searching. */
    count: Int?,
    index: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface.copy(alpha = 0.95f), contentColor = colors.onSurface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .semantics { contentDescription = "찾을 내용" },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Icon(Icons.Default.Search, contentDescription = null, tint = colors.onSurfaceVariant)
                        field()
                    }
                },
            )
            if (query.isNotEmpty() && count != null) {
                val total = if (count >= TextSearch.MAX_MATCHES) "$count+" else "$count"
                Text(
                    if (count == 0) "0/0" else "${index + 1}/$total",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (count == 0) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            val any = (count ?: 0) > 0
            IconButton(onClick = onPrevious, enabled = any) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "이전 결과")
            }
            IconButton(onClick = onNext, enabled = any) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "다음 결과")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "찾기 닫기")
            }
        }
    }
}

/** ⋮ menu items: find, go to page, editing a bundle (when one is shown), settings (기획서 4.1, 5.1, 5.6). */
@Composable
private fun ViewerMenu(
    canJump: Boolean,
    open: (ViewerDialog) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onEditBundle: (() -> Unit)?,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text("찾기") },
        enabled = canJump,
        onClick = { dismiss(); onSearch() },
    )
    DropdownMenuItem(
        text = { Text("페이지 이동") },
        enabled = canJump,
        onClick = { dismiss(); open(ViewerDialog.PageJump) },
    )
    if (onEditBundle != null) DropdownMenuItem(text = { Text("묶음 편집") }, onClick = { dismiss(); onEditBundle() })
    DropdownMenuItem(text = { Text("설정") }, onClick = { dismiss(); onSettings() })
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

/**
 * Total tab count, like a mobile browser's tab button; opens the tab switcher. While a grouped tab
 * is shown, it takes the group's colour.
 */
@Composable
private fun TabCountButton(count: Int, group: TabGroup?, onClick: () -> Unit) {
    val description = "탭 ${count}개 보기" + if (group != null) ", 그룹 ${group.name.ifBlank { "이름 없음" }}" else ""
    val color = group?.let { groupColor(it.color) }
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(
            Modifier
                .size(24.dp)
                .then(if (color != null) Modifier.background(color.copy(alpha = 0.25f), RoundedCornerShape(5.dp)) else Modifier)
                .border(2.dp, color ?: MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) "99+" else count.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
