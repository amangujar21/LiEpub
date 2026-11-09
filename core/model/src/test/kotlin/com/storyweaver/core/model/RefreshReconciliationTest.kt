package com.storyweaver.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefreshReconciliationTest {
    private val author = Author(
        id = AuthorId("a1"),
        siteUsername = "a",
        displayName = "Author",
        worksUrl = "https://example.com/a",
    )
    private val work = Work(
        id = WorkId("series-1"),
        authorId = author.id,
        title = "Series",
        canonicalUrl = "https://example.com/series/1",
        type = WorkType.SERIES,
    )
    private val ch1 = Chapter(
        id = ChapterId("c1"),
        workId = work.id,
        sitePartId = "1",
        title = "Part 1",
        orderKey = OrderKey.of(1),
        canonicalUrl = "https://example.com/s/part-1",
        revisionFingerprint = "fp1",
    )
    private val ch2 = Chapter(
        id = ChapterId("c2"),
        workId = work.id,
        sitePartId = "2",
        title = "Part 2",
        orderKey = OrderKey.of(2),
        canonicalUrl = "https://example.com/s/part-2",
        revisionFingerprint = "fp2",
    )

    @Test
    fun `detects new and unchanged chapters`() {
        val result = RefreshReconciliation.reconcile(
            previousAuthors = listOf(author),
            previousWorks = listOf(work),
            previousChapters = listOf(ch1),
            nextAuthors = listOf(author),
            nextWorks = listOf(work),
            nextChapters = listOf(ch1, ch2),
        )
        assertThat(result.newChapterIds).containsExactly(ch2.id)
        assertThat(result.changedChapterIds).isEmpty()
        assertThat(result.chapters).hasSize(2)
        assertThat(result.messages.any { it.contains("new") }).isTrue()
    }

    @Test
    fun `detects fingerprint change without dropping chapter`() {
        val updated = ch1.copy(revisionFingerprint = "fp1-new")
        val result = RefreshReconciliation.reconcile(
            previousAuthors = listOf(author),
            previousWorks = listOf(work),
            previousChapters = listOf(ch1),
            nextAuthors = listOf(author),
            nextWorks = listOf(work),
            nextChapters = listOf(updated),
        )
        assertThat(result.changedChapterIds).containsExactly(ch1.id)
        assertThat(result.chapters).hasSize(1)
    }

    @Test
    fun `keeps removed source chapters`() {
        val result = RefreshReconciliation.reconcile(
            previousAuthors = listOf(author),
            previousWorks = listOf(work),
            previousChapters = listOf(ch1, ch2),
            nextAuthors = listOf(author),
            nextWorks = listOf(work),
            nextChapters = listOf(ch1),
        )
        assertThat(result.removedChapterIds).containsExactly(ch2.id)
        assertThat(result.chapters.map { it.id }).containsExactly(ch1.id, ch2.id)
    }
}
