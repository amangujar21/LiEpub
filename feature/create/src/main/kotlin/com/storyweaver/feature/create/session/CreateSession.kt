package com.storyweaver.feature.create.session

import com.storyweaver.core.model.Author
import com.storyweaver.core.model.AuthorCatalog
import com.storyweaver.core.model.CatalogFilter
import com.storyweaver.core.model.CatalogSort
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionDraft
import com.storyweaver.core.model.CollectionId
import com.storyweaver.core.model.DuplicateDetection
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.JobPhase
import com.storyweaver.core.model.RefreshReconciliation
import com.storyweaver.core.model.TocNodeKind
import com.storyweaver.core.model.TocOperations
import com.storyweaver.core.model.Work
import com.storyweaver.core.model.WorkId
import com.storyweaver.core.model.catalogViewFromResolved
import com.storyweaver.epub.EpubImporter
import com.storyweaver.epub.EpubMerger
import com.storyweaver.epub.EpubWriteResult
import com.storyweaver.epub.EpubWriteRequest
import com.storyweaver.epub.EpubWriter
import com.storyweaver.epub.FilenameSanitizer
import com.storyweaver.epub.ImportedEpub
import com.storyweaver.epub.writeSeparate
import com.storyweaver.source.literotica.LiteroticaSource
import com.storyweaver.source.literotica.ResolveResult
import java.util.UUID

/**
 * Pure session fields for Create. [catalogView] is derived for filter/sort UI only.
 */
data class CreateSessionState(
    val urlInput: String = "",
    val phase: JobPhase = JobPhase.DRAFT,
    val statusMessage: String = "Paste author, series, or story URLs (one or more).",
    val authors: List<Author> = emptyList(),
    val works: List<Work> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val selectedWorkIds: Set<WorkId> = emptySet(),
    /** Chapter IDs excluded from export (series chapter overrides). */
    val deselectedChapterIds: Set<ChapterId> = emptySet(),
    /** Explicit work order for TOC / export; empty uses catalog order. */
    val workOrder: List<WorkId> = emptyList(),
    val expandedSeriesIds: Set<WorkId> = emptySet(),
    val catalogView: AuthorCatalog? = null,
    val filteredCatalog: AuthorCatalog? = null,
    val searchQuery: String = "",
    val sort: CatalogSort = CatalogSort.SITE_ORDER,
    val bookTitle: String = "",
    val bookSubtitle: String = "",
    val curator: String = "",
    val outputFilename: String = "",
    val accentColorArgb: Long = 0xFF7A2E3D,
    val exportMode: ExportMode = ExportMode.COMBINED,
    val epubStyle: EpubStyle = EpubStyle.MODERN,
    val importedBooks: List<ImportedEpub> = emptyList(),
    val progressCompleted: Int = 0,
    val progressTotal: Int = 0,
    val lastError: String? = null,
    val linkErrors: Map<String, String> = emptyMap(),
    val exportResult: EpubWriteResult? = null,
    val exportResults: List<EpubWriteResult> = emptyList(),
    val warnings: List<String> = emptyList(),
    val refreshMessages: List<String> = emptyList(),
    val newChapterIds: Set<ChapterId> = emptySet(),
    val changedChapterIds: Set<ChapterId> = emptySet(),
    val failedChapterUrls: List<String> = emptyList(),
    val pendingContents: Map<ChapterId, ChapterContent> = emptyMap(),
    val pendingChapters: List<Chapter> = emptyList(),
    val collectionId: CollectionId? = null,
    val concurrency: Int = 3,
) {
    val hasCatalog: Boolean get() = catalogView != null && works.isNotEmpty()
    val hasContent: Boolean get() = works.isNotEmpty() || importedBooks.isNotEmpty()
}

object CreateSessionLogic {
    val accentPresets: List<Pair<String, Long>> = listOf(
        "Burgundy" to 0xFF7A2E3DL,
        "Slate" to 0xFF3D4F5FL,
        "Forest" to 0xFF2F4F3FL,
        "Ink" to 0xFF1A1A1AL,
        "Copper" to 0xFF8B5A2BL,
    )

    fun withSearch(state: CreateSessionState, query: String): CreateSessionState {
        val filtered = state.catalogView?.let { CatalogFilter.filterAndSort(it, query, state.sort) }
        return state.copy(searchQuery = query, filteredCatalog = filtered)
    }

