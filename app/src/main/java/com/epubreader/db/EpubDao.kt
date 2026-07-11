package com.epubreader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EpubBookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: EpubBookEntity)

    @Update
    suspend fun updateBook(book: EpubBookEntity)

    @Query("DELETE FROM epub_books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("SELECT * FROM epub_books ORDER BY lastRead DESC")
    fun getAllBooks(): Flow<List<EpubBookEntity>>

    @Query("SELECT * FROM epub_books WHERE id = :id")
    suspend fun getBookById(id: String): EpubBookEntity?

    @Query("SELECT * FROM epub_books WHERE id = :id")
    fun getBookFlowById(id: String): Flow<EpubBookEntity?>

    @Query("UPDATE epub_books SET lastRead = :timestamp, readingProgress = :progress WHERE id = :id")
    suspend fun updateReadingProgress(id: String, timestamp: Long, progress: Int)
}

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun getProgressFlow(bookId: String): Flow<ReadingProgressEntity?>
}

@Dao
interface EpubSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: EpubSettingsEntity)

    @Update
    suspend fun updateSettings(settings: EpubSettingsEntity)

    @Query("SELECT * FROM epub_settings WHERE key = 'default'")
    suspend fun getSettings(): EpubSettingsEntity?

    @Query("SELECT * FROM epub_settings WHERE key = 'default'")
    fun getSettingsFlow(): Flow<EpubSettingsEntity?>
}
