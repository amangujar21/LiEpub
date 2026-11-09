package com.storyweaver.source.literotica

import com.storyweaver.core.model.SourceType
import com.storyweaver.core.model.SourceUrl
import com.storyweaver.core.model.SourceValidation
import com.storyweaver.core.network.SupportedHosts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlClassifier {
    private val authorWorks = Regex(
        """^/authors/([^/]+)(?:/works(?:/stories)?)?/?$""",
        RegexOption.IGNORE_CASE,
    )
    private val series = Regex(
        """^/series/se/([^/]+)/?$""",
        RegexOption.IGNORE_CASE,
    )
    private val story = Regex(
        """^/s/([^/]+)/?$""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(raw: String): SourceUrl {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return SourceUrl(trimmed, null, SourceType.UNKNOWN, SourceValidation.INVALID_URL, "Empty URL")
        }
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("www.", ignoreCase = true) ||
                trimmed.startsWith("literotica.", ignoreCase = true) -> "https://$trimmed"
            else -> trimmed
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: return SourceUrl(trimmed, null, SourceType.UNKNOWN, SourceValidation.INVALID_URL, "Not a valid URL")

        if (!url.isHttps && url.scheme != "https") {
            // allow http only if we will upgrade conceptually — still reject non-https hosts we fetch
            if (url.scheme != "http") {
                return SourceUrl(trimmed, null, SourceType.UNKNOWN, SourceValidation.INSECURE_SCHEME, "Only http(s) URLs are supported")
            }
        }
        if (!SupportedHosts.isAllowed(url.host)) {
            return SourceUrl(
                trimmed,
                null,
                SourceType.UNKNOWN,
                SourceValidation.UNSUPPORTED_HOST,
                "Host not allowed: ${url.host}",
            )
        }

        val path = url.encodedPath
        authorWorks.matchEntire(path)?.let { match ->
            val username = match.groupValues[1]
            val canonical = url.newBuilder()
                .scheme("https")
                .host(normalizeHost(url.host))
                .encodedPath("/authors/$username/works/stories")
                .query(null)
                .fragment(null)
                .build()
                .toString()
            return SourceUrl(trimmed, canonical, SourceType.AUTHOR_WORKS, SourceValidation.VALID)
        }
        series.matchEntire(path)?.let { match ->
            val id = match.groupValues[1]
            val canonical = "https://${normalizeHost(url.host)}/series/se/$id"
            return SourceUrl(trimmed, canonical, SourceType.SERIES, SourceValidation.VALID)
        }
        story.matchEntire(path)?.let { match ->
            val slug = match.groupValues[1]
            val canonical = "https://${normalizeHost(url.host)}/s/$slug"
            return SourceUrl(trimmed, canonical, SourceType.STORY, SourceValidation.VALID)
        }

        return SourceUrl(
            trimmed,
            null,
            SourceType.UNKNOWN,
            SourceValidation.UNSUPPORTED_PATH,
            "Unsupported Literotica path: $path",
        )
    }

    private fun normalizeHost(host: String): String =
        if (host.equals("literotica.com", ignoreCase = true)) "www.literotica.com" else host.lowercase()
}
