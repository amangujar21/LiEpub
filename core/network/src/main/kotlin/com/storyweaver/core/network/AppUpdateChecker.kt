package com.storyweaver.core.network

import com.storyweaver.core.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class AppUpdateChecker(
    private val client: OkHttpClient = createReleaseFeedClient(),
    private val feedUrl: String = AppUpdateInfo.RELEASE_FEED_URL,
    private val currentVersion: String = AppUpdateInfo.CURRENT_VERSION,
) {
    suspend fun check(): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(feedUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext "Update check failed (HTTP ${resp.code}). Try again later."
                }
                val body = resp.body?.string().orEmpty()
                parseFeed(body, currentVersion)
            }
        } catch (e: Exception) {
            "Update check failed: ${e.message ?: "network error"} (non-critical)."
        }
    }

    companion object {
        fun parseFeed(body: String, currentVersion: String): String {
            val latest = stringField(body, "version").orEmpty()
            val notes = stringField(body, "notes").orEmpty()
            val url = stringField(body, "url").orEmpty()
            if (latest.isBlank()) return "No version info in release feed."
            return if (compareVersions(latest, currentVersion) <= 0) {
                "You are on the latest version ($currentVersion)."
            } else {
                "Update available: $latest. $notes ${if (url.isNotBlank()) "Open: $url" else ""}".trim()
            }
        }

        /** Numeric dotted-version compare ("0.10.0" > "0.9.1"); a leading "v" is ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        private fun stringField(json: String, key: String): String? {
            val re = Regex(""""$key"\s*:\s*"([^"]*)"""")
            return re.find(json)?.groupValues?.get(1)
        }
    }
}
