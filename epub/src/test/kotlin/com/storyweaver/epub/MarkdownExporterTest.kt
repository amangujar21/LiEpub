package com.storyweaver.epub

import com.google.common.truth.Truth.assertThat
import com.storyweaver.core.model.Author
import com.storyweaver.core.model.AuthorId
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionId
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.ExportSnapshot
import com.storyweaver.core.model.ImportedBook
import com.storyweaver.core.model.ImportedBookId
import com.storyweaver.core.model.OrderKey
import com.storyweaver.core.model.Work
import com.storyweaver.core.model.WorkId
import com.storyweaver.core.model.WorkType
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class MarkdownExporterTest {
    private val exporter = MarkdownExporter()

    /** Two authors: one series of two chapters, plus a standalone. */
    private fun builtBook(): ImportedEpub {
        val a1 = Author(AuthorId("a1"), "shiprat", "shiprat", "https://www.literotica.com/authors/shiprat/works/stories")
        val a2 = Author(AuthorId("a2"), "other", "Other Writer", "https://www.literotica.com/authors/other/works/stories")
        val series = Work(WorkId("w1"), a1.id, "Ocean Series", canonicalUrl = "https://x/se/1", type = WorkType.SERIES)
        val single = Work(WorkId("w2"), a2.id, "One Night", canonicalUrl = "https://x/s/one", type = WorkType.STANDALONE)
        val c1 = Chapter(ChapterId("c1"), series.id, "1", "Part 1", OrderKey.of(1), "https://x/s/p1")
        val c2 = Chapter(ChapterId("c2"), series.id, "2", "Part 2", OrderKey.of(2), "https://x/s/p2")
        val c3 = Chapter(ChapterId("c3"), single.id, null, "One Night", OrderKey.of(1), "https://x/s/one")
        val contents = mapOf(
            c1.id to ChapterContent(c1.id, "Part 1", "shiprat", "<p>She said <em>hello</em> to <strong>him</strong>.</p><hr/><p>1984. That year, don't_stop.</p>", "h", null),
            c2.id to ChapterContent(c2.id, "Part 2", "shiprat", "<p>Second part<br/>new line.</p><blockquote><p>Quoted.</p></blockquote>", "h", null),
            c3.id to ChapterContent(c3.id, "One Night", "Other Writer", "<p>A single story.</p>", "h", null),
        )
        val snapshot = ExportSnapshot(
            collectionId = CollectionId("col"),
            title = "Collected",
            subtitle = null,
            curator = "Me",
            exportMode = ExportMode.COMBINED,
            epubStyle = EpubStyle.MODERN,
            accentColorArgb = 0xFF7A2E3D,
            selectedChapters = listOf(c1, c2, c3),
            selectedWorks = listOf(series, single),
            authors = listOf(a1, a2),
            chapterContents = contents,
        )
        val bytes = EpubWriter().write(EpubWriteRequest(snapshot, identifier = "urn:uuid:md")).bytes
        return EpubImporter().import(bytes, "test://collected.epub")
    }

    @Test
    fun `outline mirrors the author then series then chapter TOC`() {
        val outline = exporter.outline(builtBook())
        assertThat(outline.map { "  ".repeat(it.depth) + it.title }).containsExactly(
            "Cover",
            "shiprat",
            "  Ocean Series",
            "    Part 1",
            "    Part 2",
            "Other Writer",
            "  One Night",
        ).inOrder()
    }

    @Test
    fun `single chapter exports only that chapter as markdown`() {
        val book = builtBook()
        val part1 = exporter.outline(book).first { it.title == "Part 1" }
        val result = exporter.export(book, setOf(part1.id))

        assertThat(result.filename).isEqualTo("Collected - Part 1.md")
        assertThat(result.markdown).startsWith("# Part 1\n")
        assertThat(result.markdown).contains("She said *hello* to **him**.")
        assertThat(result.markdown).contains("* * *")
        assertThat(result.markdown).contains("1984\\. That year, don't\\_stop.")
        assertThat(result.markdown).doesNotContain("Part 2")
        assertThat(result.markdown).doesNotContain("<")
    }

    @Test
    fun `series entry exports all of its chapters, author entry everything by them`() {
        val book = builtBook()
        val outline = exporter.outline(book)
        val series = exporter.export(book, setOf(outline.first { it.title == "Ocean Series" }.id)).markdown
        assertThat(series).contains("# Part 1")
        assertThat(series).contains("Second part  \nnew line.")
        assertThat(series).contains("> Quoted.")
        assertThat(series).doesNotContain("One Night")

        val author = exporter.export(book, setOf(outline.first { it.title == "Other Writer" }.id)).markdown
        assertThat(author).contains("A single story.")
        assertThat(author).doesNotContain("Part 1")
    }

    @Test
    fun `parent and child both selected exports the child once`() {
        val book = builtBook()
        val outline = exporter.outline(book)
        val ids = outline.filter { it.title == "Ocean Series" || it.title == "Part 2" }.map { it.id }.toSet()
        val md = exporter.export(book, ids).markdown
        assertThat(Regex("Second part").findAll(md).count()).isEqualTo(1)
    }

    @Test
    fun `whole book has a title header and every chapter`() {
        val result = exporter.export(builtBook(), null)
        assertThat(result.filename).isEqualTo("Collected.md")
        assertThat(result.markdown).startsWith("# Collected\n\n*by Me*")
        listOf("She said", "Second part", "A single story.").forEach { assertThat(result.markdown).contains(it) }
    }

    @Test
    fun `chapters sharing one file are sliced at their anchors`() {
        val html = """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>
            <h2 id="ch1">Chapter One</h2><p>First text.</p>
            <h2 id="ch2">Chapter Two</h2><p>Second text.</p>
            </body></html>
        """.trimIndent().toByteArray()
        val book = ImportedEpub(
            book = ImportedBook(ImportedBookId("b"), "u", null, "Shared", null, "en", "3.0", "fp"),
            resources = mapOf("OEBPS/all.xhtml" to html),
            spineHrefs = listOf("OEBPS/all.xhtml"),
            navToc = listOf(
                ImportedNavEntry("Chapter One", "OEBPS/all.xhtml#ch1"),
                ImportedNavEntry("Chapter Two", "OEBPS/all.xhtml#ch2"),
            ),
            opfPath = "OEBPS/content.opf",
        )
        val one = exporter.export(book, setOf("0")).markdown
        assertThat(one).contains("First text.")
        assertThat(one).doesNotContain("Second text.")
        val two = exporter.export(book, setOf("1")).markdown
        assertThat(two).startsWith("## Chapter Two")
        assertThat(two).doesNotContain("First text.")
    }

    @Test
    fun `combined epub nav lists each author with their works nested`() {
        val a = Author(AuthorId("a1"), "shiprat", "shiprat", "https://www.literotica.com/authors/shiprat/works/stories")
        val w = Work(WorkId("w1"), a.id, "Solo", canonicalUrl = "https://x/s/solo", type = WorkType.STANDALONE)
        val c = Chapter(ChapterId("c1"), w.id, null, "Solo", OrderKey.of(1), "https://x/s/solo")
        val snapshot = ExportSnapshot(
            CollectionId("c"), "Solo", null, null, ExportMode.COMBINED, EpubStyle.MODERN, 0xFF000000,
            listOf(c), listOf(w), listOf(a),
            mapOf(c.id to ChapterContent(c.id, "Solo", "shiprat", "<p>x</p>", "h", null)),
        )
        val bytes = EpubWriter().write(EpubWriteRequest(snapshot)).bytes
        val nav = ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            generateSequence { zis.nextEntry }.first { it.name == "OEBPS/nav.xhtml" }
            zis.readBytes().toString(Charsets.UTF_8)
        }
        // Even a single-author book groups its stories under the author's name.
        assertThat(nav.replace(Regex("\\s+"), "")).contains(">shiprat</a><ol><li><ahref=\"text/chapter_2.xhtml\">Solo</a>")
        assertThat(nav).doesNotContain("Home")
    }
}
