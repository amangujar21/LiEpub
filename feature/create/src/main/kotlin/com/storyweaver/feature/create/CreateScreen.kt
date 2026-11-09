package com.storyweaver.feature.create

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyweaver.core.model.CatalogSort
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.JobPhase
import com.storyweaver.feature.create.session.CreateSessionLogic
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CreateScreen(
    viewModel: CreateViewModel,
    defaultOutputFolderUri: String? = null,
    onSaveToFolder: (suspend (filename: String, bytes: ByteArray) -> String?)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = state.phase == JobPhase.ANALYZING ||
        state.phase == JobPhase.DOWNLOADING ||
        state.phase == JobPhase.BUILDING ||
        state.phase == JobPhase.VALIDATING

    val pickEpub = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) {
                viewModel.importEpub(bytes, uri.toString())
            } else {
                // Surface via a no-op re-set would need error path; import handles parse errors.
            }
        }
    }

    fun writeCacheAndUri(filename: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Create", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add links or DRM-free EPUBs, arrange TOC, export.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = viewModel::onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Literotica URLs (one per line)") },
                    minLines = 2,
                    enabled = !busy,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::analyze, enabled = !busy) { Text("Analyze") }
                    OutlinedButton(
                        onClick = { pickEpub.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*")) },
                        enabled = !busy,
                    ) { Text("Import EPUB") }
                }
            }
            item {
                Button(
                    onClick = { viewModel.downloadAndExport() },
                    enabled = !busy &&
                        state.phase == JobPhase.READY &&
                        (state.selectedWorkIds.isNotEmpty() || state.importedBooks.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download & Export") }
            }
            item {
                Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
                if ((state.phase == JobPhase.DOWNLOADING || state.phase == JobPhase.ANALYZING) &&
                    state.progressTotal > 0
                ) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progressCompleted.toFloat() / state.progressTotal.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${state.progressCompleted} / ${state.progressTotal}")
                }
            }
            state.lastError?.let { err ->
                item {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.refreshMessages.isNotEmpty()) {
                item {
                    Text("Source refresh", style = MaterialTheme.typography.labelLarge)
                    state.refreshMessages.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.phase == JobPhase.WAITING_FOR_DECISION) {
                item {
                    Text(
                        "Some chapters failed. Retry or export without them.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::retryMissing) { Text("Retry missing") }
                        OutlinedButton(onClick = viewModel::exportWithoutMissing) {
                            Text("Export without them")
                        }
                    }
                }
            }
            if (state.exportResults.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                state.exportResults.forEach { result ->
                                    val uri = writeCacheAndUri(result.filename, result.bytes)
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/epub+zip"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(share, "Share EPUB"))
                                }
                            }) { Text("Share") }
                            OutlinedButton(onClick = {
                                state.exportResults.firstOrNull()?.let { result ->
                                    val uri = writeCacheAndUri(result.filename, result.bytes)
                                    val open = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/epub+zip")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(open, "Open in reader"))
                                    }
                                }
                            }) { Text("Open reader") }
                        }
                        if (onSaveToFolder != null && defaultOutputFolderUri != null) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    state.exportResults.forEach { result ->
                                        onSaveToFolder(result.filename, result.bytes)
                                    }
                                }
                            }) { Text("Save to output folder") }
                        }
                        OutlinedButton(
                            onClick = viewModel::saveCollection,
                            enabled = state.hasContent,
                        ) { Text("Save to Library") }
                    }
                }
            } else if (state.hasContent && state.phase == JobPhase.READY) {
                item {
                    OutlinedButton(onClick = viewModel::saveCollection) { Text("Save draft to Library") }
                }
            }

            if (state.importedBooks.isNotEmpty()) {
                item {
                    Text("Imported EPUBs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Merged only in Combined Book mode. Internal TOC is locked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.importedBooks, key = { it.book.id.value }) { book ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(book.book.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    book.book.creator ?: "Unknown creator",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.removeImported(book.book.id.value) },
                                enabled = !busy,
                            ) { Text("Remove") }
                        }
                    }
                }
            }

            if (state.hasCatalog || state.importedBooks.isNotEmpty()) {
                item {
                    Text("Metadata", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.bookTitle,
                        onValueChange = viewModel::onTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = state.bookSubtitle,
                        onValueChange = viewModel::onSubtitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Subtitle") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = state.curator,
                        onValueChange = viewModel::onCuratorChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Author / curator") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = state.outputFilename,
                        onValueChange = viewModel::onFilenameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Output filename (optional)") },
                        singleLine = true,
                        enabled = !busy,
                        supportingText = { Text("Leave blank to use the book title") },
                    )
                }
                item {
                    Text("Cover accent", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CreateSessionLogic.accentPresets.forEach { (label, argb) ->
                            FilterChip(
                                selected = state.accentColorArgb == argb,
                                onClick = { viewModel.onAccentChange(argb) },
                                label = { Text(label) },
                                enabled = !busy,
                            )
                        }
                    }
                }
                item {
                    Text("Export mode", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.exportMode == ExportMode.COMBINED,
                            onClick = { viewModel.onExportModeChange(ExportMode.COMBINED) },
                            label = { Text("Combined") },
                        )
                        FilterChip(
                            selected = state.exportMode == ExportMode.SEPARATE,
                            onClick = { viewModel.onExportModeChange(ExportMode.SEPARATE) },
                            label = { Text("Separate books") },
                            enabled = state.importedBooks.isEmpty(),
                        )
                    }
                    Text("EPUB style", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EpubStyle.entries.forEach { style ->
                            FilterChip(
                                selected = state.epubStyle == style,
                                onClick = { viewModel.onStyleChange(style) },
                                label = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                }
            }

            if (state.hasCatalog) {
                item {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search catalog") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CatalogSort.entries.forEach { sort ->
                            FilterChip(
                                selected = state.sort == sort,
                                onClick = { viewModel.onSortChange(sort) },
                                label = {
                                    Text(
                                        when (sort) {
                                            CatalogSort.SITE_ORDER -> "Site"
                                            CatalogSort.TITLE -> "Title"
                                            CatalogSort.NEWEST -> "Newest"
                                            CatalogSort.OLDEST -> "Oldest"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::selectAll, enabled = !busy) { Text("Select all") }
                        TextButton(onClick = viewModel::selectAllSeries, enabled = !busy) { Text("Series only") }
                        TextButton(onClick = viewModel::clearSelection, enabled = !busy) { Text("Clear") }
                    }
                    Text(
                        state.catalogView!!.author.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                val display = state.filteredCatalog ?: state.catalogView!!
                item { Text("Series", style = MaterialTheme.typography.labelLarge) }
                items(display.series, key = { it.work.id.value }) { series ->
                    val workId = series.work.id
                    val expanded = workId in state.expandedSeriesIds
                    val isNew = series.chapters.any { it.id in state.newChapterIds }
                    Column {
                        WorkRow(
                            title = series.work.title + if (isNew) " · new parts" else "",
                            subtitle = buildString {
                                append("${series.chapters.size} chapters")
                                series.work.category?.let { append(" · $it") }
                                series.work.publicationDate?.let { append(" · $it") }
                            },
                            checked = workId in state.selectedWorkIds,
                            onToggle = { viewModel.toggleWork(workId) },
                            enabled = !busy,
                            onExpand = { viewModel.toggleSeriesExpanded(workId) },
                            expandedLabel = if (expanded) "Hide chapters" else "Chapters",
                            onMoveUp = { viewModel.moveWork(workId, -1) },
                            onMoveDown = { viewModel.moveWork(workId, 1) },
                        )
                        if (expanded && workId in state.selectedWorkIds) {
                            series.chapters.forEach { ch ->
                                ChapterRow(
                                    title = ch.title,
                                    checked = ch.id !in state.deselectedChapterIds,
                                    badge = when {
                                        ch.id in state.newChapterIds -> "new"
                                        ch.id in state.changedChapterIds -> "changed"
                                        else -> null
                                    },
                                    onToggle = { viewModel.toggleChapter(ch.id) },
                                    enabled = !busy,
                                )
                            }
                        }
                    }
                }
                item { Text("Standalone", style = MaterialTheme.typography.labelLarge) }
                items(display.standalones, key = { it.work.id.value }) { item ->
                    WorkRow(
                        title = item.work.title,
                        subtitle = buildString {
                            append(item.work.category ?: "Standalone")
                            item.work.publicationDate?.let { append(" · $it") }
                        },
                        checked = item.work.id in state.selectedWorkIds,
                        onToggle = { viewModel.toggleWork(item.work.id) },
                        enabled = !busy,
                        onMoveUp = { viewModel.moveWork(item.work.id, -1) },
                        onMoveDown = { viewModel.moveWork(item.work.id, 1) },
                    )
                }
                if (state.warnings.isNotEmpty()) {
                    item {
                        Text("Warnings", style = MaterialTheme.typography.labelLarge)
                        state.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (state.linkErrors.isNotEmpty()) {
                    item {
                        Text("Link errors", style = MaterialTheme.typography.labelLarge)
                        state.linkErrors.forEach { (u, e) ->
                            Text("• $u: $e", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    onExpand: (() -> Unit)? = null,
    expandedLabel: String? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onExpand != null && expandedLabel != null) {
                    TextButton(onClick = onExpand, enabled = enabled) { Text(expandedLabel) }
                }
                if (onMoveUp != null) {
                    TextButton(onClick = onMoveUp, enabled = enabled) { Text("Up") }
                }
                if (onMoveDown != null) {
                    TextButton(onClick = onMoveDown, enabled = enabled) { Text("Down") }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    title: String,
    checked: Boolean,
    badge: String?,
    onToggle: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Text(
            title + (badge?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
