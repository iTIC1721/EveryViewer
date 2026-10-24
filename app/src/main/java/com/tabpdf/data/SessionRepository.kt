package com.tabpdf.data

import android.net.Uri
import com.tabpdf.tabs.PdfTab
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
                is PdfTab -> placements += item.placement(i)
            }
        }
        dao.saveStructure(groups, placements, viewerStates, state.currentTabId)
    }

    suspend fun setCurrentTab(tabId: String) {
        dao.upsertAppState(AppStateEntity(currentTabId = tabId))
        dao.touchTab(tabId, System.currentTimeMillis())
    }

    suspend fun isUriInUse(uri: Uri): Boolean = dao.countTabsWithUri(uri.toString()) > 0

    suspend fun saveViewerStates(states: Map<String, ViewerState>) = dao.updateViewerStates(states)

    private fun TabEntity.toTab() = PdfTab(id, groupId, Uri.parse(uri), displayName, isInternalCopy)

    private fun PdfTab.placement(position: Int) =
        TabPlacement(id, groupId, position, uri.toString(), isInternalCopy, name)
}
