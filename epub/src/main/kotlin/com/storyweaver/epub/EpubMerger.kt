package com.storyweaver.epub

import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.ExportSnapshot
import java.util.UUID

/** Options for merging standalone EPUB files into one book. */
data class MergeOptions(
    val title: String,
    /** Author shown in the merged book; null/blank joins the source books' authors. */
    val creator: String? = null,
    /** Language code; null uses the first book's language (or `en`). */
    val language: String? = null,
    /** Add a generated cover page at the front of the merged book. */
    val generateCover: Boolean = true,
    /** Insert a divider page with the book title before each merged book. */
    val titlePagePerBook: Boolean = true,
    val epubStyle: EpubStyle = EpubStyle.MODERN,
    val accentColorArgb: Long = 0xFF7A2E3D,
)

/** One line of a table-of-contents outline, for previews. [depth] 0 = top level. */
data class TocOutlineEntry(val label: String, val depth: Int)

/**
 * Merges EPUBs into a single package using the shared [PackageAssembly].
 *
 * Table of contents: every merged book becomes one top-level entry, in merge order, and
 * that book's own table of contents (including nesting and `#fragment` links) is kept
 * underneath it. Books without a usable TOC fall back to one entry per spine document.
 */
class EpubMerger(
    private val writer: EpubWriter = EpubWriter(),
    @Suppress("unused") private val importer: EpubImporter = EpubImporter(),
) {
    /** Create flow: downloaded chapters first, then imported books. */
    fun merge(
        snapshot: ExportSnapshot,
        imported: List<ImportedEpub>,
        language: String = "en",
    ): EpubWriteResult {
        if (imported.isEmpty()) {
            return writer.write(
                EpubWriteRequest(snapshot = snapshot.copy(exportMode = ExportMode.COMBINED), language = language),
            )
        }

        val build = if (snapshot.selectedChapters.isNotEmpty()) {
            PackageAssembly.buildDownloaded(snapshot.copy(exportMode = ExportMode.COMBINED))
        } else {
            PackageBuild().also {
                it.resources["styles/style.css"] = snapshot.epubStyle.stylesheetCss().toByteArray()
                addGeneratedCover(it, snapshot.title, snapshot.subtitle, snapshot.curator, snapshot.accentColorArgb, language)
            }
        }

        val ids = IdSource()
        imported.forEachIndexed { index, book ->
            appendBook(build, book, index, language, titlePage = true, ids = ids)
        }

        return PackageAssembly.finalize(
            title = snapshot.title,
            creator = snapshot.curator ?: "StoryWeaver",
            language = language,
            identifier = "urn:uuid:${UUID.randomUUID()}",
            build = build,
        )
    }

    /** Standalone merge: combine [books], in this order, into one EPUB. */
    fun mergeBooks(books: List<ImportedEpub>, options: MergeOptions): EpubWriteResult {
        require(books.isNotEmpty()) { "Add at least one EPUB to merge." }
        val language = options.language?.takeIf { it.isNotBlank() }
            ?: books.firstNotNullOfOrNull { it.book.language?.takeIf(String::isNotBlank) }
            ?: "en"
        val creator = options.creator?.takeIf { it.isNotBlank() } ?: defaultCreator(books)
        val title = options.title.ifBlank { defaultTitle(books) }

        val build = PackageBuild()
        build.resources["styles/style.css"] = options.epubStyle.stylesheetCss().toByteArray()
        if (options.generateCover) {
            val subtitle = if (books.size == 1) null else "${books.size} books"
            addGeneratedCover(build, title, subtitle, creator, options.accentColorArgb, language)
        }
        val ids = IdSource()
        books.forEachIndexed { index, book ->
            appendBook(build, book, index, language, options.titlePagePerBook, ids)
        }
        return PackageAssembly.finalize(
            title = title,
            creator = creator,
            language = language,
            identifier = "urn:uuid:${UUID.randomUUID()}",
            build = build,
        )
    }

    /**
     * The table of contents [mergeBooks] would produce for [books], flattened with depths,
     * so the UI can preview the result without building the package.
     */
    fun tocOutline(books: List<ImportedEpub>, generateCover: Boolean = true): List<TocOutlineEntry> {
        val out = mutableListOf<TocOutlineEntry>()
        if (generateCover) out += TocOutlineEntry("Cover", 0)
        books.forEach { book ->
            out += TocOutlineEntry(book.book.title, 0)
            fun walk(nodes: List<TocNodeDraft>, depth: Int) {
                nodes.forEach {
                    out += TocOutlineEntry(it.label, depth)
                    walk(it.children, depth + 1)
                }
            }
            walk(bookToc(book), 1)
        }
        return out
    }

    fun defaultTitle(books: List<ImportedEpub>): String = when (books.size) {
        0 -> "Merged book"
        1 -> books.first().book.title
        2 -> "${books[0].book.title} & ${books[1].book.title}"
        else -> "${books.first().book.title} + ${books.size - 1} more"
    }

    fun defaultCreator(books: List<ImportedEpub>): String =
        books.mapNotNull { it.book.creator?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString(", ")
            .ifEmpty { "StoryWeaver" }

    // ---------------------------------------------------------------------------------------

    private class IdSource {
        private var nav = 0
        fun navId(): String = "nav-imp-${++nav}"
    }

    /** A TOC node before namespacing. [target] = source zip path + optional `#fragment`. */
    private data class TocNodeDraft(
        val label: String,
        val target: String,
        val children: List<TocNodeDraft>,
    )

    private fun addGeneratedCover(
        build: PackageBuild,
        title: String,
        subtitle: String?,
        curator: String?,
        accentArgb: Long,
        language: String,
    ) {
        build.resources["images/cover.png"] = CoverGenerator.generatePng(
            title = title,
            subtitle = subtitle,
            curator = curator,
            accentArgb = accentArgb,
        )
        build.resources["text/cover.xhtml"] = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$language" lang="$language">
            <head><title>Cover</title><link rel="stylesheet" type="text/css" href="../styles/style.css"/></head>
            <body style="margin:0;text-align:center;">
            <img src="../images/cover.png" alt="Cover" style="max-width:100%;height:auto;"/>
            </body></html>
        """.trimIndent().toByteArray()
        build.spine += SpineItem("cover", "text/cover.xhtml")
        build.navPoints += NavPoint("cover", "Cover", "text/cover.xhtml")
    }

    /** Files from the source package that must not be copied into the merged one. */
    private fun isPackagingFile(book: ImportedEpub, path: String): Boolean =
        path.endsWith('/') ||
            path == "mimetype" ||
            path.startsWith("META-INF/", ignoreCase = true) ||
            path == book.opfPath ||
            path.endsWith(".opf", ignoreCase = true) ||
            path == book.ncxPath ||
            (path == book.navDocumentPath && path !in book.spineHrefs)

    private fun copyableKeys(book: ImportedEpub): Set<String> =
        book.resources.keys.filterTo(linkedSetOf()) { !isPackagingFile(book, it) }

    /** The book's own TOC, restricted to entries that point at files we actually copy. */
    private fun bookToc(book: ImportedEpub): List<TocNodeDraft> {
        val available = copyableKeys(book)
        fun map(entries: List<ImportedNavEntry>): List<TocNodeDraft> = entries.mapNotNull { entry ->
            val children = map(entry.children)
            val own = entry.href?.takeIf { it.substringBefore('#') in available }
            // Heading-only entries (no link) point at their first child.
            val target = own ?: children.firstOrNull()?.target ?: return@mapNotNull null
            TocNodeDraft(entry.title, target, children)
        }
        var toc = map(book.navToc)
        // A single wrapper entry named after the book would just duplicate our top-level entry.
        val only = toc.singleOrNull()
        if (only != null && only.children.isNotEmpty() &&
            only.label.trim().equals(book.book.title.trim(), ignoreCase = true)
        ) {
            toc = only.children
        }
        if (toc.isNotEmpty()) return toc
        // Fallback: one entry per spine document, labelled from its <title>/<h1>.
        return book.spineHrefs
            .distinct()
            .filter { it in available }
            .mapIndexed { i, href ->
                TocNodeDraft(documentTitle(book.resources.getValue(href)) ?: "Section ${i + 1}", href, emptyList())
            }
    }

    private fun appendBook(
        build: PackageBuild,
        book: ImportedEpub,
        index: Int,
        language: String,
        titlePage: Boolean,
        ids: IdSource,
    ) {
        val ns = "imported/i$index"
        val copied = copyableKeys(book)

        // 1. Optional divider page, placed in the spine before the book's own pages.
        var titlePath: String? = null
        if (titlePage) {
            titlePath = "text/imported_title_$index.xhtml"
            build.resources[titlePath] = titlePageXhtml(book, index, language)
            build.spine += SpineItem("imp-title-$index", titlePath)
        }

        // 2. Resources, namespaced per book so file names cannot collide; links rewritten.
        copied.forEach { path ->
            build.resources["$ns/$path"] = rewriteResource(book.resources.getValue(path), path, ns)
        }

        // 3. Spine in the book's own reading order.
        var firstDoc: String? = null
        book.spineHrefs.distinct().forEach { href ->
            if (href in copied) {
                val target = "$ns/$href"
                build.spine += SpineItem("imp-$index-${build.fileIndex++}", target)
                if (firstDoc == null) firstDoc = target
            }
        }

        // 4. TOC: one top-level entry per book, with the book's own TOC beneath it.
        fun toNav(node: TocNodeDraft): NavPoint =
            NavPoint(ids.navId(), node.label, "$ns/${node.target}", node.children.mapTo(mutableListOf()) { toNav(it) })
        val children = bookToc(book).mapTo(mutableListOf()) { toNav(it) }
        val bookHref = titlePath ?: firstDoc ?: children.firstOrNull()?.href ?: return
        build.navPoints += NavPoint(ids.navId(), book.book.title, bookHref, children)
    }

    private fun titlePageXhtml(book: ImportedEpub, index: Int, language: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$language" lang="$language">
        <head><title>${PackageAssembly.escapeXml(book.book.title)}</title>
        <link rel="stylesheet" type="text/css" href="../styles/style.css"/></head>
        <body>
        <section class="divider">
        <p class="first">Book ${index + 1}</p>
        <h1>${PackageAssembly.escapeXml(book.book.title)}</h1>
        ${book.book.creator?.let { "<p>${PackageAssembly.escapeXml(it)}</p>" } ?: ""}
        </section>
        </body></html>
    """.trimIndent().toByteArray()

    private fun rewriteResource(data: ByteArray, path: String, ns: String): ByteArray {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") -> {
                rewriteHtmlLinks(data, path, ns).toString(Charsets.UTF_8)
                    .replace(SCRIPT_TAG, "")
                    .replace(EVENT_ATTR, "")
                    .toByteArray(Charsets.UTF_8)
            }
            lower.endsWith(".svg") -> rewriteHtmlLinks(data, path, ns)
            lower.endsWith(".css") -> rewriteCssUrls(data, path, ns)
            else -> data
        }
    }

    private fun documentTitle(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.UTF_8)
        val raw = HEADING_TAG.find(text)?.groupValues?.get(1)
            ?: TITLE_TAG.find(text)?.groupValues?.get(1)
            ?: return null
        return raw.replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifEmpty { null }
    }

    private fun rewriteHtmlLinks(data: ByteArray, sourcePath: String, ns: String): ByteArray {
        val dir = sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        var text = data.toString(Charsets.UTF_8)
        val patterns = listOf(
            Regex("""(href|src)\s*=\s*"([^"]+)""""),
            Regex("""(href|src)\s*=\s*'([^']+)'"""),
        )
        patterns.forEach { re ->
            text = re.replace(text) { m ->
                val attr = m.groupValues[1]
                val ref = m.groupValues[2]
                if (ref.startsWith("#") || ref.startsWith("http") || ref.startsWith("data:") ||
                    ref.startsWith("mailto:")
                ) {
                    m.value
                } else {
                    val resolved = resolveRelative(dir, ref.substringBefore('#'))
                    val frag = if ('#' in ref) "#" + ref.substringAfter('#') else ""
                    val from = "$ns/$sourcePath".substringBeforeLast('/')
                    val rel = relativePath(from, "$ns/$resolved") + frag
                    """$attr="$rel""""
                }
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun rewriteCssUrls(data: ByteArray, sourcePath: String, ns: String): ByteArray {
        val dir = sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val text = data.toString(Charsets.UTF_8).replace(Regex("""url\((['"]?)([^'")]+)\1\)""")) { m ->
            val ref = m.groupValues[2].trim()
            if (ref.startsWith("data:") || ref.startsWith("http")) {
                m.value
            } else {
                val resolved = resolveRelative(dir, ref)
                val from = "$ns/$sourcePath".substringBeforeLast('/')
                val rel = relativePath(from, "$ns/$resolved")
                "url($rel)"
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun resolveRelative(dir: String, ref: String): String {
        val base = if (dir.isEmpty()) ref else "$dir/$ref"
        val stack = mutableListOf<String>()
        base.replace('\\', '/').split('/').forEach { p ->
            when (p) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += p
            }
        }
        return stack.joinToString("/")
    }

    private fun relativePath(fromDir: String, toPath: String): String {
        val from = fromDir.split('/').filter { it.isNotEmpty() }
        val to = toPath.split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < from.size && i < to.size && from[i] == to[i]) i++
        val ups = List(from.size - i) { ".." }
        val rest = to.drop(i)
        return (ups + rest).joinToString("/").ifEmpty { to.last() }
    }

    private companion object {
        val SCRIPT_TAG = Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val EVENT_ATTR = Regex("""\son[a-z]+\s*=\s*"[^"]*"""", RegexOption.IGNORE_CASE)
        val TITLE_TAG = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
        val HEADING_TAG = Regex("""<h[1-3][^>]*>([\s\S]*?)</h[1-3]>""", RegexOption.IGNORE_CASE)
    }
}
