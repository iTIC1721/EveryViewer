package com.tabpdf.data

import android.net.Uri
import com.tabpdf.tabs.PdfTab
import com.tabpdf.tabs.ViewerState
import java.util.UUID

/** Everything needed to rebuild the screen at launch. */
data class Session(
    val groups: List<GroupEntity>,
    val activeGroupId: String,
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
            ?: groups.first().id.also { dao.upsertAppState(AppStateEntity(activeGroupId = it)) }
        val tabs = dao.tabs()
        return Session(
            groups = groups,
            activeGroupId = activeGroupId,
            tabs = tabs.map { it.toTab() },
            viewerStates = tabs.associate { it.id to ViewerState(it.page, it.pageOffset, it.zoom, it.panX) },
        )
    }

    /** Appends [tabs] (all in the same group) after that group's last tab. */
    suspend fun addTabs(tabs: List<PdfTab>) {
        if (tabs.isEmpty()) return
        val now = System.currentTimeMillis()
        val firstPosition = dao.lastPosition(tabs.first().groupId) + 1
        dao.insertTabs(tabs.mapIndexed { i, tab ->
            TabEntity(
                id = tab.id,
                groupId = tab.groupId,
                position = firstPosition + i,
                uri = tab.uri.toString(),
                isInternalCopy = tab.isInternalCopy,
                displayName = tab.name,
                page = 0,
                pageOffset = 0f,
                zoom = 1f,
                panX = 0f,
                lastOpenedAt = now,
            )
        })
    }

    suspend fun removeTab(id: String) = dao.deleteTab(id)

    suspend fun isUriInUse(uri: Uri): Boolean = dao.countTabsWithUri(uri.toString()) > 0

    suspend fun setActiveTab(groupId: String, tabId: String) {
        dao.setActiveTab(groupId, tabId)
        dao.touchTab(tabId, System.currentTimeMillis())
    }

    suspend fun saveViewerStates(states: Map<String, ViewerState>) = dao.updateViewerStates(states)

    suspend fun replaceFile(tab: PdfTab) =
        dao.updateFile(tab.id, tab.uri.toString(), tab.name, tab.isInternalCopy)

    private fun TabEntity.toTab() = PdfTab(id, groupId, Uri.parse(uri), displayName, isInternalCopy)

    companion object {
        const val DEFAULT_GROUP_NAME = "기본"
    }
}
