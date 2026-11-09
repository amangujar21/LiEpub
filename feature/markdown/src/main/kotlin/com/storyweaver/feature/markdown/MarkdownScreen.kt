package com.storyweaver.feature.markdown

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

private const val MARKDOWN_MIME = "text/markdown"

@Composable
fun MarkdownScreen(
    viewModel: MarkdownViewModel,
    defaultOutputFolderUri: String? = null,
    onSaveToFolder: (suspend (filename: String, bytes: ByteArray) -> String?)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveMessage by remember { mutableStateOf<String?>(null) }

    val pickEpub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        saveMessage = null
        viewModel.load(displayName(context, uri), uri.toString()) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MARKDOWN_MIME)) { uri: Uri? ->
        val result = state.result ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(result.markdown.toByteArray()) } != null
                }.getOrDefault(false)
            }
            saveMessage = if (ok) "Saved ${result.filename}" else "Could not save the file"
        }
    }

    fun cacheUri(filename: String, text: String): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename).apply { writeText(text) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("Export Markdown", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Turn a whole EPUB, or just the chapters you tick, into a Markdown (.md) file. " +
                    "Works with books made here and any other DRM-free EPUB. Images are left out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Button(
                onClick = { pickEpub.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                enabled = !state.busy,
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.epub == null) "Choose EPUB" else "Choose another EPUB")
            }
        }

        if (state.busy) {
            item {
                Column {
                    Text(
                        if (state.phase == MarkdownPhase.LOADING) "Reading book…" else "Converting…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        state.error?.let { err ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.dismissError() },
                ) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        val epub = state.epub
        if (epub == null && !state.busy) {
            item {
                Text(
                    "No book loaded. Choose an EPUB, or tap “Markdown” after creating or merging a book.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        if (epub != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(epub.book.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(epub.book.creator, state.sourceName, "${state.outline.size} TOC entries")
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveMessage = null; viewModel.export(wholeBook = true) }, enabled = !state.busy) {
                        Text("Export whole book")
                    }
                    OutlinedButton(
                        onClick = { saveMessage = null; viewModel.export(wholeBook = false) },
                        enabled = state.canExportSelection,
                    ) {
                        Text(if (state.selected.isEmpty()) "Export selected" else "Export selected (${state.selected.size})")
                    }
                }
            }

            state.result?.let { result ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(result.filename, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${result.markdown.length / 1024} KB of Markdown",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { saveAs.launch(result.filename) }) { Text("Save as…") }
                                OutlinedButton(onClick = {
                                    val uri = cacheUri(result.filename, result.markdown)
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = MARKDOWN_MIME
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(share, "Share Markdown"))
                                }) { Text("Share") }
                            }
                            if (onSaveToFolder != null && defaultOutputFolderUri != null) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val saved = onSaveToFolder(result.filename, result.markdown.toByteArray())
                                        saveMessage = if (saved != null) "Saved to output folder" else "Could not save to folder"
                                    }
                                }) { Text("Save to output folder") }
                            }
                            saveMessage?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Chapters",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::selectAll, enabled = !state.busy) { Text("All") }
                    TextButton(onClick = viewModel::selectNone, enabled = !state.busy) { Text("None") }
                }
                Text(
                    "Ticking an author or series includes everything under it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(state.outline, key = { it.id }) { section ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.busy) { viewModel.toggle(section.id) }
                        .padding(start = (section.depth * 20).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = section.id in state.selected,
                        onCheckedChange = { viewModel.toggle(section.id) },
                        enabled = !state.busy,
                    )
                    Text(
                        section.title,
                        style = if (section.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "book.epub"
