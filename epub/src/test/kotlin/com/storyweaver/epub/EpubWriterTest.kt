package com.storyweaver.epub

import com.google.common.truth.Truth.assertThat
import com.storyweaver.core.model.Author
import com.storyweaver.core.model.AuthorId
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionId
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.ExportSnapshot
import com.storyweaver.core.model.OrderKey
import com.storyweaver.core.model.Work
import com.storyweaver.core.model.WorkId
import com.storyweaver.core.model.WorkType
import org.junit.Test
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

class EpubWriterTest {
    @Test
    fun `writes valid multi-chapter series epub`() {
        val author = Author(AuthorId("a1"), "shiprat", "shiprat", "https://www.literotica.com/authors/shiprat/works/stories")
        val work = Work(WorkId("w1"), author.id, "Ocean Series", canonicalUrl = "https://www.literotica.com/series/se/1", type = WorkType.SERIES)
        val c1 = Chapter(ChapterId("c1"), work.id, "1", "Part 1", OrderKey.of(1), "https://www.literotica.com/s/p1")
        val c2 = Chapter(ChapterId("c2"), work.id, "2", "Part 2", OrderKey.of(2), "https://www.literotica.com/s/p2")
        val contents = mapOf(
            c1.id to ChapterContent(c1.id, "Part 1", "shiprat", "<p>Hello one.</p>", "h1", "h1"),
            c2.id to ChapterContent(c2.id, "Part 2", "shiprat", "<p>Hello two.</p>", "h2", "h2"),
        )
        val snapshot = ExportSnapshot(
            collectionId = CollectionId("col"),
            title = "Ocean Series",
            subtitle = null,
            curator = "shiprat",
            exportMode = ExportMode.COMBINED,
            epubStyle = com.storyweaver.core.model.EpubStyle.MODERN,
            accentColorArgb = 0xFF7A2E3D,
            selectedChapters = listOf(c1, c2),
            selectedWorks = listOf(work),
            authors = listOf(author),
            chapterContents = contents,
        )

        val result = EpubWriter().write(EpubWriteRequest(snapshot, identifier = "urn:uuid:test-fixed"))
        assertThat(result.validation.ok).isTrue()
        assertThat(result.validation.issues).isEmpty()
        assertThat(result.filename).endsWith(".epub")

        val names = ZipInputStream(ByteArrayInputStream(result.bytes)).use { zis ->
            generateSequence { zis.nextEntry?.name }.toList()
        }
        assertThat(names.first()).isEqualTo("mimetype")
        assertThat(names).containsAtLeast(
            "META-INF/container.xml",
            "OEBPS/content.opf",
            "OEBPS/nav.xhtml",
            "OEBPS/toc.ncx",
        )
    }

    @Test
    fun `filename sanitizer strips illegal characters`() {
        assertThat(FilenameSanitizer.sanitize("A/B:C*.txt")).isEqualTo("ABC.txt.epub")
        assertThat(FilenameSanitizer.numberedCopy("book.epub", 2)).isEqualTo("book (2).epub")
    }

    @Test
    fun `structural validation does not surface unsupported XXE feature URIs as failures`() {
        // Android's DocumentBuilderFactory rejects Apache-only features; the feature URI
        // must never bubble out as the export error (user-reported: disallow-doctype-decl).
        val author = Author(AuthorId("a1"), "shiprat", "shiprat", "https://www.literotica.com/authors/shiprat/works/stories")
        val work = Work(WorkId("w1"), author.id, "One Off", canonicalUrl = "https://www.literotica.com/s/one", type = WorkType.STANDALONE)
        val c1 = Chapter(ChapterId("c1"), work.id, null, "One Off", OrderKey.of(1), "https://www.literotica.com/s/one")
        val snapshot = ExportSnapshot(
            collectionId = CollectionId("col"),
            title = "One Off",
            subtitle = null,
            curator = "shiprat",
            exportMode = ExportMode.COMBINED,
            epubStyle = com.storyweaver.core.model.EpubStyle.MODERN,
            accentColorArgb = 0xFF7A2E3D,
            selectedChapters = listOf(c1),
            selectedWorks = listOf(work),
            authors = listOf(author),
            chapterContents = mapOf(
                c1.id to ChapterContent(c1.id, "One Off", "shiprat", "<p>Body.</p>", "h", "h"),
            ),
        )
        val result = EpubWriter().write(EpubWriteRequest(snapshot, identifier = "urn:uuid:standalone-test"))
        assertThat(result.validation.ok).isTrue()
        assertThat(result.validation.issues.joinToString()).doesNotContain("disallow-doctype-decl")
    }
}
