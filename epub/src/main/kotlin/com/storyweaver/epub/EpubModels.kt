package com.storyweaver.epub

import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ExportSnapshot

data class EpubWriteRequest(
    val snapshot: ExportSnapshot,
    val language: String = "en",
    val identifier: String = "urn:uuid:${java.util.UUID.randomUUID()}",
)

data class EpubWriteResult(
    val bytes: ByteArray,
    val filename: String,
    val validation: StructuralValidation,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpubWriteResult) return false
        return filename == other.filename && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * filename.hashCode() + bytes.contentHashCode()
}

data class StructuralValidation(
    val ok: Boolean,
    val issues: List<String>,
)

object FilenameSanitizer {
    private val illegal = Regex("""[<>:"/\\|?*\u0000-\u001F]""")

    fun sanitize(name: String, fallback: String = "storyweaver-book"): String {
        val cleaned = name.trim()
            .replace(illegal, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        val base = cleaned.ifBlank { fallback }.take(120)
        return if (base.endsWith(".epub", ignoreCase = true)) base else "$base.epub"
    }

    fun numberedCopy(filename: String, index: Int): String {
        val stem = filename.removeSuffix(".epub").removeSuffix(".EPUB")
        return "$stem ($index).epub"
    }
}

fun EpubStyle.stylesheetCss(): String = when (this) {
    EpubStyle.MODERN -> """
        body { font-family: serif; line-height: 1.55; margin: 1.2em; color: #1a1a1a; }
        h1, h2, h3 { font-family: sans-serif; font-weight: 600; color: #2b2b2b; }
        .divider { text-align: center; margin: 3em 1em; }
        .divider h1 { font-size: 1.6em; letter-spacing: 0.02em; }
        p { margin: 0.7em 0; text-indent: 1.2em; }
        p.first { text-indent: 0; }
        a { color: #7A2E3D; }
        blockquote { margin: 1em 1.5em; color: #333; }
        hr { border: none; border-top: 1px solid #ccc; margin: 2em 0; }
    """.trimIndent()
    EpubStyle.CLASSIC -> """
        body { font-family: "Times New Roman", serif; line-height: 1.5; margin: 1em; }
        h1, h2 { text-align: center; }
        p { text-indent: 1.5em; margin: 0.4em 0; }
    """.trimIndent()
    EpubStyle.MINIMAL -> """
        body { font-family: serif; line-height: 1.5; margin: 1em; }
        h1, h2, h3 { font-weight: normal; }
        p { margin: 0.6em 0; }
    """.trimIndent()
}
