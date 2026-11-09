package com.storyweaver.feature.merge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.epub.EpubImporter
import com.storyweaver.epub.EpubMerger
import com.storyweaver.epub.MergeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picked file, read lazily so large books are only loaded off the main thread. */
class PickedFile(
    val uri: String,
    val displayName: String,
    val read: () -> ByteArray?,
)

class MergeViewModel(
    private val importer: EpubImporter,
    private val merger: EpubMerger,
    private var style: EpubStyle,
) : ViewModel() {
    private val _state = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = _state.asStateFlow()

    fun setStyle(value: EpubStyle) {
        style = value
    }

    fun addFiles(files: List<PickedFile>) {
        if (files.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(phase = MergePhase.IMPORTING, importProgress = 0 to files.size, error = null, message = null)
            }
            val problems = mutableListOf<String>()
            var added = 0
            files.forEachIndexed { index, file ->
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = file.read() ?: error("Could not read file")
                        MergeItem(
                            key = file.uri + "#" + System.nanoTime(),
                            epub = importer.import(bytes, file.uri),
                            sourceName = file.displayName,
                            sizeBytes = bytes.size.toLong(),
                        )
                    }
                }
                outcome.fold(
                    onSuccess = { item ->
                        _state.update { s ->
                            val (list, ok) = MergeLogic.add(s.items, item)
                            if (ok) added++ else problems += "${file.displayName}: already added"
                            s.copy(items = list, importProgress = index + 1 to files.size)
                        }
                    },
                    onFailure = { e ->
                        problems += "${file.displayName}: ${e.message ?: e.javaClass.simpleName}"
                        _state.update { it.copy(importProgress = index + 1 to files.size) }
                    },
                )
            }
            _state.update {
                it.copy(
                    phase = MergePhase.IDLE,
                    importProgress = null,
                    result = null,
                    message = if (added > 0) "Added $added book${if (added == 1) "" else "s"}." else null,
                    error = problems.takeIf { p -> p.isNotEmpty() }?.joinToString("\n"),
                )
            }
            refreshOutline()
        }
    }

    fun move(key: String, delta: Int) = editItems { MergeLogic.move(it, key, delta) }
    fun remove(key: String) = editItems { MergeLogic.remove(it, key) }
    fun sortByTitle() = editItems { MergeLogic.sortByTitle(it) }
    fun clear() = editItems { emptyList() }

    fun onTitleChange(v: String) = _state.update { it.copy(title = v, result = null) }
    fun onAuthorChange(v: String) = _state.update { it.copy(author = v, result = null) }
    fun onGenerateCoverChange(v: Boolean) {
        _state.update { it.copy(generateCover = v, result = null) }
        refreshOutline()
    }
    fun onTitlePagesChange(v: Boolean) = _state.update { it.copy(titlePagePerBook = v, result = null) }
    fun dismissMessages() = _state.update { it.copy(error = null, message = null) }

    /** Suggested title shown as a placeholder when the title field is blank. */
    fun suggestedTitle(s: MergeUiState): String = merger.defaultTitle(s.items.map { it.epub })
    fun suggestedAuthor(s: MergeUiState): String = merger.defaultCreator(s.items.map { it.epub })

    fun merge() {
        val s = _state.value
        if (!s.canMerge) return
        viewModelScope.launch {
            _state.update { it.copy(phase = MergePhase.MERGING, error = null, message = null, result = null) }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    merger.mergeBooks(
                        s.items.map { it.epub },
                        MergeOptions(
                            title = s.title.trim(),
                            creator = s.author.trim().ifEmpty { null },
                            generateCover = s.generateCover,
                            titlePagePerBook = s.titlePagePerBook,
                            epubStyle = style,
                        ),
                    )
                }
            }
            result.fold(
                onSuccess = { r ->
                    _state.update {
                        it.copy(
                            phase = MergePhase.DONE,
                            result = r,
                            message = buildString {
                                append("Merged ${s.items.size} books · ${r.bytes.size / 1024} KB")
                                if (!r.validation.ok) append(" · warnings: ${r.validation.issues.size}")
                            },
                            error = r.validation.issues.takeIf { i -> i.isNotEmpty() }?.take(5)?.joinToString("\n"),
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(phase = MergePhase.FAILED, error = "Merge failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                },
            )
        }
    }

    private fun editItems(transform: (List<MergeItem>) -> List<MergeItem>) {
        if (_state.value.busy) return
        _state.update { it.copy(items = transform(it.items), result = null, phase = MergePhase.IDLE) }
        refreshOutline()
    }

    private fun refreshOutline() {
        val s = _state.value
        val outline = merger.tocOutline(s.items.map { it.epub }, s.generateCover)
        _state.update { it.copy(outline = outline) }
    }

    companion object {
        fun factory(
            importer: EpubImporter,
            merger: EpubMerger,
            style: EpubStyle,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MergeViewModel(importer, merger, style) as T
        }
    }
}
