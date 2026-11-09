package com.storyweaver.source.literotica

import com.storyweaver.core.model.AuthorCatalog
import com.storyweaver.core.model.CatalogSeries
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.SourceType
import com.storyweaver.core.model.SourceUrl
import com.storyweaver.core.model.SourceValidation
import com.storyweaver.core.network.HttpFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ResolveProgress(
    val completed: Int,
    val total: Int,
    val currentUrl: String,
    val message: String,
)

interface LiteroticaSource {
    fun classify(url: String): SourceUrl
    fun classifyMany(raw: String): List<SourceUrl>
    suspend fun fetchAuthorCatalog(url: String): AuthorCatalog
    suspend fun fetchSeries(url: String): CatalogSeries
    suspend fun fetchChapterContent(url: String): ChapterContent
    /** Resolve a story page into standalone or full series when the site links membership. */
    suspend fun resolveStory(url: String): AuthorCatalog
    /**
     * Analyze one or more URLs into a merged multi-author draft model plus a combined
     * catalog view when all sources share a single author (else a synthetic multi catalog).
     */
    suspend fun resolveUrls(
        urls: List<String>,
        onProgress: (ResolveProgress) -> Unit = {},
    ): ResolveResult
    suspend fun downloadChapters(
        urls: List<String>,
        concurrency: Int = 2,
        onProgress: (completed: Int, total: Int, url: String) -> Unit = { _, _, _ -> },
    ): Map<String, Result<ChapterContent>>
}

data class ResolveResult(
    val catalog: AuthorCatalog,
    val authors: List<com.storyweaver.core.model.Author>,
    val works: List<com.storyweaver.core.model.Work>,
    val chapters: List<com.storyweaver.core.model.Chapter>,
    val warnings: List<com.storyweaver.core.model.ParseWarning>,
    val linkErrors: Map<String, String>,
)

