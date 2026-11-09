package com.storyweaver.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feedback loop for HTTP 429 / throttling behavior during multi-chapter downloads.
 * Uses a plain OkHttpClient (no host allowlist) so MockWebServer can stand in for Literotica.
 */
class HttpFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher(
        policy: RetryPolicy = RetryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 10,
            maxBackoffMs = 20,
            jitterRatio = 0.0,
            rateLimitFloorMs = 10,
        ),
        gate: RequestGate = RequestGate(minIntervalMs = 0),
    ) = HttpFetcher(client, policy, gate)

    @Test
    fun `persistent 429 fails with HTTP 429 after max attempts`() = runBlocking {
        val policy = RetryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 10,
            maxBackoffMs = 20,
            jitterRatio = 0.0,
            rateLimitFloorMs = 10,
        )
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        }
        val url = server.url("/s/ch-1").toString()

        val error = runCatching { fetcher(policy).getText(url) }.exceptionOrNull()

        assertThat(error).isInstanceOf(HttpStatusException::class.java)
        assertThat(error!!.message).contains("HTTP 429")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `429 then 200 succeeds after retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("wait"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>ok</html>"))

        val result = fetcher().getText(server.url("/s/ch-1").toString())

        assertThat(result.statusCode).isEqualTo(200)
        assertThat(result.body).contains("ok")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `honors Retry-After seconds before next attempt`() = runBlocking {
        val policy = RetryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 1,
            maxBackoffMs = 1,
            jitterRatio = 0.0,
            rateLimitFloorMs = 1,
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "1")
                .setBody("wait"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val start = System.nanoTime()
        fetcher(policy).getText(server.url("/s/ch-1").toString())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertThat(elapsedMs).isAtLeast(900)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `404 is not retried as if transient`() = runBlocking {
        val policy = RetryPolicy(
            maxAttempts = 4,
            initialBackoffMs = 5,
            maxBackoffMs = 10,
            jitterRatio = 0.0,
            rateLimitFloorMs = 5,
        )
        repeat(6) {
            server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        }

        val error = runCatching {
            fetcher(policy).getText(server.url("/s/missing").toString())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(HttpStatusException::class.java)
        assertThat(error!!.message).contains("HTTP 404")
        assertThat(server.requestCount).isEqualTo(1)
    }

    /**
     * Server rejects bursts (more than 2 in-flight). Shared RequestGate + spacing should
     * let concurrent chapter workers complete instead of failing with HTTP 429.
     */
    @Test
    fun `shared gate lets concurrent downloads survive burst rate limit`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxOk = 2
        val rejected = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = inFlight.incrementAndGet()
                return try {
                    if (n > maxOk) {
                        rejected.incrementAndGet()
                        MockResponse()
                            .setResponseCode(429)
                            .setHeader("Retry-After", "0")
                            .setBody("rate limited")
                    } else {
                        // Hold the connection briefly so concurrency matters
                        Thread.sleep(40)
                        MockResponse().setResponseCode(200).setBody("ok-${request.path}")
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        val policy = RetryPolicy(
            maxAttempts = 6,
            initialBackoffMs = 30,
            maxBackoffMs = 80,
            jitterRatio = 0.0,
            rateLimitFloorMs = 30,
        )
        // Space starts so at most ~2 overlap given 40ms hold
        val gate = RequestGate(minIntervalMs = 50)
        val shared = HttpFetcher(client, policy, gate)
        val base = server.url("/s/").toString().removeSuffix("/")
        val concurrency = 6
        val chapterSemaphore = Semaphore(concurrency)

        val results = (1..concurrency).map { n ->
            async {
                chapterSemaphore.withPermit {
                    runCatching { shared.getText("$base/ch-$n") }
                }
            }
        }.awaitAll()

        assertThat(results.all { it.isSuccess }).isTrue()
        results.forEach { r ->
            assertThat(r.getOrNull()!!.statusCode).isEqualTo(200)
        }
    }

    @Test
    fun `persistent concurrent 429 still surfaces HTTP 429`() = runBlocking {
        val concurrency = 4
        val policy = RetryPolicy(
            maxAttempts = 2,
            initialBackoffMs = 5,
            maxBackoffMs = 10,
            jitterRatio = 0.0,
            rateLimitFloorMs = 5,
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(429).setBody("always")
        }
        val gate = RequestGate(minIntervalMs = 0)
        val shared = HttpFetcher(client, policy, gate)
        val base = server.url("/s/").toString().removeSuffix("/")

        val failures = (1..concurrency).map { n ->
            async { runCatching { shared.getText("$base/ch-$n") } }
        }.awaitAll()

        assertThat(failures.all { it.isFailure }).isTrue()
        failures.forEach { r ->
            assertThat(r.exceptionOrNull()).isInstanceOf(HttpStatusException::class.java)
            assertThat(r.exceptionOrNull()!!.message).contains("HTTP 429")
        }
    }
}
