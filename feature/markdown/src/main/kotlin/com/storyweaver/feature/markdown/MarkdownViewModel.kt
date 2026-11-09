package com.storyweaver.feature.markdown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.storyweaver.epub.EpubImporter
import com.storyweaver.epub.MarkdownExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MarkdownViewModel(
    private val importer: EpubImporter,
    private val exporter: MarkdownExporter,
) : ViewModel() {
    private val _state = MutableStateFlow(MarkdownUiState())
    val state: StateFlow<MarkdownUiState> = _state.asStateFlow()

    /** Load a book from a picked file, a freshly created book, or a merge result. */
    fun load(name: String, uri: String, read: () -> ByteArray?) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(phase = MarkdownPhase.LOADING, error = null, result = null) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = read() ?: error("Could not read file")
                    val epub = importer.import(bytes, uri)
                    epub to exporter.outline(epub)
                }
            }
            outcome.fold(
                onSuccess = { (epub, outline) ->
                    _state.value = MarkdownUiState(epub = epub, sourceName = name, outline = outline)
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(phase = MarkdownPhase.IDLE, error = "$name: ${e.message ?: e.javaClass.simpleName}")
                    }
                },
            )
        }
    }

    fun toggle(id: String) = _state.update {
        it.copy(selected = MarkdownSelection.toggle(it.outline, it.selected, id), result = null)
    }

    fun selectAll() = _state.update { it.copy(selected = it.outline.map { s -> s.id }.toSet(), result = null) }
    fun selectNone() = _state.update { it.copy(selected = emptySet(), result = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** @param wholeBook export everything, ignoring the checkboxes. */
    fun export(wholeBook: Boolean) {
        val s = _state.value
        val epub = s.epub ?: return
        if (s.busy || (!wholeBook && s.selected.isEmpty())) return
        viewModelScope.launch {
            _state.update { it.copy(phase = MarkdownPhase.EXPORTING, error = null, result = null) }
            val outcome = withContext(Dispatchers.Default) {
                runCatching { exporter.export(epub, if (wholeBook) null else s.selected) }
            }
            _state.update {
                outcome.fold(
                    onSuccess = { r -> it.copy(phase = MarkdownPhase.IDLE, result = r) },
                    onFailure = { e ->
                        it.copy(phase = MarkdownPhase.IDLE, error = "Export failed: ${e.message ?: e.javaClass.simpleName}")
                    },
                )
            }
        }
    }

    companion object {
        fun factory(importer: EpubImporter, exporter: MarkdownExporter): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MarkdownViewModel(importer, exporter) as T
            }
    }
}
