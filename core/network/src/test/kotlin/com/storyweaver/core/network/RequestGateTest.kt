package com.storyweaver.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RequestGateTest {
    @Test
    fun `throttle doubles spacing up to the cap and successes relax it back to the floor`() = runBlocking {
        val gate = RequestGate(minIntervalMs = 100, maxIntervalMs = 1_000, clockMs = { 0L })
        assertThat(gate.currentIntervalMs).isEqualTo(100)

        gate.noteThrottle(50)
        assertThat(gate.currentIntervalMs).isEqualTo(200)
        repeat(5) { gate.noteThrottle(50) }
        assertThat(gate.currentIntervalMs).isEqualTo(1_000)

        repeat(200) { gate.noteSuccess() }
        assertThat(gate.currentIntervalMs).isEqualTo(100)
    }

    @Test
    fun `zero floor still backs off after a throttle`() = runBlocking {
        val gate = RequestGate(minIntervalMs = 0, clockMs = { 0L })
        gate.noteThrottle(50)
        assertThat(gate.currentIntervalMs).isGreaterThan(0)
    }
}
