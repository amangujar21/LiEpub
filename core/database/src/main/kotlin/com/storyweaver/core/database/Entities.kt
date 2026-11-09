package com.storyweaver.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val subtitle: String?,
    val curator: String?,
    val exportMode: String,
    val epubStyle: String,
    val accentColorArgb: Long,
    val nodesJson: String,
    val authorsJson: String,
    val worksJson: String,
    val chaptersJson: String,
    val sourceUrlsJson: String,
    val createdAtEpochMs: Long,
    val modifiedAtEpochMs: Long,
)

@Entity(tableName = "cached_chapters")
data class CachedChapterEntity(
    @PrimaryKey val chapterId: String,
    val canonicalUrl: String,
    val title: String,
    val authorDisplayName: String?,
    val xhtmlBody: String,
    val contentHash: String,
    val sourceFingerprint: String?,
    val fetchedAtEpochMs: Long,
    val lastReferencedEpochMs: Long,
)

@Entity(tableName = "export_history")
data class ExportHistoryEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val title: String,
    val filename: String,
    val incomplete: Boolean,
    val destinationUri: String?,
    val createdAtEpochMs: Long,
    val status: String,
)

@Entity(tableName = "imported_books")
data class ImportedBookEntity(
    @PrimaryKey val id: String,
    val collectionId: String?,
    val documentUri: String,
    val packageIdentifier: String?,
    val title: String,
    val creator: String?,
    val language: String?,
    val formatVersion: String?,
    val contentFingerprint: String,
    val available: Boolean,
)
