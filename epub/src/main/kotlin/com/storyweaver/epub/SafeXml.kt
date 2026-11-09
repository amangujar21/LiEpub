package com.storyweaver.epub

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource

/**
 * Namespace-aware XML parsing for EPUB content that works on both the JVM and Android.
 *
 * - Real EPUB 3 XHTML carries `<!DOCTYPE html>`, so DOCTYPEs are allowed, but external
 *   entities and external DTDs are never fetched (XXE hardening).
 * - Hardening features are best-effort: Android's built-in parser throws
 *   ParserConfigurationException for Apache-only feature URIs, so each one is optional.
 */
internal object SafeXml {
    private val features = listOf(
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
    )

    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            features.forEach { (feature, enabled) ->
                try {
                    setFeature(feature, enabled)
                } catch (_: Exception) {
                    // Unsupported on this implementation (e.g. Android) — continue.
                }
            }
            try {
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            } catch (_: Exception) {
                // Not all factories support these attributes.
            }
        }
    }

    private fun newBuilder(): DocumentBuilder = synchronized(factory) { factory.newDocumentBuilder() }.apply {
        // Never resolve external entities, even if a parser ignores the features above.
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
        setErrorHandler(null)
    }

    fun parse(bytes: ByteArray): Document {
        // Strip a BOM / leading whitespace so an XML declaration sits at offset 0.
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            start = 3
        }
        while (start < bytes.size && bytes[start].toInt().toChar() in " \t\r\n") start++
        return newBuilder().parse(ByteArrayInputStream(bytes, start, bytes.size - start))
    }
}
