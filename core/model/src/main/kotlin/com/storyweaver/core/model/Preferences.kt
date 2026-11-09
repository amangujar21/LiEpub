package com.storyweaver.core.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Parallel chapter downloads (1–6). Default 3 balances speed vs origin 429 risk. */
    val downloadConcurrency: Int = 4,
    val epubStyle: EpubStyle = EpubStyle.MODERN,
    val privacyMode: Boolean = false,
    val ageAcknowledged: Boolean = false,
    val defaultOutputFolderUri: String? = null,
    val cacheRetentionDays: Int = 30,
)

object AppUpdateInfo {
    const val CURRENT_VERSION = "0.4.0"
    /** Pinned public release feed URL (user-initiated check only). */
    const val RELEASE_FEED_URL = "https://raw.githubusercontent.com/amangujar21/LiEpub/main/latest.json"
}
