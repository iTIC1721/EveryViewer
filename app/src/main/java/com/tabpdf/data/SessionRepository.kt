package com.tabpdf.data

import android.net.Uri
import com.tabpdf.tabs.PdfTab
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.ViewerState
import java.util.UUID

/** Everything needed to rebuild the screen at launch. */
data class Session(
    val groups: List<TabGroup>,
    val activeGroupId: String,
    /** All tabs of all groups, in position order. */
    val tabs: List<PdfTab>,
    val viewerStates: Map<String, ViewerState>,
)

class SessionRepository(private val dao: SessionDao) {

    /** Loads the saved session, creating the default "기본" group on first launch. */
    suspend fun load(): Session {
        var groups = dao.groups()
        if (groups.isEmpty()) {
            val group = GroupEntity(UUID.randomUUID().toString(), DEFAULT_GROUP_NAME, color = 0, position = 0, activeTabId = null)
            dao.upsertGroup(group)
            groups = listOf(group)
        }
        val activeGroupId = dao.appState()?.activeGroupId?.takeIf { id -> groups.any { it.id == id } }
            ?: groups.first().id.also { setActiveGroup(it) }
        val tabs = dao.tabs()
        return Session(
            groups = groups.map { TabGroup(it.id, it.name, it.color, it.activeTabId) },
            activeGroupId = activeGroupId,
            tabs = tabs.map { it.toTab() },
            viewerStates = tabs.associate { it.id to ViewerState(it.page, it.pageOffset, it.zoom, it.panX) },
        )
    }

    /** Appends [tabs] (all in the same group) after that group's last tab. */
    suspend fun addTabs(tabs: List<PdfTab>) {
        if (tabs.isEmpty()) return
        val firstPosition = dao.lastPosition(tabs.first().groupId) + 1
        dao.insertTabs(tabs.mapIndexed { i, tab -> tab.toEntity(firstPosition + i, ViewerState()) })
    }

    /** Puts closed tabs back (undo), then fixes the group's order to [orderedIds]. */
    suspend fun restoreTabs(tabs: List<Pair<PdfTab, ViewerState>>, orderedIds: List<String>) {
        dao.insertTabs(tabs.map { (tab, state) -> tab.toEntity(0, state) })
        dao.reorderTabs(orderedIds)
    }

    suspend fun removeTab(id: String) = dao.deleteTab(id)

    suspend fun isUriInUse(uri: Uri): Boolean = dao.countTabsWithUri(uri.toString()) > 0

    suspend fun setActiveTab(groupId: String, tabId: String) {
        dao.setActiveTab(groupId, tabId)
        dao.touchTab(tabId, System.currentTimeMillis())
    }

    suspend fun setActiveGroup(groupId: String) = dao.upsertAppState(AppStateEntity(activeGroupId = groupId))

    suspend fun saveViewerStates(states: Map<String, ViewerState>) = dao.updateViewerStates(states)

    suspend fun replaceFile(tab: PdfTab) =
        dao.updateFile(tab.id, tab.uri.toString(), tab.name, tab.isInternalCopy)

    suspend fun reorderTabs(ids: List<String>) = dao.reorderTabs(ids)

    suspend fun moveTab(tabId: String, groupId: String) = dao.moveTabToEnd(tabId, groupId)

    suspend fun addGroup(group: TabGroup) =
        dao.upsertGroup(group.toEntity(dao.lastGroupPosition() + 1))

    /** Puts a closed group back (undo) with its tabs, then fixes group order to [orderedGroupIds]. */
    suspend fun restoreGroup(
        group: TabGroup,
        tabs: List<Pair<PdfTab, ViewerState>>,
        orderedGroupIds: List<String>,
    ) {
        dao.upsertGroup(group.toEntity(0))
        dao.insertTabs(tabs.mapIndexed { i, (tab, state) -> tab.toEntity(i, state) })
        dao.reorderGroups(orderedGroupIds)
    }

    suspend fun renameGroup(id: String, name: String) = dao.renameGroup(id, name)

    suspend fun setGroupColor(id: String, color: Int) = dao.setGroupColor(id, color)

    suspend fun reorderGroups(ids: List<String>) = dao.reorderGroups(ids)

    suspend fun removeGroup(id: String) = dao.deleteGroup(id)

    private fun TabEntity.toTab() = PdfTab(id, groupId, Uri.parse(uri), displayName, isInternalCopy)

    private fun PdfTab.toEntity(position: Int, state: ViewerState) = TabEntity(
        id = id,
        groupId = groupId,
        position = position,
        uri = uri.toString(),
        isInternalCopy = isInternalCopy,
        displayName = name,
        page = state.page,
        pageOffset = state.pageOffset,
        zoom = state.zoom,
        panX = state.panX,
        lastOpenedAt = System.currentTimeMillis(),
    )

    private fun TabGroup.toEntity(position: Int) = GroupEntity(id, name, color, position, activeTabId)

    companion object {
        const val DEFAULT_GROUP_NAME = "기본"
    }
}
