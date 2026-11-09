package com.storyweaver.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * One exportable entry of a book's table of contents, flattened in reading order.
 *
 * [id] is stable for a given book (`"0"`, `"0.2"`, `"1"`, …) and is what callers pass back
 * to [MarkdownExporter.export]. Books without a usable TOC get one entry per spine document.
 */
data class MarkdownSection(
    val id: String,
    val title: String,
    val depth: Int,
    val parentId: String?,
)

data class MarkdownResult(
    val markdown: String,
    val filename: String,
    val sectionCount: Int,
)

/**
 * Converts an imported EPUB, or chosen chapters of it, to a single Markdown document.
 *
 * A TOC entry covers its own document through every following document up to the next entry
 * that is not one of its descendants, so selecting a series/author entry exports all of its
 * chapters. Entries that point at `#fragments` inside a shared document are sliced at those
 * anchors. Images are dropped (a lone `.md` file has nowhere to keep them).
 */
class MarkdownExporter {
    fun outline(epub: ImportedEpub): List<MarkdownSection> = entries(epub).map { it.section }

    /**
     * @param sectionIds entries to export, or null for the whole book. When an entry and one of
     * its ancestors are both selected the ancestor wins, so nothing is exported twice.
     */
    fun export(epub: ImportedEpub, sectionIds: Set<String>? = null): MarkdownResult {
        val all = entries(epub)
        val bookTitle = epub.book.title
        val spine = spineDocs(epub)

        if (sectionIds == null) {
            val body = spine.indices.joinToString("\n\n") { i -> convertRange(epub, spine, i, null, i, null) }
            val header = buildString {
                append("# ").append(escapeLine(bookTitle))
                epub.book.creator?.takeIf { it.isNotBlank() }?.let { append("\n\n*by ").append(escapeInline(it)).append('*') }
            }
            return MarkdownResult(
                markdown = tidy("$header\n\n$body"),
                filename = filenameFor(bookTitle),
                sectionCount = all.size,
            )
        }

        val byId = all.associateBy { it.section.id }
        val chosen = all.filter { it.section.id in sectionIds }
            .filter { e -> ancestors(e.section, byId).none { it in sectionIds } }
        require(chosen.isNotEmpty()) { "Nothing selected to export" }

        val parts = chosen.map { entry ->
            val markdown = convertEntry(epub, spine, all, entry)
            if (startsWithHeading(markdown)) markdown else "# ${escapeLine(entry.section.title)}\n\n$markdown"
        }
        val name = if (chosen.size == 1) "$bookTitle - ${chosen.first().section.title}" else bookTitle
        return MarkdownResult(
            markdown = tidy(parts.joinToString("\n\n---\n\n")),
            filename = filenameFor(name),
            sectionCount = chosen.size,
        )
    }

    // ---- TOC → document ranges -------------------------------------------------------------

    private data class Entry(
        val section: MarkdownSection,
        /** Zip path of the target document, without fragment; null for external/unresolved links. */
        val path: String?,
        val fragment: String?,
        /** Index in [entries] just past this entry's subtree. */
        var subtreeEnd: Int = 0,
    )

