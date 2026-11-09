package com.storyweaver.epub

import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.ExportSnapshot
import com.storyweaver.core.model.WorkType
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class SpineItem(val id: String, val href: String)

internal data class NavPoint(
    val id: String,
    val label: String,
    val href: String,
    val children: MutableList<NavPoint> = mutableListOf(),
)

/**
 * Mutable package under construction: resource paths are relative to OEBPS/.
 */
internal data class PackageBuild(
    val resources: LinkedHashMap<String, ByteArray> = linkedMapOf(),
    val spine: MutableList<SpineItem> = mutableListOf(),
    val navPoints: MutableList<NavPoint> = mutableListOf(),
    var fileIndex: Int = 1,
)

/**
 * Single EPUB package assembler used by [EpubWriter] and [EpubMerger].
 */
internal object PackageAssembly {
    fun buildDownloaded(snapshot: ExportSnapshot): PackageBuild {
        val build = PackageBuild()
        val authorsById = snapshot.authors.associateBy { it.id }
        val worksById = snapshot.selectedWorks.associateBy { it.id }
        val chapters = snapshot.selectedChapters
        val contents = snapshot.chapterContents

        build.resources["styles/style.css"] = snapshot.epubStyle.stylesheetCss().toByteArray()
        val coverBytes = CoverGenerator.generatePng(
            title = snapshot.title,
            subtitle = snapshot.subtitle,
            curator = snapshot.curator,
            accentArgb = snapshot.accentColorArgb,
        )
        build.resources["images/cover.png"] = coverBytes
        build.resources["text/cover.xhtml"] = coverPage(snapshot.title).toByteArray(Charsets.UTF_8)
        build.spine += SpineItem("cover", "text/cover.xhtml")
        build.navPoints += NavPoint("cover", "Cover", "text/cover.xhtml")

        val byAuthor = chapters.groupBy { worksById[it.workId]?.authorId }
        byAuthor.forEach { (authorId, authorChapters) ->
            val author = authorId?.let { authorsById[it] }
            val authorTitle = author?.displayName ?: "Author"
            val authorNavChildren = mutableListOf<NavPoint>()
            val useAuthorDivider = snapshot.exportMode == ExportMode.COMBINED && byAuthor.size > 1
            if (useAuthorDivider) {
                val id = "author-${build.fileIndex}"
                val path = "text/author_${build.fileIndex}.xhtml"
                build.resources[path] = dividerPage(authorTitle, "Author")
                build.spine += SpineItem(id, path)
                build.navPoints += NavPoint(id, authorTitle, path, children = authorNavChildren)
                build.fileIndex++
            }

            val byWork = authorChapters.groupBy { it.workId }
            byWork.forEach { (workId, workChapters) ->
                val work = worksById[workId]
                val workTitle = work?.title ?: "Work"
                val workNavChildren = mutableListOf<NavPoint>()
                val isSeries = work?.type == WorkType.SERIES || workChapters.size > 1

                if (isSeries) {
                    val id = "series-${build.fileIndex}"
                    val path = "text/series_${build.fileIndex}.xhtml"
                    build.resources[path] = dividerPage(workTitle, "Series")
                    build.spine += SpineItem(id, path)
                    val seriesNav = NavPoint(id, workTitle, path, children = workNavChildren)
                    if (useAuthorDivider) authorNavChildren += seriesNav else build.navPoints += seriesNav
                    build.fileIndex++
                }

                workChapters.sortedBy { it.orderKey }.forEach { chapter ->
                    val content = contents[chapter.id]
                        ?: ChapterContent(
                            chapterId = chapter.id,
                            title = chapter.title,
                            authorDisplayName = author?.displayName,
                            xhtmlBody = "<p>Content unavailable.</p>",
                            contentHash = "missing",
                            sourceFingerprint = null,
                        )
                    val id = "chap-${build.fileIndex}"
                    val path = "text/chapter_${build.fileIndex}.xhtml"
                    build.resources[path] = chapterPage(chapter.title, content.xhtmlBody, author?.displayName)
                    build.spine += SpineItem(id, path)
                    val leaf = NavPoint(id, chapter.title, path)
                    when {
                        isSeries -> workNavChildren += leaf
                        useAuthorDivider -> authorNavChildren += leaf
                        else -> build.navPoints += leaf
                    }
                    build.fileIndex++
                }
            }
        }
        return build
    }

