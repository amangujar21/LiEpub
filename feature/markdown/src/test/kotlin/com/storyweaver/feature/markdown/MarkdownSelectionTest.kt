package com.storyweaver.feature.markdown

import com.google.common.truth.Truth.assertThat
import com.storyweaver.epub.MarkdownSection
import org.junit.Test

class MarkdownSelectionTest {
    private val outline = listOf(
        MarkdownSection("0", "shiprat", 0, null),
        MarkdownSection("0.0", "Ocean Series", 1, "0"),
        MarkdownSection("0.0.0", "Part 1", 2, "0.0"),
        MarkdownSection("0.0.1", "Part 2", 2, "0.0"),
        MarkdownSection("1", "Other", 0, null),
    )

    @Test
    fun `checking a series checks its chapters`() {
        assertThat(MarkdownSelection.toggle(outline, emptySet(), "0.0"))
            .containsExactly("0.0", "0.0.0", "0.0.1")
    }

    @Test
    fun `unchecking a chapter unchecks its fully selected ancestors`() {
        val all = MarkdownSelection.toggle(outline, emptySet(), "0")
        assertThat(MarkdownSelection.toggle(outline, all, "0.0.1")).containsExactly("0.0.0")
    }
}