    private fun entries(epub: ImportedEpub): List<Entry> {
        val spine = spineDocs(epub)
        val out = mutableListOf<Entry>()
        fun walk(list: List<ImportedNavEntry>, depth: Int, parentId: String?) {
            list.forEachIndexed { index, nav ->
                val id = if (parentId == null) "$index" else "$parentId.$index"
                val path = nav.href?.substringBefore('#')?.takeIf { it in spine }
                val fragment = nav.href?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }
                val entry = Entry(MarkdownSection(id, nav.title.ifBlank { "Untitled" }, depth, parentId), path, fragment)
                out += entry
                walk(nav.children, depth + 1, id)
                entry.subtreeEnd = out.size
            }
        }
        walk(epub.navToc, 0, null)
        if (out.none { it.path != null }) {
            out.clear()
            spine.forEachIndexed { i, path ->
                val title = documentTitle(epub.resources[path]) ?: "Section ${i + 1}"
                out += Entry(MarkdownSection("$i", title, 0, null), path, null, subtreeEnd = i + 1)
            }
        }
        return out
    }

    private fun spineDocs(epub: ImportedEpub): List<String> =
        epub.spineHrefs.distinct().filter { epub.resources.containsKey(it) }

    private fun ancestors(section: MarkdownSection, byId: Map<String, Entry>): Sequence<String> =
        generateSequence(section.parentId) { byId[it]?.section?.parentId }

    private fun convertEntry(epub: ImportedEpub, spine: List<String>, all: List<Entry>, entry: Entry): String {
        // Container entries without their own link (e.g. a series heading) start at their first linked child.
        val index = all.indexOf(entry)
        val start = all.subList(index, entry.subtreeEnd).firstOrNull { it.path != null }
            ?: return ""
        val startDoc = spine.indexOf(start.path)
        // The first linked entry after this subtree that lands at or after our start bounds the range.
        val boundary = all.drop(entry.subtreeEnd).firstOrNull { other ->
            val doc = other.path?.let(spine::indexOf) ?: -1
            doc > startDoc || (doc == startDoc && other.fragment != null && other.fragment != start.fragment)
        }
        return if (boundary == null) {
            convertRange(epub, spine, startDoc, start.fragment, spine.lastIndex, null)
        } else {
            val boundaryDoc = spine.indexOf(boundary.path)
            if (boundary.fragment != null) {
                convertRange(epub, spine, startDoc, start.fragment, boundaryDoc, boundary.fragment)
            } else {
                convertRange(epub, spine, startDoc, start.fragment, boundaryDoc - 1, null)
            }
        }
    }

    /** Documents [from]..[to] inclusive; starts at anchor [fromId] and stops before anchor [toId] (on [to]). */
    private fun convertRange(
        epub: ImportedEpub,
        spine: List<String>,
        from: Int,
        fromId: String?,
        to: Int,
        toId: String?,
    ): String = (from..to).mapNotNull { i ->
        val bytes = epub.resources[spine[i]] ?: return@mapNotNull null
        val md = convertDocument(bytes, startId = fromId.takeIf { i == from }, stopId = toId.takeIf { i == to })
        md.trim().takeIf { it.isNotEmpty() }
    }.joinToString("\n\n")

    // ---- XHTML → Markdown -----------------------------------------------------------------

    private fun convertDocument(bytes: ByteArray, startId: String?, stopId: String?): String {
        val doc = Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.htmlParser())
        val body = doc.body()
        startId?.let { id -> body.getElementById(id)?.before(TextNode(START_MARK)) }
        stopId?.let { id -> body.getElementById(id)?.before(TextNode(STOP_MARK)) }
        var md = MarkdownWriter().render(body)
        if (startId != null && md.contains(START_MARK)) md = md.substringAfter(START_MARK)
        if (stopId != null && md.contains(STOP_MARK)) md = md.substringBefore(STOP_MARK)
        return md.replace(START_MARK, "").replace(STOP_MARK, "")
    }

    private fun documentTitle(bytes: ByteArray?): String? {
        bytes ?: return null
        val doc = Jsoup.parse(bytes.toString(Charsets.UTF_8))
        return (doc.selectFirst("h1, h2, h3")?.text() ?: doc.title()).trim().takeIf { it.isNotEmpty() }
    }

    private fun startsWithHeading(md: String): Boolean = md.trimStart().startsWith("#")

    private fun tidy(md: String): String =
        md.lines().joinToString("\n") { line ->
            // Keep CommonMark hard breaks (two trailing spaces); drop other trailing whitespace.
            if (line.endsWith("  ") && line.isNotBlank()) line.trimEnd() + "  " else line.trimEnd()
        }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim() + "\n"

    private fun filenameFor(name: String): String =
        FilenameSanitizer.sanitize(name, fallback = "book").removeSuffix(".epub").let { "$it.md" }

    companion object {
        private const val START_MARK = "\uE000SW-START\uE000"
        private const val STOP_MARK = "\uE000SW-STOP\uE000"

        internal fun escapeInline(text: String): String =
            text.replace(Regex("""([\\`*_\[\]<])"""), """\\$1""")

        private val BLOCK_MARKER = Regex("""^(\s*)(#{1,6}|>|[-+]|\d+[.)])(?=\s|$)""")

        /** Escaped plain text placed at the start of a line, e.g. a heading built from a TOC title. */
        internal fun escapeLine(text: String): String = escapeLineStart(escapeInline(text))

        /**
         * Stops already inline-escaped prose from being read as a heading, quote or list item
         * ("#1 rule", "- she said", "1984. That year").
         */
        internal fun escapeLineStart(line: String): String {
            val m = BLOCK_MARKER.find(line) ?: return line
            val (indent, marker) = m.destructured
            val escaped = if (marker.first().isDigit()) {
                marker.dropLast(1) + "\\" + marker.last()
            } else {
                "\\" + marker
            }
            return indent + escaped + line.substring(m.range.last + 1)
        }
    }
}

/** Small block/inline HTML → CommonMark renderer for book prose. */
private class MarkdownWriter {
    private val blocks = mutableListOf<String>()
    private val inline = StringBuilder()

    fun render(root: Element): String {
        renderBlocks(root.childNodes())
        flush()
        return blocks.joinToString("\n\n")
    }

    private fun flush() {
        val text = inline.toString().trim { it == ' ' || it == '\n' }
        inline.clear()
        if (text.isNotEmpty() && text.replace("  \n", "").isNotBlank()) {
            blocks += text.lines().joinToString("\n") { MarkdownExporter.escapeLineStart(it) }
        }
    }

    private fun renderBlocks(nodes: List<Node>) {
        nodes.forEach { node ->
            when (node) {
                is TextNode -> inline.append(collapse(node.wholeText, inline))
                is Element -> renderElement(node)
            }
        }
    }

