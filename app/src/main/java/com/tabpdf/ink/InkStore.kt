package com.tabpdf.ink

import com.tabpdf.TabPdfApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every tab's handwriting (기획서 5.8): loaded when a tab is first shown, saved stroke by stroke
 * through the ordered write queue, with undo/redo per tab (kept in memory only, 기획서 6장).
 * Main thread only.
 */
class InkStore(private val app: TabPdfApp, private val scope: CoroutineScope) {
    private class TabInk {
        val strokes = MutableStateFlow<List<InkStroke>>(emptyList())
        val undo = ArrayDeque<Edit>()
        val redo = ArrayDeque<Edit>()
    }

    private sealed interface Edit {
        val strokes: List<InkStroke>

        data class Added(override val strokes: List<InkStroke>) : Edit

        /** [gesture] lets one eraser drag that wipes several strokes undo as one. */
        data class Erased(override val strokes: List<InkStroke>, val gesture: Long) : Edit
    }

    private val tabs = mutableMapOf<String, TabInk>()

    /** Bumped whenever any tab's undo/redo history changes. */
    private val _history = MutableStateFlow(0)
    val history: StateFlow<Int> = _history.asStateFlow()

    private val dao get() = app.inkDao

    /** Tab [tabId]'s strokes, all pages, in drawing order. Starts loading them on first use. */
    fun strokes(tabId: String): StateFlow<List<InkStroke>> = tab(tabId).strokes.asStateFlow()

    private fun tab(tabId: String): TabInk = tabs.getOrPut(tabId) {
        TabInk().also { ink ->
            scope.launch {
                val loaded = withContext(Dispatchers.Default) { dao.strokesOf(tabId).map { it.toInkStroke() } }
                // Strokes drawn while loading come after the saved ones.
                val drawn = ink.strokes.value
                ink.strokes.value = loaded + drawn.filter { d -> loaded.none { it.id == d.id } }
            }
        }
    }

    private var lastSeq = 0L

    /** Drawing order: time-based, so strokes from later sessions always come after earlier ones. */
    fun nextSeq(): Long {
        lastSeq = maxOf(System.currentTimeMillis(), lastSeq + 1)
        return lastSeq
    }

    /** One line, as one or more strokes (its parts on the pages it crossed): undone as one. */
    fun add(tabId: String, strokes: List<InkStroke>) {
        val ink = tab(tabId)
        ink.strokes.value = ink.strokes.value + strokes
        record(ink, Edit.Added(strokes))
        save(tabId, strokes)
    }

    fun erase(tabId: String, ids: Set<String>, gesture: Long) {
        val ink = tab(tabId)
        val gone = ink.strokes.value.filter { it.id in ids }
        if (gone.isEmpty()) return
        ink.strokes.value = ink.strokes.value.filterNot { it.id in ids }
        val last = ink.undo.lastOrNull()
        if (last is Edit.Erased && last.gesture == gesture && ink.redo.isEmpty()) {
            ink.undo[ink.undo.lastIndex] = last.copy(strokes = last.strokes + gone)
            _history.value++
        } else {
            record(ink, Edit.Erased(gone, gesture))
        }
        delete(gone)
    }

    fun canUndo(tabId: String) = tabs[tabId]?.undo?.isNotEmpty() == true
    fun canRedo(tabId: String) = tabs[tabId]?.redo?.isNotEmpty() == true

    fun undo(tabId: String) {
        val ink = tabs[tabId] ?: return
        val edit = ink.undo.removeLastOrNull() ?: return
        apply(tabId, ink, edit, forward = false)
        ink.redo.addLast(edit)
        _history.value++
    }

    fun redo(tabId: String) {
        val ink = tabs[tabId] ?: return
        val edit = ink.redo.removeLastOrNull() ?: return
        apply(tabId, ink, edit, forward = true)
        ink.undo.addLast(edit)
        _history.value++
    }

    private fun apply(tabId: String, ink: TabInk, edit: Edit, forward: Boolean) {
        val adds = (edit is Edit.Added) == forward
        if (adds) {
            ink.strokes.value = (ink.strokes.value + edit.strokes).sortedBy { it.seq }
            save(tabId, edit.strokes)
        } else {
            val ids = edit.strokes.map { it.id }.toSet()
            ink.strokes.value = ink.strokes.value.filterNot { it.id in ids }
            delete(edit.strokes)
        }
    }

    private fun record(ink: TabInk, edit: Edit) {
        ink.undo.addLast(edit)
        if (ink.undo.size > MAX_UNDO) ink.undo.removeFirst()
        ink.redo.clear()
        _history.value++
    }

    /**
     * Tab [tabId]'s pages were rearranged (a bundle edited): each stroke goes to [move] of its
     * page, or away if that is null. Undo history is dropped — it was about the old pages.
     */
    fun movePages(tabId: String, move: (Int) -> Int?) {
        val ink = tab(tabId)
        val moved = mutableListOf<InkStroke>()
        val gone = mutableListOf<InkStroke>()
        for (s in ink.strokes.value) {
            val page = move(s.page)
            if (page == null) gone += s else if (page != s.page) moved += InkStroke(s.id, page, s.seq, s.tool, s.stroke)
        }
        val movedIds = moved.associateBy { it.id }
        ink.strokes.value = ink.strokes.value.filterNot { s -> gone.any { it.id == s.id } }.map { movedIds[it.id] ?: it }
        ink.undo.clear()
        ink.redo.clear()
        _history.value++
        if (gone.isNotEmpty()) delete(gone)
        if (moved.isNotEmpty()) save(tabId, moved)
    }

    /** The tabs are gone for good (their undo offer ended): drop their strokes. */
    fun forget(tabIds: List<String>) {
        tabIds.forEach { tabs.remove(it) }
        _history.value++
        app.enqueueWrite { dao.deleteTabs(tabIds) }
    }

    /** Removes strokes of tabs closed in an earlier run whose undo period never ended normally. */
    fun deleteOrphans() = app.enqueueWrite { dao.deleteOrphans() }

    private fun save(tabId: String, strokes: List<InkStroke>) {
        app.enqueueWrite { dao.insert(strokes.map { it.toEntity(tabId) }) }
    }

    private fun delete(strokes: List<InkStroke>) {
        val ids = strokes.map { it.id }
        app.enqueueWrite { dao.delete(ids) }
    }

    private companion object {
        const val MAX_UNDO = 200
    }
}
