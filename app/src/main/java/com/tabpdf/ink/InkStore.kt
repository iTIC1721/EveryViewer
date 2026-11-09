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
        val texts = MutableStateFlow<List<InkText>>(emptyList())
        /** In page order. Not part of undo: they are not handwriting. */
        val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
        val undo = ArrayDeque<Edit>()
        val redo = ArrayDeque<Edit>()
    }

    private sealed interface Edit {
        data class Added(val strokes: List<InkStroke>) : Edit

        /** [gesture] lets one eraser drag that wipes several strokes undo as one. */
        data class Erased(val strokes: List<InkStroke>, val gesture: Long) : Edit

        /** A text box made ([before] null), changed, or removed ([after] null). */
        data class Text(val before: InkText?, val after: InkText?) : Edit
    }

    private val tabs = mutableMapOf<String, TabInk>()

    /** Bumped whenever any tab's undo/redo history changes. */
    private val _history = MutableStateFlow(0)
    val history: StateFlow<Int> = _history.asStateFlow()

    private val dao get() = app.inkDao

    /** Tab [tabId]'s strokes, all pages, in drawing order. Starts loading them on first use. */
    fun strokes(tabId: String): StateFlow<List<InkStroke>> = tab(tabId).strokes.asStateFlow()

    /** Tab [tabId]'s text boxes, all pages, in the order they were made. */
    fun texts(tabId: String): StateFlow<List<InkText>> = tab(tabId).texts.asStateFlow()

    /** Tab [tabId]'s bookmarks (기획서 5.5), in page order. */
    fun bookmarks(tabId: String): StateFlow<List<Bookmark>> = tab(tabId).bookmarks.asStateFlow()

    private fun tab(tabId: String): TabInk = tabs.getOrPut(tabId) {
        TabInk().also { ink ->
            scope.launch {
                val (strokes, texts) = withContext(Dispatchers.Default) {
                    dao.strokesOf(tabId).map { it.toInkStroke() } to dao.textsOf(tabId).map { it.toInkText(defaultTextStyle) }
                }
                val marks = withContext(Dispatchers.Default) { dao.bookmarksOf(tabId).map { it.toBookmark() } }
                // Put there while loading: kept, before a saved one on the same page.
                val put = ink.bookmarks.value
                ink.bookmarks.value = (put + marks.filter { m -> put.none { it.page == m.page } }).sortedBy { it.page }
                // Strokes drawn while loading come after the saved ones.
                val drawn = ink.strokes.value
                ink.strokes.value = strokes + drawn.filter { d -> strokes.none { it.id == d.id } }
                val typed = ink.texts.value
                ink.texts.value = texts.filter { t -> typed.none { it.id == t.id } } + typed
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

    /**
     * Text box [before] became [after] (기획서 5.8.6): made when [before] is null, removed when
     * [after] is null. One undo step.
     */
    fun changeText(tabId: String, before: InkText?, after: InkText?) {
        if (before == after) return
        val ink = tab(tabId)
        replaceText(tabId, ink, before, after)
        record(ink, Edit.Text(before, after))
    }

    private fun replaceText(tabId: String, ink: TabInk, gone: InkText?, new: InkText?) {
        val list = ink.texts.value.filterNot { it.id == gone?.id || it.id == new?.id }
        ink.texts.value = if (new == null) list else (list + new).sortedBy { it.seq }
        if (new != null) app.enqueueWrite { dao.insertTexts(listOf(new.toEntity(tabId))) }
        else if (gone != null) app.enqueueWrite { dao.deleteTexts(listOf(gone.id)) }
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
        val strokes = when (edit) {
            is Edit.Added -> edit.strokes
            is Edit.Erased -> edit.strokes
            is Edit.Text -> {
                if (forward) replaceText(tabId, ink, edit.before, edit.after) else replaceText(tabId, ink, edit.after, edit.before)
                return
            }
        }
        val adds = (edit is Edit.Added) == forward
        if (adds) {
            ink.strokes.value = (ink.strokes.value + strokes).sortedBy { it.seq }
            save(tabId, strokes)
        } else {
            val ids = strokes.map { it.id }.toSet()
            ink.strokes.value = ink.strokes.value.filterNot { it.id in ids }
            delete(strokes)
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
        val movedTexts = mutableListOf<InkText>()
        val goneTexts = mutableListOf<InkText>()
        for (t in ink.texts.value) {
            val page = move(t.page)
            if (page == null) goneTexts += t else if (page != t.page) movedTexts += t.copy(page = page)
        }
        val movedTextIds = movedTexts.associateBy { it.id }
        ink.texts.value = ink.texts.value.filterNot { t -> goneTexts.any { it.id == t.id } }.map { movedTextIds[it.id] ?: it }
        val marks = ink.bookmarks.value.mapNotNull { b -> move(b.page)?.let { b.copy(page = it) } }
        val goneMarks = ink.bookmarks.value.filter { move(it.page) == null }
        val movedMarks = marks.filter { m -> ink.bookmarks.value.any { it.id == m.id && it.page != m.page } }
        ink.bookmarks.value = marks.sortedBy { it.page }
        if (goneMarks.isNotEmpty()) app.enqueueWrite { dao.deleteBookmarks(goneMarks.map { it.id }) }
        if (movedMarks.isNotEmpty()) app.enqueueWrite { dao.insertBookmarks(movedMarks.map { it.toEntity(tabId) }) }
        ink.undo.clear()
        ink.redo.clear()
        _history.value++
        if (gone.isNotEmpty()) delete(gone)
        if (moved.isNotEmpty()) save(tabId, moved)
        if (goneTexts.isNotEmpty()) app.enqueueWrite { dao.deleteTexts(goneTexts.map { it.id }) }
        if (movedTexts.isNotEmpty()) app.enqueueWrite { dao.insertTexts(movedTexts.map { it.toEntity(tabId) }) }
    }

    /**
     * Puts a bookmark on page [page] at [y] (a fraction down it), or takes away the one there
     * (one per page, so the same button does both).
     */
    fun toggleBookmark(tabId: String, page: Int, y: Float) {
        val ink = tab(tabId)
        val there = ink.bookmarks.value.firstOrNull { it.page == page }
        if (there != null) {
            removeBookmark(tabId, there.id)
            return
        }
        val mark = Bookmark(java.util.UUID.randomUUID().toString(), page, y.coerceIn(0f, 1f), null, System.currentTimeMillis())
        ink.bookmarks.value = (ink.bookmarks.value + mark).sortedBy { it.page }
        app.enqueueWrite { dao.insertBookmarks(listOf(mark.toEntity(tabId))) }
    }

    fun removeBookmark(tabId: String, id: String) {
        val ink = tab(tabId)
        ink.bookmarks.value = ink.bookmarks.value.filterNot { it.id == id }
        app.enqueueWrite { dao.deleteBookmarks(listOf(id)) }
    }

    /** Names bookmark [id]; a blank [name] goes back to naming it after its page. */
    fun renameBookmark(tabId: String, id: String, name: String) {
        val ink = tab(tabId)
        val mark = ink.bookmarks.value.firstOrNull { it.id == id } ?: return
        val renamed = mark.copy(name = name.trim().ifEmpty { null })
        ink.bookmarks.value = ink.bookmarks.value.map { if (it.id == id) renamed else it }
        app.enqueueWrite { dao.insertBookmarks(listOf(renamed.toEntity(tabId))) }
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

        /** For a stored text box whose style can't be read. */
        val defaultTextStyle = CharStyle(DEFAULT_TEXT_SIZE, 0xFF000000.toInt())
    }
}
