package com.storyweaver.feature.create.session

import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class ChapterDownloadFailure(
    val url: String,
    val message: String,
)

data class DownloadOutcome(
    val contents: Map<ChapterId, ChapterContent>,
    val failedUrls: List<String>,
    /** Same order as [failedUrls]; includes exception/HTTP/parse reason when available. */
    val failures: List<ChapterDownloadFailure> = emptyList(),
)

/**
 * Cache-aware chapter download with bounded parallelism.
 * Cache hits skip [fetch]; misses run under a semaphore of size [concurrency].
 */
suspend fun downloadSelectedChapters(
    chapters: List<Chapter>,
    concurrency: Int,
    loadCached: suspend (String) -> ChapterContent?,
    fetch: suspend (String) -> ChapterContent,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): DownloadOutcome = coroutineScope {
    val total = chapters.size
    if (total == 0) {
        onProgress(0, 0)
        return@coroutineScope DownloadOutcome(emptyMap(), emptyList(), emptyList())
    }
    val limit = concurrency.coerceIn(1, 6)
    val semaphore = Semaphore(limit)
    val progressMutex = Mutex()
    var done = 0
    val results = chapters.map { ch ->
        async {
            val cached = loadCached(ch.canonicalUrl)
            val pair: Pair<ChapterId, Result<ChapterContent>> = if (cached != null) {
                ch.id to Result.success(cached.copy(chapterId = ch.id, title = ch.title))
            } else {
                semaphore.withPermit {
                    ch.id to runCatching {
                        fetch(ch.canonicalUrl).copy(chapterId = ch.id, title = ch.title)
                    }
                }
            }
            progressMutex.withLock {
                done++
                onProgress(done, total)
            }
            Triple(ch, pair.first, pair.second)
        }
    }.awaitAll()

    val contents = linkedMapOf<ChapterId, ChapterContent>()
    val failures = mutableListOf<ChapterDownloadFailure>()
    results.forEach { (ch, id, result) ->
        result.fold(
            onSuccess = { contents[id] = it },
            onFailure = { err ->
                failures += ChapterDownloadFailure(
                    url = ch.canonicalUrl,
                    message = err.message?.takeIf { it.isNotBlank() } ?: err.toString(),
                )
            },
        )
    }
    DownloadOutcome(
        contents = contents,
        failedUrls = failures.map { it.url },
        failures = failures,
    )
}
