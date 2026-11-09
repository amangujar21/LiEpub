package com.storyweaver.epub

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

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

        // Well-formedness check. SafeXml allows the standard `<!DOCTYPE html>` used by real
        // EPUB 3 content while never loading external entities/DTDs, and only sets parser
        // features the platform supports (Android rejects Apache-only feature URIs).
        entries.filterKeys { it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") || it.endsWith(".xml") }
            .forEach { (name, bytes) ->
                try {
                    SafeXml.parse(bytes)
                } catch (e: Exception) {
                    issues += "XML parse failed for $name: ${e.message}"
                }
            }

        // Basic spine/manifest path checks
        val opf = entries["OEBPS/content.opf"]?.toString(Charsets.UTF_8).orEmpty()
        val hrefRegex = Regex("""href="([^"]+)"""")
        hrefRegex.findAll(opf).forEach { match ->
            val href = match.groupValues[1]
            // Hrefs are XML-escaped, percent-encoded URLs; zip entry names are plain paths.
            val decoded = runCatching {
                java.net.URLDecoder.decode(
                    href.replace("&amp;", "&").replace("&apos;", "'").replace("+", "%2B"),
                    "UTF-8",
                )
            }.getOrDefault(href)
            val path = "OEBPS/$decoded"
            if (!entries.containsKey(path) && !href.startsWith("http")) {
                issues += "Manifest href missing: $href"
            }
        }

        return StructuralValidation(ok = issues.isEmpty(), issues = issues)
    }

}
