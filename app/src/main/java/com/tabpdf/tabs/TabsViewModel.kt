package com.tabpdf.tabs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabpdf.TabPdfApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** A file being opened that is already open in another place (기획서 5.1). */
data class DuplicatePrompt(
    val file: OpenedFile,
    /** Where the file was to be opened: a group, or null for ungrouped. */
    val targetGroupId: String?,
    val existing: DocTab,
    /** Name of the group the existing tab is in, or null if it is ungrouped. */
    val existingPlace: String?,
)

/** Something just closed that the snackbar can bring back. */
data class UndoOffer(val id: Long, val message: String)

/** A request for tab [tabId]'s viewer to show [page]; [id] makes repeated jumps distinct. */
data class PageJump(val id: Long, val tabId: String, val page: Int)

/**
 * Tabs and tab groups, Chrome-style (기획서 3장), persisted so they come back after the app is
 * closed (기획서 5.3). Every structural change goes through [commit], which saves the whole
 * structure via the ordered write queue; reading positions are saved separately, 0.5 s after they
 * stop changing and immediately via [flush] when the app goes to the background.
 */
class TabsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TabPdfApp
    private val repository = app.repository

    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state.asStateFlow()

    private val _duplicatePrompt = MutableStateFlow<DuplicatePrompt?>(null)
    val duplicatePrompt: StateFlow<DuplicatePrompt?> = _duplicatePrompt.asStateFlow()
    private val pendingDuplicates = ArrayDeque<DuplicatePrompt>()

    private val _undoOffer = MutableStateFlow<UndoOffer?>(null)
    val undoOffer: StateFlow<UndoOffer?> = _undoOffer.asStateFlow()
    private var undoable: Closed? = null
    private var nextUndoId = 0L

    private val _pageJump = MutableStateFlow<PageJump?>(null)
    val pageJump: StateFlow<PageJump?> = _pageJump.asStateFlow()
    private var nextJumpId = 0L

    val settings = app.settings

    private val viewerStates = mutableMapOf<String, ViewerState>()
    private val unsavedStates = mutableMapOf<String, ViewerState>()
    private val pageCounts = mutableMapOf<String, Int>()
    private var saveJob: Job? = null

    private val loadJob = viewModelScope.launch {
        val (saved, states) = repository.load()
        viewerStates += states
        // Groups are never empty (as in Chrome), and something should be on screen if any tab is open.
        val cleaned = saved.copy(top = saved.top.filter { it !is TabGroup || saved.tabsOf(it.id).isNotEmpty() })
            .withValidCurrent(null, 0)
        _state.value = cleaned
        if (cleaned != saved) commit(cleaned)
        app.enqueueWrite { FileAccess.releaseUnused(app, cleaned.allTabs.map { it.uri }.toSet()) }
    }

    private fun current(): SessionState = _state.value!!

    /** Shows [new] and saves its structure. */
    private fun commit(new: SessionState) {
        _state.value = new
        val states = viewerStates.toMap()
        app.enqueueWrite { repository.saveStructure(new, states) }
    }

    // ---- Opening and showing tabs ----

    /**
     * Opens [files] in [groupId] (null: as ungrouped tabs) and shows the first one. A file already
     * open in that same place is not duplicated; one open elsewhere asks first ([duplicatePrompt]).
     */
    fun open(files: List<OpenedFile>, groupId: String?) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            loadJob.join()
            var s = current()
            val target = groupId?.takeIf { s.group(it) != null }
            val added = mutableListOf<DocTab>()
            var show: String? = null
            for (file in files) {
                val here = (s.context(target) + added).firstOrNull { it.uri == file.uri }
                val elsewhere = s.allTabs.firstOrNull { it.uri == file.uri && it.groupId != target }
                val tab = when {
                    here != null -> here
                    elsewhere != null -> {
                        pendingDuplicates += DuplicatePrompt(file, target, elsewhere, elsewhere.groupId?.let { placeName(s, it) })
                        null
                    }
                    else -> newTab(file, target).also { added += it }
                }
                if (show == null && tab != null) show = tab.id
            }
            for (tab in added) s = s.appendTab(tab)
            if (show != null) s = s.copy(currentTabId = show)
            if (s != current()) commit(s)
            showNextDuplicate()
        }
    }

    private fun placeName(s: SessionState, groupId: String) =
        s.group(groupId)?.displayName(s.tabsOf(groupId).size).orEmpty()

    private fun newTab(file: OpenedFile, groupId: String?) =
        DocTab(UUID.randomUUID().toString(), groupId, file.uri, file.name, file.isInternalCopy)

    private fun showNextDuplicate() {
        if (_duplicatePrompt.value == null) _duplicatePrompt.value = pendingDuplicates.removeFirstOrNull()
    }

    /**
     * Answer to [duplicatePrompt]: open another tab for it where it was asked (true), go to the
     * one already open (false), or neither (null — dialog dismissed).
     */
    fun resolveDuplicate(openHere: Boolean?) {
        val prompt = _duplicatePrompt.value ?: return
        _duplicatePrompt.value = null
        when (openHere) {
            true -> {
                val tab = newTab(prompt.file, prompt.targetGroupId?.takeIf { current().group(it) != null })
                commit(current().appendTab(tab).copy(currentTabId = tab.id))
            }
            false -> select(prompt.existing.id)
            null -> Unit
        }
        showNextDuplicate()
    }

    /** Shows tab [id] in the viewer. */
    fun select(id: String) {
        val s = current()
        if (s.tab(id) == null || s.currentTabId == id) return
        _state.value = s.copy(currentTabId = id)
        app.enqueueWrite { repository.setCurrentTab(id) }
    }

    /** The pager settled on [id]. */
    fun onTabShown(id: String) = select(id)

    // ---- Closing (with undo) ----

    fun close(id: String) {
        val s = current()
        val tab = s.tab(id) ?: return
        val context = s.context(tab.groupId)
        val index = context.indexOf(tab)
        val group = tab.groupId?.let { s.group(it) }
        val topIndex = s.top.indexOfFirst { it.id == (group?.id ?: id) }
        val emptiedGroup = group?.takeIf { context.size == 1 }
        commit(s.removeTab(id).withValidCurrent(tab.groupId, index))
        unsavedStates -= id
        offerUndo(Closed.Tab(tab, index, topIndex, emptiedGroup, viewerState(id)), "탭을 닫았습니다")
    }

    /** Closes a group and all its tabs. */
    fun closeGroup(groupId: String) {
        val s = current()
        val group = s.group(groupId) ?: return
        val topIndex = s.top.indexOf(group)
        val tabs = s.tabsOf(groupId)
        val next = s.copy(top = s.top - group, groupTabs = s.groupTabs - groupId)
        commit(next.withValidCurrent(null, topIndex))
        tabs.forEach { unsavedStates -= it.id }
        offerUndo(
            Closed.Group(group, topIndex, tabs, tabs.associate { it.id to viewerState(it.id) }),
            "'${group.displayName(tabs.size)}' 그룹을 닫았습니다",
        )
    }

    private sealed interface Closed {
        /** [emptiedGroup] is set when closing the tab removed its (now empty) group. */
        data class Tab(
            val tab: DocTab,
            val index: Int,
            val topIndex: Int,
            val emptiedGroup: TabGroup?,
            val state: ViewerState,
        ) : Closed

        data class Group(
            val group: TabGroup,
            val topIndex: Int,
            val tabs: List<DocTab>,
            val states: Map<String, ViewerState>,
        ) : Closed
    }

    private fun offerUndo(closed: Closed, message: String) {
        commitUndo()
        undoable = closed
        _undoOffer.value = UndoOffer(nextUndoId++, message)
    }

    /** Brings back what was last closed. */
    fun undo() {
        val closed = undoable ?: return
        undoable = null
        _undoOffer.value = null
        val s = current()
        val restored = when (closed) {
            is Closed.Tab -> {
                viewerStates[closed.tab.id] = closed.state
                val groupId = closed.tab.groupId
                when {
                    groupId == null -> s.insertTop(closed.topIndex, closed.tab)
                    s.group(groupId) != null -> s.insertInGroup(groupId, closed.index, closed.tab)
                    closed.emptiedGroup != null -> s.insertTop(closed.topIndex, closed.emptiedGroup)
                        .copy(groupTabs = s.groupTabs + (groupId to listOf(closed.tab)))
                    else -> s.insertTop(closed.topIndex, closed.tab.copy(groupId = null))
                }.copy(currentTabId = closed.tab.id)
            }
            is Closed.Group -> {
                viewerStates += closed.states
                s.insertTop(closed.topIndex, closed.group)
                    .copy(groupTabs = s.groupTabs + (closed.group.id to closed.tabs))
            }
        }
        commit(restored)
    }

    /** The undo offer is gone: free the closed tabs' files if no other tab uses them. */
    fun commitUndo() {
        val closed = undoable ?: return
        undoable = null
        _undoOffer.value = null
        val tabs = when (closed) {
            is Closed.Tab -> listOf(closed.tab)
            is Closed.Group -> closed.tabs
        }
        tabs.forEach { viewerStates -= it.id }
        app.enqueueWrite {
            for (tab in tabs) if (!repository.isUriInUse(tab.uri)) FileAccess.release(app, tab)
        }
    }

    // ---- Replacing a missing file ----

    /**
     * Points a tab whose file went missing at a newly chosen file, keeping its place. If that file
     * is already open in another tab of the same place, the broken tab is closed and the other one
     * shown instead, so a file is never open twice there (기획서 5.1).
     */
    fun replaceFile(id: String, file: OpenedFile) {
        val s = current()
        val old = s.tab(id) ?: return
        val existing = s.context(old.groupId).firstOrNull { it.id != id && it.uri == file.uri }
        if (existing != null) {
            commit(s.removeTab(id).copy(currentTabId = existing.id))
            app.enqueueWrite { if (!repository.isUriInUse(old.uri)) FileAccess.release(app, old) }
            return
        }
        // Bumping the revision makes the viewer retry even when the same location was picked again.
        val updated = old.copy(uri = file.uri, name = file.name, isInternalCopy = file.isInternalCopy, revision = old.revision + 1)
        commit(s.replaceTab(updated))
        app.enqueueWrite { if (!repository.isUriInUse(old.uri)) FileAccess.release(app, old) }
    }

    // ---- Arranging ----

    // The rules live in SessionEdits.kt; these only commit the result.

    fun reorderTop(ids: List<String>) = commit(current().reorderTop(ids))

    fun reorderGroup(groupId: String, ids: List<String>) = current().reorderGroup(groupId, ids)?.let(::commit)

    /** Dropping ungrouped tab [draggedId] onto ungrouped tab [targetId] makes a new, unnamed group of the two. */
    fun groupTogether(draggedId: String, targetId: String) =
        current().let { it.groupTogether(draggedId, targetId, newGroup(it, "")) }?.let(::commit)

    fun addToGroup(tabId: String, groupId: String) = current().addToGroup(tabId, groupId)?.let(::commit)

    fun addToNewGroup(tabId: String, name: String) =
        current().let { it.addToNewGroup(tabId, newGroup(it, name.trim())) }?.let(::commit)

    fun removeFromGroup(tabId: String) = current().removeFromGroup(tabId)?.let(::commit)

    fun ungroup(groupId: String) = current().ungroup(groupId)?.let(::commit)

    fun renameGroup(groupId: String, name: String) =
        current().updateGroup(groupId) { it.copy(name = name.trim()) }?.let(::commit)

    fun setGroupColor(groupId: String, color: Int) =
        current().updateGroup(groupId) { it.copy(color = color) }?.let(::commit)

    private fun newGroup(s: SessionState, name: String) =
        TabGroup(UUID.randomUUID().toString(), name, s.nextGroupColor(GROUP_COLOR_COUNT))

    // ---- Reading positions ----

    fun viewerState(id: String): ViewerState = viewerStates[id] ?: ViewerState()

    /** Page count of tab [id], once its viewer has opened the file. */
    fun pageCount(id: String): Int? = pageCounts[id]

    fun onDocumentOpened(id: String, pageCount: Int) {
        pageCounts[id] = pageCount
    }

    /** Asks tab [tabId]'s viewer to go to [page] (0-based). */
    fun jumpToPage(tabId: String, page: Int) {
        _pageJump.value = PageJump(nextJumpId++, tabId, page)
    }

    fun onPageJumpHandled(jump: PageJump) {
        if (_pageJump.value == jump) _pageJump.value = null
    }

    fun updateViewerState(id: String, state: ViewerState) {
        val s = _state.value ?: return
        if (s.tab(id) == null || viewerStates[id] == state) return
        viewerStates[id] = state
        unsavedStates[id] = state
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /** Writes pending reading positions now. Safe to call from onStop. */
    fun flush() {
        if (unsavedStates.isEmpty()) return
        val batch = unsavedStates.toMap()
        unsavedStates.clear()
        app.enqueueWrite { repository.saveViewerStates(batch) }
    }

    override fun onCleared() {
        flush()
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 500L
        const val GROUP_COLOR_COUNT = 8
    }
}

