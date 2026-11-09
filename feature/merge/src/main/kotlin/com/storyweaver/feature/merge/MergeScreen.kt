package com.storyweaver.feature.merge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MergeScreen(
    viewModel: MergeViewModel,
    defaultOutputFolderUri: String? = null,
    onSaveToFolder: (suspend (filename: String, bytes: ByteArray) -> String?)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showToc by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    val pickEpubs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        viewModel.addFiles(
            uris.map { uri ->
                PickedFile(
                    uri = uri.toString(),
                    displayName = displayName(context, uri),
                    read = { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } },
                )
            },
        )
    }

    val saveAs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri: Uri? ->
        val result = state.result ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(result.bytes) } != null
                }.getOrDefault(false)
            }
            saveMessage = if (ok) "Saved ${result.filename}" else "Could not save the file"
        }
    }

    fun cacheUri(filename: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("Merge EPUBs", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Combine DRM-free EPUBs into one book. The table of contents follows the order below, " +
                    "with each book's own chapters nested under it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { pickEpubs.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add EPUBs")
                }
                if (state.items.size > 1) {
                    TextButton(onClick = viewModel::sortByTitle, enabled = !state.busy) { Text("Sort A→Z") }
                }
                if (state.items.isNotEmpty()) {
                    TextButton(onClick = viewModel::clear, enabled = !state.busy) { Text("Clear") }
                }
            }
        }

        if (state.busy) {
            item {
                Column {
                    val progress = state.importProgress
                    Text(
                        when {
                            state.phase == MergePhase.MERGING -> "Merging…"
                            progress != null -> "Reading ${minOf(progress.first + 1, progress.second)} of ${progress.second}…"
                            else -> "Working…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (progress != null && progress.second > 0) {
                        LinearProgressIndicator(
                            progress = { progress.first.toFloat() / progress.second },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        state.error?.let { err ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        (saveMessage ?: state.message)?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }

        if (state.items.isEmpty() && !state.busy) {
            item {
                Text(
                    "No books yet. Tap “Add EPUBs” and pick two or more files (you can select several at once).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                item.creator,
                                "${item.tocEntryCount} TOC entries".takeIf { item.tocEntryCount > 0 } ?: "no TOC",
                                formatSize(item.sizeBytes),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { viewModel.move(item.key, -1) }, enabled = index > 0 && !state.busy) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { viewModel.move(item.key, 1) }, enabled = index < state.items.lastIndex && !state.busy) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                    }
                    IconButton(onClick = { viewModel.remove(item.key) }, enabled = !state.busy) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
            }
        }

        if (state.items.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        label = { Text("Book title") },
                        placeholder = { Text(viewModel.suggestedTitle(state), maxLines = 1) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.author,
                        onValueChange = viewModel::onAuthorChange,
                        label = { Text("Author") },
                        placeholder = { Text(viewModel.suggestedAuthor(state), maxLines = 1) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow("Generate a cover", state.generateCover, viewModel::onGenerateCoverChange)
                    SwitchRow("Title page before each book", state.titlePagePerBook, viewModel::onTitlePagesChange)
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showToc = !showToc },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Merged table of contents (${state.outline.size})",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (showToc) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (showToc) "Hide" else "Show",
                            )
                        }
                        if (showToc) {
                            Spacer(Modifier.height(6.dp))
                            val shown = state.outline.take(MAX_TOC_PREVIEW)
                            shown.forEach { entry ->
                                Text(
                                    entry.label,
                                    style = if (entry.depth == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                    color = if (entry.depth == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = (entry.depth * 16).dp, top = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (state.outline.size > shown.size) {
                                Text(
                                    "… and ${state.outline.size - shown.size} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        saveMessage = null
                        viewModel.merge()
                    },
                    enabled = state.canMerge,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.items.size == 1) "Rebuild book" else "Merge ${state.items.size} books")
                }
            }
        }

        state.result?.let { result ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.filename, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveAs.launch(result.filename) }) { Text("Save as…") }
                        OutlinedButton(onClick = {
                            val uri = cacheUri(result.filename, result.bytes)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/epub+zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share EPUB"))
                        }) { Text("Share") }
                        OutlinedButton(onClick = {
                            val uri = cacheUri(result.filename, result.bytes)
                            val open = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/epub+zip")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(open, "Open in reader")) }
                        }) { Text("Open") }
                    }
                    if (onSaveToFolder != null && defaultOutputFolderUri != null) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val saved = onSaveToFolder(result.filename, result.bytes)
                                saveMessage = if (saved != null) "Saved to output folder" else "Could not save to folder"
                            }
                        }) { Text("Save to output folder") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val MAX_TOC_PREVIEW = 300

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "book.epub"
