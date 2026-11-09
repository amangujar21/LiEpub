package com.storyweaver.epub

import com.storyweaver.core.model.ImportedBook
import com.storyweaver.core.model.ImportedBookId
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.w3c.dom.Document
import org.w3c.dom.Element

data class ImportedEpub(
    val book: ImportedBook,
    val resources: Map<String, ByteArray>,
    val spineHrefs: List<String>,
    val navToc: List<ImportedNavEntry>,
    val opfPath: String,
    val warnings: List<String> = emptyList(),
    /** Zip path of the EPUB 3 navigation document, if any. */
    val navDocumentPath: String? = null,
    /** Zip path of the EPUB 2 NCX, if any. */
    val ncxPath: String? = null,
)

/**
 * One entry of an imported book's table of contents.
 *
 * [href] is resolved to a full zip path (e.g. `OEBPS/Text/ch1.xhtml`), keeping any
 * `#fragment`, so it can be matched against [ImportedEpub.resources] keys directly.
 * External links resolve to null.
 */
data class ImportedNavEntry(
    val title: String,
    val href: String?,
    val children: List<ImportedNavEntry> = emptyList(),
)

/**
 * Safe EPUB 2/3 reader with ZIP-slip, size, and DRM checks.
 */
class EpubImporter(
    private val maxEntries: Int = 5_000,
    private val maxTotalUncompressed: Long = 200L * 1024 * 1024,
    private val maxSingleEntry: Long = 50L * 1024 * 1024,
    private val maxCompressionRatio: Double = 100.0,
) {
    fun import(bytes: ByteArray, documentUri: String, id: ImportedBookId = ImportedBookId("import-${sha256(bytes).take(12)}")): ImportedEpub {
        val warnings = mutableListOf<String>()
        val entries = readZipSafely(bytes)
        if (!entries.containsKey("mimetype") && !entries.keys.any { it.equals("mimetype", true) }) {
            // Some producers omit mimetype; warn but continue if container exists
            warnings += "Missing mimetype entry"
        }
        val container = entries["META-INF/container.xml"]
            ?: entries.entries.firstOrNull { it.key.equals("META-INF/container.xml", true) }?.value
            ?: throw IllegalArgumentException("Not a valid EPUB: missing META-INF/container.xml")
        val opfPath = parseContainerRootfile(container)
            ?: throw IllegalArgumentException("container.xml has no rootfile")
        val opfBytes = entries[opfPath]
            ?: entries.entries.firstOrNull { it.key.equals(opfPath, true) }?.value
            ?: throw IllegalArgumentException("OPF not found at $opfPath")

        if (entries.keys.any { it.contains("META-INF/encryption.xml", ignoreCase = true) }) {
            val enc = entries.entries.first { it.key.contains("encryption.xml", ignoreCase = true) }.value
            val encText = enc.toString(Charsets.UTF_8)
            if (encText.contains("http://ns.adobe.com/digitaleditions/enc", ignoreCase = true) ||
                encText.contains("EncryptedData") && !encText.contains("font")
            ) {
                // Font obfuscation is often OK; full DRM is not
                if (!encText.contains("http://www.idpf.org/2008/embedding", ignoreCase = true) &&
                    !encText.contains("http://ns.adobe.com/pdf/enc", ignoreCase = true)
                ) {
                    if (encText.contains("EncryptionMethod") && encText.contains("CipherData")) {
                        throw IllegalArgumentException("DRM/encryption detected; only DRM-free EPUBs are supported")
                    }
                } else {
                    warnings += "Font obfuscation present; fonts may need special handling"
                }
            }
        }

        val opfDoc = parseXml(opfBytes)
        val packageEl = opfDoc.documentElement
        val version = packageEl.getAttribute("version").ifBlank { "2.0" }
        val metadata = packageEl.getElementsByTagName("metadata").item(0) as? Element
            ?: packageEl.getElementsByTagNameNS("*", "metadata").item(0) as? Element
        val title = textOf(metadata, "title") ?: "Imported Book"
        val creator = textOf(metadata, "creator")
        val language = textOf(metadata, "language")
        val identifier = textOf(metadata, "identifier")
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "").let {
            if (it.isEmpty()) "" else "$it/"
        }

        val manifest = mutableMapOf<String, ManifestItem>()
        val manifestEl = packageEl.getElementsByTagName("manifest").item(0) as? Element
            ?: packageEl.getElementsByTagNameNS("*", "manifest").item(0) as? Element
        if (manifestEl != null) {
            val items = manifestEl.getElementsByTagName("item")
            val itemsNs = manifestEl.getElementsByTagNameNS("*", "item")
            val list = if (items.length > 0) items else itemsNs
            for (i in 0 until list.length) {
                val el = list.item(i) as Element
                val itemId = el.getAttribute("id")
                val href = el.getAttribute("href")
                val media = el.getAttribute("media-type")
                val props = el.getAttribute("properties")
                if (itemId.isNotBlank() && href.isNotBlank()) {
                    manifest[itemId] = ManifestItem(itemId, resolvePath(opfDir, href), media, props)
                }
            }
        }

        val spineHrefs = mutableListOf<String>()
        val spineEl = packageEl.getElementsByTagName("spine").item(0) as? Element
            ?: packageEl.getElementsByTagNameNS("*", "spine").item(0) as? Element
        if (spineEl != null) {
            val refs = spineEl.getElementsByTagName("itemref")
            val refsNs = spineEl.getElementsByTagNameNS("*", "itemref")
            val list = if (refs.length > 0) refs else refsNs
            for (i in 0 until list.length) {
                val idref = (list.item(i) as Element).getAttribute("idref")
                manifest[idref]?.let { spineHrefs += it.href }
            }
        }

        val navSource = findNavSource(manifest)
        val navToc = parseNav(entries, navSource, warnings)
        val fingerprint = sha256(bytes)
        val book = ImportedBook(
            id = id,
            documentUri = documentUri,
            packageIdentifier = identifier,
            title = title,
            creator = creator,
            language = language,
            formatVersion = version,
            contentFingerprint = fingerprint,
            available = true,
        )
        // Keep only permitted resources
        val resources = entries.filterKeys { path ->
            !path.endsWith("/") && path != "mimetype"
        }
        if (resources.values.any { it.size > 1 && looksLikeScript(it) }) {
            warnings += "Scripted content may be neutralized on merge"
        }
        return ImportedEpub(
            book = book,
            resources = resources,
            spineHrefs = spineHrefs,
            navToc = navToc,
            opfPath = opfPath,
            warnings = warnings,
            navDocumentPath = navSource.navPath,
            ncxPath = navSource.ncxPath,
        )
    }

    private data class NavSource(val navPath: String?, val ncxPath: String?)

    private fun findNavSource(manifest: Map<String, ManifestItem>): NavSource {
        val nav = manifest.values.firstOrNull { "nav" in it.properties.split(Regex("\\s+")) }?.href
        val ncx = (
            manifest.values.firstOrNull { it.mediaType.contains("ncx", ignoreCase = true) }
                ?: manifest.values.firstOrNull { it.href.endsWith(".ncx", true) }
            )?.href
        return NavSource(nav, ncx)
    }

    private fun looksLikeScript(bytes: ByteArray): Boolean {
        val head = bytes.take(200).toByteArray().toString(Charsets.UTF_8)
        return head.contains("<script", ignoreCase = true)
    }

    private fun parseNav(
        entries: Map<String, ByteArray>,
        source: NavSource,
        warnings: MutableList<String>,
    ): List<ImportedNavEntry> {
        // Prefer the EPUB 3 nav document; fall back to the NCX when it is missing or empty.
        source.navPath?.let { path ->
            val bytes = entries[path]
            if (bytes != null) {
                val parsed = runCatching { parseNavXhtml(bytes, dirOf(path)) }
                    .onFailure { warnings += "Failed to parse navigation document" }
                    .getOrDefault(emptyList())
                if (parsed.isNotEmpty()) return parsed
            }
        }
        source.ncxPath?.let { path ->
            val bytes = entries[path] ?: return emptyList()
            return runCatching { parseNcx(bytes, dirOf(path)) }.getOrElse {
                warnings += "Failed to parse NCX"
                emptyList()
            }
        }
        return emptyList()
    }

    private fun dirOf(path: String): String =
        path.substringBeforeLast('/', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "$it/" }

    /** Resolve a TOC href relative to the nav/NCX file into a zip path, keeping `#fragment`. */
    private fun resolveNavHref(baseDir: String, href: String?): String? {
        val raw = href?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (URI_SCHEME.containsMatchIn(raw)) return null // http:, mailto:, …
        val pathPart = raw.substringBefore('#')
        if (pathPart.isEmpty()) return null
        val fragment = raw.substringAfter('#', missingDelimiterValue = "")
        val resolved = resolvePath(baseDir, pathPart)
        return if (fragment.isNotEmpty()) "$resolved#$fragment" else resolved
    }

    private fun localName(el: Element): String =
        (el.localName ?: el.tagName.substringAfter(':')).lowercase()

    /** First direct child element named [name] (case-insensitive, prefix-agnostic). */
    private fun directChild(parent: Element, name: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && localName(n) == name.lowercase()) return n
        }
        return null
    }

    private fun labelText(el: Element?): String? =
        el?.textContent?.replace(WHITESPACE, " ")?.trim()?.ifEmpty { null }

    private fun parseNavXhtml(bytes: ByteArray, base: String): List<ImportedNavEntry> {
        val doc = parseXml(bytes)
        val navs = doc.getElementsByTagName("nav")
        var toc: Element? = null
        for (i in 0 until navs.length) {
            val el = navs.item(i) as Element
            val type = el.getAttribute("epub:type").ifBlank { el.getAttributeNS("http://www.idpf.org/2007/ops", "type") }
            if (type.contains("toc") || toc == null) toc = el
        }
        val rootOl = toc?.getElementsByTagName("ol")?.item(0) as? Element ?: return emptyList()
        return parseOl(rootOl, base)
    }

    private fun parseOl(ol: Element, base: String): List<ImportedNavEntry> {
        val result = mutableListOf<ImportedNavEntry>()
        val children = ol.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element || localName(node) != "li") continue
            // Use this <li>'s own label only — never one from a nested list.
            val label = directChild(node, "a") ?: directChild(node, "span")
            val title = labelText(label) ?: "Section"
            val href = label?.takeIf { localName(it) == "a" }?.getAttribute("href")
            val nested = directChild(node, "ol")
            result += ImportedNavEntry(title, resolveNavHref(base, href), nested?.let { parseOl(it, base) }.orEmpty())
        }
        return result
    }

    private fun parseNcx(bytes: ByteArray, base: String): List<ImportedNavEntry> {
        val doc = parseXml(bytes)
        val navMap = (doc.getElementsByTagName("navMap").item(0) ?: doc.getElementsByTagNameNS("*", "navMap").item(0))
            as? Element ?: return emptyList()
        fun walk(parent: Element): List<ImportedNavEntry> {
            val list = mutableListOf<ImportedNavEntry>()
            val points = parent.childNodes
            for (i in 0 until points.length) {
                val n = points.item(i)
                if (n !is Element || localName(n) != "navpoint") continue
                val label = labelText(directChild(n, "navLabel")) ?: "Section"
                val src = directChild(n, "content")?.getAttribute("src")
                list += ImportedNavEntry(label, resolveNavHref(base, src), walk(n))
            }
            return list
        }
        return walk(navMap)
    }

    private fun readZipSafely(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                count++
                if (count > maxEntries) throw IllegalArgumentException("ZIP entry count exceeds limit")
                val name = entry.name.replace('\\', '/')
                if (name.contains("..") || name.startsWith("/")) {
                    throw IllegalArgumentException("ZIP-slip blocked: $name")
                }
                if (entry.isDirectory) continue
                val data = zis.readBytes()
                if (data.size > maxSingleEntry) throw IllegalArgumentException("Entry too large: $name")
                if (entry.compressedSize > 0) {
                    val ratio = data.size.toDouble() / entry.compressedSize.toDouble()
                    if (ratio > maxCompressionRatio && data.size > 1024 * 1024) {
                        throw IllegalArgumentException("Suspicious compression ratio for $name")
                    }
                }
                total += data.size
                if (total > maxTotalUncompressed) throw IllegalArgumentException("Decompressed size exceeds limit")
                out[name] = data
            }
        }
        return out
    }

    private fun parseContainerRootfile(container: ByteArray): String? {
        val doc = parseXml(container)
        val rootfiles = doc.getElementsByTagName("rootfile")
        if (rootfiles.length == 0) return null
        return (rootfiles.item(0) as Element).getAttribute("full-path")
    }

    private fun parseXml(bytes: ByteArray): Document = SafeXml.parse(bytes)

    private fun textOf(metadata: Element?, local: String): String? {
        if (metadata == null) return null
        val nodes = metadata.getElementsByTagNameNS("*", local)
        if (nodes.length == 0) {
            val plain = metadata.getElementsByTagName(local)
            if (plain.length == 0) return null
            return plain.item(0).textContent?.trim()?.ifBlank { null }
        }
        return nodes.item(0).textContent?.trim()?.ifBlank { null }
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val clean = percentDecode(href.substringBefore('#')).replace('\\', '/')
        if (clean.startsWith("/")) return clean.trimStart('/')
        if (baseDir.isEmpty()) return clean
        val combined = (baseDir + clean).split('/')
        val stack = mutableListOf<String>()
        combined.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += part
            }
        }
        return stack.joinToString("/")
    }

    /** Decode %XX escapes as UTF-8. Unlike URLDecoder, '+' stays a literal plus. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 <= s.lastIndex) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    companion object {
        private val URI_SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")
        private val WHITESPACE = Regex("""\s+""")

        fun sha256(bytes: ByteArray): String {
            val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
            return dig.joinToString("") { "%02x".format(it) }
        }
    }
}

private fun sha256(bytes: ByteArray): String = EpubImporter.sha256(bytes)
