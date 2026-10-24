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

/** What the screen needs once the saved session has loaded. */
data class LoadedSession(val activeGroupId: String, val initialTabId: String?)

/**
 * Open tabs of the active group, persisted so they come back after the app is closed (기획서 5.3).
 * Reading positions are saved 0.5 s after they stop changing, and immediately via [flush] when the
 * app goes to the background.
 */
class TabsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TabPdfApp
    private val repository = app.repository

    private val _session = MutableStateFlow<LoadedSession?>(null)
    val session: StateFlow<LoadedSession?> = _session.asStateFlow()

    private val _tabs = MutableStateFlow<List<PdfTab>>(emptyList())
    val tabs: StateFlow<List<PdfTab>> = _tabs.asStateFlow()

    /** Tab the pager should move to next; cleared by [onNavigated]. */
    private val _navigateTo = MutableStateFlow<String?>(null)
    val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()

    private val viewerStates = mutableMapOf<String, ViewerState>()
    private val unsavedStates = mutableMapOf<String, ViewerState>()
    private var saveJob: Job? = null

    private val loadJob = viewModelScope.launch {
        val saved = repository.load()
        val group = saved.groups.first { it.id == saved.activeGroupId }
        _tabs.value = saved.tabs.filter { it.groupId == group.id }
        viewerStates += saved.viewerStates
        _session.value = LoadedSession(group.id, group.activeTabId)
    }

    /**
     * Appends a tab for each file not already open and moves to the first file of the selection.
     * A file that is already open is not duplicated (기획서 5.1).
     */
    fun open(files: List<OpenedFile>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            loadJob.join()
            val groupId = _session.value!!.activeGroupId
            val current = _tabs.value
            val added = mutableListOf<PdfTab>()
            var target: String? = null
            for (file in files) {
                val tab = (current + added).firstOrNull { it.uri == file.uri }
                    ?: PdfTab(UUID.randomUUID().toString(), groupId, file.uri, file.name, file.isInternalCopy)
                        .also { added += it }
                if (target == null) target = tab.id
            }
            if (added.isNotEmpty()) {
                _tabs.value = current + added
                app.enqueueWrite { repository.addTabs(added) }
            }
            _navigateTo.value = target
        }
    }

    fun close(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _tabs.value = _tabs.value - tab
        viewerStates -= id
        unsavedStates -= id
        app.enqueueWrite {
            repository.removeTab(id)
            if (!repository.isUriInUse(tab.uri)) FileAccess.release(app, tab)
        }
    }

    /**
     * Points a tab whose file went missing at a newly chosen file, keeping its place in the list.
     * If that file is already open in another tab, the broken tab is closed and the other one shown
     * instead, so a file is never open twice in a group (기획서 5.1).
     */
    fun replaceFile(id: String, file: OpenedFile) {
        val old = _tabs.value.firstOrNull { it.id == id } ?: return
        val existing = _tabs.value.firstOrNull { it.id != id && it.uri == file.uri }
        if (existing != null) {
            close(id)
            select(existing.id)
            return
        }
        // Bumping the revision makes the viewer retry even when the same location was picked again.
        val updated = old.copy(
            uri = file.uri,
            name = file.name,
            isInternalCopy = file.isInternalCopy,
            revision = old.revision + 1,
        )
        _tabs.value = _tabs.value.map { if (it.id == id) updated else it }
        app.enqueueWrite {
            repository.replaceFile(updated)
            if (!repository.isUriInUse(old.uri)) FileAccess.release(app, old)
        }
    }

    fun select(id: String) {
        _navigateTo.value = id
    }

    fun onNavigated() {
        _navigateTo.value = null
    }

    /** The pager settled on [id]; remembered as the group's active tab. */
    fun onTabShown(id: String) {
        val groupId = _session.value?.activeGroupId ?: return
        app.enqueueWrite { repository.setActiveTab(groupId, id) }
    }

    fun viewerState(id: String): ViewerState = viewerStates[id] ?: ViewerState()

    fun updateViewerState(id: String, state: ViewerState) {
        if (_tabs.value.none { it.id == id } || viewerStates[id] == state) return
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

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
