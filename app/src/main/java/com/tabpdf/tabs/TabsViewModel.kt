package com.tabpdf.tabs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.ink.strokes.Stroke
import com.tabpdf.TabPdfApp
import com.tabpdf.ink.InkStore
import com.tabpdf.ink.InkStroke
import com.tabpdf.ink.InkTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import com.tabpdf.doc.BundleDocument
import com.tabpdf.doc.BundleItem
import com.tabpdf.doc.BundlePages
import com.tabpdf.doc.Bundles
import com.tabpdf.doc.Document
import com.tabpdf.doc.Documents
import com.tabpdf.doc.FileFormat
import com.tabpdf.doc.ImageDocument
import com.tabpdf.doc.Thumbnails
import com.tabpdf.doc.TocEntry
import com.tabpdf.doc.awaitComplete
import com.tabpdf.doc.guarded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.tabpdf.export.PdfExport
import com.tabpdf.pdf.PdfDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import java.io.File
import java.io.FileNotFoundException

/** Several files picked at once, waiting for the choice: a tab each, or one bundle (기획서 5.1). */
data class OpenChoice(val files: List<OpenedFile>, val groupId: String?)

/**
 * A bundle being edited (기획서 5.1: 파일 묶음): its name and files as they will be, applied when
 * the editor closes; [original] and [originalName] as they were.
 */
data class BundleEdit(
    val tabId: String,
    val name: String,
    val items: List<BundleItem>,
    val original: List<BundleItem>,
    val originalName: String,
)

/** Something just closed that the snackbar can bring back. */
data class UndoOffer(val id: Long, val message: String)

/** A request for tab [tabId]'s viewer to show [page]; [id] makes repeated jumps distinct. */
/** A request to show [page] of a tab, from [y] down it (a fraction) if given. */
data class PageJump(val id: Long, val tabId: String, val page: Int, val y: Float? = null)

/**
 * Tabs and tab groups, Chrome-style (기획서 3장), persisted so they come back after the app is
 * closed (기획서 5.3). Every structural change goes through [commit], which saves the whole
 * structure via the ordered write queue; reading positions are saved separately, 0.5 s after they
 * stop changing and immediately via [flush] when the app goes to the background.
 */
class TabsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TabPdfApp
    private val repository = app.repository

    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state.asStateFlow()

    private val _openChoice = MutableStateFlow<OpenChoice?>(null)
    val openChoice: StateFlow<OpenChoice?> = _openChoice.asStateFlow()

    private val _bundleEdit = MutableStateFlow<BundleEdit?>(null)
    val bundleEdit: StateFlow<BundleEdit?> = _bundleEdit.asStateFlow()

    /** Documents open in viewers, by tab: a bundle's page counts are read from it when it is edited. */
    private val openDocuments = mutableMapOf<String, Document>()

    /** A document opened with a password is open (on screen or beside it), see [TabsScreen]'s screen guard. */
    private val _lockedOpen = MutableStateFlow(false)
    val lockedOpen: StateFlow<Boolean> = _lockedOpen.asStateFlow()

    private val _undoOffer = MutableStateFlow<UndoOffer?>(null)
    val undoOffer: StateFlow<UndoOffer?> = _undoOffer.asStateFlow()
    private var undoable: Closed? = null
    private var nextUndoId = 0L

    private val _pageJump = MutableStateFlow<PageJump?>(null)
    val pageJump: StateFlow<PageJump?> = _pageJump.asStateFlow()
    private var nextJumpId = 0L

    val settings = app.settings

    /** Handwriting of every tab (기획서 5.8). */
    val ink = InkStore(app, viewModelScope)

    /** Handwriting mode (기획서 5.8.3): off at every start, so the app opens as a plain viewer. */
    private val _inkMode = MutableStateFlow(false)
    val inkMode: StateFlow<Boolean> = _inkMode.asStateFlow()

    private val viewerStates = mutableMapOf<String, ViewerState>()
    private val unsavedStates = mutableMapOf<String, ViewerState>()
    private val pageCounts = mutableMapOf<String, Int>()
    private var saveJob: Job? = null

    private val loadJob = viewModelScope.launch {
        val (saved, states) = repository.load()
        viewerStates += states
        // Groups are never empty (as in Chrome), and something should be on screen if any tab is open.
        val cleaned = saved.copy(top = saved.top.filter { it !is TabGroup || saved.tabsOf(it.id).isNotEmpty() })
            .withValidCurrent(null, 0)
        // Before any tab is shown (and so opened): which ones ended the app last time.
        withContext(Dispatchers.IO) { OpenGuard.load(app) }
        _state.value = cleaned
        if (cleaned != saved) commit(cleaned)
        app.enqueueWrite {
            FileAccess.releaseUnused(app, FileAccess.filesInUse(cleaned.allTabs.map { it.uri to it.format }), app.startedAt)
            Thumbnails.keepOnly(app, cleaned.allTabs.map { it.id })
            // The last export was shared in an earlier session; one made from a locked PDF has no
            // password, so it isn't kept on the disk longer than that (as for thumbnails).
            withContext(Dispatchers.IO) {
                File(app.cacheDir, EXPORT_DIR).deleteRecursively()
                File(app.cacheDir, PdfExport.WORK_DIR).deleteRecursively()
                // Copies for reading bookmarks, and of images being shown, left when the app ended meanwhile.
                for (temp in listOf(PdfDocument.TEMP_DIR, ImageDocument.TEMP_DIR)) {
                    File(app.cacheDir, temp).listFiles()?.forEach { if (it.lastModified() < app.startedAt) it.delete() }
                }
            }
        }
        ink.deleteOrphans()
    }

    /** Tabs whose file was being opened when the app last ended: opened only when asked ([OpenGuard]). */
    val heldBack: StateFlow<Set<String>> = OpenGuard.held

    fun reopen(tabId: String) = OpenGuard.release(tabId)

    /** Tab [tabId]'s file is about to be opened, or is open and drawn ([OpenGuard]). */
    suspend fun opening(tabId: String) = OpenGuard.begin(app, tabId)
    suspend fun opened(tabId: String) = OpenGuard.end(app, tabId)

    private fun current(): SessionState = _state.value!!

    /** Shows [new] and saves its structure. */
    private fun commit(new: SessionState) {
        _state.value = new
        val states = viewerStates.toMap()
        app.enqueueWrite { repository.saveStructure(new, states) }
    }

    // ---- Opening and showing tabs ----

    /**
     * Opens [files] in [groupId] (null: as ungrouped tabs) and shows the first one. A file already
     * open gets another tab all the same, as a browser opens a page twice (기획서 11장 3번): each
     * tab keeps its own place and handwriting. With [askToBundle], several files wait for the
     * choice between a tab each and one bundle ([openChoice]). With [asNewGroup], ungrouped files
     * go into a new unnamed group instead.
     */
    fun open(files: List<OpenedFile>, groupId: String?, askToBundle: Boolean = false, asNewGroup: Boolean = false) {
        if (files.isEmpty()) return
        if (askToBundle && files.size > 1) {
            _openChoice.value = OpenChoice(files, groupId)
            return
        }
        viewModelScope.launch {
            loadJob.join()
            var s = current()
            val target = groupId?.takeIf { s.group(it) != null }
            val added = files.map { newTab(it, target) }
            if (target == null && asNewGroup) {
                val group = newGroup(s, "")
                s = s.appendGroup(group, added)
                commit(s.copy(currentTabId = s.tabsOf(group.id).first().id))
                return@launch
            }
            for (tab in added) s = s.appendTab(tab)
            commit(s.copy(currentTabId = added.first().id))
        }
    }

    /** Answer to [openChoice]: one bundle (true), a tab each (false), or nothing (null — dismissed). */
    fun resolveOpenChoice(bundle: Boolean?) {
        val choice = _openChoice.value ?: return
        _openChoice.value = null
        when (bundle) {
            true -> openBundle(choice.files, choice.groupId)
            false -> open(choice.files, choice.groupId, asNewGroup = opensAsGroup(choice))
            // The files were made readable for nothing: free what no tab uses.
            null -> app.enqueueWrite {
                val used = FileAccess.filesInUse(repository.tabFiles())
                for (file in choice.files) if (file.uri !in used) FileAccess.release(app, file.uri, file.isInternalCopy)
            }
        }
    }

    /** Whether "따로 열기" of [choice] makes a new group (기획서 5.1: several files from outside any group). */
    fun opensAsGroup(choice: OpenChoice) = choice.groupId == null && choice.files.size > 1 && settings.openManyAsGroup.value

    /** Opens [files] as one bundle tab (기획서 5.1: 파일 묶음), named after the first file. */
    private fun openBundle(files: List<OpenedFile>, groupId: String?) {
        viewModelScope.launch {
            loadJob.join()
            val items = files.map { BundleItem(UUID.randomUUID().toString(), it.uri, it.name, it.format, it.isInternalCopy) }
            val uri = withContext(Dispatchers.IO) { Bundles.create(app, items) }
            val s = current()
            val tab = DocTab(
                UUID.randomUUID().toString(), groupId?.takeIf { s.group(it) != null }, uri,
                bundleName(files.first().name, files.size), isInternalCopy = true, FileFormat.Bundle,
            )
            commit(s.appendTab(tab).copy(currentTabId = tab.id))
        }
    }

    private fun bundleName(first: String, count: Int) = "${first.substringBeforeLast('.')} 외 ${count - 1}개"

    private fun newTab(file: OpenedFile, groupId: String?) =
        DocTab(UUID.randomUUID().toString(), groupId, file.uri, file.name, file.isInternalCopy, file.format)

    /** Shows tab [id] in the viewer. */
    fun select(id: String) {
        val s = current()
        if (s.tab(id) == null || s.currentTabId == id) return
        _state.value = s.copy(currentTabId = id)
        app.enqueueWrite { repository.setCurrentTab(id) }
    }

    /** The pager settled on [id]. */
    fun onTabShown(id: String) = select(id)

    // ---- Closing (with undo) ----

    fun close(id: String) {
        val s = current()
        val tab = s.tab(id) ?: return
        val context = s.context(tab.groupId)
        val index = context.indexOf(tab)
        val group = tab.groupId?.let { s.group(it) }
        val topIndex = s.top.indexOfFirst { it.id == (group?.id ?: id) }
        val emptiedGroup = group?.takeIf { context.size == 1 }
        commit(s.removeTab(id).withValidCurrent(tab.groupId, index))
        unsavedStates -= id
        offerUndo(Closed.Tab(tab, index, topIndex, emptiedGroup, viewerState(id)), "탭을 닫았습니다")
    }

    /** Closes a group and all its tabs. */
    fun closeGroup(groupId: String) {
        val s = current()
        val group = s.group(groupId) ?: return
        val topIndex = s.top.indexOf(group)
        val tabs = s.tabsOf(groupId)
        val next = s.copy(top = s.top - group, groupTabs = s.groupTabs - groupId)
        commit(next.withValidCurrent(null, topIndex))
        tabs.forEach { unsavedStates -= it.id }
        offerUndo(
            Closed.Group(group, topIndex, tabs, tabs.associate { it.id to viewerState(it.id) }),
            "'${group.displayName(tabs.size)}' 그룹을 닫았습니다",
        )
    }

    private sealed interface Closed {
        /** [emptiedGroup] is set when closing the tab removed its (now empty) group. */
        data class Tab(
            val tab: DocTab,
            val index: Int,
            val topIndex: Int,
            val emptiedGroup: TabGroup?,
            val state: ViewerState,
        ) : Closed

        data class Group(
            val group: TabGroup,
            val topIndex: Int,
            val tabs: List<DocTab>,
            val states: Map<String, ViewerState>,
        ) : Closed
    }

    private fun offerUndo(closed: Closed, message: String) {
        commitUndo()
        undoable = closed
        _undoOffer.value = UndoOffer(nextUndoId++, message)
    }

    /** Brings back what was last closed. */
    fun undo() {
        val closed = undoable ?: return
        undoable = null
        _undoOffer.value = null
        val s = current()
        val restored = when (closed) {
            is Closed.Tab -> {
                viewerStates[closed.tab.id] = closed.state
                val groupId = closed.tab.groupId
                when {
                    groupId == null -> s.insertTop(closed.topIndex, closed.tab)
                    s.group(groupId) != null -> s.insertInGroup(groupId, closed.index, closed.tab)
                    closed.emptiedGroup != null -> s.insertTop(closed.topIndex, closed.emptiedGroup)
                        .copy(groupTabs = s.groupTabs + (groupId to listOf(closed.tab)))
                    else -> s.insertTop(closed.topIndex, closed.tab.copy(groupId = null))
                }.copy(currentTabId = closed.tab.id)
            }
            is Closed.Group -> {
                viewerStates += closed.states
                s.insertTop(closed.topIndex, closed.group)
                    .copy(groupTabs = s.groupTabs + (closed.group.id to closed.tabs))
            }
        }
        commit(restored)
    }

    /** The undo offer is gone: free the closed tabs' files if no other tab uses them. */
    fun commitUndo() {
        val closed = undoable ?: return
        undoable = null
        _undoOffer.value = null
        val tabs = when (closed) {
            is Closed.Tab -> listOf(closed.tab)
            is Closed.Group -> closed.tabs
        }
        tabs.forEach { viewerStates -= it.id }
        ink.forget(tabs.map { it.id })
        app.enqueueWrite {
            for (tab in tabs) releaseIfUnused(tab)
            Thumbnails.forget(app, tabs.map { it.id })
        }
    }

    // ---- Replacing a missing file ----

    /**
     * Points a tab whose file went missing at a newly chosen file, keeping its place. If that file
     * is already open in another tab of the same place, the broken tab is closed and the other one
     * shown instead, so a file is never open twice there (기획서 5.1).
     */
    fun replaceFile(id: String, file: OpenedFile) {
        val s = current()
        val old = s.tab(id) ?: return
        val existing = s.context(old.groupId).firstOrNull { it.id != id && it.uri == file.uri }
        if (existing != null) {
            commit(s.removeTab(id).copy(currentTabId = existing.id))
            ink.forget(listOf(id))
            app.enqueueWrite {
                releaseIfUnused(old)
                Thumbnails.forget(app, listOf(id))
            }
            return
        }
        // Bumping the revision makes the viewer retry even when the same location was picked again.
        val updated = old.copy(
            uri = file.uri, name = file.name, isInternalCopy = file.isInternalCopy, format = file.format,
            revision = old.revision + 1,
        )
        commit(s.replaceTab(updated))
        app.enqueueWrite { releaseIfUnused(old) }
    }

    /**
     * In the write queue, after the tab is gone from the saved session: frees its file — and a
     * bundle's list and files — where no saved tab or bundle uses them any more.
     */
    private suspend fun releaseIfUnused(tab: DocTab) {
        val used = FileAccess.filesInUse(repository.tabFiles())
        if (tab.format == FileFormat.Bundle) {
            val items = try {
                withContext(Dispatchers.IO) { Bundles.read(tab.uri) }
            } catch (_: Exception) {
                emptyList()
            }
            for (item in items) if (item.uri !in used) FileAccess.release(app, item.uri, item.isInternalCopy)
        }
        if (tab.uri !in used) FileAccess.release(app, tab)
    }

    // ---- Bundles (기획서 5.1: 파일 묶음) ----

    /** A viewer opened ([document]) or let go of (null) tab [tabId]'s file. */
    fun onDocument(tabId: String, document: Document?) {
        if (document != null) openDocuments[tabId] = document else openDocuments -= tabId
        _lockedOpen.value = openDocuments.values.any { it.locked }
    }

    /** Edits tab [tabId]'s bundle; a tab of one file starts as a bundle of just that file. */
    fun startBundleEdit(tabId: String) {
        val tab = current().tab(tabId) ?: return
        viewModelScope.launch {
            val items = if (tab.format == FileFormat.Bundle) {
                try {
                    withContext(Dispatchers.IO) { Bundles.read(tab.uri) }
                } catch (_: Exception) {
                    return@launch
                }
            } else {
                listOf(BundleItem(UUID.randomUUID().toString(), tab.uri, tab.name, tab.format, tab.isInternalCopy))
            }
            _bundleEdit.value = BundleEdit(tabId, tab.name, items, items, tab.name)
        }
    }

    fun moveBundleItem(fromId: String, toId: String) = updateBundleEdit { edit ->
        val items = edit.items.toMutableList()
        val from = items.indexOfFirst { it.id == fromId }
        val to = items.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return@updateBundleEdit edit
        items.add(to, items.removeAt(from))
        edit.copy(items = items)
    }

    /** A bundle keeps at least one file: the last one can't be taken out. */
    fun removeBundleItem(id: String) = updateBundleEdit { edit ->
        if (edit.items.size <= 1) edit else edit.copy(items = edit.items.filterNot { it.id == id })
    }

    fun renameBundle(name: String) = updateBundleEdit { edit ->
        edit.copy(name = name.trim().ifEmpty { edit.name })
    }

    /** Files picked from the editor's ＋, added at the end. */
    fun addBundleItems(files: List<OpenedFile>) = updateBundleEdit { edit ->
        edit.copy(items = edit.items + files.map { BundleItem(UUID.randomUUID().toString(), it.uri, it.name, it.format, it.isInternalCopy) })
    }

    private inline fun updateBundleEdit(change: (BundleEdit) -> BundleEdit) {
        _bundleEdit.value = _bundleEdit.value?.let(change)
    }

    /**
     * Closes the editor and applies what changed: the list is saved, handwriting and the reading
     * position move with their files' pages (strokes on a file taken out go with it), and files no
     * longer used anywhere are freed. The viewer then opens the bundle as it now is. A tab of one
     * file whose list changed becomes a bundle tab, its file the bundle's first (기획서 5.1).
     */
    fun finishBundleEdit() {
        val edit = _bundleEdit.value ?: return
        _bundleEdit.value = null
        if (edit.items == edit.original && edit.name == edit.originalName) return
        viewModelScope.launch {
            val tab = current().tab(edit.tabId) ?: return@launch
            val becomesBundle = tab.format != FileFormat.Bundle && edit.items != edit.original
            var bundleUri = tab.uri
            if (edit.items != edit.original) {
                val counts = pageCounts(tab, edit)
                val move = BundlePages.mover(edit.original.map { it.id to (counts[it.id] ?: 1) }, edit.items.map { it.id to (counts[it.id] ?: 1) })
                if (becomesBundle) bundleUri = withContext(Dispatchers.IO) { Bundles.create(app, edit.items) }
                else withContext(Dispatchers.IO) { Bundles.write(tab.uri, edit.items) }
                ink.movePages(tab.id, move)
                viewerStates[tab.id]?.let { state ->
                    viewerStates[tab.id] = move(state.page)?.let { state.copy(page = it) } ?: ViewerState()
                }
            }
            val now = current().tab(edit.tabId) ?: return@launch
            val name = if (becomesBundle && edit.name == edit.originalName) bundleName(edit.items.first().name, edit.items.size) else edit.name
            val changed = if (becomesBundle) {
                // The file itself now belongs to the bundle's list.
                now.copy(uri = bundleUri, format = FileFormat.Bundle, isInternalCopy = true)
            } else now
            // A new revision makes the viewer open the bundle again as it now is.
            commit(current().replaceTab(changed.copy(name = name, revision = now.revision + 1)))
            val gone = edit.original.filter { old -> edit.items.none { it.id == old.id } }
            if (gone.isNotEmpty()) app.enqueueWrite {
                val used = FileAccess.filesInUse(repository.tabFiles())
                for (item in gone) if (item.uri !in used) FileAccess.release(app, item.uri, item.isInternalCopy)
            }
        }
    }

    /** Pages of every file before and after [edit]: from the open bundle, and by opening the others. */
    private suspend fun pageCounts(tab: DocTab, edit: BundleEdit): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        // A tab of one file isn't a bundle yet: its file is opened below like one added.
        if (tab.format == FileFormat.Bundle) counts += (openDocuments[tab.id] as? BundleDocument)?.pageCounts()
            ?: try {
                val reopened = BundleDocument.open(app, tab.uri)
                try { reopened.pageCounts() } finally { reopened.close() }
            } catch (_: Exception) {
                emptyMap()
            }
        for (item in edit.items) if (item.id !in counts) {
            counts[item.id] = try {
                val document = Documents.open(app, item.uri, item.name, item.format)
                try {
                    document.awaitComplete()
                    document.pageCount
                } finally {
                    document.close()
                }
            } catch (_: Exception) {
                1 // shown as one blank page
            }
        }
        return counts
    }

    // ---- Arranging ----

    // The rules live in SessionEdits.kt; these only commit the result.

    fun reorderTop(ids: List<String>) = commit(current().reorderTop(ids))

    fun reorderGroup(groupId: String, ids: List<String>) = current().reorderGroup(groupId, ids)?.let(::commit)

    /** Dropping ungrouped tab [draggedId] onto ungrouped tab [targetId] makes a new, unnamed group of the two. */
    fun groupTogether(draggedId: String, targetId: String) =
        current().let { it.groupTogether(draggedId, targetId, newGroup(it, "")) }?.let(::commit)

    fun addToGroup(tabId: String, groupId: String) = current().addToGroup(tabId, groupId)?.let(::commit)

    fun addToNewGroup(tabId: String, name: String) =
        current().let { it.addToNewGroup(tabId, newGroup(it, name.trim())) }?.let(::commit)

    fun removeFromGroup(tabId: String) = current().removeFromGroup(tabId)?.let(::commit)

    fun ungroup(groupId: String) = current().ungroup(groupId)?.let(::commit)

    fun renameGroup(groupId: String, name: String) =
        current().updateGroup(groupId) { it.copy(name = name.trim()) }?.let(::commit)

    fun setGroupColor(groupId: String, color: Int) =
        current().updateGroup(groupId) { it.copy(color = color) }?.let(::commit)

    private fun newGroup(s: SessionState, name: String) =
        TabGroup(UUID.randomUUID().toString(), name, s.nextGroupColor(GROUP_COLOR_COUNT))

    // ---- Handwriting ----

    fun setInkMode(on: Boolean) {
        _inkMode.value = on
    }

    /** A line drawn, as its [parts] on each page it crossed: one undo takes them all back. */
    fun addStroke(tabId: String, parts: List<Pair<Int, Stroke>>, tool: InkTool) {
        if (current().tab(tabId) == null || parts.isEmpty()) return
        ink.add(tabId, parts.map { (page, stroke) -> InkStroke(UUID.randomUUID().toString(), page, ink.nextSeq(), tool, stroke) })
    }

    fun eraseStrokes(tabId: String, ids: Set<String>, gesture: Long) = ink.erase(tabId, ids, gesture)

    fun undoInk() = current().currentTabId?.let(ink::undo)

    fun redoInk() = current().currentTabId?.let(ink::redo)

    // ---- Reading positions ----

    fun viewerState(id: String): ViewerState = viewerStates[id] ?: ViewerState()

    /** Page count of tab [id], once its viewer has opened the file. */
    fun pageCount(id: String): Int? = pageCounts[id]

    fun onDocumentOpened(id: String, pageCount: Int) {
        pageCounts[id] = pageCount
    }

    /** Asks tab [tabId]'s viewer to go to [page] (0-based). */
    fun jumpToPage(tabId: String, page: Int, y: Float? = null) {
        _pageJump.value = PageJump(nextJumpId++, tabId, page, y)
    }

    /** Tab [tabId]'s table of contents (기획서 5.5), or null if its file isn't open. */
    suspend fun tableOfContents(tabId: String): List<TocEntry>? = openDocuments[tabId]?.let { guarded(emptyList()) { it.tableOfContents() } }

    fun onPageJumpHandled(jump: PageJump) {
        if (_pageJump.value == jump) _pageJump.value = null
    }

    fun updateViewerState(id: String, state: ViewerState) {
        val s = _state.value ?: return
        if (s.tab(id) == null || viewerStates[id] == state) return
        viewerStates[id] = state
        unsavedStates[id] = state
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /** Writes pending reading positions now. Safe to call from onStop. */
    fun flush() {
        if (unsavedStates.isEmpty()) return
        val batch = unsavedStates.toMap()
        unsavedStates.clear()
        app.enqueueWrite { repository.saveViewerStates(batch) }
    }

    // ---- PDF export (기획서 5.9) ----

    sealed interface ExportState {
        data class Running(val done: Int, val total: Int) : ExportState
        /** Made and, for [saved], written to the chosen place; else ready to share as [file]. */
        data class Done(val file: File?, val saved: Boolean) : ExportState
        data object Failed : ExportState
    }

    private val _export = MutableStateFlow<ExportState?>(null)
    val export: StateFlow<ExportState?> = _export.asStateFlow()
    private var exportJob: Job? = null

    /** Whether tab [tabId]'s file is open, so it can be exported. */
    fun canExport(tabId: String) = openDocuments.containsKey(tabId)

    /** Whether tab [tabId] was opened with a password (its export won't have one). */
    fun isLocked(tabId: String) = openDocuments[tabId]?.locked == true

    /** The name an export of [tab] is offered under: the file's name as a PDF. */
    fun exportName(tab: DocTab) = exportFileName(tab.name)

    /** The size of the file at [uri] as its provider tells it, or null if it doesn't. */
    private fun sizeOf(uri: Uri): Long? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Exports tab [tabId] to [destination] (a file the user picked), or for sharing if null. */
    fun startExport(tabId: String, destination: Uri?) {
        val tab = current().tab(tabId) ?: return
        exportJob?.cancel()
        _export.value = ExportState.Running(0, 0)
        exportJob = viewModelScope.launch {
            val dir = File(app.cacheDir, EXPORT_DIR)
            val target = File(dir, exportName(tab))
            // The name is checked to be plain, but the file must be in the shared folder whatever it is.
            if (target.parentFile?.canonicalPath != dir.canonicalPath) {
                _export.value = ExportState.Failed
                return@launch
            }
            var document: Document? = null
            // A file the save dialog just made is empty; one the user chose to replace is not, and is
            // never deleted on failure (the export is written there only once it is complete).
            val madeForIt = destination != null && withContext(Dispatchers.IO) { sizeOf(destination) == 0L }
            try {
                withContext(Dispatchers.IO) {
                    // Only the latest export is kept (for the app it is shared with to read).
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
                // A copy of its own, so the viewer keeps drawing meanwhile.
                document = Documents.open(app, tab.uri, tab.name, tab.format)
                PdfExport.export(app, tab.uri, document, ink.strokes(tabId).value, target) { done, total ->
                    _export.value = ExportState.Running(done, total)
                }
                if (destination != null) {
                    withContext(Dispatchers.IO) {
                        app.contentResolver.openOutputStream(destination, "wt")?.use { out -> target.inputStream().use { it.copyTo(out) } }
                            ?: throw FileNotFoundException()
                        target.delete()
                    }
                    _export.value = ExportState.Done(null, saved = true)
                } else {
                    _export.value = ExportState.Done(target, saved = false)
                }
            } catch (e: Throwable) {
                // Out of memory or stack on a crafted file is a failed export, not a crash.
                if (e !is Exception && e !is OutOfMemoryError && e !is StackOverflowError) throw e
                // Only the kind: messages may hold the file's address (and so its name).
                if (e !is CancellationException) android.util.Log.w("EveryViewer", "Export failed: ${e.javaClass.simpleName}")
                // The empty file made for it is no use.
                destination?.takeIf { madeForIt }?.let { uri -> withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } } }
                target.delete()
                _export.value = if (e is CancellationException) null else ExportState.Failed
                if (e is CancellationException) throw e
            } finally {
                document?.let { withContext(NonCancellable) { it.close() } }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _export.value = null
    }

    fun exportHandled() {
        if (_export.value !is ExportState.Running) _export.value = null
    }

    override fun onCleared() {
        flush()
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 500L
        const val GROUP_COLOR_COUNT = 8

        /** Under the cache folder; the FileProvider shares it (res/xml/export_paths.xml). */
        const val EXPORT_DIR = "export"

        /**
         * The file name an export of a file named [name] gets: that name as a PDF. The name comes from
         * the app that gave the file, so it is cut to a plain name: no folders ("../"), no characters
         * file systems refuse, not hidden, not too long. Its length is counted in bytes, as file
         * systems count it (255 at most): 100 Hangul letters are 300 bytes. A letter is never cut in two.
         */
        fun exportFileName(name: String): String {
            val plain = name.substringBeforeLast('.', name)
                .map { c -> if (c.code < 32 || c in "/\\:*?\"<>|") '_' else c }.joinToString("")
                .trim().trimStart('.').trim()
            val base = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < plain.length) {
                val codePoint = plain.codePointAt(i)
                val size = String(Character.toChars(codePoint)).toByteArray().size
                if (bytes + size > MAX_NAME_BYTES) break
                base.appendCodePoint(codePoint)
                bytes += size
                i += Character.charCount(codePoint)
            }
            return base.toString().trim().ifBlank { "document" } + ".pdf"
        }

        /** Bytes of an export's name before ".pdf": room left for "(1)" and the like added by other apps. */
        private const val MAX_NAME_BYTES = 200
    }
}

