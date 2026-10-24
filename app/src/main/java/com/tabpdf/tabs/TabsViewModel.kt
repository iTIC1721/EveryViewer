package com.tabpdf.tabs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabpdf.TabPdfApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PdfTab(
    val id: String,
    val groupId: String,
    val uri: Uri,
    val name: String,
    val isInternalCopy: Boolean,
    /** Bumped when the tab's file is replaced; not persisted. */
    val revision: Int = 0,
)

data class TabGroup(
    val id: String,
    val name: String,
    /** Index into the group colour palette. */
    val color: Int,
    /** Tab to show when switching back to this group. */
    val activeTabId: String?,
)

/** All groups and their tabs; the screen shows [activeGroupId]. */
data class SessionState(
    val groups: List<TabGroup>,
    val tabsByGroup: Map<String, List<PdfTab>>,
    val activeGroupId: String,
) {
    val activeGroup: TabGroup get() = groups.first { it.id == activeGroupId }
    val activeTabs: List<PdfTab> get() = tabsOf(activeGroupId)
    fun tabsOf(groupId: String): List<PdfTab> = tabsByGroup[groupId].orEmpty()
    fun group(id: String): TabGroup? = groups.firstOrNull { it.id == id }
}

/** A file being opened that is already open in another group (기획서 5.1). */
data class DuplicatePrompt(val file: OpenedFile, val existing: PdfTab, val existingGroup: TabGroup)

/** Something just closed that the snackbar can bring back. */
data class UndoOffer(val id: Long, val message: String)

/**
 * Tab groups and their tabs, persisted so they come back after the app is closed (기획서 5.3).
 * Reading positions are saved 0.5 s after they stop changing, and immediately via [flush] when the
 * app goes to the background. All DB writes go through [TabPdfApp.enqueueWrite] so they apply in
 * order.
 */
class TabsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TabPdfApp
    private val repository = app.repository

    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state.asStateFlow()

    /** Tab the pager should move to next; cleared by [onNavigated]. */
    private val _navigateTo = MutableStateFlow<String?>(null)
    val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()

    private val _duplicatePrompt = MutableStateFlow<DuplicatePrompt?>(null)
    val duplicatePrompt: StateFlow<DuplicatePrompt?> = _duplicatePrompt.asStateFlow()
    private val pendingDuplicates = ArrayDeque<DuplicatePrompt>()

    private val _undoOffer = MutableStateFlow<UndoOffer?>(null)
    val undoOffer: StateFlow<UndoOffer?> = _undoOffer.asStateFlow()
    private var undoable: Closed? = null
    private var nextUndoId = 0L

    private val viewerStates = mutableMapOf<String, ViewerState>()
    private val unsavedStates = mutableMapOf<String, ViewerState>()
    private var saveJob: Job? = null

    private val loadJob = viewModelScope.launch {
        val saved = repository.load()
        viewerStates += saved.viewerStates
        _state.value = SessionState(
            groups = saved.groups,
            tabsByGroup = saved.tabs.groupBy { it.groupId },
            activeGroupId = saved.activeGroupId,
        )
        app.enqueueWrite { FileAccess.releaseUnused(app, saved.tabs.map { it.uri }.toSet()) }
    }

    private fun current(): SessionState = _state.value!!

    private fun setTabs(groupId: String, tabs: List<PdfTab>) = _state.update {
        it!!.copy(tabsByGroup = it.tabsByGroup + (groupId to tabs))
    }

    // ---- Tabs ----

    /**
     * Opens [files] in the active group and moves to the first one. A file already open in this
     * group is not duplicated; one open in another group asks first ([duplicatePrompt]).
     */
    fun open(files: List<OpenedFile>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            loadJob.join()
            val s = current()
            val groupTabs = s.activeTabs
            val added = mutableListOf<PdfTab>()
            var target: String? = null
            for (file in files) {
                val here = (groupTabs + added).firstOrNull { it.uri == file.uri }
                val elsewhere = s.tabsByGroup.values.flatten().firstOrNull { it.uri == file.uri && it.groupId != s.activeGroupId }
                val tab = when {
                    here != null -> here
                    elsewhere != null -> {
                        pendingDuplicates += DuplicatePrompt(file, elsewhere, s.group(elsewhere.groupId)!!)
                        null
                    }
                    else -> newTab(file, s.activeGroupId).also { added += it }
                }
                if (target == null && tab != null) target = tab.id
            }
            if (added.isNotEmpty()) {
                setTabs(s.activeGroupId, groupTabs + added)
                app.enqueueWrite { repository.addTabs(added) }
            }
            if (target != null) _navigateTo.value = target
            showNextDuplicate()
        }
    }

    private fun newTab(file: OpenedFile, groupId: String) =
        PdfTab(UUID.randomUUID().toString(), groupId, file.uri, file.name, file.isInternalCopy)

    private fun showNextDuplicate() {
        if (_duplicatePrompt.value == null) _duplicatePrompt.value = pendingDuplicates.removeFirstOrNull()
    }

    /**
     * Answer to [duplicatePrompt]: open another tab for it here (true), go to the one already open
     * (false), or neither (null — dialog dismissed).
     */
    fun resolveDuplicate(openHere: Boolean?) {
        val prompt = _duplicatePrompt.value ?: return
        _duplicatePrompt.value = null
        if (openHere == true) {
            val groupId = current().activeGroupId
            val tab = newTab(prompt.file, groupId)
            setTabs(groupId, current().tabsOf(groupId) + tab)
            app.enqueueWrite { repository.addTabs(listOf(tab)) }
            _navigateTo.value = tab.id
        } else if (openHere == false) {
            switchGroup(prompt.existingGroup.id, showTabId = prompt.existing.id)
        }
        showNextDuplicate()
    }

    fun close(id: String) {
        val s = current()
        val tab = s.tabsByGroup.values.flatten().firstOrNull { it.id == id } ?: return
        val groupTabs = s.tabsOf(tab.groupId)
        setTabs(tab.groupId, groupTabs - tab)
        unsavedStates -= id
        offerUndo(Closed.Tab(tab, groupTabs.indexOf(tab), viewerState(id)), "탭을 닫았습니다")
        app.enqueueWrite { repository.removeTab(id) }
    }

    /**
     * Points a tab whose file went missing at a newly chosen file, keeping its place in the list.
     * If that file is already open in another tab of the group, the broken tab is closed and the
     * other one shown instead, so a file is never open twice in a group (기획서 5.1).
     */
    fun replaceFile(id: String, file: OpenedFile) {
        val s = current()
        val old = s.tabsByGroup.values.flatten().firstOrNull { it.id == id } ?: return
        val groupTabs = s.tabsOf(old.groupId)
        val existing = groupTabs.firstOrNull { it.id != id && it.uri == file.uri }
        if (existing != null) {
            setTabs(old.groupId, groupTabs - old)
            app.enqueueWrite {
                repository.removeTab(id)
                releaseIfUnused(old)
            }
            select(existing.id)
            return
        }
        // Bumping the revision makes the viewer retry even when the same location was picked again.
        val updated = old.copy(uri = file.uri, name = file.name, isInternalCopy = file.isInternalCopy, revision = old.revision + 1)
        setTabs(old.groupId, groupTabs.map { if (it.id == id) updated else it })
        app.enqueueWrite {
            repository.replaceFile(updated)
            releaseIfUnused(old)
        }
    }

    /** New order of the active group's tabs, by id (탭 개요 drag). */
    fun reorderTabs(ids: List<String>) {
        val s = current()
        val byId = s.activeTabs.associateBy { it.id }
        setTabs(s.activeGroupId, ids.mapNotNull { byId[it] })
        app.enqueueWrite { repository.reorderTabs(ids) }
    }

    fun moveTab(tabId: String, toGroupId: String) {
        val s = current()
        val tab = s.tabsByGroup.values.flatten().firstOrNull { it.id == tabId } ?: return
        if (tab.groupId == toGroupId) return
        val moved = tab.copy(groupId = toGroupId)
        _state.update {
            it!!.copy(
                tabsByGroup = it.tabsByGroup +
                    (tab.groupId to it.tabsOf(tab.groupId) - tab) +
                    (toGroupId to it.tabsOf(toGroupId) + moved)
            )
        }
        app.enqueueWrite { repository.moveTab(tabId, toGroupId) }
    }

    fun moveTabToNewGroup(tabId: String, groupName: String) {
        val groupId = createGroup(groupName, switchTo = false)
        moveTab(tabId, groupId)
    }

    fun select(id: String) {
        _navigateTo.value = id
    }

    fun onNavigated() {
        _navigateTo.value = null
    }

    /** The pager settled on [id]; remembered as the group's active tab. */
    fun onTabShown(id: String) {
        val s = _state.value ?: return
        val group = s.activeGroup
        if (group.activeTabId == id) return
        _state.value = s.copy(groups = s.groups.map { if (it.id == group.id) it.copy(activeTabId = id) else it })
        app.enqueueWrite { repository.setActiveTab(group.id, id) }
    }

    // ---- Groups ----

    /** Shows group [id], at [showTabId] or else the tab last seen there. */
    fun switchGroup(id: String, showTabId: String? = null) {
        val s = current()
        if (s.group(id) == null) return
        if (s.activeGroupId != id) {
            _state.value = s.copy(activeGroupId = id)
            app.enqueueWrite { repository.setActiveGroup(id) }
        }
        if (showTabId != null) _navigateTo.value = showTabId
    }

    /** Adds a group at the end with the first palette colour not yet used; returns its id. */
    fun createGroup(name: String, switchTo: Boolean = true): String {
        val s = current()
        val used = s.groups.map { it.color }.toSet()
        val color = (0 until GROUP_COLOR_COUNT).firstOrNull { it !in used } ?: (s.groups.size % GROUP_COLOR_COUNT)
        val group = TabGroup(UUID.randomUUID().toString(), name.trim(), color, activeTabId = null)
        _state.value = s.copy(groups = s.groups + group)
        app.enqueueWrite { repository.addGroup(group) }
        if (switchTo) switchGroup(group.id)
        return group.id
    }

    fun renameGroup(id: String, name: String) {
        updateGroup(id) { it.copy(name = name.trim()) }
        app.enqueueWrite { repository.renameGroup(id, name.trim()) }
    }

    fun setGroupColor(id: String, color: Int) {
        updateGroup(id) { it.copy(color = color) }
        app.enqueueWrite { repository.setGroupColor(id, color) }
    }

    fun reorderGroups(ids: List<String>) {
        val s = current()
        val byId = s.groups.associateBy { it.id }
        _state.value = s.copy(groups = ids.mapNotNull { byId[it] })
        app.enqueueWrite { repository.reorderGroups(ids) }
    }

    /** Closes a group and all its tabs. The last remaining group cannot be closed (기획서 4.3). */
    fun closeGroup(id: String) {
        val s = current()
        if (s.groups.size <= 1) return
        val group = s.group(id) ?: return
        val index = s.groups.indexOf(group)
        val tabs = s.tabsOf(id)
        val remaining = s.groups - group
        val nextActive = if (s.activeGroupId == id) remaining[(index - 1).coerceAtLeast(0)].id else s.activeGroupId
        _state.value = s.copy(groups = remaining, tabsByGroup = s.tabsByGroup - id, activeGroupId = nextActive)
        tabs.forEach { unsavedStates -= it.id }
        offerUndo(
            Closed.Group(group, index, tabs.map { it to viewerState(it.id) }, wasActive = s.activeGroupId == id),
            "'${group.name}' 그룹을 닫았습니다",
        )
        app.enqueueWrite {
            repository.removeGroup(id)
            if (nextActive != s.activeGroupId) repository.setActiveGroup(nextActive)
        }
    }

    private fun updateGroup(id: String, change: (TabGroup) -> TabGroup) = _state.update { s ->
        s!!.copy(groups = s.groups.map { if (it.id == id) change(it) else it })
    }

    // ---- Undo ----

    private sealed interface Closed {
        data class Tab(val tab: PdfTab, val index: Int, val state: ViewerState) : Closed
        data class Group(
            val group: TabGroup,
            val index: Int,
            val tabs: List<Pair<PdfTab, ViewerState>>,
            val wasActive: Boolean,
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
        when (closed) {
            is Closed.Tab -> {
                val s = current()
                val groupId = closed.tab.groupId
                if (s.group(groupId) == null) return
                val tabs = s.tabsOf(groupId).toMutableList().apply { add(closed.index.coerceAtMost(size), closed.tab) }
                setTabs(groupId, tabs)
                viewerStates[closed.tab.id] = closed.state
                app.enqueueWrite { repository.restoreTabs(listOf(closed.tab to closed.state), tabs.map { it.id }) }
                if (groupId == s.activeGroupId) _navigateTo.value = closed.tab.id
            }
            is Closed.Group -> {
                val s = current()
                val groups = s.groups.toMutableList().apply { add(closed.index.coerceAtMost(size), closed.group) }
                _state.value = s.copy(
                    groups = groups,
                    tabsByGroup = s.tabsByGroup + (closed.group.id to closed.tabs.map { it.first }),
                    activeGroupId = if (closed.wasActive) closed.group.id else s.activeGroupId,
                )
                closed.tabs.forEach { (tab, state) -> viewerStates[tab.id] = state }
                app.enqueueWrite {
                    repository.restoreGroup(closed.group, closed.tabs, groups.map { it.id })
                    if (closed.wasActive) repository.setActiveGroup(closed.group.id)
                }
            }
        }
    }

    /** The undo offer is gone: free the closed tabs' files if no other tab uses them. */
    fun commitUndo() {
        val closed = undoable ?: return
        undoable = null
        _undoOffer.value = null
        val tabs = when (closed) {
            is Closed.Tab -> listOf(closed.tab)
            is Closed.Group -> closed.tabs.map { it.first }
        }
        tabs.forEach { viewerStates -= it.id }
        app.enqueueWrite { tabs.forEach { releaseIfUnused(it) } }
    }

    private suspend fun releaseIfUnused(tab: PdfTab) {
        if (!repository.isUriInUse(tab.uri)) FileAccess.release(app, tab)
    }

    // ---- Reading positions ----

    fun viewerState(id: String): ViewerState = viewerStates[id] ?: ViewerState()

    fun updateViewerState(id: String, state: ViewerState) {
        val s = _state.value ?: return
        if (s.tabsByGroup.values.none { tabs -> tabs.any { it.id == id } } || viewerStates[id] == state) return
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
