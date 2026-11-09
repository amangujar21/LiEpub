package com.storyweaver.core.model

data class Author(
    val id: AuthorId,
    val siteUsername: String,
    val displayName: String,
    val worksUrl: String,
)

data class Work(
    val id: WorkId,
    val authorId: AuthorId,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val publicationDate: String? = null,
    val canonicalUrl: String,
    val type: WorkType,
)

data class Chapter(
    val id: ChapterId,
    val workId: WorkId,
    val sitePartId: String?,
    val title: String,
    val orderKey: OrderKey,
    val canonicalUrl: String,
    val publicationDate: String? = null,
    val revisionFingerprint: String? = null,
)

/**
 * Lexicographically sortable order key that supports labels like "05.1"
 * without floating-point comparison.
 */
data class OrderKey(val segments: List<Int>) : Comparable<OrderKey> {
    init {
        require(segments.isNotEmpty()) { "OrderKey requires at least one segment" }
    }

    override fun compareTo(other: OrderKey): Int {
        val max = maxOf(segments.size, other.segments.size)
        for (i in 0 until max) {
            val a = segments.getOrElse(i) { 0 }
            val b = other.segments.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun toString(): String = segments.joinToString(".")

    companion object {
        fun parse(raw: String): OrderKey {
            val cleaned = raw.trim()
                .removePrefix("Ch.")
                .removePrefix("ch.")
                .removePrefix("Chapter")
                .removePrefix("chapter")
                .trim()
                .trimStart('#', ':', '-', ' ')
            val parts = cleaned.split('.', '-', '_', ' ')
                .mapNotNull { token ->
                    token.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()
                }
            return if (parts.isEmpty()) OrderKey(listOf(0)) else OrderKey(parts)
        }

        fun of(vararg segments: Int): OrderKey = OrderKey(segments.toList())
    }
}

data class ContinuationPage(
    val chapterId: ChapterId,
    val canonicalUrl: String,
    val pageOrder: Int,
)

data class ChapterContent(
    val chapterId: ChapterId,
    val title: String,
    val authorDisplayName: String?,
    val xhtmlBody: String,
    val contentHash: String,
    val sourceFingerprint: String?,
)

data class ParseWarning(
    val code: String,
    val message: String,
    val url: String? = null,
)

data class AuthorCatalog(
    val author: Author,
    val series: List<CatalogSeries>,
    val standalones: List<CatalogStandalone>,
    val warnings: List<ParseWarning> = emptyList(),
)

data class CatalogSeries(
    val work: Work,
    val chapters: List<Chapter>,
    val selectedByDefault: Boolean = true,
)

data class CatalogStandalone(
    val work: Work,
    val chapter: Chapter,
    val selectedByDefault: Boolean = false,
)

data class ResolvedDraft(
    val authors: List<Author>,
    val works: List<Work>,
    val chapters: List<Chapter>,
    val warnings: List<ParseWarning> = emptyList(),
)

data class ImportedBook(
    val id: ImportedBookId,
    val documentUri: String,
    val packageIdentifier: String?,
    val title: String,
    val creator: String?,
    val language: String?,
    val formatVersion: String?,
    val contentFingerprint: String,
    val available: Boolean = true,
)

data class CachedChapterRef(
    val chapterId: ChapterId,
    val contentHash: String,
    val sourceFingerprint: String?,
    val fetchedAtEpochMs: Long,
    val lastReferencedEpochMs: Long,
)

data class ExportRecord(
    val id: String,
    val collectionId: CollectionId,
    val title: String,
    val filename: String,
    val incomplete: Boolean,
    val destinationUri: String?,
    val createdAtEpochMs: Long,
    val status: String,
)

data class SavedCollection(
    val draft: CollectionDraft,
    val authors: List<Author> = emptyList(),
    val works: List<Work> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val importedBooks: List<ImportedBook> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val createdAtEpochMs: Long,
    val modifiedAtEpochMs: Long,
)

enum class CatalogSort {
    SITE_ORDER,
    TITLE,
    NEWEST,
    OLDEST,
}

object CatalogFilter {
    fun filterAndSort(
        catalog: AuthorCatalog,
        query: String,
        sort: CatalogSort,
    ): AuthorCatalog {
        val q = query.trim().lowercase()
        fun matches(title: String, category: String?): Boolean {
            if (q.isEmpty()) return true
            return title.lowercase().contains(q) ||
                (category?.lowercase()?.contains(q) == true)
        }
        val series = catalog.series.filter { matches(it.work.title, it.work.category) }
        val standalones = catalog.standalones.filter { matches(it.work.title, it.work.category) }
        fun <T> sortWorks(list: List<T>, title: (T) -> String, date: (T) -> String?): List<T> =
            when (sort) {
                CatalogSort.SITE_ORDER -> list
                CatalogSort.TITLE -> list.sortedBy { title(it).lowercase() }
                CatalogSort.NEWEST -> list.sortedByDescending { date(it).orEmpty() }
                CatalogSort.OLDEST -> list.sortedBy { date(it).orEmpty() }
            }
        return catalog.copy(
            series = sortWorks(series, { it.work.title }, { it.work.publicationDate }),
            standalones = sortWorks(standalones, { it.work.title }, { it.work.publicationDate }),
        )
    }
}

object DuplicateDetection {
    fun duplicateWorkIds(works: List<Work>): Set<WorkId> {
        val byUrl = works.groupBy { it.canonicalUrl.lowercase() }
        return byUrl.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()
    }

    fun likelyTitleCreatorMatch(
        titleA: String,
        creatorA: String?,
        titleB: String,
        creatorB: String?,
    ): Boolean {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("""\s+"""), " ")
        if (norm(titleA) != norm(titleB)) return false
        val ca = creatorA?.let(::norm).orEmpty()
        val cb = creatorB?.let(::norm).orEmpty()
        return ca.isEmpty() || cb.isEmpty() || ca == cb
    }

    /** Human-readable warnings for duplicate canonical URLs among selected works. */
    fun warningsFor(works: List<Work>): List<String> {
        val dups = duplicateWorkIds(works)
        if (dups.isEmpty()) return emptyList()
        return works.filter { it.id in dups }
            .groupBy { it.canonicalUrl.lowercase() }
            .filter { it.value.size > 1 }
            .map { (url, list) ->
                "Likely duplicate works for $url: ${list.joinToString { it.title }}"
            }
    }
}

/** Build a filterable/sortable catalog view from a flat resolved draft (UI only). */
fun catalogViewFromResolved(
    authors: List<Author>,
    works: List<Work>,
    chapters: List<Chapter>,
): AuthorCatalog {
    val author = when {
        authors.size == 1 -> authors.first()
        authors.isEmpty() -> Author(
            id = AuthorId("author-none"),
            siteUsername = "none",
            displayName = "No content",
            worksUrl = "",
        )
        else -> authors.first().copy(
            displayName = "${authors.size} authors",
        )
    }
    val series = works.filter { it.type == WorkType.SERIES }.map { w ->
        CatalogSeries(
            work = w,
            chapters = chapters.filter { it.workId == w.id }.sortedBy { it.orderKey },
            selectedByDefault = true,
        )
    }
    val standalones = works.filter { it.type == WorkType.STANDALONE }.mapNotNull { w ->
        val ch = chapters.firstOrNull { it.workId == w.id } ?: return@mapNotNull null
        CatalogStandalone(work = w, chapter = ch, selectedByDefault = true)
    }
    return AuthorCatalog(author = author, series = series, standalones = standalones)
}
