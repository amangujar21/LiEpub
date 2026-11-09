package com.storyweaver.epub

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EpubMergeBooksTest {
    private val importer = EpubImporter()
    private val merger = EpubMerger()

    /** EPUB 3 book with a nested nav (Part → chapters), a fragment link, and a file name with a space. */
    private fun epub3Book(): ImportedEpub = importer.import(
        zip(
            "OEBPS/content.opf" to opf(
                title = "Alpha",
                creator = "Ann",
                manifest = """
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="text/one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/two%20b.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="css/book.css" media-type="text/css"/>
                """,
                spine = """<itemref idref="c1"/><itemref idref="c2"/>""",
            ),
            "OEBPS/nav.xhtml" to """
                <?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                <nav epub:type="toc"><ol>
                  <li><span>Part One</span>
                    <ol>
                      <li><a href="text/one.xhtml">Chapter 1</a></li>
                      <li><a href="text/one.xhtml#mid">Chapter 1, later</a></li>
                    </ol>
                  </li>
                  <li><a href="text/two%20b.xhtml">Chapter 2</a></li>
                </ol></nav>
                <nav epub:type="landmarks"><ol><li><a href="text/one.xhtml">Start</a></li></ol></nav>
                </body></html>
            """.trimIndent(),
            "OEBPS/text/one.xhtml" to xhtml("One", """<p>a</p><p id="mid">b</p><link href="../css/book.css"/>"""),
            "OEBPS/text/two b.xhtml" to xhtml("Two", "<p>c</p>"),
            "OEBPS/css/book.css" to "p { margin: 0 }",
        ),
        "content://alpha",
    )

    /** EPUB 2 book whose NCX lives in a different folder than the content. */
    private fun epub2Book(): ImportedEpub = importer.import(
        zip(
            "OPS/package.opf" to opf(
                title = "Beta",
                creator = "Bob",
                version = "2.0",
                manifest = """
                    <item id="ncx" href="meta/toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="b1" href="Text/b1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="b2" href="Text/b2.xhtml" media-type="application/xhtml+xml"/>
                """,
                spine = """<itemref idref="b1"/><itemref idref="b2"/>""",
            ),
            "OPS/meta/toc.ncx" to """
                <?xml version="1.0"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
                  <navPoint id="p1" playOrder="1"><navLabel><text>Beta</text></navLabel><content src="../Text/b1.xhtml"/>
                    <navPoint id="p2" playOrder="2"><navLabel><text>Opening</text></navLabel><content src="../Text/b1.xhtml"/></navPoint>
                    <navPoint id="p3" playOrder="3"><navLabel><text>Ending</text></navLabel><content src="../Text/b2.xhtml"/></navPoint>
                  </navPoint>
                </navMap></ncx>
            """.trimIndent(),
            "OPS/Text/b1.xhtml" to xhtml("B1", "<p>x</p>"),
            "OPS/Text/b2.xhtml" to xhtml("B2", "<p>y</p>"),
        ),
        "content://beta",
    )

    /** Book with no TOC at all: falls back to spine documents, labelled by heading. */
    private fun noTocBook(): ImportedEpub = importer.import(
        zip(
            "content.opf" to opf(
                title = "Gamma",
                creator = "Ann",
                manifest = """<item id="g1" href="g1.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<itemref idref="g1"/>""",
            ),
            "g1.xhtml" to xhtml("ignored", "<h1>The &amp; Only</h1><p>z</p>"),
        ),
        "content://gamma",
    )

    @Test
    fun `importer resolves toc hrefs to zip paths and keeps fragments`() {
        val alpha = epub3Book()
        val part = alpha.navToc.first()
        assertThat(part.title).isEqualTo("Part One")
        assertThat(part.href).isNull()
        assertThat(part.children.map { it.href })
            .containsExactly("OEBPS/text/one.xhtml", "OEBPS/text/one.xhtml#mid").inOrder()
        assertThat(alpha.navToc[1].href).isEqualTo("OEBPS/text/two b.xhtml")
        assertThat(alpha.navDocumentPath).isEqualTo("OEBPS/nav.xhtml")

        val beta = epub2Book()
        assertThat(beta.navToc.single().children.map { it.href })
            .containsExactly("OPS/Text/b1.xhtml", "OPS/Text/b2.xhtml").inOrder()
        assertThat(beta.ncxPath).isEqualTo("OPS/meta/toc.ncx")
    }

    @Test
    fun `merged toc follows merge order with each book's own toc nested beneath it`() {
        val outline = merger.tocOutline(listOf(epub2Book(), epub3Book(), noTocBook()))
        assertThat(outline.map { "  ".repeat(it.depth) + it.label }).containsExactly(
            "Cover",
            "Beta",
            "  Opening", // single "Beta" wrapper entry is lifted, not duplicated
            "  Ending",
            "Alpha",
            "  Part One",
            "    Chapter 1",
            "    Chapter 1, later",
            "  Chapter 2",
            "Gamma",
            "  The & Only",
        ).inOrder()
    }

    @Test
    fun `merged package is valid and every toc link points at a real file`() {
        val result = merger.mergeBooks(
            listOf(epub3Book(), epub2Book(), noTocBook()),
            MergeOptions(title = "Omnibus"),
        )
        assertThat(result.validation.issues).isEmpty()
        assertThat(result.validation.ok).isTrue()
        assertThat(result.filename).isEqualTo("Omnibus.epub")

        val files = unzip(result.bytes)
        val nav = files.getValue("OEBPS/nav.xhtml").toString(Charsets.UTF_8)
        val ncx = files.getValue("OEBPS/toc.ncx").toString(Charsets.UTF_8)
        val navHrefs = Regex("""href="([^"]+)"""").findAll(nav).map { it.groupValues[1] }.toList()
        val ncxSrcs = Regex("""src="([^"]+)"""").findAll(ncx).map { it.groupValues[1] }.toList()
        assertThat(navHrefs).isNotEmpty()
        (navHrefs + ncxSrcs).forEach { href ->
            val path = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
            assertThat(files).containsKey("OEBPS/$path")
        }
        // Fragment and percent-encoded space survive into the merged TOC.
        assertThat(navHrefs).contains("imported/i0/OEBPS/text/one.xhtml#mid")
        assertThat(navHrefs).contains("imported/i0/OEBPS/text/two%20b.xhtml")

        // Book order in the TOC and spine matches merge order.
        assertThat(nav.indexOf(">Alpha<")).isLessThan(nav.indexOf(">Beta<"))
        assertThat(nav.indexOf(">Beta<")).isLessThan(nav.indexOf(">Gamma<"))

        // Source packaging files are not copied into the merged book.
        assertThat(files.keys.filter { it.startsWith("OEBPS/imported/") && (it.endsWith(".opf") || it.endsWith(".ncx") || "META-INF" in it) })
            .isEmpty()
        assertThat(files).doesNotContainKey("OEBPS/imported/i0/OEBPS/nav.xhtml")
        // CSS link inside imported content was rewritten to the namespaced location.
        assertThat(files.getValue("OEBPS/imported/i0/OEBPS/text/one.xhtml").toString(Charsets.UTF_8))
            .contains("""href="../css/book.css"""")
    }

    @Test
    fun `options control cover, divider pages and metadata`() {
        val result = merger.mergeBooks(
            listOf(epub3Book(), noTocBook()),
            MergeOptions(title = "", generateCover = false, titlePagePerBook = false),
        )
        val files = unzip(result.bytes)
        assertThat(files.keys.none { it.endsWith("cover.png") }).isTrue()
        assertThat(files.keys.none { "imported_title_" in it }).isTrue()
        val opf = files.getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        assertThat(opf).contains("<dc:title>Alpha &amp; Gamma</dc:title>")
        assertThat(opf).contains("<dc:creator>Ann</dc:creator>")
        assertThat(result.validation.ok).isTrue()
    }

    @Test
    fun `external entities in imported books are never resolved`() {
        val secret = java.io.File.createTempFile("sw-secret", ".txt").apply {
            writeText("TOP-SECRET")
            deleteOnExit()
        }
        val evilNav = """
            <?xml version="1.0"?>
            <!DOCTYPE html [ <!ENTITY xxe SYSTEM "${secret.toURI()}"> ]>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol><li><a href="c.xhtml">Leak &xxe;</a></li></ol></nav>
            </body></html>
        """.trimIndent()
        val book = runCatching {
            importer.import(
                zip(
                    "content.opf" to opf(
                        title = "Evil",
                        creator = "X",
                        manifest = """
                            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                            <item id="c" href="c.xhtml" media-type="application/xhtml+xml"/>
                        """,
                        spine = """<itemref idref="c"/>""",
                    ),
                    "nav.xhtml" to evilNav,
                    "c.xhtml" to xhtml("C", "<p>c</p>"),
                ),
                "content://evil",
            )
        }.getOrNull()
        // Either the TOC failed to parse (and was dropped) or the entity expanded to nothing.
        book?.navToc?.forEach { assertThat(it.title).doesNotContain("TOP-SECRET") }
    }

    // --- fixture helpers -------------------------------------------------------------------

    private fun opf(title: String, creator: String, manifest: String, spine: String, version: String = "3.0") = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="$version" unique-identifier="id">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:identifier id="id">urn:test:$title</dc:identifier>
        <dc:title>$title</dc:title><dc:creator>$creator</dc:creator><dc:language>en</dc:language>
        </metadata>
        <manifest>$manifest</manifest>
        <spine>$spine</spine>
        </package>
    """.trimIndent()

    private fun xhtml(title: String, body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body>$body</body></html>
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val rootfile = entries.first { it.first.endsWith(".opf") }.first
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="$rootfile" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent(),
        )
        entries.forEach { files[it.first] = it.second }
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            files.forEach { (name, text) ->
                val data = text.toByteArray()
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                out[e.name] = zis.readBytes()
            }
        }
        return out
    }
}
