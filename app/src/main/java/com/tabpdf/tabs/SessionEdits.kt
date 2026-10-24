package com.tabpdf.tabs

/*
 * Pure edits of the tab structure (기획서 3장, 5.4). Each returns the new state, or null when the
 * edit doesn't apply (unknown id, already there…). Kept free of Android and storage so they can be
 * tested directly; TabsViewModel commits their results.
 */

/** Removes a tab; a group left empty is removed too. */
internal fun SessionState.removeTab(id: String): SessionState {
    val tab = tab(id) ?: return this
    val groupId = tab.groupId ?: return copy(top = top.filterNot { it.id == id })
    val rest = tabsOf(groupId).filterNot { it.id == id }
    return if (rest.isEmpty()) {
        copy(top = top.filterNot { it.id == groupId }, groupTabs = groupTabs - groupId)
    } else {
        copy(groupTabs = groupTabs + (groupId to rest))
    }
}

/** Adds [tab] at the end of its place: its group, or the top-level list. */
internal fun SessionState.appendTab(tab: DocTab): SessionState =
    if (tab.groupId == null) copy(top = top + tab)
    else copy(groupTabs = groupTabs + (tab.groupId to tabsOf(tab.groupId) + tab))

internal fun SessionState.insertTop(index: Int, item: TopItem): SessionState =
    copy(top = top.toMutableList().apply { add(index.coerceIn(0, size), item) })

internal fun SessionState.insertInGroup(groupId: String, index: Int, tab: DocTab): SessionState =
    copy(groupTabs = groupTabs + (groupId to tabsOf(groupId).toMutableList().apply { add(index.coerceIn(0, size), tab) }))

internal fun SessionState.replaceTab(tab: DocTab): SessionState =
    if (tab.groupId == null) copy(top = top.map { if (it.id == tab.id) tab else it })
    else copy(groupTabs = groupTabs + (tab.groupId to tabsOf(tab.groupId).map { if (it.id == tab.id) tab else it }))

/**
 * Keeps the current tab if it still exists; otherwise picks the one now at [index] of the same
 * place ([groupId] or ungrouped), or its previous neighbour, or else any tab.
 */
internal fun SessionState.withValidCurrent(groupId: String?, index: Int): SessionState {
    if (currentTab != null) return this
    val context = context(groupId)
    val next = context.getOrNull(index.coerceAtMost(context.size - 1)) ?: allTabs.firstOrNull()
    return copy(currentTabId = next?.id)
}

/** New top-level order (ungrouped tabs and groups), by id; unknown ids are ignored. */
internal fun SessionState.reorderTop(ids: List<String>): SessionState {
    val byId = top.associateBy { it.id }
    val ordered = ids.mapNotNull { byId[it] }
    // Never lose an item because the given order was missing it.
    return copy(top = ordered + top.filterNot { it in ordered })
}

internal fun SessionState.reorderGroup(groupId: String, ids: List<String>): SessionState? {
    val tabs = groupTabs[groupId] ?: return null
    val byId = tabs.associateBy { it.id }
    val ordered = ids.mapNotNull { byId[it] }
    return copy(groupTabs = groupTabs + (groupId to ordered + tabs.filterNot { it in ordered }))
}

/** Ungrouped tab [draggedId] dropped onto ungrouped tab [targetId]: [group] of the two, where the target was. */
internal fun SessionState.groupTogether(draggedId: String, targetId: String, group: TabGroup): SessionState? {
    if (draggedId == targetId) return null
    val dragged = tab(draggedId)?.takeIf { it.groupId == null } ?: return null
    val target = tab(targetId)?.takeIf { it.groupId == null } ?: return null
    val top = top.toMutableList().apply {
        set(indexOf(target), group)
        remove(dragged)
    }
    return copy(
        top = top,
        groupTabs = groupTabs + (group.id to listOf(target.copy(groupId = group.id), dragged.copy(groupId = group.id))),
    )
}

/** Moves tab [tabId] to the end of group [groupId]. */
internal fun SessionState.addToGroup(tabId: String, groupId: String): SessionState? {
    val tab = tab(tabId) ?: return null
    if (tab.groupId == groupId || group(groupId) == null) return null
    return removeTab(tabId).appendTab(tab.copy(groupId = groupId))
}

/** Puts tab [tabId] into the new [group]: in the tab's place, or right after its old group. */
internal fun SessionState.addToNewGroup(tabId: String, group: TabGroup): SessionState? {
    val tab = tab(tabId) ?: return null
    val anchor = top.indexOfFirst { it.id == (tab.groupId ?: tab.id) }
    val removed = removeTab(tabId)
    val at = if (tab.groupId == null) anchor else anchor + if (removed.group(tab.groupId) != null) 1 else 0
    return removed.insertTop(at, group)
        .copy(groupTabs = removed.groupTabs + (group.id to listOf(tab.copy(groupId = group.id))))
}

/** Takes tab [tabId] out of its group; it lands right after the group (or in its place, if it was the last tab). */
internal fun SessionState.removeFromGroup(tabId: String): SessionState? {
    val tab = tab(tabId) ?: return null
    val groupId = tab.groupId ?: return null
    val groupIndex = top.indexOfFirst { it.id == groupId }
    val removed = removeTab(tabId)
    val at = if (removed.group(groupId) != null) groupIndex + 1 else groupIndex
    return removed.insertTop(at, tab.copy(groupId = null))
}

/** Dissolves a group: its tabs take its place in the top-level list, in order. */
internal fun SessionState.ungroup(groupId: String): SessionState? {
    val group = group(groupId) ?: return null
    val index = top.indexOf(group)
    val top = top.toMutableList().apply {
        removeAt(index)
        addAll(index, tabsOf(groupId).map { it.copy(groupId = null) })
    }
    return copy(top = top, groupTabs = groupTabs - groupId)
}

internal fun SessionState.updateGroup(groupId: String, change: (TabGroup) -> TabGroup): SessionState? {
    if (group(groupId) == null) return null
    return copy(top = top.map { if (it is TabGroup && it.id == groupId) change(it) else it })
}

/** The first of [colorCount] palette colours no group uses yet, cycling once all are taken. */
internal fun SessionState.nextGroupColor(colorCount: Int): Int {
    val used = groups.map { it.color }.toSet()
    return (0 until colorCount).firstOrNull { it !in used } ?: (groups.size % colorCount)
}
