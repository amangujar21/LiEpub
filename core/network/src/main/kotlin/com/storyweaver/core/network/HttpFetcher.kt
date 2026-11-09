package com.storyweaver.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class FetchResult(
    val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val body: String,
    val contentType: String?,
)

class HttpFetcher(
    private val client: OkHttpClient,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val gate: RequestGate = RequestGate(),
) {
    suspend fun getText(url: String): FetchResult = withContext(Dispatchers.IO) {
        var attempt = 1
        var lastError: Exception? = null
        while (attempt <= retryPolicy.maxAttempts) {
            gate.acquire()
            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (retryPolicy.shouldRetry(code, attempt)) {
                        val retryAfterMs = parseRetryAfterMs(response.header("Retry-After"))
                        val wait = retryAfterMs ?: retryPolicy.backoffMs(attempt, statusCode = code)
                        gate.noteThrottle(wait)
                        delay(wait)
                        attempt++
                        return@use
                    }
                    if (!response.isSuccessful) {
                        // Permanent / non-retryable HTTP — do not fall through to network retry.
                        throw HttpStatusException(code, url)
                    }
                    val body = response.body?.string().orEmpty()
                    gate.noteSuccess()
                    return@withContext FetchResult(
                        url = url,
                        finalUrl = response.request.url.toString(),
                        statusCode = code,
                        body = body,
                        contentType = response.header("Content-Type"),
                    )
                }
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt >= retryPolicy.maxAttempts) throw e
                val wait = retryPolicy.backoffMs(attempt)
                gate.noteThrottle(wait / 2)
                delay(wait)
                attempt++
            }
        }
        throw lastError ?: IOException("Failed to fetch $url")
    }

    companion object {
        /**
         * Retry-After: delta-seconds (preferred) or ignored if HTTP-date / unparsable.
         * Returns milliseconds, or null when the header is absent/unusable.
         */
        fun parseRetryAfterMs(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val seconds = header.trim().toLongOrNull() ?: return null
            return (seconds * 1000L).coerceIn(0L, 120_000L)
        }
    }
}
