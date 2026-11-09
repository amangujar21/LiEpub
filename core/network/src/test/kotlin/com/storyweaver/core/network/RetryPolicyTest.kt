package com.storyweaver.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RetryPolicyTest {
    private val policy = RetryPolicy(
        maxAttempts = 5,
        initialBackoffMs = 500,
        maxBackoffMs = 20_000,
        jitterRatio = 0.0,
        rateLimitFloorMs = 1_500,
    )

    @Test
    fun `retries 429 and 5xx while attempts remain`() {
        assertThat(policy.shouldRetry(429, 1)).isTrue()
        assertThat(policy.shouldRetry(503, 2)).isTrue()
        assertThat(policy.shouldRetry(500, 3)).isTrue()
        assertThat(policy.shouldRetry(429, 5)).isFalse()
    }

    @Test
    fun `does not retry client errors`() {
        assertThat(policy.shouldRetry(400, 1)).isFalse()
        assertThat(policy.shouldRetry(403, 1)).isFalse()
        assertThat(policy.shouldRetry(404, 1)).isFalse()
    }

    @Test
    fun `backoff doubles until cap for ordinary retries`() {
        assertThat(policy.backoffMs(1)).isEqualTo(500)
        assertThat(policy.backoffMs(2)).isEqualTo(1000)
        assertThat(policy.backoffMs(3)).isEqualTo(2000)
        assertThat(policy.backoffMs(4)).isEqualTo(4000)
        assertThat(policy.backoffMs(5)).isEqualTo(8000)
        assertThat(policy.backoffMs(6)).isEqualTo(16000)
        assertThat(policy.backoffMs(7)).isEqualTo(20000)
    }

    @Test
    fun `429 uses rate-limit floor for first backoff`() {
        assertThat(policy.backoffMs(1, statusCode = 429)).isEqualTo(1_500)
        assertThat(policy.backoffMs(2, statusCode = 429)).isEqualTo(3_000)
    }
}
