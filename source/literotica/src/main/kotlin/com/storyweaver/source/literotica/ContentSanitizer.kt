package com.storyweaver.source.literotica

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist
import java.security.MessageDigest

object ContentSanitizer {
    private val safelist: Safelist = Safelist.none()
        .addTags(
            "p", "br", "em", "strong", "i", "b", "u", "s", "blockquote",
            "h1", "h2", "h3", "h4", "hr", "ul", "ol", "li", "a", "span", "div",
        )
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https")

    fun sanitizeStoryHtml(rawHtml: String, baseUri: String): SanitizedContent {
        val cleaned = Jsoup.clean(rawHtml, baseUri, safelist)
        val doc = Jsoup.parseBodyFragment(cleaned, baseUri)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(true)

        // Resolve links to absolute HTTPS and drop unsafe leftovers
        doc.select("a[href]").forEach { a ->
            val abs = a.absUrl("href")
            if (abs.startsWith("https://") || abs.startsWith("http://")) {
                val https = abs.replaceFirst("^http://".toRegex(), "https://")
                a.attr("href", https)
            } else {
                a.unwrap()
            }
        }

        // Strip empty wrappers that only existed for layout
        doc.select("div, span").forEach { el ->
            if (el.attributes().isEmpty && el.children().isEmpty() && el.text().isBlank()) {
                el.remove()
            }
        }

        val body = buildString {
            for (node in doc.body().childNodes()) {
                append(renderNode(node))
            }
        }.trim()

        val hash = sha256(body)
        return SanitizedContent(xhtmlBody = body.ifBlank { "<p></p>" }, contentHash = hash)
    }

    private fun renderNode(node: Node): String = when (node) {
        is TextNode -> escapeXml(node.text())
        is Element -> node.outerHtml()
        else -> ""
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class SanitizedContent(
    val xhtmlBody: String,
    val contentHash: String,
)
