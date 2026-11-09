package com.storyweaver.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `same version is latest`() {
        val msg = AppUpdateChecker.parseFeed(
            """{"version":"0.2.0","notes":"n","url":"https://example.com"}""",
            "0.2.0",
        )
        assertThat(msg).contains("latest version")
    }

    @Test
    fun `newer version reports update`() {
        val msg = AppUpdateChecker.parseFeed(
            """{"version":"0.3.0","notes":"fixes","url":"https://example.com/r"}""",
            "0.2.0",
        )
        assertThat(msg).contains("Update available: 0.3.0")
        assertThat(msg).contains("fixes")
    }

    @Test
    fun `blank version message`() {
        val msg = AppUpdateChecker.parseFeed("""{"notes":"x"}""", "0.2.0")
        assertThat(msg).contains("No version info")
    }

    @Test
    fun `older feed version is not reported as an update`() {
        val msg = AppUpdateChecker.parseFeed("""{"version":"0.2.0"}""", "0.10.0")
        assertThat(msg).contains("latest version (0.10.0)")
        assertThat(AppUpdateChecker.compareVersions("v0.10.0", "0.9.1")).isGreaterThan(0)
    }
}
