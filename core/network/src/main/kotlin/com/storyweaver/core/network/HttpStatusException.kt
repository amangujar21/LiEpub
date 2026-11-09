package com.storyweaver.core.network

import java.io.IOException

/**
 * Non-success HTTP response that has already been classified by [RetryPolicy].
 * Must not be retried again by the generic network-error path in [HttpFetcher].
 */
class HttpStatusException(
    val statusCode: Int,
    val requestUrl: String,
    message: String = "HTTP $statusCode for $requestUrl",
) : IOException(message)
