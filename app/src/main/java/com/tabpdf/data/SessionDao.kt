package com.tabpdf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.tabpdf.tabs.ViewerState

@Dao
abstract class SessionDao {
    @Query("SELECT * FROM tab_groups ORDER BY position")
    abstract suspend fun groups(): List<GroupEntity>

    @Query("SELECT * FROM tabs ORDER BY position")
    abstract suspend fun tabs(): List<TabEntity>

    @Query("SELECT * FROM app_state WHERE id = 0")
    abstract suspend fun appState(): AppStateEntity?

    @Upsert
    abstract suspend fun upsertGroup(group: GroupEntity)

    @Upsert
    abstract suspend fun upsertAppState(state: AppStateEntity)

    @Insert
    abstract suspend fun insertTabs(tabs: List<TabEntity>)

    @Query("DELETE FROM tabs WHERE id = :id")
    abstract suspend fun deleteTab(id: String)

    /** Deletes the group and, by cascade, its tabs. */
    @Query("DELETE FROM tab_groups WHERE id = :id")
    abstract suspend fun deleteGroup(id: String)

    @Query("UPDATE tab_groups SET name = :name WHERE id = :id")
    abstract suspend fun renameGroup(id: String, name: String)

    @Query("UPDATE tab_groups SET color = :color WHERE id = :id")
    abstract suspend fun setGroupColor(id: String, color: Int)

    @Query("UPDATE tab_groups SET position = :position WHERE id = :id")
    abstract suspend fun setGroupPosition(id: String, position: Int)

    @Query("UPDATE tabs SET position = :position WHERE id = :id")
    abstract suspend fun setTabPosition(id: String, position: Int)

    @Query("UPDATE tabs SET groupId = :groupId, position = :position WHERE id = :id")
    abstract suspend fun moveTab(id: String, groupId: String, position: Int)

    @Query("SELECT COALESCE(MAX(position), -1) FROM tabs WHERE groupId = :groupId")
    abstract suspend fun lastPosition(groupId: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM tab_groups")
    abstract suspend fun lastGroupPosition(): Int

    @Query("SELECT COUNT(*) FROM tabs WHERE uri = :uri")
    abstract suspend fun countTabsWithUri(uri: String): Int

    @Query("UPDATE tab_groups SET activeTabId = :tabId WHERE id = :groupId")
    abstract suspend fun setActiveTab(groupId: String, tabId: String?)

    @Query("UPDATE tabs SET lastOpenedAt = :time WHERE id = :id")
    abstract suspend fun touchTab(id: String, time: Long)

    @Query(
        "UPDATE tabs SET page = :page, pageOffset = :pageOffset, zoom = :zoom, panX = :panX WHERE id = :id"
    )
    abstract suspend fun updateViewerState(id: String, page: Int, pageOffset: Float, zoom: Float, panX: Float)

    @Query("UPDATE tabs SET uri = :uri, displayName = :name, isInternalCopy = :isInternalCopy WHERE id = :id")
    abstract suspend fun updateFile(id: String, uri: String, name: String, isInternalCopy: Boolean)

    @Transaction
    open suspend fun updateViewerStates(states: Map<String, ViewerState>) {
        for ((id, s) in states) updateViewerState(id, s.page, s.pageOffset, s.zoom, s.panX)
    }

    /** Rewrites positions so [ids] are in that order. */
    @Transaction
    open suspend fun reorderTabs(ids: List<String>) {
        ids.forEachIndexed { i, id -> setTabPosition(id, i) }
    }

    @Transaction
    open suspend fun reorderGroups(ids: List<String>) {
        ids.forEachIndexed { i, id -> setGroupPosition(id, i) }
    }

    @Transaction
    open suspend fun moveTabToEnd(id: String, groupId: String) {
        moveTab(id, groupId, lastPosition(groupId) + 1)
    }
}
