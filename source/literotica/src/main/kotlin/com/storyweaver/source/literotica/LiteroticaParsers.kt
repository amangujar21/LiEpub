package com.storyweaver.source.literotica

import com.storyweaver.core.model.Author
import com.storyweaver.core.model.AuthorCatalog
import com.storyweaver.core.model.AuthorId
import com.storyweaver.core.model.CatalogSeries
import com.storyweaver.core.model.CatalogStandalone
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.OrderKey
import com.storyweaver.core.model.ParseWarning
import com.storyweaver.core.model.Work
import com.storyweaver.core.model.WorkId
import com.storyweaver.core.model.WorkType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

object LiteroticaParsers {
    private val PAGE_QUERY = Regex("""[?&]page=(\d+)""")
    private val PAGE_PATH = Regex("""/s/[^/?#]+/(\d+)/?(?:[?#].*)?$""")
    private val STORY_SLUG = Regex("""/s/([^/?#]+)""")

    /**
     * Pages 2..N of a multi-page story, in reading order.
     *
     * Literotica pagination can be abbreviated ("1 2 3 … 12") and also includes Next/page-1
     * links, so the highest linked page number defines N and every page in between is
     * included exactly once. Only links to the same story slug count (not the next chapter).
     */
    fun continuationPageUrls(pageUrl: String, hrefs: List<String>): List<String> {
        val slug = STORY_SLUG.find(pageUrl)?.groupValues?.get(1) ?: return emptyList()
        val base = pageUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        val byPage = sortedMapOf<Int, String>()
        hrefs.forEach { href ->
            if (STORY_SLUG.find(href)?.groupValues?.get(1) != slug) return@forEach
            val n = PAGE_QUERY.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: PAGE_PATH.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            if (n >= 2) byPage.putIfAbsent(n, href.substringBefore('#'))
        }
        val last = (byPage.keys.maxOrNull() ?: return emptyList()).coerceAtMost(MAX_PAGES)
        return (2..last).map { n -> byPage[n] ?: "$base?page=$n" }
    }

    private const val MAX_PAGES = 500

