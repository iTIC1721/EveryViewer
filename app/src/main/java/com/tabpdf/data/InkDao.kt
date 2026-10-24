package com.tabpdf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InkDao {
    @Query("SELECT * FROM ink_strokes WHERE tabId = :tabId ORDER BY page, seq")
    suspend fun strokesOf(tabId: String): List<InkStrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(strokes: List<InkStrokeEntity>)

    @Query("DELETE FROM ink_strokes WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM ink_strokes WHERE tabId IN (:tabIds)")
    suspend fun deleteTabs(tabIds: List<String>)

    /** Strokes of tabs that no longer exist (closed while the app died during the undo period). */
    @Query("DELETE FROM ink_strokes WHERE tabId NOT IN (SELECT id FROM tabs)")
    suspend fun deleteOrphans()
}
