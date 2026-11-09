package com.storyweaver.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CollectionEntity::class,
        CachedChapterEntity::class,
        ExportHistoryEntity::class,
        ImportedBookEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class StoryWeaverDatabase : RoomDatabase() {
    abstract fun collections(): CollectionDao
    abstract fun cachedChapters(): CachedChapterDao
    abstract fun exportHistory(): ExportHistoryDao
    abstract fun importedBooks(): ImportedBookDao

    companion object {
        fun create(context: Context): StoryWeaverDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                StoryWeaverDatabase::class.java,
                "storyweaver.db",
            ).fallbackToDestructiveMigration().build()
    }
}
