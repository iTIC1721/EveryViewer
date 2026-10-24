package com.tabpdf.tabs

import android.net.Uri

/** An entry of the top-level list in the tab switcher: an ungrouped tab or a group. */
sealed interface TopItem {
    val id: String
}

data class DocTab(
    override val id: String,
    /** Null when the tab is not in a group. */
    val groupId: String?,
    val uri: Uri,
    val name: String,
    val isInternalCopy: Boolean,
    /** Bumped when the tab's file is replaced; not persisted. */
    val revision: Int = 0,
) : TopItem

data class TabGroup(
    override val id: String,
    /** May be blank; see [displayName]. */
    val name: String,
    /** Index into the group colour palette. */
    val color: Int,
) : TopItem

/**
 * The whole tab structure, Chrome-style (기획서 3장): a top-level list mixing ungrouped tabs and
 * groups, and each group's own tabs. Groups are never empty.
 */
data class SessionState(
    val top: List<TopItem>,
    val groupTabs: Map<String, List<DocTab>>,
    val currentTabId: String?,
) {
    val groups: List<TabGroup> get() = top.filterIsInstance<TabGroup>()
    val ungroupedTabs: List<DocTab> get() = top.filterIsInstance<DocTab>()
    val allTabs: List<DocTab> get() = top.flatMap { if (it is TabGroup) tabsOf(it.id) else listOf(it as DocTab) }

    fun tabsOf(groupId: String): List<DocTab> = groupTabs[groupId].orEmpty()
    fun group(id: String): TabGroup? = groups.firstOrNull { it.id == id }
    fun tab(id: String?): DocTab? = id?.let { allTabs.firstOrNull { tab -> tab.id == id } }

    /** Tabs the viewer swipes through: a group's tabs, or all ungrouped tabs (null). */
    fun context(groupId: String?): List<DocTab> = if (groupId == null) ungroupedTabs else tabsOf(groupId)

    val currentTab: DocTab? get() = tab(currentTabId)

    companion object {
        val Empty = SessionState(emptyList(), emptyMap(), null)
    }
}

/** A group's title, or "탭 n개" while it has no name (as Chrome does). */
fun TabGroup.displayName(tabCount: Int): String = name.ifBlank { "탭 ${tabCount}개" }
