package com.storyweaver.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storyweaver.core.model.AppUpdateInfo
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ThemeMode
import com.storyweaver.core.model.UserSettings

@Composable
fun SettingsScreen(
    settings: UserSettings = UserSettings(),
    cacheSummary: String = "—",
    outputFolderLabel: String = "Not set",
    updateMessage: String? = null,
    onThemeChange: (ThemeMode) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    onConcurrencyChange: (Int) -> Unit = {},
    onStyleChange: (EpubStyle) -> Unit = {},
    onPrivacyChange: (Boolean) -> Unit = {},
    onPickOutputFolder: () -> Unit = {},
    onClearOutputFolder: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Local preferences only. No analytics or telemetry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { onThemeChange(mode) },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        SettingSwitch(
            title = "Dynamic Color",
            subtitle = "Optional Material You colors (may override StoryWeaver palette)",
            checked = settings.dynamicColor,
            onCheckedChange = onDynamicColorChange,
        )

        Text("Download concurrency (max 6)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 4, 6).forEach { n ->
                FilterChip(
                    selected = settings.downloadConcurrency == n,
                    onClick = { onConcurrencyChange(n) },
                    label = { Text("$n") },
                )
            }
        }

        Text("Default EPUB style", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EpubStyle.entries.forEach { style ->
                FilterChip(
                    selected = settings.epubStyle == style,
                    onClick = { onStyleChange(style) },
                    label = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Text("Default output folder", style = MaterialTheme.typography.titleMedium)
        Text(outputFolderLabel, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickOutputFolder) { Text("Choose folder") }
            if (settings.defaultOutputFolderUri != null) {
                OutlinedButton(onClick = onClearOutputFolder) { Text("Clear") }
            }
        }
        Text(
            "Exports can be saved here without silent overwrite (numbered copies on collision).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingSwitch(
            title = "Privacy mode",
            subtitle = "Biometric lock, secure recent apps, neutral notifications",
            checked = settings.privacyMode,
            onCheckedChange = onPrivacyChange,
        )

        Text("Cache", style = MaterialTheme.typography.titleMedium)
        Text(cacheSummary, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClearCache) { Text("Clear chapter cache") }

        Text("App updates", style = MaterialTheme.typography.titleMedium)
        Text("Version ${AppUpdateInfo.CURRENT_VERSION}", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCheckUpdates) { Text("Check for app updates") }
        updateMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Update check is manual only. No silent downloads or installs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "StoryWeaver is not affiliated with Literotica. For personal use of public pages only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
