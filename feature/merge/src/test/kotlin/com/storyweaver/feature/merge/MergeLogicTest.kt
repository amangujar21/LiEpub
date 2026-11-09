package com.storyweaver.feature.merge

import com.google.common.truth.Truth.assertThat
import com.storyweaver.core.model.ImportedBook
import com.storyweaver.core.model.ImportedBookId
import com.storyweaver.epub.ImportedEpub
import org.junit.Test

class MergeLogicTest {
    private fun item(title: String, fingerprint: String = title) = MergeItem(
        key = "k-$title-$fingerprint",
        epub = ImportedEpub(
            book = ImportedBook(
                id = ImportedBookId(title),
                documentUri = "content://$title",
                packageIdentifier = null,
                title = title,
                creator = null,
                language = null,
                formatVersion = "3.0",
                contentFingerprint = fingerprint,
            ),
            resources = emptyMap(),
            spineHrefs = emptyList(),
            navToc = emptyList(),
            opfPath = "content.opf",
        ),
        sourceName = "$title.epub",
        sizeBytes = 1,
    )

    @Test
    fun `add rejects the same file twice`() {
        val (once, ok1) = MergeLogic.add(emptyList(), item("A", "same"))
        val (twice, ok2) = MergeLogic.add(once, item("A copy", "same"))
        assertThat(ok1).isTrue()
        assertThat(ok2).isFalse()
        assertThat(twice).hasSize(1)
    }

    @Test
    fun `move reorders and clamps at the ends`() {
        val list = listOf(item("A"), item("B"), item("C"))
        assertThat(MergeLogic.move(list, list[2].key, -1).map { it.title }).containsExactly("A", "C", "B").inOrder()
        assertThat(MergeLogic.move(list, list[0].key, -1)).isEqualTo(list)
        assertThat(MergeLogic.move(list, list[0].key, 5).map { it.title }).containsExactly("B", "C", "A").inOrder()
    }

    @Test
    fun `sort uses natural number order`() {
        val list = listOf(item("Part 10"), item("Part 2"), item("part 1"), item("Epilogue"))
        assertThat(MergeLogic.sortByTitle(list).map { it.title })
            .containsExactly("Epilogue", "part 1", "Part 2", "Part 10").inOrder()
    }

    @Test
    fun `remove drops only the given item`() {
        val list = listOf(item("A"), item("B"))
        assertThat(MergeLogic.remove(list, list[0].key).map { it.title }).containsExactly("B")
    }
}
