package com.tabpdf.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tab group. Groups and ungrouped tabs share one top-level order (Chrome-style tab switcher),
 * so [position] is in the same space as an ungrouped tab's [TabEntity.position].
 */
@Entity(tableName = "tab_groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    /** May be blank: shown as "탭 n개" until named. */
    val name: String,
    /** Index into the fixed group colour palette. */
    val color: Int,
    val position: Int,
)

/** 기획서 6장 Tab, with the reading position flattened in. */
@Entity(
    tableName = "tabs",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("groupId")],
)
data class TabEntity(
    @PrimaryKey val id: String,
    /** Null for a tab that is not in a group. */
    val groupId: String?,
    /** Order within the group, or in the top-level list for an ungrouped tab. */
    val position: Int,
    /** Persistable-permission content URI, or a file URI to our own copy. */
    val uri: String,
    /** True if [uri] points at a copy in app storage that is deleted with the tab. */
    val isInternalCopy: Boolean,
    val displayName: String,
    /** FileFormat.key. Tabs from before v3 were all PDFs. */
    @ColumnInfo(defaultValue = "pdf") val format: String,
    val page: Int,
    /** Scroll offset into [page], as a fraction of the page height. */
    val pageOffset: Float,
    val zoom: Float,
    /** Horizontal pan, as a fraction of the viewport width. */
    val panX: Float,
    val lastOpenedAt: Long,
)

/** Single row (id 0) holding app-wide state. */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val id: Int = 0,
    /** Tab shown in the viewer. */
    val currentTabId: String?,
)
