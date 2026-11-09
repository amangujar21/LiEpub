package com.storyweaver.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * Shared, adaptive politeness gate for outbound HTTP.
 *
 * - Spaces request *starts* by a current interval so parallel chapter workers do not burst.
 *   The interval starts at [minIntervalMs] and adapts (AIMD): it doubles, up to
 *   [maxIntervalMs], whenever the origin signals throttling, and shrinks back by 10% per
 *   successful response. Fast when the site is happy, gentle when it is not.
 * - After a 429/503-style signal, applies a process-wide cooldown so concurrent retries
 *   do not stampede the origin as soon as each worker's private backoff ends.
 */
class RequestGate(
    private val minIntervalMs: Long = 100,
    private val maxIntervalMs: Long = 2_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var nextStartAtMs: Long = 0
    private var cooldownUntilMs: Long = 0
    private var intervalMs: Long = minIntervalMs

    /** Current spacing between request starts (for diagnostics/tests). */
    val currentIntervalMs: Long get() = intervalMs

    /**
     * Wait until it is polite to start another request, then reserve the next start slot.
     */
    suspend fun acquire() {
        mutex.withLock {
            val now = clockMs()
            val waitUntil = max(nextStartAtMs, cooldownUntilMs)
            val wait = waitUntil - now
            if (wait > 0) {
                delay(wait)
            }
            val after = clockMs()
            nextStartAtMs = after + intervalMs
        }
    }

    /** A request succeeded: relax the spacing a little, never below [minIntervalMs]. */
    suspend fun noteSuccess() {
        if (intervalMs == minIntervalMs) return
        mutex.withLock {
            intervalMs = max(minIntervalMs, intervalMs - max(1, intervalMs / 10))
        }
    }

    /**
     * Record that the origin asked us to slow down. Concurrent workers will observe this
     * on their next [acquire] or via [waitForCooldown].
     *
     * @param waitMs suggested wait from Retry-After or local backoff (milliseconds)
     */
    suspend fun noteThrottle(waitMs: Long) {
        val clamped = waitMs.coerceIn(50, 120_000)
        mutex.withLock {
            val until = clockMs() + clamped
            if (until > cooldownUntilMs) {
                cooldownUntilMs = until
            }
            // Double the spacing (from at least 50 ms, so a zero floor still backs off).
            intervalMs = min(maxIntervalMs, max(intervalMs, 50) * 2)
        }
    }

    /** Block until any active throttle cooldown has elapsed. */
    suspend fun waitForCooldown() {
        val wait = mutex.withLock {
            val remaining = cooldownUntilMs - clockMs()
            remaining
        }
        if (wait > 0) {
            delay(wait)
        }
    }
}
