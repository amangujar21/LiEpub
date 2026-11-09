package com.storyweaver.core.model

/**
 * Manual source refresh: match chapters by stable IDs / canonical URLs,
 * surface new and changed content without silently replacing cache.
 */
object RefreshReconciliation {
    data class Result(
        val authors: List<Author>,
        val works: List<Work>,
        val chapters: List<Chapter>,
        val newChapterIds: Set<ChapterId>,
        val changedChapterIds: Set<ChapterId>,
        val removedChapterIds: Set<ChapterId>,
        val messages: List<String>,
    )

    fun reconcile(
        previousAuthors: List<Author>,
        previousWorks: List<Work>,
        previousChapters: List<Chapter>,
        nextAuthors: List<Author>,
        nextWorks: List<Work>,
        nextChapters: List<Chapter>,
        cachedFingerprintByUrl: Map<String, String?> = emptyMap(),
    ): Result {
        val prevByUrl = previousChapters.associateBy { it.canonicalUrl.lowercase() }
        val nextByUrl = nextChapters.associateBy { it.canonicalUrl.lowercase() }

        val newIds = mutableSetOf<ChapterId>()
        val changedIds = mutableSetOf<ChapterId>()
        nextChapters.forEach { ch ->
            val prev = prevByUrl[ch.canonicalUrl.lowercase()]
            if (prev == null) {
                newIds += ch.id
            } else {
                val fingerprintChanged = prev.revisionFingerprint != null &&
                    ch.revisionFingerprint != null &&
                    prev.revisionFingerprint != ch.revisionFingerprint
                val cached = cachedFingerprintByUrl[ch.canonicalUrl]
                val cacheChanged = cached != null &&
                    ch.revisionFingerprint != null &&
                    cached != ch.revisionFingerprint
                if (fingerprintChanged || cacheChanged) {
                    changedIds += ch.id
                }
            }
        }

        val removed = previousChapters.filter { prev ->
            prev.canonicalUrl.lowercase() !in nextByUrl
        }
        val removedIds = removed.map { it.id }.toSet()

        // Keep removed chapters that still exist in the previous collection (may be cached).
        val keptRemoved = removed.map { it.copy() }
        val mergedChapters = (nextChapters + keptRemoved).distinctBy { it.canonicalUrl.lowercase() }
        val authors = (nextAuthors + previousAuthors).distinctBy { it.id }
        val works = (nextWorks + previousWorks.filter { w ->
            mergedChapters.any { it.workId == w.id } || nextWorks.none { it.id == w.id }
        }).distinctBy { it.id }

        val messages = buildList {
            if (newIds.isNotEmpty()) add("${newIds.size} new chapter(s) discovered.")
            if (changedIds.isNotEmpty()) {
                add("${changedIds.size} chapter(s) changed on source (cached text not replaced).")
            }
            if (removedIds.isNotEmpty()) {
                add("${removedIds.size} source chapter(s) unavailable; kept if cached.")
            }
            if (isEmpty()) add("No source changes detected.")
        }

        return Result(
            authors = authors,
            works = works,
            chapters = mergedChapters,
            newChapterIds = newIds,
            changedChapterIds = changedIds,
            removedChapterIds = removedIds,
            messages = messages,
        )
    }
}