    fun parseAuthorCatalog(html: String, pageUrl: String): AuthorCatalog {
        val doc = Jsoup.parse(html, pageUrl)
        val username = extractAuthorUsername(pageUrl, doc)
        val displayName = doc.selectFirst("h1, [itemprop=name], .author_name, .panel h1")
            ?.text()
            ?.trim()
            ?.ifBlank { username }
            ?: username
        val author = Author(
            id = AuthorId("author-$username"),
            siteUsername = username,
            displayName = displayName,
            worksUrl = pageUrl,
        )
        val warnings = mutableListOf<ParseWarning>()

        val series = linkedMapOf<String, MutableSeries>()
        val standalones = mutableListOf<CatalogStandalone>()
        val assignedStorySlugs = mutableSetOf<String>()

        // Prefer explicit series links and story cards with structural signals
        doc.select("a[href*=/series/se/]").forEach { link ->
            val href = link.absUrl("href")
            val id = Regex("""/series/se/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@forEach
            val title = link.text().trim().ifBlank { "Series $id" }
            series.getOrPut(id) {
                MutableSeries(
                    work = Work(
                        id = WorkId("series-$id"),
                        authorId = author.id,
                        title = title,
                        canonicalUrl = "https://www.literotica.com/series/se/$id",
                        type = WorkType.SERIES,
                    ),
                    chapters = mutableListOf(),
                )
            }.let {
                if (title.isNotBlank() && it.work.title.startsWith("Series ")) {
                    series[id] = it.copy(work = it.work.copy(title = title))
                }
            }
        }

        // Live author works pages: series title card + sibling "_parts" rows.
        // Part cards do not embed a /series/se/ link, so walk structure explicitly.
        attachChaptersFromSeriesPartsLayout(doc, author, series, assignedStorySlugs)

        // Story entries: links to /s/
        val storyLinks = doc.select("a[href*=/s/]")
            .mapNotNull { el ->
                val href = el.absUrl("href")
                val slug = Regex("""/s/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                StoryLink(slug = slug, title = el.text().trim().ifBlank { slug }, url = "https://www.literotica.com/s/$slug", element = el)
            }
            .distinctBy { it.slug }

        storyLinks.forEachIndexed { index, story ->
            if (story.slug in assignedStorySlugs) return@forEachIndexed

            // Only look at the story link's local row/card — not the whole page —
            // so standalones are not absorbed into an earlier series block.
            val localRoot = story.element.closest("li, article, tr, .story, .work, .panel")
                ?: story.element.parent()
            val seriesHref = findLocalSeriesHref(story.element, localRoot)
            val seriesId = seriesHref?.let { Regex("""/series/se/([^/?#]+)""").find(it)?.groupValues?.get(1) }
            val category = localRoot
                ?.selectFirst("[class*=category], .category, a[href*=/c/]")
                ?.text()?.trim()
            val date = localRoot
                ?.selectFirst("time, [class*=date], .date")
                ?.text()?.trim()

            if (seriesId != null) {
                val bucket = seriesBucket(series, seriesId, author, titleHint = null)
                appendChapter(bucket, story, date)
                assignedStorySlugs += story.slug
            } else {
                val workId = WorkId("standalone-${story.slug}")
                val chapterId = ChapterId("chapter-${story.slug}")
                standalones += CatalogStandalone(
                    work = Work(
                        id = workId,
                        authorId = author.id,
                        title = story.title,
                        category = category,
                        publicationDate = date,
                        canonicalUrl = story.url,
                        type = WorkType.STANDALONE,
                    ),
                    chapter = Chapter(
                        id = chapterId,
                        workId = workId,
                        sitePartId = null,
                        title = story.title,
                        orderKey = OrderKey.of(index + 1),
                        canonicalUrl = story.url,
                        publicationDate = date,
                    ),
                    selectedByDefault = false,
                )
            }
        }

        if (series.isEmpty() && standalones.isEmpty()) {
            warnings += ParseWarning(
                code = "EMPTY_CATALOG",
                message = "No stories or series detected on author works page",
                url = pageUrl,
            )
        }

        return AuthorCatalog(
            author = author,
            series = series.values.map {
                CatalogSeries(
                    work = it.work,
                    chapters = it.chapters.sortedBy { ch -> ch.orderKey },
                    selectedByDefault = true,
                )
            },
            standalones = standalones,
            warnings = warnings,
        )
    }

    fun parseSeriesPage(html: String, pageUrl: String): CatalogSeries {
        val doc = Jsoup.parse(html, pageUrl)
        val seriesId = Regex("""/series/se/([^/?#]+)""").find(pageUrl)?.groupValues?.get(1)
            ?: "unknown"
        val title = doc.selectFirst("h1, .series_title, [itemprop=name]")?.text()?.trim()
            ?: "Series $seriesId"
        val authorName = doc.selectFirst("a[href*=/authors/], .author, [itemprop=author]")?.text()?.trim()
            ?: "Unknown"
        val authorUsername = doc.selectFirst("a[href*=/authors/]")?.attr("href")
            ?.let { Regex("""/authors/([^/]+)""").find(it)?.groupValues?.get(1) }
            ?: authorName.lowercase(Locale.US).replace(" ", "-")
        val author = Author(
            id = AuthorId("author-$authorUsername"),
            siteUsername = authorUsername,
            displayName = authorName,
            worksUrl = "https://www.literotica.com/authors/$authorUsername/works/stories",
        )
        val work = Work(
            id = WorkId("series-$seriesId"),
            authorId = author.id,
            title = title,
            canonicalUrl = pageUrl.substringBefore('?').substringBefore('#'),
            type = WorkType.SERIES,
        )

        val chapters = doc.select("a[href*=/s/]")
            .mapNotNull { el ->
                val href = el.absUrl("href")
                val slug = Regex("""/s/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val text = el.text().trim().ifBlank { slug }
                StoryLink(slug, text, "https://www.literotica.com/s/$slug", el)
            }
            .distinctBy { it.slug }
            .mapIndexed { index, story ->
                val orderLabel = Regex("""(?:Ch\.?|Chapter|Part)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
                    .find(story.title)?.groupValues?.get(1)
                Chapter(
                    id = ChapterId("chapter-$seriesId-${index + 1}-${story.slug}"),
                    workId = work.id,
                    sitePartId = (index + 1).toString(),
                    title = story.title,
                    orderKey = orderLabel?.let(OrderKey::parse) ?: OrderKey.of(index + 1),
                    canonicalUrl = story.url,
                )
            }
            .sortedBy { it.orderKey }

        return CatalogSeries(work = work, chapters = chapters, selectedByDefault = true)
    }

    fun parseStoryPage(html: String, pageUrl: String): StoryPageParse {
        val doc = Jsoup.parse(html, pageUrl)
        val title = doc.selectFirst("h1, .headline, [itemprop=name], .panel h1")?.text()?.trim()
            ?: "Untitled"
        val authorEl = doc.selectFirst("a[href*=/authors/], .y_eS, [itemprop=author] a, .author")
        val authorName = authorEl?.text()?.trim() ?: "Unknown"
        val authorUsername = authorEl?.attr("href")
            ?.let { Regex("""/authors/([^/]+)""").find(it)?.groupValues?.get(1) }
            ?: authorName.lowercase(Locale.US).replace(Regex("""\s+"""), "-")
        val seriesLink = doc.selectFirst("a[href*=/series/se/]")?.absUrl("href")
        val category = doc.selectFirst("a[href*=/c/], .category")?.text()?.trim()
        val description = doc.selectFirst(".panel .aa_ht p, [itemprop=description], .story_description")
            ?.text()?.trim()
        val date = doc.selectFirst("time, .date, span[class*=date]")?.text()?.trim()

        val contentRoot = findStoryBody(doc)
            ?: throw IllegalStateException("Story body not found for $pageUrl")
        val sanitized = ContentSanitizer.sanitizeStoryHtml(contentRoot.html(), pageUrl)

        val continuationUrls = continuationPageUrls(pageUrl, doc.select("a[href]").map { it.absUrl("href") })

        val slug = Regex("""/s/([^/?#]+)""").find(pageUrl)?.groupValues?.get(1) ?: "story"
        val workType = if (seriesLink != null) WorkType.SERIES else WorkType.STANDALONE
        val workId = if (seriesLink != null) {
            val sid = Regex("""/series/se/([^/?#]+)""").find(seriesLink)?.groupValues?.get(1) ?: "unknown"
            WorkId("series-$sid")
        } else {
            WorkId("standalone-$slug")
        }

        val chapter = Chapter(
            id = ChapterId("chapter-$slug"),
            workId = workId,
            sitePartId = null,
            title = title,
            orderKey = OrderKey.of(1),
            canonicalUrl = pageUrl.substringBefore('?').substringBefore('#'),
            publicationDate = date,
            revisionFingerprint = sanitized.contentHash,
        )

        val content = ChapterContent(
            chapterId = chapter.id,
            title = title,
            authorDisplayName = authorName,
            xhtmlBody = sanitized.xhtmlBody,
            contentHash = sanitized.contentHash,
            sourceFingerprint = sanitized.contentHash,
        )

        return StoryPageParse(
            author = Author(
                id = AuthorId("author-$authorUsername"),
                siteUsername = authorUsername,
                displayName = authorName,
                worksUrl = "https://www.literotica.com/authors/$authorUsername/works/stories",
            ),
            work = Work(
                id = workId,
                authorId = AuthorId("author-$authorUsername"),
                title = title,
                description = description,
                category = category,
                publicationDate = date,
                canonicalUrl = chapter.canonicalUrl,
                type = workType,
            ),
            chapter = chapter,
            content = content,
            seriesUrl = seriesLink,
            continuationUrls = continuationUrls,
        )
    }

    private fun findStoryBody(doc: Document): Element? {
        // Prefer explicit story-body hooks. Avoid broad ".panel" / "article .panel" —
        // on the live site those match "Story Info" chrome and author widgets first.
        val preferred = listOf(
            "[itemprop=articleBody]",
            "[class*=article__content]",
            "div.aa_ht.aa_ht__story",
            ".aa_ht",
            "#storytext",
            ".story_text",
        )
        preferred.forEach { sel ->
            doc.select(sel)
                .maxByOrNull { it.text().length }
                ?.takeIf { it.text().isNotBlank() }
                ?.let { return it }
        }
        // Among remaining article/panel candidates, pick the longest prose block
        // so we do not ship metadata chrome ("Story Info", word counts, tags).
        val scored = doc.select("article [class*=content], article .panel, .panel.article, main article")
            .map { el -> el to proseScore(el) }
            .filter { (_, score) -> score >= MIN_STORY_BODY_CHARS }
            .sortedByDescending { it.second }
        scored.firstOrNull()?.let { return it.first }

        return doc.select("main p, article p, .panel p")
            .groupBy { it.parent() }
            .maxByOrNull { (_, ps) -> ps.sumOf { it.text().length } }
            ?.key
            ?.takeIf { proseScore(it) >= MIN_STORY_BODY_CHARS }
    }

    /** Rough signal for real story prose vs short chrome/metadata blocks. */
    private fun proseScore(el: Element): Int {
        val text = el.text().trim()
        if (text.length < MIN_STORY_BODY_CHARS) return text.length
        // Penalize blocks that look like story-info widgets
        val lower = text.lowercase(Locale.US)
        val chromeHits = listOf("story info", "words", "followers", "favorites").count { it in lower }
        val paragraphCount = el.select("p").size
        return text.length + paragraphCount * 40 - chromeHits * 80
    }

    private const val MIN_STORY_BODY_CHARS = 80

    private fun extractAuthorUsername(pageUrl: String, doc: Document): String {
        Regex("""/authors/([^/]+)""", RegexOption.IGNORE_CASE).find(pageUrl)?.groupValues?.get(1)
            ?.let { return it }
        doc.selectFirst("a[href*=/authors/]")?.attr("href")
            ?.let { Regex("""/authors/([^/]+)""").find(it)?.groupValues?.get(1) }
            ?.let { return it }
        return "unknown"
    }

    /**
     * Live Literotica author works: each series is a title card (`*series_head*`) followed by
     * a sibling `*parts*` list of part cards. Part cards do not contain `/series/se/` links.
     */
    private fun attachChaptersFromSeriesPartsLayout(
        doc: Document,
        author: Author,
        series: LinkedHashMap<String, MutableSeries>,
        assignedStorySlugs: MutableSet<String>,
    ) {
        val heads = doc.select("[class*=series_head]")
        heads.forEach { head ->
            val seriesLink = head.selectFirst("a[href*=/series/se/]") ?: return@forEach
            val href = seriesLink.absUrl("href")
            val seriesId = Regex("""/series/se/([^/?#]+)""").find(href)?.groupValues?.get(1)
                ?: return@forEach
            val title = seriesLink.text().trim().ifBlank { "Series $seriesId" }
            val bucket = seriesBucket(series, seriesId, author, titleHint = title)
            val parts = findPartsContainer(head) ?: return@forEach
            parts.select("a[href*=/s/]")
                .mapNotNull { el ->
                    val storyHref = el.absUrl("href")
                    val slug = Regex("""/s/([^/?#]+)""").find(storyHref)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    StoryLink(
                        slug = slug,
                        title = el.text().trim().ifBlank { slug },
                        url = "https://www.literotica.com/s/$slug",
                        element = el,
                    )
                }
                .distinctBy { it.slug }
                .forEach { story ->
                    if (story.slug in assignedStorySlugs) return@forEach
                    val date = story.element.closest("article, li, [class*=part_row], [class*=part_card]")
                        ?.selectFirst("time, [class*=date], .date")
                        ?.text()?.trim()
                    appendChapter(bucket, story, date)
                    assignedStorySlugs += story.slug
                }
        }
    }

    private fun findPartsContainer(seriesHead: Element): Element? {
        var sibling = seriesHead.nextElementSibling()
        var steps = 0
        while (sibling != null && steps < 6) {
            val cls = sibling.className()
            if (cls.contains("series_head", ignoreCase = true)) break
            if (cls.contains("parts", ignoreCase = true)) return sibling
            if (sibling.selectFirst("a[href*=/s/]") != null &&
                sibling.selectFirst("a[href*=/series/se/]") == null
            ) {
                return sibling
            }
            sibling = sibling.nextElementSibling()
            steps++
        }
        val parent = seriesHead.parent() ?: return null
        parent.selectFirst("[class*=parts]")?.let { return it }
        // Classic fixtures: <section><h2>series</h2><ul>stories</ul>
        return parent.select("ul, ol").firstOrNull { list ->
            list.selectFirst("a[href*=/s/]") != null
        }
    }

    private fun seriesBucket(
        series: LinkedHashMap<String, MutableSeries>,
        seriesId: String,
        author: Author,
        titleHint: String?,
    ): MutableSeries {
        val existing = series[seriesId]
        if (existing != null) {
            if (titleHint != null &&
                titleHint.isNotBlank() &&
                existing.work.title.startsWith("Series ")
            ) {
                val updated = existing.copy(work = existing.work.copy(title = titleHint))
                series[seriesId] = updated
                return updated
            }
            return existing
        }
        val created = MutableSeries(
            work = Work(
                id = WorkId("series-$seriesId"),
                authorId = author.id,
                title = titleHint?.takeIf { it.isNotBlank() } ?: "Series $seriesId",
                canonicalUrl = "https://www.literotica.com/series/se/$seriesId",
                type = WorkType.SERIES,
            ),
            chapters = mutableListOf(),
        )
        series[seriesId] = created
        return created
    }

    private fun appendChapter(bucket: MutableSeries, story: StoryLink, date: String?) {
        val order = bucket.chapters.size + 1
        bucket.chapters += Chapter(
            id = ChapterId("chapter-${bucket.work.id.value.removePrefix("series-")}-$order-${story.slug}"),
            workId = bucket.work.id,
            sitePartId = order.toString(),
            title = story.title,
            orderKey = OrderKey.of(order),
            canonicalUrl = story.url,
            publicationDate = date,
        )
    }

    /**
     * Series membership is only accepted from the story row itself, a nearby
     * section heading (h1–h3), or a shared series_head ancestor block.
     * Page-wide series links alone are ignored.
     */
    private fun findLocalSeriesHref(storyLink: Element, localRoot: Element?): String? {
        localRoot?.select("a[href*=/series/se/]")?.firstOrNull()?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        var parent = storyLink.parent()
        var depth = 0
        while (parent != null && depth < 8) {
            for (heading in parent.children()) {
                val tag = heading.tagName().lowercase(Locale.US)
                if (tag == "h1" || tag == "h2" || tag == "h3") {
                    val href = heading.selectFirst("a[href*=/series/se/]")?.absUrl("href")
                    if (!href.isNullOrBlank()) return href
                }
                // CSS-module series title row (not always an h*)
                val cls = heading.className()
                if (cls.contains("series_head", ignoreCase = true)) {
                    val href = heading.selectFirst("a[href*=/series/se/]")?.absUrl("href")
                    if (!href.isNullOrBlank()) return href
                }
            }
            parent = parent.parent()
            depth++
        }
        return null
    }

    private data class StoryLink(
        val slug: String,
        val title: String,
        val url: String,
        val element: Element,
    )

    private data class MutableSeries(
        val work: Work,
        val chapters: MutableList<Chapter>,
    )
}

data class StoryPageParse(
    val author: Author,
    val work: Work,
    val chapter: Chapter,
    val content: ChapterContent,
    val seriesUrl: String?,
    val continuationUrls: List<String>,
)
