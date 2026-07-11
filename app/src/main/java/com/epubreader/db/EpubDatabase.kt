package com.epubreader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.epubreader.EpubBook
import com.epubreader.EpubChapter
import com.epubreader.EpubSettings

@Database(
    entities = [EpubBookEntity::class, ReadingProgressEntity::class, EpubSettingsEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class EpubDatabase : RoomDatabase() {
    abstract fun epubBookDao(): EpubBookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun epubSettingsDao(): EpubSettingsDao

    companion object {
        @Volatile private var INSTANCE: EpubDatabase? = null

        fun getDatabase(context: Context): EpubDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EpubDatabase::class.java,
                    "epub_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
