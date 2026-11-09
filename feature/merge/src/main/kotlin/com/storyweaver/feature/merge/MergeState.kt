package com.storyweaver.feature.merge

import com.storyweaver.epub.EpubWriteResult
import com.storyweaver.epub.ImportedEpub
import com.storyweaver.epub.ImportedNavEntry
import com.storyweaver.epub.TocOutlineEntry

/** One EPUB queued for merging. */
data class MergeItem(
    val key: String,
    val epub: ImportedEpub,
    val sourceName: String,
    val sizeBytes: Long,
) {
    val title: String get() = epub.book.title
    val creator: String? get() = epub.book.creator
    val tocEntryCount: Int get() = countEntries(epub.navToc)

    private fun countEntries(list: List<ImportedNavEntry>): Int = list.sumOf { 1 + countEntries(it.children) }
}

enum class MergePhase { IDLE, IMPORTING, MERGING, DONE, FAILED }

data class MergeUiState(
    val items: List<MergeItem> = emptyList(),
    /** Blank = derive from the books (see [com.storyweaver.epub.EpubMerger.defaultTitle]). */
    val title: String = "",
    /** Blank = join the books' authors. */
    val author: String = "",
    val generateCover: Boolean = true,
    val titlePagePerBook: Boolean = true,
    val phase: MergePhase = MergePhase.IDLE,
    val importProgress: Pair<Int, Int>? = null,
    val message: String? = null,
    val error: String? = null,
    val outline: List<TocOutlineEntry> = emptyList(),
    val result: EpubWriteResult? = null,
) {
    val busy: Boolean get() = phase == MergePhase.IMPORTING || phase == MergePhase.MERGING
    val canMerge: Boolean get() = items.isNotEmpty() && !busy
}

/** Pure list operations, kept separate from the ViewModel so they are unit-testable. */
object MergeLogic {
    /** Appends [item] unless the same file content is already queued. Returns the new list and whether it was added. */
    fun add(items: List<MergeItem>, item: MergeItem): Pair<List<MergeItem>, Boolean> {
        val fingerprint = item.epub.book.contentFingerprint
        if (items.any { it.epub.book.contentFingerprint == fingerprint }) return items to false
        return (items + item) to true
    }

    fun move(items: List<MergeItem>, key: String, delta: Int): List<MergeItem> {
        val from = items.indexOfFirst { it.key == key }
        if (from < 0) return items
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (to == from) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun remove(items: List<MergeItem>, key: String): List<MergeItem> = items.filterNot { it.key == key }

    /** Orders by title using natural number ordering ("Part 2" before "Part 10"). */
    fun sortByTitle(items: List<MergeItem>): List<MergeItem> =
        items.sortedWith { a, b -> naturalCompare(a.title, b.title) }

    fun naturalCompare(a: String, b: String): Int {
        val re = Regex("""\d+|\D+""")
        val pa = re.findAll(a.lowercase()).map { it.value }.toList()
        val pb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                    ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else {
                x.compareTo(y)
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}