    fun finalize(
        title: String,
        creator: String,
        language: String,
        identifier: String,
        build: PackageBuild,
        filenameTitle: String = title,
    ): EpubWriteResult {
        val navDoc = buildNavXhtml(title, build.navPoints)
        val ncxDoc = buildNcx(title, identifier, build.navPoints)
        val opf = buildOpf(
            title = title,
            creator = creator,
            language = language,
            identifier = identifier,
            style = "styles/style.css",
            spineItems = build.spine,
            modified = Instant.now().toString(),
            hasCover = build.resources.containsKey("images/cover.png"),
            extraManifest = build.resources.keys
                .filter { path ->
                    path != "styles/style.css" &&
                        path != "images/cover.png" &&
                        build.spine.none { it.href == path }
                }
                .mapIndexed { index, path ->
                    val media = mediaTypeFor(path)
                    """<item id="res-$index" href="$path" media-type="$media"/>"""
                },
        )
        val allFiles = linkedMapOf<String, ByteArray>()
        allFiles["mimetype"] = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        allFiles["META-INF/container.xml"] = containerXml().toByteArray(Charsets.UTF_8)
        allFiles["OEBPS/content.opf"] = opf.toByteArray(Charsets.UTF_8)
        allFiles["OEBPS/nav.xhtml"] = navDoc.toByteArray(Charsets.UTF_8)
        allFiles["OEBPS/toc.ncx"] = ncxDoc.toByteArray(Charsets.UTF_8)
        build.resources.forEach { (path, bytes) ->
            allFiles["OEBPS/$path"] = bytes
        }
        val bytes = zipEpub(allFiles)
        val validation = StructuralValidator.validate(bytes)
        return EpubWriteResult(
            bytes = bytes,
            filename = FilenameSanitizer.sanitize(filenameTitle),
            validation = validation,
        )
    }

    fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun zipEpub(files: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val mime = files.getValue("mimetype")
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mime)
            zos.closeEntry()
            files.forEach { (name, data) ->
                if (name == "mimetype") return@forEach
                val entry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun mediaTypeFor(path: String): String = when {
        path.endsWith(".css", true) -> "text/css"
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".gif", true) -> "image/gif"
        path.endsWith(".svg", true) -> "image/svg+xml"
        path.endsWith(".xhtml", true) || path.endsWith(".html", true) || path.endsWith(".htm", true) ->
            "application/xhtml+xml"
        path.endsWith(".otf", true) -> "font/otf"
        path.endsWith(".ttf", true) -> "font/ttf"
        path.endsWith(".woff", true) -> "font/woff"
        else -> "application/octet-stream"
    }

