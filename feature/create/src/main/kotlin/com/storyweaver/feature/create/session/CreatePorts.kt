package com.storyweaver.feature.create.session

import com.storyweaver.core.model.Author
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterContent
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.CollectionDraft
import com.storyweaver.core.model.CollectionId
import com.storyweaver.core.model.Work

interface CollectionStore {
    suspend fun save(
        draft: CollectionDraft,
        authors: List<Author>,
        works: List<Work>,
        chapters: List<Chapter>,
        sourceUrls: List<String>,
    )
}

interface ChapterCachePort {
    suspend fun getByUrl(url: String): ChapterContent?
    suspend fun putAll(contents: Map<ChapterId, ChapterContent>, urlById: Map<ChapterId, String>)
}

interface ExportHistoryPort {
    suspend fun record(
        collectionId: CollectionId,
        title: String,
        filename: String,
        incomplete: Boolean,
    )
}
