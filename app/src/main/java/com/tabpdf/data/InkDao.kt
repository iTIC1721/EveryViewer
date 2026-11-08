package com.tabpdf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface InkDao {
    @Query("SELECT * FROM ink_strokes WHERE tabId = :tabId ORDER BY page, seq")
    suspend fun strokesOf(tabId: String): List<InkStrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(strokes: List<InkStrokeEntity>)

    @Query("DELETE FROM ink_strokes WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM ink_strokes WHERE tabId IN (:tabIds)")
    suspend fun deleteStrokesOfTabs(tabIds: List<String>)

    /** Strokes of tabs that no longer exist (closed while the app died during the undo period). */
    @Query("DELETE FROM ink_strokes WHERE tabId NOT IN (SELECT id FROM tabs)")
    suspend fun deleteOrphanStrokes()

    @Query("SELECT * FROM ink_texts WHERE tabId = :tabId ORDER BY page, seq")
    suspend fun textsOf(tabId: String): List<InkTextEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTexts(texts: List<InkTextEntity>)

    @Query("DELETE FROM ink_texts WHERE id IN (:ids)")
    suspend fun deleteTexts(ids: List<String>)

    @Query("DELETE FROM ink_texts WHERE tabId IN (:tabIds)")
    suspend fun deleteTextsOfTabs(tabIds: List<String>)

    @Query("DELETE FROM ink_texts WHERE tabId NOT IN (SELECT id FROM tabs)")
    suspend fun deleteOrphanTexts()

    @Transaction
    suspend fun deleteTabs(tabIds: List<String>) {
        deleteStrokesOfTabs(tabIds)
        deleteTextsOfTabs(tabIds)
    }

    @Transaction
    suspend fun deleteOrphans() {
        deleteOrphanStrokes()
        deleteOrphanTexts()
    }
}
