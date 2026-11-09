package com.storyweaver.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TocOperationsTest {
    private val author = Author(
        id = AuthorId("author-shiprat"),
        siteUsername = "shiprat",
        displayName = "shiprat",
        worksUrl = "https://www.literotica.com/authors/shiprat/works/stories",
    )

    private val seriesWork = Work(
        id = WorkId("series-1"),
        authorId = author.id,
        title = "Ocean Series",
        canonicalUrl = "https://www.literotica.com/series/se/1",
        type = WorkType.SERIES,
    )

    private val standaloneWork = Work(
        id = WorkId("standalone-story-a"),
        authorId = author.id,
        title = "Lone Story",
        canonicalUrl = "https://www.literotica.com/s/lone-story",
        type = WorkType.STANDALONE,
    )

    private val chapters = listOf(
        Chapter(
            id = ChapterId("c1"),
            workId = seriesWork.id,
            sitePartId = "1",
            title = "Part 1",
            orderKey = OrderKey.of(1),
            canonicalUrl = "https://www.literotica.com/s/part-1",
        ),
        Chapter(
            id = ChapterId("c2"),
            workId = seriesWork.id,
            sitePartId = "2",
            title = "Part 2",
            orderKey = OrderKey.of(2),
            canonicalUrl = "https://www.literotica.com/s/part-2",
        ),
        Chapter(
            id = ChapterId("chapter-lone-story"),
            workId = standaloneWork.id,
            sitePartId = null,
            title = "Lone Story",
            orderKey = OrderKey.of(1),
            canonicalUrl = "https://www.literotica.com/s/lone-story",
        ),
    )

    @Test
    fun `builds hierarchy for selected series`() {
        val catalog = AuthorCatalog(
            author = author,
            series = listOf(CatalogSeries(seriesWork, chapters.filter { it.workId == seriesWork.id })),
            standalones = emptyList(),
        )
        val draft = TocOperations.buildFromCatalog(
            collectionId = CollectionId("col-1"),
            catalog = catalog,
            selectedWorkIds = setOf(seriesWork.id),
        )
        val errors = TocOperations.validateHierarchy(draft.nodes)
        assertThat(errors).isEmpty()
        assertThat(draft.nodes.map { it.kind }).containsAtLeast(
            TocNodeKind.AUTHOR,
            TocNodeKind.SERIES,
            TocNodeKind.CHAPTER,
        )
        assertThat(draft.nodes.count { it.kind == TocNodeKind.CHAPTER }).isEqualTo(2)
    }

    @Test
    fun `buildFromResolved multi-author series and standalone`() {
        val author2 = author.copy(id = AuthorId("author-other"), siteUsername = "other", displayName = "other")
        val series2 = seriesWork.copy(id = WorkId("series-2"), authorId = author2.id, title = "Other Series")
        val ch2 = chapters[0].copy(id = ChapterId("c-other"), workId = series2.id)
        val draft = TocOperations.buildFromResolved(
            collectionId = CollectionId("col-m"),
            authors = listOf(author, author2),
            works = listOf(seriesWork, series2, standaloneWork),
            chapters = chapters + ch2,
            selectedWorkIds = setOf(seriesWork.id, series2.id, standaloneWork.id),
        )
        assertThat(TocOperations.validateHierarchy(draft.nodes)).isEmpty()
        assertThat(draft.nodes.count { it.kind == TocNodeKind.AUTHOR }).isEqualTo(2)
        assertThat(draft.nodes.count { it.kind == TocNodeKind.SERIES }).isEqualTo(2)
        assertThat(draft.nodes.count { it.kind == TocNodeKind.STANDALONE }).isEqualTo(1)
    }

    @Test
    fun `selectedChapterIds uses real standalone chapter ids`() {
        val draft = TocOperations.buildFromResolved(
            collectionId = CollectionId("col-1"),
            authors = listOf(author),
            works = listOf(seriesWork, standaloneWork),
            chapters = chapters,
            selectedWorkIds = setOf(seriesWork.id, standaloneWork.id),
        )
        val ids = TocOperations.selectedChapterIds(draft, chapters)
        assertThat(ids).containsExactly(
            ChapterId("c1"),
            ChapterId("c2"),
            ChapterId("chapter-lone-story"),
        )
        assertThat(ids.none { it.value.startsWith("chapter-for-") }).isTrue()
    }

    @Test
    fun `selectedChapterIds matches export snapshot chapter set`() {
        val draft = TocOperations.buildFromResolved(
            collectionId = CollectionId("col-1"),
            authors = listOf(author),
            works = listOf(seriesWork, standaloneWork),
            chapters = chapters,
            selectedWorkIds = setOf(seriesWork.id, standaloneWork.id),
        )
        val ids = TocOperations.selectedChapterIds(draft, chapters)
        val snapshot = TocOperations.toExportSnapshot(
            draft = draft,
            authors = listOf(author),
            works = listOf(seriesWork, standaloneWork),
            chapters = chapters,
            contents = emptyMap(),
        )
        assertThat(snapshot.selectedChapters.map { it.id }.toSet()).isEqualTo(ids)
    }

    @Test
    fun `export snapshot follows work order and chapter deselection`() {
        val draft = TocOperations.buildFromResolved(
            collectionId = CollectionId("col-order"),
            authors = listOf(author),
            works = listOf(seriesWork, standaloneWork),
            chapters = chapters,
            selectedWorkIds = setOf(seriesWork.id, standaloneWork.id),
            workOrder = listOf(standaloneWork.id, seriesWork.id),
            deselectedChapterIds = setOf(chapters.first { it.workId == seriesWork.id }.id),
        )
        val snapshot = TocOperations.toExportSnapshot(
            draft = draft,
            authors = listOf(author),
            works = listOf(seriesWork, standaloneWork),
            chapters = chapters,
            contents = emptyMap(),
        )
        assertThat(snapshot.selectedWorks.map { it.id })
            .containsExactly(standaloneWork.id, seriesWork.id)
            .inOrder()
        assertThat(snapshot.selectedChapters.map { it.id })
            .doesNotContain(chapters.first { it.workId == seriesWork.id }.id)
    }

    @Test
    fun `reorder sibling moves node among peers`() {
        val catalog = AuthorCatalog(
            author = author,
            series = listOf(CatalogSeries(seriesWork, chapters.filter { it.workId == seriesWork.id })),
            standalones = emptyList(),
        )
        val draft = TocOperations.buildFromCatalog(
            CollectionId("col-1"),
            catalog,
            setOf(seriesWork.id),
        )
        val chapterNodes = draft.nodes.filter { it.kind == TocNodeKind.CHAPTER }.sortedBy { it.position }
        val firstId = chapterNodes.first().id
        val reordered = TocOperations.reorderSibling(draft.nodes, firstId, 1)
        val after = reordered.filter { it.kind == TocNodeKind.CHAPTER }.sortedBy { it.position }
        assertThat(after.last().id).isEqualTo(firstId)
    }

    @Test
    fun `merge catalogs dedupes works by id`() {
        val catalog = AuthorCatalog(
            author = author,
            series = listOf(CatalogSeries(seriesWork, chapters.filter { it.workId == seriesWork.id })),
            standalones = emptyList(),
        )
        val merged = TocOperations.mergeCatalogs(listOf(catalog, catalog))
        assertThat(merged.series).hasSize(1)
        assertThat(merged.series.first().chapters).hasSize(2)
    }

    @Test
    fun `export snapshot is immutable selection`() {
        val catalog = AuthorCatalog(
            author = author,
            series = listOf(CatalogSeries(seriesWork, chapters.filter { it.workId == seriesWork.id })),
            standalones = emptyList(),
        )
        val draft = TocOperations.buildFromCatalog(
            CollectionId("col-1"),
            catalog,
            setOf(seriesWork.id),
        )
        val snapshot = TocOperations.toExportSnapshot(
            draft = draft,
            authors = listOf(author),
            works = listOf(seriesWork),
            chapters = chapters.filter { it.workId == seriesWork.id },
            contents = emptyMap(),
        )
        assertThat(snapshot.selectedChapters).hasSize(2)
        assertThat(snapshot.selectedWorks).containsExactly(seriesWork)
    }

    @Test
    fun `duplicate detection warnings for same url`() {
        val a = standaloneWork
        val b = standaloneWork.copy(id = WorkId("standalone-story-b"), title = "Copy")
        val warnings = DuplicateDetection.warningsFor(listOf(a, b))
        assertThat(warnings).isNotEmpty()
        assertThat(DuplicateDetection.duplicateWorkIds(listOf(a, b))).hasSize(2)
    }
}
