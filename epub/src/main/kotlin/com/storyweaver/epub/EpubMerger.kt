package com.storyweaver.epub

import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.ExportSnapshot
import java.util.UUID

/**
 * Merges downloaded StoryWeaver content with imported EPUBs into a single package
 * using the shared [PackageAssembly] (no intermediate double-write).
 */
class EpubMerger(
    private val writer: EpubWriter = EpubWriter(),
    private val importer: EpubImporter = EpubImporter(),
) {
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
            val empty = PackageBuild()
            empty.resources["styles/style.css"] = snapshot.epubStyle.stylesheetCss().toByteArray()
            val cover = CoverGenerator.generatePng(
                title = snapshot.title,
                subtitle = snapshot.subtitle,
                curator = snapshot.curator,
                accentArgb = snapshot.accentColorArgb,
            )
            empty.resources["images/cover.png"] = cover
            empty.resources["text/cover.xhtml"] = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$language" lang="$language">
                <head><title>Cover</title><link rel="stylesheet" href="../styles/style.css"/></head>
                <body style="margin:0;text-align:center;">
                <img src="../images/cover.png" alt="Cover" style="max-width:100%;height:auto;"/>
                </body></html>
            """.trimIndent().toByteArray()
            empty.spine += SpineItem("cover", "text/cover.xhtml")
            empty.navPoints += NavPoint("cover", "Cover", "text/cover.xhtml")
            empty
        }

        imported.forEachIndexed { importIndex, book ->
            val ns = "imported/i$importIndex"
            val titlePageId = "imp-title-$importIndex"
            val titlePath = "text/imported_title_$importIndex.xhtml"
            build.resources[titlePath] = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$language" lang="$language">
                <head><title>${PackageAssembly.escapeXml(book.book.title)}</title>
                <link rel="stylesheet" type="text/css" href="../styles/style.css"/></head>
                <body>
                <section class="divider">
                <p class="first">Imported book</p>
                <h1>${PackageAssembly.escapeXml(book.book.title)}</h1>
                ${book.book.creator?.let { "<p>${PackageAssembly.escapeXml(it)}</p>" } ?: ""}
                </section>
                </body></html>
            """.trimIndent().toByteArray()
            build.spine += SpineItem(titlePageId, titlePath)
            val children = mutableListOf<NavPoint>()
            val bookNav = NavPoint(titlePageId, book.book.title, titlePath, children)

            book.resources.forEach { (path, data) ->
                if (path.endsWith('/')) return@forEach
                val target = "$ns/$path"
                var rewritten = data
                if (path.endsWith(".xhtml", true) || path.endsWith(".html", true) ||
                    path.endsWith(".htm", true) || path.endsWith(".svg", true)
                ) {
                    rewritten = rewriteHtmlLinks(data, path, ns)
                } else if (path.endsWith(".css", true)) {
                    rewritten = rewriteCssUrls(data, path, ns)
                }
                if (path.endsWith(".xhtml", true) || path.endsWith(".html", true)) {
                    val text = rewritten.toString(Charsets.UTF_8)
                        .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\son\w+\s*="[^"]*""""), "")
                    rewritten = text.toByteArray(Charsets.UTF_8)
                }
                build.resources[target] = rewritten
            }

            book.spineHrefs.forEach { href ->
                val clean = href.substringBefore('#')
                val target = "$ns/$clean"
                if (build.resources.containsKey(target)) {
                    val id = "imp-$importIndex-${build.fileIndex}"
                    build.spine += SpineItem(id, target)
                    val label = book.navToc.flattenLabels().firstOrNull { it.second == href }?.first
                        ?: clean.substringAfterLast('/')
                    children += NavPoint(id, label, target)
                    build.fileIndex++
                }
            }
            if (children.isEmpty()) {
                build.resources.keys.filter { it.startsWith("$ns/") && it.endsWith(".xhtml", true) }
                    .take(20)
                    .forEach { path ->
                        val id = "imp-$importIndex-${build.fileIndex}"
                        build.spine += SpineItem(id, path)
                        children += NavPoint(id, path.substringAfterLast('/'), path)
                        build.fileIndex++
                    }
            }
            build.navPoints += bookNav
        }

        val identifier = "urn:uuid:${UUID.randomUUID()}"
        return PackageAssembly.finalize(
            title = snapshot.title,
            creator = snapshot.curator ?: "StoryWeaver",
            language = language,
            identifier = identifier,
            build = build,
        )
    }

    private fun List<ImportedNavEntry>.flattenLabels(): List<Pair<String, String?>> =
        flatMap { listOf(it.title to it.href) + it.children.flattenLabels() }

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
}