    fun withSort(state: CreateSessionState, sort: CatalogSort): CreateSessionState {
        val filtered = state.catalogView?.let { CatalogFilter.filterAndSort(it, state.searchQuery, sort) }
        return state.copy(sort = sort, filteredCatalog = filtered)
    }

    fun applyResolve(state: CreateSessionState, result: ResolveResult): CreateSessionState {
        if (result.works.isEmpty() && state.importedBooks.isEmpty()) {
            return state.copy(
                phase = JobPhase.FAILED,
                lastError = result.linkErrors.entries.joinToString("\n") { (u, e) -> "$u → $e" }
                    .ifBlank { "No works found." },
                statusMessage = "Analysis failed.",
                linkErrors = result.linkErrors,
                catalogView = null,
                filteredCatalog = null,
                authors = emptyList(),
                works = emptyList(),
                chapters = emptyList(),
            )
        }
        if (result.works.isEmpty()) {
            return state.copy(
                phase = JobPhase.READY,
                linkErrors = result.linkErrors,
                warnings = result.warnings.map { it.message },
                statusMessage = "No Literotica works; imported EPUBs only.",
                lastError = null,
            )
        }
        val catalog = catalogViewFromResolved(result.authors, result.works, result.chapters)
        val defaultSelected = catalog.series.map { it.work.id }.toSet() +
            catalog.standalones.filter { it.selectedByDefault }.map { it.work.id }
        val title = when {
            result.works.size == 1 -> result.works.first().title
            result.authors.size == 1 -> "Selected Stories — ${result.authors.first().displayName}"
            else -> "Collected Stories — ${result.authors.size} Authors"
        }
        val filtered = CatalogFilter.filterAndSort(catalog, "", CatalogSort.SITE_ORDER)
        val dupWarnings = DuplicateDetection.warningsFor(result.works)
        val order = result.works.map { it.id }
        return state.copy(
            phase = JobPhase.READY,
            catalogView = catalog,
            filteredCatalog = filtered,
            authors = result.authors,
            works = result.works,
            chapters = result.chapters,
            selectedWorkIds = defaultSelected.ifEmpty { result.works.map { it.id }.toSet() },
            deselectedChapterIds = emptySet(),
            workOrder = order,
            bookTitle = title,
            curator = result.authors.joinToString { it.displayName },
            outputFilename = "",
            statusMessage = "Found ${catalog.series.size} series and ${catalog.standalones.size} standalones.",
            warnings = result.warnings.map { it.message } + dupWarnings,
            linkErrors = result.linkErrors,
            collectionId = state.collectionId ?: CollectionId(UUID.randomUUID().toString()),
            lastError = null,
            exportResult = null,
            exportResults = emptyList(),
            failedChapterUrls = emptyList(),
            sort = CatalogSort.SITE_ORDER,
            searchQuery = "",
            refreshMessages = emptyList(),
            newChapterIds = emptySet(),
            changedChapterIds = emptySet(),
        )
    }

    fun applyRefresh(
        state: CreateSessionState,
        result: ResolveResult,
        cachedFingerprints: Map<String, String?> = emptyMap(),
    ): CreateSessionState {
        if (result.works.isEmpty() && result.chapters.isEmpty()) {
            return state.copy(
                phase = JobPhase.READY,
                lastError = result.linkErrors.entries.joinToString("\n") { (u, e) -> "$u → $e" }
                    .ifBlank { "Refresh found no works." },
                statusMessage = "Refresh incomplete.",
                linkErrors = result.linkErrors,
            )
        }
        val reconciled = RefreshReconciliation.reconcile(
            previousAuthors = state.authors,
            previousWorks = state.works,
            previousChapters = state.chapters,
            nextAuthors = result.authors,
            nextWorks = result.works,
            nextChapters = result.chapters,
            cachedFingerprintByUrl = cachedFingerprints,
        )
        val catalog = catalogViewFromResolved(
            reconciled.authors,
            reconciled.works,
            reconciled.chapters,
        )
        val previousSelected = state.selectedWorkIds
        val newWorkIds = reconciled.works.map { it.id }.toSet() - state.works.map { it.id }.toSet()
        // Auto-select newly discovered works (typically new series chapters already under work).
        val selected = (previousSelected + newWorkIds).intersect(reconciled.works.map { it.id }.toSet())
            .ifEmpty { reconciled.works.map { it.id }.toSet() }
        val order = (state.workOrder + reconciled.works.map { it.id }).distinct()
            .filter { id -> reconciled.works.any { it.id == id } }
        val filtered = CatalogFilter.filterAndSort(catalog, state.searchQuery, state.sort)
        // New chapters are selected by default (not in deselected set).
        return state.copy(
            phase = JobPhase.READY,
            catalogView = catalog,
            filteredCatalog = filtered,
            authors = reconciled.authors,
            works = reconciled.works,
            chapters = reconciled.chapters,
            selectedWorkIds = selected,
            workOrder = order,
            statusMessage = reconciled.messages.joinToString(" "),
            refreshMessages = reconciled.messages,
            newChapterIds = reconciled.newChapterIds,
            changedChapterIds = reconciled.changedChapterIds,
            warnings = state.warnings + result.warnings.map { it.message } +
                DuplicateDetection.warningsFor(reconciled.works),
            linkErrors = result.linkErrors,
            lastError = null,
            exportResult = null,
            exportResults = emptyList(),
        )
    }