    private fun coverPage(title: String): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">""")
        appendLine("<head>")
        appendLine("""<meta charset="utf-8"/>""")
        appendLine("<title>${escapeXml(title)}</title>")
        appendLine("""<style type="text/css">body{margin:0;text-align:center;background:#F7F3EE;}img{max-width:100%;height:auto;}</style>""")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("""<img src="../images/cover.png" alt="${escapeXml(title)}"/>""")
        appendLine("</body></html>")
    }

    private fun dividerPage(title: String, kind: String): ByteArray =
        xhtmlShell(
            title,
            """
            <section class="divider">
              <p class="first">$kind</p>
              <h1>${escapeXml(title)}</h1>
            </section>
            """.trimIndent(),
        ).toByteArray(Charsets.UTF_8)

    private fun chapterPage(title: String, body: String, author: String?): ByteArray =
        xhtmlShell(
            title,
            """
            <header>
              <h1>${escapeXml(title)}</h1>
              ${author?.let { """<p class="first">${escapeXml(it)}</p>""" } ?: ""}
            </header>
            <section>
              $body
            </section>
            """.trimIndent(),
        ).toByteArray(Charsets.UTF_8)

    private fun xhtmlShell(title: String, body: String): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">""")
        appendLine("<head>")
        appendLine("""<meta charset="utf-8"/>""")
        appendLine("<title>${escapeXml(title)}</title>")
        appendLine("""<link rel="stylesheet" type="text/css" href="../styles/style.css"/>""")
        appendLine("</head>")
        appendLine("<body>")
        appendLine(body)
        appendLine("</body>")
        appendLine("</html>")
    }

    private fun buildNavXhtml(bookTitle: String, points: List<NavPoint>): String {
        fun render(list: List<NavPoint>): String = buildString {
            appendLine("<ol>")
            list.forEach { p ->
                appendLine("<li><a href=\"${p.href}\">${escapeXml(p.label)}</a>")
                if (p.children.isNotEmpty()) append(render(p.children))
                appendLine("</li>")
            }
            appendLine("</ol>")
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">""")
            appendLine("<head>")
            appendLine("""<meta charset="utf-8"/>""")
            appendLine("<title>${escapeXml(bookTitle)}</title>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("""<nav epub:type="toc" id="toc">""")
            appendLine("<h1>Table of Contents</h1>")
            append(render(points))
            appendLine("</nav>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private fun buildNcx(title: String, identifier: String, points: List<NavPoint>): String {
        var play = 1
        fun render(list: List<NavPoint>): String = buildString {
            list.forEach { p ->
                val order = play++
                appendLine("""<navPoint id="${p.id}" playOrder="$order">""")
                appendLine("""<navLabel><text>${escapeXml(p.label)}</text></navLabel>""")
                appendLine("""<content src="${p.href}"/>""")
                if (p.children.isNotEmpty()) append(render(p.children))
                appendLine("</navPoint>")
            }
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">""")
            appendLine("<head>")
            appendLine("""<meta name="dtb:uid" content="${escapeXml(identifier)}"/>""")
            appendLine("""<meta name="dtb:depth" content="4"/>""")
            appendLine("""<meta name="dtb:totalPageCount" content="0"/>""")
            appendLine("""<meta name="dtb:maxPageNumber" content="0"/>""")
            appendLine("</head>")
            appendLine("<docTitle><text>${escapeXml(title)}</text></docTitle>")
            appendLine("<navMap>")
            append(render(points))
            appendLine("</navMap>")
            appendLine("</ncx>")
        }
    }

    private fun buildOpf(
        title: String,
        creator: String,
        language: String,
        identifier: String,
        style: String,
        spineItems: List<SpineItem>,
        modified: String,
        hasCover: Boolean,
        extraManifest: List<String>,
    ): String {
        val manifestItems = buildString {
            appendLine("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            appendLine("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            appendLine("""<item id="css" href="$style" media-type="text/css"/>""")
            if (hasCover) {
                appendLine("""<item id="cover-image" href="images/cover.png" media-type="image/png" properties="cover-image"/>""")
            }
            spineItems.forEach { item ->
                appendLine("""<item id="${item.id}" href="${item.href}" media-type="application/xhtml+xml"/>""")
            }
            extraManifest.forEach { appendLine(it) }
        }
        val spine = spineItems.joinToString("\n") { """<itemref idref="${it.id}"/>""" }
        val modifiedStamp = modified.replace(Regex("""\.\d+"""), "").let {
            if (it.endsWith("Z")) it else "${it.take(19)}Z"
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id" xml:lang="$language">""")
            appendLine("""<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
            appendLine("""<dc:identifier id="pub-id">${escapeXml(identifier)}</dc:identifier>""")
            appendLine("<dc:title>${escapeXml(title)}</dc:title>")
            appendLine("<dc:creator>${escapeXml(creator)}</dc:creator>")
            appendLine("<dc:language>$language</dc:language>")
            appendLine("""<meta property="dcterms:modified">$modifiedStamp</meta>""")
            if (hasCover) appendLine("""<meta name="cover" content="cover-image"/>""")
            appendLine("""<meta name="generator" content="StoryWeaver"/>""")
            appendLine("</metadata>")
            appendLine("<manifest>")
            append(manifestItems)
            appendLine("</manifest>")
            appendLine("""<spine toc="ncx">""")
            appendLine(spine)
            appendLine("</spine>")
            appendLine("</package>")
        }
    }

    private fun containerXml(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""")
        appendLine("<rootfiles>")
        appendLine("""<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>""")
        appendLine("</rootfiles>")
        appendLine("</container>")
    }
}
