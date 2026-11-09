package com.storyweaver.feature.markdown

import com.storyweaver.epub.ImportedEpub
import com.storyweaver.epub.MarkdownResult
import com.storyweaver.epub.MarkdownSection

enum class MarkdownPhase { IDLE, LOADING, EXPORTING }

data class MarkdownUiState(
    val epub: ImportedEpub? = null,
    val sourceName: String? = null,
    val outline: List<MarkdownSection> = emptyList(),
    val selected: Set<String> = emptySet(),
    val phase: MarkdownPhase = MarkdownPhase.IDLE,
    val result: MarkdownResult? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = phase != MarkdownPhase.IDLE
    val canExportSelection: Boolean get() = epub != null && selected.isNotEmpty() && !busy
}

/** Pure selection rules, kept out of the ViewModel so they are unit-testable. */
object MarkdownSelection {
    /** Ids of [id] and every entry nested under it. */
    fun subtree(outline: List<MarkdownSection>, id: String): Set<String> {
        val children = outline.groupBy { it.parentId }
        val out = mutableSetOf(id)
        fun walk(parent: String) {
            children[parent].orEmpty().forEach {
                out += it.id
                walk(it.id)
            }
        }
        walk(id)
        return out
    }

    /**
     * Checking an entry checks everything under it (a series brings its chapters);
     * unchecking clears it, its children, and any ancestors that were fully checked.
     */
    fun toggle(outline: List<MarkdownSection>, selected: Set<String>, id: String): Set<String> {
        val subtree = subtree(outline, id)
        if (id !in selected) return selected + subtree
        val byId = outline.associateBy { it.id }
        val ancestors = generateSequence(byId[id]?.parentId) { byId[it]?.parentId }.toSet()
        return selected - subtree - ancestors
    }
}
