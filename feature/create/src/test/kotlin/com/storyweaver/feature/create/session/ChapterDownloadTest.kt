package com.storyweaver.feature.create.session

import com.google.common.truth.Truth.assertThat
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.OrderKey
import com.storyweaver.core.model.WorkId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChapterDownloadTest {
    private fun chapter(n: Int) = Chapter(
        id = ChapterId("c$n"),
        workId = WorkId("w1"),
        sitePartId = n.toString(),
        title = "Ch $n",
        orderKey = OrderKey.of(n),
        canonicalUrl = "https://www.literotica.com/s/ch-$n",
    )

    private fun content(ch: Chapter) = ChapterContent(
        chapterId = ch.id,
        title = ch.title,
        authorDisplayName = null,
        xhtmlBody = "<p>${ch.title}</p>",
        contentHash = "h${ch.id.value}",
        sourceFingerprint = null,
    )

    @Test
    fun `respects concurrency bound`() {
        runBlocking {
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val chapters = (1..6).map { chapter(it) }
            val outcome = downloadSelectedChapters(
                chapters = chapters,
                concurrency = 2,
                loadCached = { null },
                fetch = {
                    val now = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { maxOf(it, now) }
                    delay(40)
                    inFlight.decrementAndGet()
                    content(chapters.first { c -> c.canonicalUrl == it })
                },
            )
            assertThat(outcome.failedUrls).isEmpty()
            assertThat(outcome.contents).hasSize(6)
            assertThat(maxInFlight.get()).isAtMost(2)
        }
    }

    @Test
    fun `cache hits skip fetch`() {
        runBlocking {
            var fetches = 0
            val chapters = listOf(chapter(1), chapter(2))
            val outcome = downloadSelectedChapters(
                chapters = chapters,
                concurrency = 4,
                loadCached = { url ->
                    if (url.endsWith("ch-1")) content(chapters[0]) else null
                },
                fetch = {
                    fetches++
                    content(chapters[1])
                },
            )
            assertThat(fetches).isEqualTo(1)
            assertThat(outcome.contents).hasSize(2)
        }
    }

    @Test
    fun `partial failures collected`() {
        runBlocking {
            val chapters = listOf(chapter(1), chapter(2), chapter(3))
            val outcome = downloadSelectedChapters(
                chapters = chapters,
                concurrency = 3,
                loadCached = { null },
                fetch = { url ->
                    if (url.endsWith("ch-2")) error("boom")
                    content(chapters.first { it.canonicalUrl == url })
                },
            )
            assertThat(outcome.contents).hasSize(2)
            assertThat(outcome.failedUrls).containsExactly("https://www.literotica.com/s/ch-2")
            assertThat(outcome.failures).hasSize(1)
            assertThat(outcome.failures.single().url).isEqualTo("https://www.literotica.com/s/ch-2")
            assertThat(outcome.failures.single().message).contains("boom")
        }
    }
}
