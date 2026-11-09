package com.storyweaver.feature.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storyweaver.core.model.ExportRecord
import com.storyweaver.core.model.SavedCollection
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(
    collections: List<SavedCollection> = emptyList(),
    exportHistory: List<ExportRecord> = emptyList(),
    onOpen: (SavedCollection) -> Unit = {},
    onDelete: (SavedCollection) -> Unit = {},
    onRefresh: (SavedCollection) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Library", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Saved collections. Open to re-export or check sources.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (collections.isEmpty()) {
                item {
                    Text(
                        "No saved collections yet. Analyze links on Create, then Save to Library.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(collections, key = { it.draft.id.value }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(item) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.draft.title, style = MaterialTheme.typography.titleMedium)
                            item.draft.curator?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Modified ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.modifiedAtEpochMs))} · " +
                                    "${item.works.size} works · ${item.chapters.size} chapters",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                TextButton(onClick = { onOpen(item) }) { Text("Open") }
                                TextButton(onClick = { onRefresh(item) }) { Text("Check updates") }
                                TextButton(onClick = { onDelete(item) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            if (exportHistory.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Recent exports", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "New files only — exports never overwrite silently.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(exportHistory.take(20), key = { it.id }) { record ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(record.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${record.filename} · ${record.status}" +
                                    if (record.incomplete) " · incomplete" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(record.createdAtEpochMs)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
