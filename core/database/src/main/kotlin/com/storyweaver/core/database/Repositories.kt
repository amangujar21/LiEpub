package com.storyweaver.core.database

import com.storyweaver.core.model.Author
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionDraft
import com.storyweaver.core.model.CollectionId
import com.storyweaver.core.model.EpubStyle
import com.storyweaver.core.model.ExportMode
import com.storyweaver.core.model.ExportRecord
import com.storyweaver.core.model.SavedCollection
import com.storyweaver.core.model.Work
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class CollectionRepository(private val db: StoryWeaverDatabase) {
    fun observeCollections(): Flow<List<SavedCollection>> =
        db.collections().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun list(): List<SavedCollection> = db.collections().listAll().map { it.toDomain() }

    suspend fun get(id: CollectionId): SavedCollection? = db.collections().get(id.value)?.toDomain()

    suspend fun save(
        draft: CollectionDraft,
        authors: List<Author>,
        works: List<Work>,
        chapters: List<Chapter>,
        sourceUrls: List<String> = emptyList(),
        createdAt: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        val existing = db.collections().get(draft.id.value)
        val entity = CollectionEntity(
            id = draft.id.value,
            title = draft.title,
            subtitle = draft.subtitle,
            curator = draft.curator,
            exportMode = draft.exportMode.name,
            epubStyle = draft.epubStyle.name,
            accentColorArgb = draft.accentColorArgb,
            nodesJson = JsonCodec.nodesToJson(draft.nodes),
            authorsJson = JsonCodec.authorsToJson(authors),
            worksJson = JsonCodec.worksToJson(works),
            chaptersJson = JsonCodec.chaptersToJson(chapters),
            sourceUrlsJson = JsonCodec.stringListToJson(sourceUrls),
            createdAtEpochMs = createdAt ?: existing?.createdAtEpochMs ?: now,
            modifiedAtEpochMs = now,
        )
        db.collections().upsert(entity)
    }

    suspend fun delete(id: CollectionId) = db.collections().delete(id.value)

    private fun CollectionEntity.toDomain(): SavedCollection {
        val draft = CollectionDraft(
            id = CollectionId(id),
            title = title,
            subtitle = subtitle,
            curator = curator,
            exportMode = ExportMode.valueOf(exportMode),
            epubStyle = EpubStyle.valueOf(epubStyle),
            accentColorArgb = accentColorArgb,
            nodes = JsonCodec.nodesFromJson(nodesJson),
        )
        return SavedCollection(
            draft = draft,
            authors = JsonCodec.authorsFromJson(authorsJson),
            works = JsonCodec.worksFromJson(worksJson),
            chapters = JsonCodec.chaptersFromJson(chaptersJson),
            sourceUrls = JsonCodec.stringListFromJson(sourceUrlsJson),
            createdAtEpochMs = createdAtEpochMs,
            modifiedAtEpochMs = modifiedAtEpochMs,
        )
    }
}

class ChapterCacheRepository(private val db: StoryWeaverDatabase) {
    suspend fun get(chapterId: ChapterId): ChapterContent? {
        val e = db.cachedChapters().get(chapterId.value) ?: return null
        db.cachedChapters().upsert(e.copy(lastReferencedEpochMs = System.currentTimeMillis()))
        return e.toContent()
    }

    suspend fun getByUrl(url: String): ChapterContent? =
        db.cachedChapters().getByUrl(url)?.toContent()

    suspend fun put(content: ChapterContent, canonicalUrl: String) {
        val now = System.currentTimeMillis()
        db.cachedChapters().upsert(
            CachedChapterEntity(
                chapterId = content.chapterId.value,
                canonicalUrl = canonicalUrl,
                title = content.title,
                authorDisplayName = content.authorDisplayName,
                xhtmlBody = content.xhtmlBody,
                contentHash = content.contentHash,
                sourceFingerprint = content.sourceFingerprint,
                fetchedAtEpochMs = now,
                lastReferencedEpochMs = now,
            ),
        )
    }

    suspend fun putAll(contents: Map<ChapterId, ChapterContent>, urlById: Map<ChapterId, String>) {
        val now = System.currentTimeMillis()
        db.cachedChapters().upsertAll(
            contents.map { (id, c) ->
                CachedChapterEntity(
                    chapterId = id.value,
                    canonicalUrl = urlById[id] ?: "",
                    title = c.title,
                    authorDisplayName = c.authorDisplayName,
                    xhtmlBody = c.xhtmlBody,
                    contentHash = c.contentHash,
                    sourceFingerprint = c.sourceFingerprint,
                    fetchedAtEpochMs = now,
                    lastReferencedEpochMs = now,
                )
            },
        )
    }

    suspend fun purgeOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        return db.cachedChapters().deleteOlderThan(cutoff)
    }

    suspend fun clearAll() = db.cachedChapters().clearAll()

    suspend fun stats(): Pair<Int, Long> {
        val count = db.cachedChapters().count()
        val bytes = db.cachedChapters().totalBodyBytes() ?: 0L
        return count to bytes
    }

    private fun CachedChapterEntity.toContent() = ChapterContent(
        chapterId = ChapterId(chapterId),
        title = title,
        authorDisplayName = authorDisplayName,
        xhtmlBody = xhtmlBody,
        contentHash = contentHash,
        sourceFingerprint = sourceFingerprint,
    )
}

class ExportHistoryRepository(private val db: StoryWeaverDatabase) {
    fun observe(): Flow<List<ExportRecord>> =
        db.exportHistory().observeAll().map { list ->
            list.map {
                ExportRecord(
                    id = it.id,
                    collectionId = CollectionId(it.collectionId),
                    title = it.title,
                    filename = it.filename,
                    incomplete = it.incomplete,
                    destinationUri = it.destinationUri,
                    createdAtEpochMs = it.createdAtEpochMs,
                    status = it.status,
                )
            }
        }

    suspend fun record(
        collectionId: CollectionId,
        title: String,
        filename: String,
        incomplete: Boolean,
        destinationUri: String?,
        status: String,
    ) {
        db.exportHistory().insert(
            ExportHistoryEntity(
                id = UUID.randomUUID().toString(),
                collectionId = collectionId.value,
                title = title,
                filename = filename,
                incomplete = incomplete,
                destinationUri = destinationUri,
                createdAtEpochMs = System.currentTimeMillis(),
                status = status,
            ),
        )
    }
}
