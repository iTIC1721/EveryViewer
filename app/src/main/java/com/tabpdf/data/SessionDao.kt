package com.tabpdf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.tabpdf.tabs.ViewerState

/** Where a tab sits in the structure, without touching its reading position. */
data class TabPlacement(
    val id: String,
    val groupId: String?,
    val position: Int,
    val uri: String,
    val isInternalCopy: Boolean,
    val displayName: String,
)

@Dao
abstract class SessionDao {
    @Query("SELECT * FROM tab_groups ORDER BY position")
    abstract suspend fun groups(): List<GroupEntity>

    @Query("SELECT * FROM tabs ORDER BY position")
    abstract suspend fun tabs(): List<TabEntity>

    @Query("SELECT * FROM app_state WHERE id = 0")
    abstract suspend fun appState(): AppStateEntity?

    @Query("SELECT id FROM tabs")
    abstract suspend fun tabIds(): List<String>

    @Upsert
    abstract suspend fun upsertGroups(groups: List<GroupEntity>)

    @Upsert
    abstract suspend fun upsertAppState(state: AppStateEntity)

    @Insert
    abstract suspend fun insertTabs(tabs: List<TabEntity>)

    @Query("DELETE FROM tabs WHERE id IN (:ids)")
    abstract suspend fun deleteTabs(ids: List<String>)

    @Query("DELETE FROM tab_groups WHERE id NOT IN (:keep)")
    abstract suspend fun deleteGroupsExcept(keep: List<String>)

    @Query(
        "UPDATE tabs SET groupId = :groupId, position = :position, uri = :uri, " +
            "isInternalCopy = :isInternalCopy, displayName = :displayName WHERE id = :id"
    )
    abstract suspend fun updatePlacement(
        id: String, groupId: String?, position: Int, uri: String, isInternalCopy: Boolean, displayName: String,
    )

    @Query("SELECT COUNT(*) FROM tabs WHERE uri = :uri")
    abstract suspend fun countTabsWithUri(uri: String): Int

    @Query("UPDATE tabs SET lastOpenedAt = :time WHERE id = :id")
    abstract suspend fun touchTab(id: String, time: Long)

    @Query(
        "UPDATE tabs SET page = :page, pageOffset = :pageOffset, zoom = :zoom, panX = :panX WHERE id = :id"
    )
    abstract suspend fun updateViewerState(id: String, page: Int, pageOffset: Float, zoom: Float, panX: Float)

    @Transaction
    open suspend fun updateViewerStates(states: Map<String, ViewerState>) {
        for ((id, s) in states) updateViewerState(id, s.page, s.pageOffset, s.zoom, s.panX)
    }

    /**
     * Makes the stored groups and tab placements exactly match the given structure: removes what
     * is gone, inserts new tabs (with [newTabStates] or a fresh reading position), and re-places
     * the rest. Reading positions of existing tabs are left alone.
     */
    @Transaction
    open suspend fun saveStructure(
        groups: List<GroupEntity>,
        tabs: List<TabPlacement>,
        newTabStates: Map<String, ViewerState>,
        currentTabId: String?,
    ) {
        // Order matters: new groups must exist before tabs point at them, and a removed group is
        // deleted only after its tabs have been re-placed — the delete cascades to its tabs.
        val existing = tabIds().toSet()
        val keep = tabs.map { it.id }.toSet()
        upsertGroups(groups)
        val now = System.currentTimeMillis()
        val inserts = mutableListOf<TabEntity>()
        for (t in tabs) {
            if (t.id in existing) {
                updatePlacement(t.id, t.groupId, t.position, t.uri, t.isInternalCopy, t.displayName)
            } else {
                val s = newTabStates[t.id] ?: ViewerState()
                inserts += TabEntity(
                    t.id, t.groupId, t.position, t.uri, t.isInternalCopy, t.displayName,
                    s.page, s.pageOffset, s.zoom, s.panX, now,
                )
            }
        }
        if (inserts.isNotEmpty()) insertTabs(inserts)
        (existing - keep).chunked(500).forEach { deleteTabs(it) }
        deleteGroupsExcept(groups.map { it.id })
        upsertAppState(AppStateEntity(currentTabId = currentTabId))
    }
}
