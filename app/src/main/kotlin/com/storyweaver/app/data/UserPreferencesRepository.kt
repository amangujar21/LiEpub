package com.storyweaver.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ThemeMode
import com.storyweaver.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val concurrency = intPreferencesKey("download_concurrency")
        val epubStyle = stringPreferencesKey("epub_style")
        val privacy = booleanPreferencesKey("privacy_mode")
        val ageAck = booleanPreferencesKey("age_acknowledged")
        val outputFolder = stringPreferencesKey("default_output_folder")
        val cacheDays = intPreferencesKey("cache_retention_days")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            themeMode = prefs[Keys.theme]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
            } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.dynamicColor] ?: false,
            downloadConcurrency = (prefs[Keys.concurrency] ?: 3).coerceIn(1, 6),
            epubStyle = prefs[Keys.epubStyle]?.let {
                runCatching { EpubStyle.valueOf(it) }.getOrDefault(EpubStyle.MODERN)
            } ?: EpubStyle.MODERN,
            privacyMode = prefs[Keys.privacy] ?: false,
            ageAcknowledged = prefs[Keys.ageAck] ?: false,
            defaultOutputFolderUri = prefs[Keys.outputFolder],
            cacheRetentionDays = prefs[Keys.cacheDays] ?: 30,
        )
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.dataStore.edit { prefs ->
            val current = UserSettings(
                themeMode = prefs[Keys.theme]?.let {
                    runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
                } ?: ThemeMode.SYSTEM,
                dynamicColor = prefs[Keys.dynamicColor] ?: false,
                downloadConcurrency = (prefs[Keys.concurrency] ?: 3).coerceIn(1, 6),
                epubStyle = prefs[Keys.epubStyle]?.let {
                    runCatching { EpubStyle.valueOf(it) }.getOrDefault(EpubStyle.MODERN)
                } ?: EpubStyle.MODERN,
                privacyMode = prefs[Keys.privacy] ?: false,
                ageAcknowledged = prefs[Keys.ageAck] ?: false,
                defaultOutputFolderUri = prefs[Keys.outputFolder],
                cacheRetentionDays = prefs[Keys.cacheDays] ?: 30,
            )
            val next = transform(current)
            prefs[Keys.theme] = next.themeMode.name
            prefs[Keys.dynamicColor] = next.dynamicColor
            prefs[Keys.concurrency] = next.downloadConcurrency
            prefs[Keys.epubStyle] = next.epubStyle.name
            prefs[Keys.privacy] = next.privacyMode
            prefs[Keys.ageAck] = next.ageAcknowledged
            if (next.defaultOutputFolderUri != null) {
                prefs[Keys.outputFolder] = next.defaultOutputFolderUri!!
            } else {
                prefs.remove(Keys.outputFolder)
            }
            prefs[Keys.cacheDays] = next.cacheRetentionDays
        }
    }
}
