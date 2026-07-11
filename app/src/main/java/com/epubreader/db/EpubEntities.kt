package com.epubreader.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.epubreader.EpubBook
import com.epubreader.EpubChapter
import com.epubreader.EpubSettings
import com.epubreader.ReadingTheme
import com.epubreader.ScrollMode
import com.epubreader.TextAlignment
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "epub_books")
data class EpubBookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val identifier: String,
    val language: String,
    val publisher: String,
    val description: String,
    val coverHref: String,
    val coverImage: ByteArray? = null,
    val chaptersJson: String,
    val spineJson: String,
    val metadataJson: String,
    val filePath: String,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastRead: Long = 0,
    val readingProgress: Int = 0
)

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int = 0,
    val scrollPosition: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "epub_settings")
data class EpubSettingsEntity(
    @PrimaryKey val key: String = "default",
    val fontSize: Float = 16f,
    val lineHeight: Float = 1.5f,
    val margin: Float = 16f,
    val theme: String = ReadingTheme.SYSTEM.name,
    val scrollMode: String = ScrollMode.VERTICAL.name,
    val fontFamily: String = "sans-serif",
    val textAlign: String = TextAlignment.JUSTIFY.name,
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = false,
    val showPageNumbers: Boolean = true,
    val showProgress: Boolean = true,
    // TTS Settings
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsLanguage: String = "zh-CN",
    val ttsVoiceName: String? = null,
    val ttsPlaybackSpeed: Float = 1.0f,
    val ttsHighlightText: Boolean = true,
    val ttsAutoScroll: Boolean = true,
    val ttsSkipFootnotes: Boolean = true
)

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun chaptersToJson(chapters: List<EpubChapter>): String = json.encodeToString(chapters)
    
    @TypeConverter
    fun jsonToChapters(json: String): List<EpubChapter> = json.decodeFromString()

    @TypeConverter
    fun spineToJson(spine: List<String>): String = json.encodeToString(spine)
    
    @TypeConverter
    fun jsonToSpine(json: String): List<String> = json.decodeFromString()

    @TypeConverter
    fun metadataToJson(metadata: Map<String, String>): String = json.encodeToString(metadata)
    
    @TypeConverter
    fun jsonToMetadata(json: String): Map<String, String> = json.decodeFromString()
}