    private fun renderElement(el: Element) {
        when (val tag = el.normalName()) {
            in SKIP -> Unit
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush()
                val text = inlineOf(el).replace(Regex("\\s*\n\\s*"), " ").trim()
                if (text.isNotEmpty()) blocks += "#".repeat(tag[1].digitToInt()) + " " + text
            }
            "p" -> {
                flush()
                inline.append(inlineOf(el))
                flush()
            }
            "hr" -> {
                flush()
                blocks += "* * *"
            }
            "br" -> inline.append("  \n")
            "blockquote" -> {
                flush()
                val inner = MarkdownWriter().render(el)
                if (inner.isNotBlank()) blocks += inner.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
            }
            "pre" -> {
                flush()
                blocks += "```\n" + el.wholeText().trimEnd('\n') + "\n```"
            }
            "ul", "ol" -> {
                flush()
                val list = renderList(el, ordered = tag == "ol")
                if (list.isNotBlank()) blocks += list
            }
            "table" -> {
                flush()
                renderTable(el)?.let { blocks += it }
            }
            in BLOCK_CONTAINERS -> {
                flush()
                renderBlocks(el.childNodes())
                flush()
            }
            else -> inline.append(inlineElement(el))
        }
    }

    private fun renderList(el: Element, ordered: Boolean): String {
        var n = 1
        return el.children().filter { it.normalName() == "li" }.joinToString("\n") { li ->
            val marker = if (ordered) "${n++}. " else "- "
            val nested = li.children().filter { it.normalName() == "ul" || it.normalName() == "ol" }
            nested.forEach { it.remove() }
            val text = MarkdownWriter().render(li).replace("\n\n", "\n")
            val sub = nested.joinToString("\n") { renderList(it, it.normalName() == "ol") }
            buildString {
                append(marker)
                append(text.lines().joinToString("\n" + " ".repeat(marker.length)))
                if (sub.isNotBlank()) append("\n").append(sub.lines().joinToString("\n") { " ".repeat(marker.length) + it })
            }
        }
    }

    private fun renderTable(table: Element): String? {
        val rows = table.select("tr").map { tr ->
            tr.children().filter { it.normalName() == "td" || it.normalName() == "th" }
                .map { inlineOf(it).replace("|", "\\|").replace(Regex("\\s*\n\\s*"), " ").trim() }
        }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.size }
        val padded = rows.map { it + List(width - it.size) { "" } }
        return buildString {
            append("| ").append(padded.first().joinToString(" | ")).append(" |\n")
            append("|").append(" --- |".repeat(width))
            padded.drop(1).forEach { append("\n| ").append(it.joinToString(" | ")).append(" |") }
        }
    }

    private fun inlineOf(el: Element): String {
        val sb = StringBuilder()
        el.childNodes().forEach { child ->
            when (child) {
                is TextNode -> sb.append(collapse(child.wholeText, sb))
                is Element -> sb.append(inlineElement(child))
            }
        }
        return sb.toString()
    }

    private fun inlineElement(el: Element): String = when (el.normalName()) {
        in SKIP, "img", "svg" -> ""
        "br" -> "  \n"
        "strong", "b" -> wrap(inlineOf(el), "**")
        "em", "i", "cite", "dfn" -> wrap(inlineOf(el), "*")
        "s", "del", "strike" -> wrap(inlineOf(el), "~~")
        "code", "kbd", "samp" -> el.text().takeIf { it.isNotEmpty() }?.let { "`${it.replace("`", "'")}`" } ?: ""
        "a" -> {
            val text = inlineOf(el)
            val href = el.attr("href")
            if (text.isNotBlank() && (href.startsWith("http://") || href.startsWith("https://"))) {
                "[${text.trim()}](${href.replace(" ", "%20").replace(")", "%29")})"
            } else {
                text
            }
        }
        else -> if (el.normalName() in BLOCK_CONTAINERS || el.normalName() == "p") " ${inlineOf(el)} " else inlineOf(el)
    }

    /** Emphasis markers must hug the text, so move surrounding whitespace outside them. */
    private fun wrap(text: String, marker: String): String {
        if (text.isBlank()) return text
        val lead = text.takeWhile { it == ' ' }
        val trail = text.takeLastWhile { it == ' ' }
        return lead + marker + text.trim(' ') + marker + trail
    }

    private fun collapse(raw: String, before: CharSequence): String {
        var text = MarkdownExporter.escapeInline(raw.replace('\u00A0', ' ').replace(Regex("\\s+"), " "))
        if (text.startsWith(" ") && (before.isEmpty() || before.last() == ' ' || before.last() == '\n')) {
            text = text.trimStart()
        }
        return text
    }

    companion object {
        val SKIP = setOf("head", "script", "style", "noscript", "template", "title", "meta", "link")
        val BLOCK_CONTAINERS = setOf(
            "div", "section", "article", "main", "header", "footer", "aside", "nav", "figure",
            "figcaption", "body", "html", "center", "dl", "dt", "dd", "address", "details", "summary",
        )
    }
}