class DefaultLiteroticaSource(
    private val fetcher: HttpFetcher,
) : LiteroticaSource {
    override fun classify(url: String): SourceUrl = UrlClassifier.classify(url)

    override fun classifyMany(raw: String): List<SourceUrl> {
        val tokens = raw.split(Regex("""[\s,;]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return tokens.map { classify(it) }
    }

    override suspend fun fetchAuthorCatalog(url: String): AuthorCatalog {
        val classified = classify(url)
        require(classified.validation == SourceValidation.VALID) {
            classified.message ?: "Invalid author URL"
        }
        require(classified.sourceType == SourceType.AUTHOR_WORKS) {
            "Expected author works URL, got ${classified.sourceType}"
        }
        val canonical = classified.canonical!!
        val page = fetcher.getText(canonical)
        return LiteroticaParsers.parseAuthorCatalog(page.body, page.finalUrl)
    }

    override suspend fun fetchSeries(url: String): CatalogSeries {
        val classified = classify(url)
        require(classified.validation == SourceValidation.VALID && classified.sourceType == SourceType.SERIES) {
            classified.message ?: "Invalid series URL"
        }
        val page = fetcher.getText(classified.canonical!!)
        return LiteroticaParsers.parseSeriesPage(page.body, page.finalUrl)
    }

    override suspend fun fetchChapterContent(url: String): ChapterContent {
        val classified = classify(url)
        require(classified.validation == SourceValidation.VALID && classified.sourceType == SourceType.STORY) {
            classified.message ?: "Invalid story URL"
        }
        val page = fetcher.getText(classified.canonical!!)
        val parsed = LiteroticaParsers.parseStoryPage(page.body, page.finalUrl)
        if (parsed.continuationUrls.isEmpty()) {
            return parsed.content
        }
        // Merge continuation pages in order
        val bodies = buildList {
            add(parsed.content.xhtmlBody)
            parsed.continuationUrls.forEach { contUrl ->
                val contPage = fetcher.getText(contUrl)
                val cont = LiteroticaParsers.parseStoryPage(contPage.body, contPage.finalUrl)
                add(cont.content.xhtmlBody)
            }
        }
        val merged = bodies.joinToString("\n")
        val hash = ContentSanitizer.sha256(merged)
        return parsed.content.copy(xhtmlBody = merged, contentHash = hash, sourceFingerprint = hash)
    }

    override suspend fun resolveStory(url: String): AuthorCatalog {
        val classified = classify(url)
        require(classified.validation == SourceValidation.VALID && classified.sourceType == SourceType.STORY) {
            classified.message ?: "Invalid story URL"
        }
        val page = fetcher.getText(classified.canonical!!)
        val parsed = LiteroticaParsers.parseStoryPage(page.body, page.finalUrl)
        val seriesUrl = parsed.seriesUrl
        if (seriesUrl != null) {
            val series = fetchSeries(seriesUrl)
            // Prefer author metadata from the story page when series page is sparse
            val author = parsed.author.copy(
                displayName = parsed.author.displayName.takeIf { it != "Unknown" }
                    ?: series.work.authorId.value.removePrefix("author-"),
            )
            return AuthorCatalog(
                author = author,
                series = listOf(
                    series.copy(
                        work = series.work.copy(authorId = author.id),
                        selectedByDefault = true,
                    ),
                ),
                standalones = emptyList(),
            )
        }
        return AuthorCatalog(
            author = parsed.author,
            series = emptyList(),
            standalones = listOf(
                com.storyweaver.core.model.CatalogStandalone(
                    work = parsed.work.copy(type = com.storyweaver.core.model.WorkType.STANDALONE),
                    chapter = parsed.chapter,
                    selectedByDefault = true,
                ),
            ),
        )
    }

    override suspend fun resolveUrls(
        urls: List<String>,
        onProgress: (ResolveProgress) -> Unit,
    ): ResolveResult {
        val classified = urls.flatMap { raw ->
            if (raw.contains("\n") || raw.contains(" ") || raw.contains(",")) {
                classifyMany(raw)
            } else {
                listOf(classify(raw))
            }
        }.distinctBy { it.canonical ?: it.submitted }

        val valid = classified.filter { it.validation == SourceValidation.VALID && it.canonical != null }
        val linkErrors = classified
            .filter { it.validation != SourceValidation.VALID }
            .associate { it.submitted to (it.message ?: it.validation.name) }
            .toMutableMap()

        val catalogs = mutableListOf<AuthorCatalog>()
        val total = valid.size.coerceAtLeast(1)
        valid.forEachIndexed { index, source ->
            val canonical = source.canonical!!
            onProgress(
                ResolveProgress(
                    completed = index,
                    total = total,
                    currentUrl = canonical,
                    message = "Analyzing ${index + 1}/$total…",
                ),
            )
            try {
                when (source.sourceType) {
                    SourceType.AUTHOR_WORKS -> catalogs += fetchAuthorCatalog(canonical)
                    SourceType.SERIES -> {
                        val series = fetchSeries(canonical)
                        catalogs += AuthorCatalog(
                            author = com.storyweaver.core.model.Author(
                                id = series.work.authorId,
                                siteUsername = series.work.authorId.value.removePrefix("author-"),
                                displayName = series.work.authorId.value.removePrefix("author-"),
                                worksUrl = "",
                            ),
                            series = listOf(series),
                            standalones = emptyList(),
                        )
                    }
                    SourceType.STORY -> catalogs += resolveStory(canonical)
                    SourceType.UNKNOWN -> linkErrors[source.submitted] = "Unknown source type"
                }
            } catch (e: Exception) {
                linkErrors[canonical] = e.message ?: e.toString()
            }
        }
        onProgress(ResolveProgress(total, total, "", "Analysis complete"))

        if (catalogs.isEmpty()) {
            val emptyAuthor = com.storyweaver.core.model.Author(
                id = com.storyweaver.core.model.AuthorId("author-none"),
                siteUsername = "none",
                displayName = "No content",
                worksUrl = "",
            )
            return ResolveResult(
                catalog = AuthorCatalog(emptyAuthor, emptyList(), emptyList()),
                authors = emptyList(),
                works = emptyList(),
                chapters = emptyList(),
                warnings = emptyList(),
                linkErrors = linkErrors,
            )
        }

        // Merge by author then flatten
        val byAuthor = catalogs.groupBy { it.author.id }
        val mergedPerAuthor = byAuthor.map { (_, list) ->
            com.storyweaver.core.model.TocOperations.mergeCatalogs(list)
        }
        val primary = mergedPerAuthor.first()
        val allSeries = mergedPerAuthor.flatMap { it.series }.distinctBy { it.work.id }
        val allStandalones = mergedPerAuthor.flatMap { it.standalones }.distinctBy { it.work.id }
        val allAuthors = mergedPerAuthor.map { it.author }.distinctBy { it.id }
        val warnings = mergedPerAuthor.flatMap { it.warnings }
        val catalog = AuthorCatalog(
            author = if (allAuthors.size == 1) primary.author else primary.author.copy(
                displayName = if (allAuthors.size > 1) {
                    "${allAuthors.size} authors"
                } else {
                    primary.author.displayName
                },
            ),
            series = allSeries,
            standalones = allStandalones,
            warnings = warnings,
        )
        return ResolveResult(
            catalog = catalog,
            authors = allAuthors,
            works = allSeries.map { it.work } + allStandalones.map { it.work },
            chapters = allSeries.flatMap { it.chapters } + allStandalones.map { it.chapter },
            warnings = warnings,
            linkErrors = linkErrors,
        )
    }

    override suspend fun downloadChapters(
        urls: List<String>,
        concurrency: Int,
        onProgress: (completed: Int, total: Int, url: String) -> Unit,
    ): Map<String, Result<ChapterContent>> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        var completed = 0
        val total = urls.size
        urls.map { url ->
            async {
                semaphore.withPermit {
                    val result = runCatching { fetchChapterContent(url) }
                    synchronized(this@DefaultLiteroticaSource) {
                        completed++
                        onProgress(completed, total, url)
                    }
                    url to result
                }
            }
        }.awaitAll().toMap()
    }
}
