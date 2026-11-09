package com.storyweaver.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY modifiedAtEpochMs DESC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY modifiedAtEpochMs DESC")
    suspend fun listAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CachedChapterDao {
    @Query("SELECT * FROM cached_chapters WHERE chapterId = :id LIMIT 1")
    suspend fun get(id: String): CachedChapterEntity?

    @Query("SELECT * FROM cached_chapters WHERE canonicalUrl = :url LIMIT 1")
    suspend fun getByUrl(url: String): CachedChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedChapterEntity>)

    @Query("SELECT * FROM cached_chapters")
    suspend fun listAll(): List<CachedChapterEntity>

    @Query("DELETE FROM cached_chapters WHERE lastReferencedEpochMs < :beforeEpochMs")
    suspend fun deleteOlderThan(beforeEpochMs: Long): Int

    @Query("DELETE FROM cached_chapters")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM cached_chapters")
    suspend fun count(): Int

    @Query("SELECT SUM(LENGTH(xhtmlBody)) FROM cached_chapters")
    suspend fun totalBodyBytes(): Long?
}

@Dao
interface ExportHistoryDao {
    @Query("SELECT * FROM export_history ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 50): List<ExportHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ExportHistoryEntity)
}

@Dao
interface ImportedBookDao {
    @Query("SELECT * FROM imported_books WHERE collectionId = :collectionId")
    suspend fun forCollection(collectionId: String): List<ImportedBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportedBookEntity)

    @Query("DELETE FROM imported_books WHERE id = :id")
    suspend fun delete(id: String)
}
