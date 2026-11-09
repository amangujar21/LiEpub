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
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubImportMergeTest {
    @Test
    fun coverPngIsValidSignature() {
        val png = CoverGenerator.generatePng("Title", "Sub", "Author")
        assertThat(png.size).isGreaterThan(100)
        assertThat(png[0]).isEqualTo(0x89.toByte())
        assertThat(png[1]).isEqualTo('P'.code.toByte())
    }

    @Test
    fun importsMinimalEpub3() {
        val bytes = minimalEpubBytes()
        val imported = EpubImporter().import(bytes, "content://test/book.epub")
        assertThat(imported.book.title).isEqualTo("Fixture Book")
        assertThat(imported.spineHrefs).isNotEmpty()
        assertThat(imported.book.contentFingerprint).isNotEmpty()
    }

    @Test
    fun rejectsZipSlip() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        try {
            EpubImporter().import(baos.toByteArray(), "uri")
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("ZIP-slip")
        }
    }

    @Test
    fun mergeDownloadedWithImportValidates() {
        val author = Author(AuthorId("a1"), "ship", "ship", "")
        val work = Work(WorkId("w1"), author.id, "Story", canonicalUrl = "https://example.com/s", type = WorkType.STANDALONE)
        val chapter = Chapter(ChapterId("c1"), work.id, null, "Story", OrderKey.of(1), "https://example.com/s")
        val content = ChapterContent(chapter.id, "Story", "ship", "<p>Hello.</p>", "h1", "h1")
        val snapshot = ExportSnapshot(
            collectionId = CollectionId("col"),
            title = "Combined",
            subtitle = null,
            curator = "ship",
            exportMode = ExportMode.COMBINED,
            epubStyle = com.storyweaver.core.model.EpubStyle.MODERN,
            accentColorArgb = 0xFF7A2E3D,
            selectedChapters = listOf(chapter),
            selectedWorks = listOf(work),
            authors = listOf(author),
            chapterContents = mapOf(chapter.id to content),
        )
        val imported = EpubImporter().import(minimalEpubBytes(), "content://imp")
        val result = EpubMerger().merge(snapshot, listOf(imported))
        assertThat(result.validation.ok).isTrue()
        assertThat(result.bytes.size).isGreaterThan(1000)
    }

    private fun minimalEpubBytes(): ByteArray {
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.US_ASCII),
            "META-INF/container.xml" to """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray(),
            "OEBPS/content.opf" to """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">urn:test:1</dc:identifier>
                <dc:title>Fixture Book</dc:title>
                <dc:language>en</dc:language>
                <dc:creator>Fixture Author</dc:creator>
                <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                </metadata>
                <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/></spine>
                </package>
            """.trimIndent().toByteArray(),
            "OEBPS/nav.xhtml" to """
                <?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body><nav epub:type="toc"><ol><li><a href="text/c1.xhtml">Chapter</a></li></ol></nav></body>
                </html>
            """.trimIndent().toByteArray(),
            "OEBPS/text/c1.xhtml" to """
                <?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><body><p>Hi</p></body></html>
            """.trimIndent().toByteArray(),
        )
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val mime = files.getValue("mimetype")
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            }
            zos.putNextEntry(entry)
            zos.write(mime)
            zos.closeEntry()
            files.forEach { (name, data) ->
                if (name == "mimetype") return@forEach
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
