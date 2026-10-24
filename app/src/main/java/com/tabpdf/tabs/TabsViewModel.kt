package com.tabpdf.tabs

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.tabpdf.ui.ViewerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class PdfTab(val id: String, val uri: Uri, val name: String)

/**
 * Open tabs, in memory only for now — persisting them across app restarts is 개발 2단계.
 */
class TabsViewModel : ViewModel() {
    private val _tabs = MutableStateFlow<List<PdfTab>>(emptyList())
    val tabs: StateFlow<List<PdfTab>> = _tabs.asStateFlow()

    /** Tab the pager should move to next; cleared by [onNavigated]. */
    private val _navigateTo = MutableStateFlow<String?>(null)
    val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()

    private val viewerStates = mutableMapOf<String, ViewerState>()

    /**
     * Appends a tab for each file not already open and moves to the first file of the selection.
     * A file that is already open is not duplicated (기획서 5.1).
     */
    fun open(files: List<Pair<Uri, String>>) {
        if (files.isEmpty()) return
        var target: String? = null
        _tabs.update { current ->
            val result = current.toMutableList()
            for ((uri, name) in files) {
                val existing = result.firstOrNull { it.uri == uri }
                val tab = existing ?: PdfTab(UUID.randomUUID().toString(), uri, name).also { result += it }
                if (target == null) target = tab.id
            }
            result
        }
        _navigateTo.value = target
    }

    fun close(id: String) {
        _tabs.update { tabs -> tabs.filterNot { it.id == id } }
        viewerStates.remove(id)
    }

    fun onNavigated() {
        _navigateTo.value = null
    }

    fun viewerState(id: String): ViewerState = viewerStates[id] ?: ViewerState()

    fun saveViewerState(id: String, state: ViewerState) {
        if (_tabs.value.any { it.id == id }) viewerStates[id] = state
    }
}
