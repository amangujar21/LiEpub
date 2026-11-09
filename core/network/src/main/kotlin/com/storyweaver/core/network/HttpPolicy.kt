package com.storyweaver.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

object SupportedHosts {
    val LITEROTICA = setOf(
        "www.literotica.com",
        "literotica.com",
        "www.literotica.nl",
        "literotica.nl",
    )

    /** Pinned hosts for user-initiated app update feed only. */
    val RELEASE_FEED = setOf(
        "raw.githubusercontent.com",
    )

    val ALLOWED: Set<String> get() = LITEROTICA

    fun isAllowed(host: String): Boolean =
        LITEROTICA.any { it.equals(host, ignoreCase = true) }

    fun isReleaseFeedHost(host: String): Boolean =
        RELEASE_FEED.any { it.equals(host, ignoreCase = true) }
}

class HostAllowlistInterceptor(
    private val allowed: (String) -> Boolean = SupportedHosts::isAllowed,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!allowed(host)) {
            throw IOException("Blocked request to unsupported host: $host")
        }
        if (!request.url.isHttps) {
            throw IOException("Only HTTPS is permitted")
        }
        val response = chain.proceed(request)
        val finalHost = response.request.url.host
        if (!allowed(finalHost) || !response.request.url.isHttps) {
            throw IOException("Blocked redirect to unsupported URL: ${response.request.url}")
        }
        return response
    }
}

class ResponseSizeLimitInterceptor(
    private val maxBytes: Long = 8L * 1024L * 1024L,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            body.close()
            throw IOException("Response too large: $contentLength bytes")
        }
        return response
    }
}

data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialBackoffMs: Long = 500,
    val maxBackoffMs: Long = 20_000,
    val jitterRatio: Double = 0.2,
    /** Floor applied when backing off after HTTP 429 (servers often need a longer cool-down). */
    val rateLimitFloorMs: Long = 1_500,
) {
    fun shouldRetry(code: Int, attempt: Int): Boolean {
        if (attempt >= maxAttempts) return false
        return code == 429 || code in 500..599
    }

    fun backoffMs(attempt: Int, statusCode: Int? = null): Long {
        val baseInitial =
            if (statusCode == 429) maxOf(initialBackoffMs, rateLimitFloorMs) else initialBackoffMs
        val exp = baseInitial * (1L shl (attempt - 1).coerceAtLeast(0))
        val capped = exp.coerceAtMost(maxBackoffMs)
        val jitter = (capped * jitterRatio * (Math.random() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(50)
    }
}

fun createStoryWeaverClient(
    userAgent: String = "StoryWeaver/0.1 (personal; local EPUB export)",
    connectTimeout: Duration = Duration.ofSeconds(15),
    readTimeout: Duration = Duration.ofSeconds(30),
): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.8")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(HostAllowlistInterceptor(SupportedHosts::isAllowed))
        .addInterceptor(ResponseSizeLimitInterceptor())
        .build()

/** Client for user-initiated release-feed checks only (pinned hosts, small body). */
fun createReleaseFeedClient(
    userAgent: String = "StoryWeaver/0.2 (personal; update check)",
    connectTimeout: Duration = Duration.ofSeconds(10),
    readTimeout: Duration = Duration.ofSeconds(10),
): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept", "application/json,text/plain;q=0.9,*/*;q=0.8")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(HostAllowlistInterceptor(SupportedHosts::isReleaseFeedHost))
        .addInterceptor(ResponseSizeLimitInterceptor(maxBytes = 256L * 1024L))
        .build()

fun HttpUrl.withoutTracking(): HttpUrl =
    newBuilder()
        .fragment(null)
        .apply {
            queryParameterNames
                .filter { name ->
                    name.startsWith("utm_", ignoreCase = true) ||
                        name.equals("fbclid", ignoreCase = true) ||
                        name.equals("gclid", ignoreCase = true)
                }
                .forEach { removeAllQueryParameters(it) }
        }
        .build()