    fun buildDraft(state: CreateSessionState): CollectionDraft {
        val collectionId = state.collectionId ?: CollectionId(UUID.randomUUID().toString())
        var draft = TocOperations.buildFromResolved(
            collectionId = collectionId,
            authors = state.authors,
            works = state.works,
            chapters = state.chapters,
            selectedWorkIds = state.selectedWorkIds,
            title = state.bookTitle.ifBlank { null },
            curator = state.curator.ifBlank { null },
            workOrder = state.workOrder,
            deselectedChapterIds = state.deselectedChapterIds,
            accentColorArgb = state.accentColorArgb,
        ).copy(
            subtitle = state.bookSubtitle.ifBlank { null },
            exportMode = if (state.importedBooks.isNotEmpty()) ExportMode.COMBINED else state.exportMode,
            epubStyle = state.epubStyle,
            accentColorArgb = state.accentColorArgb,
        )
        state.importedBooks.forEach { imp ->
            val sectionTitles = imp.navToc.map { it.title }.ifEmpty {
                imp.spineHrefs.map { it.substringAfterLast('/') }
            }
            draft = TocOperations.appendImportedBook(draft, imp.book, sectionTitles.take(40))
        }
        return draft
    }

    fun selectedChapters(state: CreateSessionState): List<Chapter> {
        val draft = buildDraft(state)
        val ids = TocOperations.selectedChapterIds(draft, state.chapters)
        val workRank = (state.workOrder.ifEmpty { state.works.map { it.id } })
            .mapIndexed { i, id -> id to i }.toMap()
        return state.chapters
            .filter { it.id in ids }
            .sortedWith(compareBy({ workRank[it.workId] ?: Int.MAX_VALUE }, { it.orderKey }))
    }

    fun toggleChapter(state: CreateSessionState, chapterId: ChapterId): CreateSessionState {
        val next = state.deselectedChapterIds.toMutableSet()
        if (!next.add(chapterId)) next.remove(chapterId)
        return state.copy(deselectedChapterIds = next)
    }

    fun moveWork(state: CreateSessionState, workId: WorkId, delta: Int): CreateSessionState {
        val order = state.workOrder.ifEmpty { state.works.map { it.id } }.toMutableList()
        val index = order.indexOf(workId)
        if (index < 0) return state
        val newIndex = (index + delta).coerceIn(0, order.lastIndex)
        if (newIndex == index) return state
        order.removeAt(index)
        order.add(newIndex, workId)
        return state.copy(workOrder = order)
    }

    fun toggleSeriesExpanded(state: CreateSessionState, workId: WorkId): CreateSessionState {
        val next = state.expandedSeriesIds.toMutableSet()
        if (!next.add(workId)) next.remove(workId)
        return state.copy(expandedSeriesIds = next)
    }

    fun addImported(state: CreateSessionState, book: ImportedEpub): CreateSessionState {
        if (state.importedBooks.any { it.book.contentFingerprint == book.book.contentFingerprint }) {
            return state.copy(lastError = "That EPUB is already imported (same content fingerprint).")
        }
        val likelyDup = state.importedBooks.any {
            DuplicateDetection.likelyTitleCreatorMatch(
                it.book.title,
                it.book.creator,
                book.book.title,
                book.book.creator,
            )
        }
        val warnings = state.warnings.toMutableList()
        if (likelyDup) {
            warnings += "Likely duplicate import: “${book.book.title}”"
        }
        warnings += book.warnings
        val title = state.bookTitle.ifBlank {
            if (state.works.isEmpty() && state.importedBooks.isEmpty()) book.book.title else state.bookTitle
        }
        return state.copy(
            importedBooks = state.importedBooks + book,
            bookTitle = title,
            exportMode = ExportMode.COMBINED,
            phase = if (state.phase == JobPhase.DRAFT) JobPhase.READY else state.phase,
            statusMessage = "Imported “${book.book.title}”. Combined Book mode required for imports.",
            warnings = warnings.distinct(),
            lastError = null,
            collectionId = state.collectionId ?: CollectionId(UUID.randomUUID().toString()),
        )
    }

