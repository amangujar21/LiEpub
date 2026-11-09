package com.storyweaver.feature.create.session

import com.google.common.truth.Truth.assertThat
import com.storyweaver.core.model.Author
import com.storyweaver.core.model.AuthorId
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionDraft
import com.storyweaver.core.model.CollectionId
import com.storyweaver.core.model.JobPhase
import com.storyweaver.core.model.OrderKey
import com.storyweaver.core.model.ParseWarning
import com.storyweaver.core.model.TocNode
import com.storyweaver.core.model.TocNodeId
import com.storyweaver.core.model.TocNodeKind
import com.storyweaver.core.model.Work
import com.storyweaver.core.model.WorkId
import com.storyweaver.core.model.WorkType
import com.storyweaver.source.literotica.ResolveResult
import org.junit.Test

class CreateSessionLogicTest {
    private val author = Author(
        id = AuthorId("author-a"),
        siteUsername = "a",
        displayName = "Author A",
        worksUrl = "https://www.literotica.com/authors/a/works/stories",
    )
    private val work = Work(
        id = WorkId("standalone-x"),
        authorId = author.id,
        title = "Story X",
        canonicalUrl = "https://www.literotica.com/s/x",
        type = WorkType.STANDALONE,
    )
    private val chapter = Chapter(
        id = ChapterId("chapter-x"),
        workId = work.id,
        sitePartId = null,
        title = "Story X",
        orderKey = OrderKey.of(1),
        canonicalUrl = work.canonicalUrl,
    )

    @Test
    fun `moveWork reorders work list`() {
        val w1 = work
        val w2 = work.copy(id = WorkId("standalone-y"), title = "Y", canonicalUrl = "https://www.literotica.com/s/y")
        val state = CreateSessionState(
            works = listOf(w1, w2),
            workOrder = listOf(w1.id, w2.id),
        )
        val moved = CreateSessionLogic.moveWork(state, w1.id, 1)
        assertThat(moved.workOrder).containsExactly(w2.id, w1.id).inOrder()
    }

    @Test
    fun `toggleChapter tracks deselection`() {
        val state = CreateSessionState()
        val next = CreateSessionLogic.toggleChapter(state, chapter.id)
        assertThat(next.deselectedChapterIds).contains(chapter.id)
        val back = CreateSessionLogic.toggleChapter(next, chapter.id)
        assertThat(back.deselectedChapterIds).doesNotContain(chapter.id)
    }

    @Test
    fun `applyResolve sets ready state and selection`() {
        val result = ResolveResult(
            catalog = com.storyweaver.core.model.AuthorCatalog(author, emptyList(), emptyList()),
            authors = listOf(author),
            works = listOf(work),
            chapters = listOf(chapter),
            warnings = listOf(ParseWarning("W", "warn")),
            linkErrors = emptyMap(),
        )
        val next = CreateSessionLogic.applyResolve(CreateSessionState(urlInput = "u"), result)
        assertThat(next.phase).isEqualTo(JobPhase.READY)
        assertThat(next.works).hasSize(1)
        assertThat(next.catalogView).isNotNull()
        assertThat(next.selectedWorkIds).contains(work.id)
        assertThat(next.warnings).contains("warn")
    }

    @Test
    fun `loadSaved restores selection without synthetic chapter ids`() {
        val draft = CollectionDraft(
            id = CollectionId("col-1"),
            title = "Saved",
            nodes = listOf(
                TocNode(
                    id = TocNodeId("a1"),
                    parentId = null,
                    kind = TocNodeKind.AUTHOR,
                    referencedEntityId = author.id.value,
                    displayTitle = author.displayName,
                    position = 0,
                    selected = true,
                ),
                TocNode(
                    id = TocNodeId("s1"),
                    parentId = TocNodeId("a1"),
                    kind = TocNodeKind.STANDALONE,
                    referencedEntityId = work.id.value,
                    displayTitle = work.title,
                    position = 0,
                    selected = true,
                ),
            ),
        )
        val next = CreateSessionLogic.loadSaved(
            CreateSessionState(),
            draft,
            listOf(author),
            listOf(work),
            listOf(chapter),
            listOf(work.canonicalUrl),
        )
        assertThat(next.chapters.single().id).isEqualTo(chapter.id)
        assertThat(next.selectedWorkIds).containsExactly(work.id)
        assertThat(next.phase).isEqualTo(JobPhase.READY)
    }

    @Test
    fun `buildDraft uses session metadata`() {
        val state = CreateSessionState(
            authors = listOf(author),
            works = listOf(work),
            chapters = listOf(chapter),
            selectedWorkIds = setOf(work.id),
            bookTitle = "My Title",
            bookSubtitle = "Sub",
            curator = "Cur",
            collectionId = CollectionId("c1"),
        )
        val draft = CreateSessionLogic.buildDraft(state)
        assertThat(draft.title).isEqualTo("My Title")
        assertThat(draft.subtitle).isEqualTo("Sub")
        assertThat(draft.curator).isEqualTo("Cur")
    }
}
