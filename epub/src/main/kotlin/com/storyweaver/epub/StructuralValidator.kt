package com.storyweaver.epub

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object StructuralValidator {
    fun validate(epubBytes: ByteArray): StructuralValidation {
        val issues = mutableListOf<String>()
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(epubBytes)).use { zis ->
            var entry = zis.nextEntry
            var index = 0
            while (entry != null) {
                if (index == 0 && entry.name != "mimetype") {
                    issues += "First ZIP entry must be mimetype"
                }
                if (entry.name.contains("..") || entry.name.startsWith("/")) {
                    issues += "Unsafe ZIP entry path: ${entry.name}"
                }
                entries[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
                index++
            }
        }

        if (entries["mimetype"]?.toString(Charsets.US_ASCII) != "application/epub+zip") {
            issues += "Invalid or missing mimetype"
        }
        if (!entries.containsKey("META-INF/container.xml")) {
            issues += "Missing META-INF/container.xml"
        }
        if (!entries.containsKey("OEBPS/content.opf")) {
            issues += "Missing OEBPS/content.opf"
        }
        if (!entries.containsKey("OEBPS/nav.xhtml")) {
            issues += "Missing nav document"
        }
        if (!entries.containsKey("OEBPS/toc.ncx")) {
            issues += "Missing NCX fallback"
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Harden against XXE. Features are best-effort: Android's built-in parser
            // rejects Apache-only feature URIs (throws with the URI as the message),
            // which previously aborted the entire export as "Export failed".
            secureXmlFeatures().forEach { (feature, enabled) ->
                try {
                    setFeature(feature, enabled)
                } catch (_: Exception) {
                    // Unsupported on this factory implementation — continue.
                }
            }
            try {
                setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            } catch (_: Exception) {
                // Not all factories support these attributes.
            }
        }

        entries.filterKeys { it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") || it.endsWith(".xml") }
            .forEach { (name, bytes) ->
                try {
                    // Normalize: strip BOM/leading whitespace so XML declaration is at offset 0
                    val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    issues += "XML parse failed for $name: ${e.message}"
                }
            }

        // Basic spine/manifest path checks
        val opf = entries["OEBPS/content.opf"]?.toString(Charsets.UTF_8).orEmpty()
        val hrefRegex = Regex("""href="([^"]+)"""")
        hrefRegex.findAll(opf).forEach { match ->
            val href = match.groupValues[1]
            val path = "OEBPS/$href"
            if (!entries.containsKey(path) && !href.startsWith("http")) {
                issues += "Manifest href missing: $href"
            }
        }

        return StructuralValidation(ok = issues.isEmpty(), issues = issues)
    }

    private fun secureXmlFeatures(): List<Pair<String, Boolean>> = listOf(
        // Prefer disallowing DOCTYPE entirely (our packages never need one).
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )
}