    fun removeImported(state: CreateSessionState, bookId: String): CreateSessionState =
        state.copy(importedBooks = state.importedBooks.filter { it.book.id.value != bookId })

    fun loadSaved(
        state: CreateSessionState,
        draft: CollectionDraft,
        authors: List<Author>,
        works: List<Work>,
        chapters: List<Chapter>,
        sourceUrls: List<String>,
    ): CreateSessionState {
        val catalog = catalogViewFromResolved(authors, works, chapters)
        val selected = draft.nodes
            .filter {
                (it.kind == TocNodeKind.SERIES || it.kind == TocNodeKind.STANDALONE) && it.selected
            }
            .map { WorkId(it.referencedEntityId) }
            .toSet()
            .ifEmpty { works.map { it.id }.toSet() }
        val deselected = draft.nodes
            .filter { it.kind == TocNodeKind.CHAPTER && !it.selected }
            .map { ChapterId(it.referencedEntityId) }
            .toSet()
        val order = TocOperations.workOrderFromDraft(draft).ifEmpty { works.map { it.id } }
        return state.copy(
            urlInput = sourceUrls.joinToString("\n"),
            phase = JobPhase.READY,
            catalogView = catalog,
            filteredCatalog = catalog,
            authors = authors,
            works = works,
            chapters = chapters,
            selectedWorkIds = selected,
            deselectedChapterIds = deselected,
            workOrder = order,
            bookTitle = draft.title,
            bookSubtitle = draft.subtitle.orEmpty(),
            curator = draft.curator.orEmpty(),
            exportMode = draft.exportMode,
            epubStyle = draft.epubStyle,
            accentColorArgb = draft.accentColorArgb,
            collectionId = draft.id,
            importedBooks = emptyList(),
            statusMessage = "Loaded “${draft.title}” from Library.",
            lastError = null,
            searchQuery = "",
            sort = CatalogSort.SITE_ORDER,
            refreshMessages = emptyList(),
            newChapterIds = emptySet(),
            changedChapterIds = emptySet(),
        )
    }
}

