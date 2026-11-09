package com.storyweaver.app.di

import android.content.Context
import com.storyweaver.app.data.UserPreferencesRepository
import com.storyweaver.core.database.ChapterCacheRepository
import com.storyweaver.core.database.CollectionRepository
import com.storyweaver.core.database.ExportHistoryRepository
import com.storyweaver.core.database.StoryWeaverDatabase
import com.storyweaver.core.model.Author
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionDraft
import com.storyweaver.core.model.CollectionId
import com.storyweaver.core.model.Work
import com.storyweaver.core.network.AppUpdateChecker
import com.storyweaver.core.network.HttpFetcher
import com.storyweaver.core.network.createStoryWeaverClient
import com.storyweaver.epub.EpubImporter
import com.storyweaver.epub.EpubMerger
import com.storyweaver.epub.EpubWriter
import com.storyweaver.feature.create.session.ChapterCachePort
import com.storyweaver.feature.create.session.CollectionStore
import com.storyweaver.feature.create.session.ExportHistoryPort
import com.storyweaver.source.literotica.DefaultLiteroticaSource
import com.storyweaver.source.literotica.LiteroticaSource

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val client by lazy { createStoryWeaverClient() }
    private val fetcher by lazy { HttpFetcher(client) }

    val database: StoryWeaverDatabase by lazy { StoryWeaverDatabase.create(appContext) }
    val preferences: UserPreferencesRepository by lazy { UserPreferencesRepository(appContext) }
    val collections: CollectionRepository by lazy { CollectionRepository(database) }
    val chapterCache: ChapterCacheRepository by lazy { ChapterCacheRepository(database) }
    val exportHistory: ExportHistoryRepository by lazy { ExportHistoryRepository(database) }

    val literoticaSource: LiteroticaSource by lazy { DefaultLiteroticaSource(fetcher) }
    val epubWriter: EpubWriter by lazy { EpubWriter() }
    val epubImporter: EpubImporter by lazy { EpubImporter() }
    val epubMerger: EpubMerger by lazy { EpubMerger(epubWriter, epubImporter) }
    val appUpdateChecker: AppUpdateChecker by lazy { AppUpdateChecker() }

    val collectionStore: CollectionStore by lazy {
        object : CollectionStore {
            override suspend fun save(
                draft: CollectionDraft,
                authors: List<Author>,
                works: List<Work>,
                chapters: List<Chapter>,
                sourceUrls: List<String>,
            ) {
                collections.save(draft, authors, works, chapters, sourceUrls)
            }
        }
    }

    val chapterCachePort: ChapterCachePort by lazy {
        object : ChapterCachePort {
            override suspend fun getByUrl(url: String): ChapterContent? = chapterCache.getByUrl(url)
            override suspend fun putAll(
                contents: Map<ChapterId, ChapterContent>,
                urlById: Map<ChapterId, String>,
            ) {
                chapterCache.putAll(contents, urlById)
            }
        }
    }

    val exportHistoryPort: ExportHistoryPort by lazy {
        object : ExportHistoryPort {
            override suspend fun record(
                collectionId: CollectionId,
                title: String,
                filename: String,
                incomplete: Boolean,
            ) {
                exportHistory.record(
                    collectionId = collectionId,
                    title = title,
                    filename = filename,
                    incomplete = incomplete,
                    destinationUri = null,
                    status = if (incomplete) "INCOMPLETE" else "COMPLETE",
                )
            }
        }
    }
}
