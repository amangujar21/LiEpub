package com.storyweaver.feature.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.storyweaver.core.model.Author
import com.storyweaver.core.model.CatalogSort
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionDraft
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.JobPhase
import com.storyweaver.core.model.Work
import com.storyweaver.core.model.WorkId
import com.storyweaver.epub.EpubImporter
import com.storyweaver.epub.EpubMerger
import com.storyweaver.epub.EpubWriter
import com.storyweaver.feature.create.session.ChapterCachePort
import com.storyweaver.feature.create.session.CollectionStore
import com.storyweaver.feature.create.session.CreatePipeline
import com.storyweaver.feature.create.session.CreateSessionLogic
import com.storyweaver.feature.create.session.CreateSessionState
import com.storyweaver.feature.create.session.ExportHistoryPort
import com.storyweaver.source.literotica.LiteroticaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI alias for session state (stable name for Compose). */
typealias CreateUiState = CreateSessionState

class CreateViewModel(
    private val pipeline: CreatePipeline,
    initialConcurrency: Int = 6,
    initialStyle: EpubStyle = EpubStyle.MODERN,
) : ViewModel() {

    private val _state = MutableStateFlow(
        CreateSessionState(concurrency = initialConcurrency, epubStyle = initialStyle),
    )
    val state: StateFlow<CreateSessionState> = _state.asStateFlow()

    fun onUrlChange(value: String) {
        _state.update { it.copy(urlInput = value, lastError = null) }
    }

    fun onSearchChange(value: String) {
        _state.update { CreateSessionLogic.withSearch(it, value) }
    }

    fun onSortChange(sort: CatalogSort) {
        _state.update { CreateSessionLogic.withSort(it, sort) }
    }

    fun onTitleChange(v: String) = _state.update { it.copy(bookTitle = v) }
    fun onSubtitleChange(v: String) = _state.update { it.copy(bookSubtitle = v) }
    fun onCuratorChange(v: String) = _state.update { it.copy(curator = v) }
    fun onFilenameChange(v: String) = _state.update { it.copy(outputFilename = v) }
    fun onAccentChange(argb: Long) = _state.update { it.copy(accentColorArgb = argb) }
    fun onExportModeChange(mode: ExportMode) = _state.update {
        if (it.importedBooks.isNotEmpty() && mode == ExportMode.SEPARATE) {
            it.copy(
                exportMode = ExportMode.COMBINED,
                lastError = "Imported EPUBs require Combined Book mode.",
            )
        } else {
            it.copy(exportMode = mode)
        }
    }
    fun onStyleChange(style: EpubStyle) = _state.update { it.copy(epubStyle = style) }
    fun setConcurrency(n: Int) = _state.update { it.copy(concurrency = n.coerceIn(1, 6)) }

    fun analyze() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = JobPhase.ANALYZING,
                    statusMessage = "Analyzing…",
                    lastError = null,
                    exportResult = null,
                    exportResults = emptyList(),
                    failedChapterUrls = emptyList(),
                )
            }
            val next = pipeline.analyze(_state.value) { completed, total, message ->
                _state.update {
                    it.copy(
                        progressCompleted = completed,
                        progressTotal = total,
                        statusMessage = message,
                    )
                }
            }
            _state.value = next
        }
    }

    fun toggleWork(workId: WorkId) {
        _state.update { state ->
            val next = state.selectedWorkIds.toMutableSet()
            if (!next.add(workId)) next.remove(workId)
            state.copy(selectedWorkIds = next)
        }
    }

    fun toggleChapter(chapterId: ChapterId) {
        _state.update { CreateSessionLogic.toggleChapter(it, chapterId) }
    }

    fun toggleSeriesExpanded(workId: WorkId) {
        _state.update { CreateSessionLogic.toggleSeriesExpanded(it, workId) }
    }

    fun moveWork(workId: WorkId, delta: Int) {
        _state.update { CreateSessionLogic.moveWork(it, workId, delta) }
    }

    fun selectAll() {
        val catalog = _state.value.catalogView ?: return
        val all = catalog.series.map { it.work.id } + catalog.standalones.map { it.work.id }
        _state.update { it.copy(selectedWorkIds = all.toSet()) }
    }

    fun selectAllSeries() {
        val catalog = _state.value.catalogView ?: return
        _state.update { it.copy(selectedWorkIds = catalog.series.map { s -> s.work.id }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedWorkIds = emptySet()) }
    }

    fun importEpub(bytes: ByteArray, documentUri: String) {
        _state.update { pipeline.importEpubBytes(it, bytes, documentUri) }
    }

    fun removeImported(bookId: String) {
        _state.update { CreateSessionLogic.removeImported(it, bookId) }
    }

    fun downloadAndExport(allowIncomplete: Boolean = false) {
        viewModelScope.launch {
            val start = _state.value
            if (start.selectedWorkIds.isEmpty() && start.importedBooks.isEmpty()) {
                _state.update { it.copy(lastError = "Select at least one work or import an EPUB.") }
                return@launch
            }
            _state.update {
                it.copy(
                    phase = JobPhase.DOWNLOADING,
                    progressCompleted = 0,
                    progressTotal = CreateSessionLogic.selectedChapters(it).size.coerceAtLeast(1),
                    statusMessage = "Downloading…",
                    lastError = null,
                    exportResult = null,
                    exportResults = emptyList(),
                )
            }
            try {
                if (allowIncomplete && start.phase == JobPhase.WAITING_FOR_DECISION) {
                    _state.update { it.copy(phase = JobPhase.BUILDING, statusMessage = "Building EPUB…") }
                    _state.value = pipeline.exportWithoutMissing(start)
                } else {
                    _state.value = pipeline.download(_state.value) { done, total ->
                        _state.update {
                            it.copy(
                                progressCompleted = done,
                                progressTotal = total,
                                statusMessage = "Downloading $done / $total…",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = JobPhase.FAILED,
                        lastError = e.message ?: e.toString(),
                        statusMessage = "Export failed.",
                    )
                }
            }
        }
    }

    fun retryMissing() {
        viewModelScope.launch {
            val start = _state.value
            if (start.phase != JobPhase.WAITING_FOR_DECISION) return@launch
            _state.update {
                it.copy(
                    phase = JobPhase.DOWNLOADING,
                    statusMessage = "Retrying missing…",
                    progressCompleted = 0,
                    progressTotal = start.failedChapterUrls.size,
                )
            }
            try {
                _state.value = pipeline.retryMissing(start) { done, total ->
                    _state.update { it.copy(progressCompleted = done, progressTotal = total) }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = JobPhase.FAILED,
                        lastError = e.message ?: e.toString(),
                        statusMessage = "Export failed.",
                    )
                }
            }
        }
    }

    fun exportWithoutMissing() {
        viewModelScope.launch {
            val start = _state.value
            if (start.phase != JobPhase.WAITING_FOR_DECISION) return@launch
            _state.update { it.copy(phase = JobPhase.BUILDING, statusMessage = "Building EPUB…") }
            try {
                _state.value = pipeline.exportWithoutMissing(start)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = JobPhase.FAILED,
                        lastError = e.message ?: e.toString(),
                        statusMessage = "Export failed.",
                    )
                }
            }
        }
    }

    fun saveCollection() {
        viewModelScope.launch {
            _state.value = pipeline.saveCollection(_state.value)
        }
    }

    fun loadSaved(
        draft: CollectionDraft,
        authors: List<Author>,
        works: List<Work>,
        chapters: List<Chapter>,
        sourceUrls: List<String>,
    ) {
        _state.update {
            CreateSessionLogic.loadSaved(it, draft, authors, works, chapters, sourceUrls)
        }
    }

    companion object {
        fun factory(
            source: LiteroticaSource,
            epubWriter: EpubWriter = EpubWriter(),
            epubImporter: EpubImporter = EpubImporter(),
            epubMerger: EpubMerger = EpubMerger(epubWriter, epubImporter),
            collections: CollectionStore,
            chapterCache: ChapterCachePort,
            exportHistory: ExportHistoryPort,
            concurrency: Int = 4,
            style: EpubStyle = EpubStyle.MODERN,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val pipeline = CreatePipeline(
                        source = source,
                        epubWriter = epubWriter,
                        epubImporter = epubImporter,
                        epubMerger = epubMerger,
                        collections = collections,
                        chapterCache = chapterCache,
                        exportHistory = exportHistory,
                    )
                    return CreateViewModel(
                        pipeline = pipeline,
                        initialConcurrency = concurrency,
                        initialStyle = style,
                    ) as T
                }
            }
    }
}