class CreatePipeline(
    private val source: LiteroticaSource,
    private val epubWriter: EpubWriter,
    private val epubImporter: EpubImporter = EpubImporter(),
    private val epubMerger: EpubMerger = EpubMerger(epubWriter, epubImporter),
    private val collections: CollectionStore,
    private val chapterCache: ChapterCachePort,
    private val exportHistory: ExportHistoryPort,
) {
    suspend fun analyze(
        state: CreateSessionState,
        onProgress: (completed: Int, total: Int, message: String) -> Unit,
    ): CreateSessionState {
        val raw = state.urlInput.trim()
        if (raw.isEmpty()) {
            return state.copy(lastError = "Enter at least one URL.")
        }
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return try {
            val result = source.resolveUrls(lines) { progress ->
                onProgress(progress.completed, progress.total, progress.message)
            }
            val isRefresh = state.works.isNotEmpty() && state.collectionId != null
            if (isRefresh) {
                CreateSessionLogic.applyRefresh(state, result)
            } else {
                CreateSessionLogic.applyResolve(state, result)
            }
        } catch (e: Exception) {
            state.copy(
                phase = JobPhase.FAILED,
                lastError = e.message ?: e.toString(),
                statusMessage = "Analysis failed.",
            )
        }
    }

    fun importEpubBytes(state: CreateSessionState, bytes: ByteArray, documentUri: String): CreateSessionState {
        return try {
            val imported = epubImporter.import(bytes, documentUri)
            CreateSessionLogic.addImported(state, imported)
        } catch (e: Exception) {
            state.copy(lastError = e.message ?: e.toString(), statusMessage = "Import failed.")
        }
    }

    suspend fun download(
        state: CreateSessionState,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CreateSessionState {
        if (state.selectedWorkIds.isEmpty() && state.importedBooks.isEmpty()) {
            return state.copy(lastError = "Select at least one work or import an EPUB.")
        }
        if (state.works.isEmpty() && state.importedBooks.isNotEmpty()) {
            return finishExport(
                state = state.copy(collectionId = state.collectionId ?: CollectionId(UUID.randomUUID().toString())),
                chapters = emptyList(),
                contents = emptyMap(),
                incomplete = false,
            )
        }
        if (state.works.isEmpty()) return state
        val chapters = CreateSessionLogic.selectedChapters(state)
        if (chapters.isEmpty() && state.importedBooks.isEmpty()) {
            return state.copy(lastError = "No chapters selected.")
        }
        if (chapters.isEmpty()) {
            return finishExport(
                state = state.copy(collectionId = state.collectionId ?: CollectionId(UUID.randomUUID().toString())),
                chapters = emptyList(),
                contents = emptyMap(),
                incomplete = false,
            )
        }
        val outcome = downloadSelectedChapters(
            chapters = chapters,
            concurrency = state.concurrency,
            loadCached = { url -> chapterCache.getByUrl(url) },
            fetch = { url -> source.fetchChapterContent(url) },
            onProgress = onProgress,
        )
        return if (outcome.failedUrls.isNotEmpty()) {
            state.copy(
                phase = JobPhase.WAITING_FOR_DECISION,
                statusMessage = "${outcome.failedUrls.size} chapter(s) failed.",
                lastError = formatDownloadFailures(outcome.failures),
                failedChapterUrls = outcome.failedUrls,
                pendingContents = outcome.contents,
                pendingChapters = chapters,
                collectionId = state.collectionId ?: CollectionId(UUID.randomUUID().toString()),
                progressCompleted = chapters.size,
                progressTotal = chapters.size,
            )
        } else {
            finishExport(
                state = state.copy(
                    collectionId = state.collectionId ?: CollectionId(UUID.randomUUID().toString()),
                    pendingContents = outcome.contents,
                    pendingChapters = chapters,
                ),
                chapters = chapters,
                contents = outcome.contents,
                incomplete = false,
            )
        }
    }

    suspend fun retryMissing(
        state: CreateSessionState,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CreateSessionState {
        if (state.phase != JobPhase.WAITING_FOR_DECISION) return state
        val failed = state.failedChapterUrls
        val chapters = state.pendingChapters.filter { it.canonicalUrl in failed.toSet() }
        val retry = downloadSelectedChapters(
            chapters = chapters,
            concurrency = state.concurrency,
            loadCached = { null },
            fetch = { url -> source.fetchChapterContent(url) },
            onProgress = onProgress,
        )
        val contents = state.pendingContents.toMutableMap()
        contents.putAll(retry.contents)
        return if (retry.failedUrls.isNotEmpty()) {
            state.copy(
                phase = JobPhase.WAITING_FOR_DECISION,
                failedChapterUrls = retry.failedUrls,
                pendingContents = contents,
                lastError = formatDownloadFailures(retry.failures),
                statusMessage = "${retry.failedUrls.size} still failed.",
            )
        } else {
            finishExport(
                state = state,
                chapters = state.pendingChapters,
                contents = contents,
                incomplete = false,
            )
        }
    }

    suspend fun exportWithoutMissing(state: CreateSessionState): CreateSessionState {
        if (state.phase != JobPhase.WAITING_FOR_DECISION) return state
        val chapters = state.pendingChapters.filter { it.id in state.pendingContents.keys }
        return finishExport(
            state = state,
            chapters = chapters,
            contents = state.pendingContents,
            incomplete = true,
        )
    }

    private fun formatDownloadFailures(failures: List<ChapterDownloadFailure>): String {
        if (failures.isEmpty()) return ""
        val byReason = failures.groupBy { it.message }
        val summary = byReason.entries
            .sortedByDescending { it.value.size }
            .joinToString("\n") { (reason, items) ->
                "${items.size}× $reason"
            }
        val samples = failures.take(8).joinToString("\n") { f ->
            "${f.url}\n  → ${f.message}"
        }
        val more = if (failures.size > 8) "\n…and ${failures.size - 8} more" else ""
        return "Failure summary:\n$summary\n\n$samples$more"
    }

    suspend fun saveCollection(state: CreateSessionState): CreateSessionState {
        if (state.works.isEmpty() && state.importedBooks.isEmpty()) return state
        val draft = CreateSessionLogic.buildDraft(state)
        collections.save(
            draft = draft,
            authors = state.authors,
            works = state.works,
            chapters = state.chapters,
            sourceUrls = state.urlInput.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        return state.copy(
            collectionId = draft.id,
            statusMessage = "Collection saved to Library.",
        )
    }

    private suspend fun finishExport(
        state: CreateSessionState,
        chapters: List<Chapter>,
        contents: Map<ChapterId, ChapterContent>,
        incomplete: Boolean,
    ): CreateSessionState {
        val draft = CreateSessionLogic.buildDraft(state)
        val works = state.works.filter { it.id in state.selectedWorkIds }
        val authors = state.authors

        if (contents.isNotEmpty()) {
            val urlById = chapters.associate { it.id to it.canonicalUrl }
            chapterCache.putAll(contents, urlById)
        }
        collections.save(
            draft = draft,
            authors = authors,
            works = works,
            chapters = if (chapters.isNotEmpty()) chapters else state.chapters.filter { it.workId in state.selectedWorkIds },
            sourceUrls = state.urlInput.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )

        val snapshot = TocOperations.toExportSnapshot(
            draft = draft,
            authors = authors,
            works = works,
            chapters = chapters.ifEmpty {
                state.chapters.filter { ch ->
                    ch.workId in state.selectedWorkIds && ch.id !in state.deselectedChapterIds
                }
            },
            contents = contents,
        ).copy(
            title = draft.title,
            subtitle = draft.subtitle,
            curator = draft.curator,
            exportMode = draft.exportMode,
            epubStyle = draft.epubStyle,
            accentColorArgb = draft.accentColorArgb,
        )

        val filenameOverride = state.outputFilename.trim().takeIf { it.isNotEmpty() }

        return if (draft.exportMode == ExportMode.SEPARATE && state.importedBooks.isEmpty()) {
            val results = epubWriter.writeSeparate(EpubWriteRequest(snapshot)).map { result ->
                if (filenameOverride != null && resultsWouldBeSingle(snapshot)) {
                    result.copy(filename = FilenameSanitizer.sanitize(filenameOverride))
                } else {
                    result
                }
            }
            val bad = results.filter { !it.validation.ok }
            if (bad.isNotEmpty()) {
                state.copy(
                    phase = JobPhase.FAILED,
                    lastError = bad.joinToString("\n") { r ->
                        "${r.filename}: ${r.validation.issues.joinToString()}"
                    },
                    statusMessage = "EPUB validation failed.",
                    collectionId = draft.id,
                )
            } else {
                exportHistory.record(draft.id, draft.title, results.joinToString { it.filename }, incomplete)
                state.copy(
                    phase = JobPhase.COMPLETE,
                    statusMessage = "Ready: ${results.size} EPUB(s)${if (incomplete) " (incomplete)" else ""}",
                    exportResults = results,
                    exportResult = results.firstOrNull(),
                    failedChapterUrls = emptyList(),
                    collectionId = draft.id,
                    pendingContents = emptyMap(),
                    pendingChapters = emptyList(),
                )
            }
        } else {
            val result = if (state.importedBooks.isNotEmpty()) {
                epubMerger.merge(snapshot, state.importedBooks)
            } else {
                epubWriter.write(EpubWriteRequest(snapshot))
            }.let { raw ->
                if (filenameOverride != null) {
                    raw.copy(filename = FilenameSanitizer.sanitize(filenameOverride))
                } else {
                    raw
                }
            }
            if (!result.validation.ok) {
                state.copy(
                    phase = JobPhase.FAILED,
                    lastError = result.validation.issues.joinToString("\n"),
                    statusMessage = "EPUB validation failed.",
                    collectionId = draft.id,
                )
            } else {
                exportHistory.record(draft.id, draft.title, result.filename, incomplete)
                state.copy(
                    phase = JobPhase.COMPLETE,
                    statusMessage = "Ready: ${result.filename} (${result.bytes.size} bytes)${if (incomplete) " — incomplete export" else ""}",
                    exportResult = result,
                    exportResults = listOf(result),
                    failedChapterUrls = emptyList(),
                    collectionId = draft.id,
                    pendingContents = emptyMap(),
                    pendingChapters = emptyList(),
                )
            }
        }
    }

    private fun resultsWouldBeSingle(snapshot: com.storyweaver.core.model.ExportSnapshot): Boolean =
        snapshot.selectedWorks.size == 1
}
