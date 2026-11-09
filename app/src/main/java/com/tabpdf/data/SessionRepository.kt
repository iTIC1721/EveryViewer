package com.tabpdf.data

import android.net.Uri
import com.tabpdf.doc.FileFormat
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.SessionState
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.TopItem
import com.tabpdf.tabs.ViewerState

class SessionRepository(private val dao: SessionDao) {

    /** Loads the saved structure and every tab's reading position. */
    suspend fun load(): Pair<SessionState, Map<String, ViewerState>> {
        val groups = dao.groups()
        val tabs = dao.tabs()
        // Groups and ungrouped tabs share one position space; merge them into the top-level list.
        val top = (groups.map { it.position to (TabGroup(it.id, it.name, it.color) as TopItem) } +
            tabs.filter { it.groupId == null }.map { it.position to (it.toTab() as TopItem) })
            .sortedBy { it.first }
            .map { it.second }
        val groupTabs = tabs.filter { it.groupId != null }
            .groupBy { it.groupId!! }
            .mapValues { (_, list) -> list.sortedBy { it.position }.map { it.toTab() } }
        val state = SessionState(top, groupTabs, dao.appState()?.currentTabId)
        val viewerStates = tabs.associate { it.id to ViewerState(it.page, it.pageOffset, it.zoom, it.panX) }
        return state to viewerStates
    }

    /**
     * Stores [state]'s structure. Tabs new to the database get their reading position from
     * [viewerStates] (undo brings tabs back with theirs); existing tabs keep the stored one.
     */
    suspend fun saveStructure(state: SessionState, viewerStates: Map<String, ViewerState>) {
        val groups = mutableListOf<GroupEntity>()
        val placements = mutableListOf<TabPlacement>()
        state.top.forEachIndexed { i, item ->
            when (item) {
                is TabGroup -> {
                    groups += GroupEntity(item.id, item.name, item.color, i)
                    state.tabsOf(item.id).forEachIndexed { j, tab -> placements += tab.placement(j) }
                }
                is DocTab -> placements += item.placement(i)
            }
        }
        dao.saveStructure(groups, placements, viewerStates, state.currentTabId)
    }

    suspend fun setCurrentTab(tabId: String) {
        dao.upsertAppState(AppStateEntity(currentTabId = tabId))
        dao.touchTab(tabId, System.currentTimeMillis())
    }

    /** Every saved tab's file and format. */
    suspend fun tabFiles(): List<Pair<Uri, FileFormat>> = dao.tabFiles().map { Uri.parse(it.uri) to FileFormat.fromKey(it.format) }

    /** The looks chosen for tabs' documents (기획서 5.10), by tab; tabs that follow the theme are left out. */
    suspend fun darkLooks(): Map<String, Boolean> = dao.darkLooks().associate { it.id to (it.darkLook == true) }

    suspend fun setDarkLook(tabId: String, dark: Boolean?) = dao.setDarkLook(tabId, dark)

    /** When each tab was last shown, by tab. */
    suspend fun lastOpened(): Map<String, Long> = dao.lastOpened().associate { it.id to it.lastOpenedAt }

    suspend fun saveViewerStates(states: Map<String, ViewerState>) = dao.updateViewerStates(states)

    private fun TabEntity.toTab() = DocTab(
        id, groupId, Uri.parse(uri), displayName, isInternalCopy, FileFormat.fromKey(format),
        source = sourceUri?.let(Uri::parse),
    )

    private fun DocTab.placement(position: Int) =
        TabPlacement(id, groupId, position, uri.toString(), isInternalCopy, name, format.key, source?.toString())
}
