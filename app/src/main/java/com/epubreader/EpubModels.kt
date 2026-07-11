package com.epubreader

import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.Serializable

@Serializable
data class EpubChapter(
    val id: String,
    val title: String,
    val href: String,
    val content: String = "",
    val order: Int = 0,
    val level: Int = 0,
    val children: List<EpubChapter> = emptyList()
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        title = parcel.readString() ?: "",
        href = parcel.readString() ?: "",
        content = parcel.readString() ?: "",
        order = parcel.readInt(),
        level = parcel.readInt(),
        children = parcel.createTypedArrayList(EpubChapter.CREATOR)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(title)
        parcel.writeString(href)
        parcel.writeString(content)
        parcel.writeInt(order)
        parcel.writeInt(level)
        parcel.writeTypedList(children)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<EpubChapter> {
        override fun createFromParcel(parcel: Parcel): EpubChapter = EpubChapter(parcel)
        override fun newArray(size: Int): Array<EpubChapter?> = arrayOfNulls(size)
    }
}

data class TocItem(
    val title: String,
    val href: String,
    val children: List<TocItem> = emptyList(),
    val level: Int = 0
)

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String = ""
)

@Serializable
data class EpubBook(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val identifier: String = "",
    val language: String = "en",
    val publisher: String = "",
    val description: String = "",
    val coverHref: String = "",
    val coverImage: ByteArray? = null,
    val chapters: List<EpubChapter> = emptyList(),
    val spine: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val filePath: String = ""
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        title = parcel.readString() ?: "",
        author = parcel.readString() ?: "",
        identifier = parcel.readString() ?: "",
        language = parcel.readString() ?: "en",
        publisher = parcel.readString() ?: "",
        description = parcel.readString() ?: "",
        coverHref = parcel.readString() ?: "",
        coverImage = parcel.createByteArray(),
        chapters = parcel.createTypedArrayList(EpubChapter.CREATOR),
        spine = parcel.createStringArrayList(),
        metadata = parcel.readHashMap(String::class.java, String::class.java),
        filePath = parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(title)
        parcel.writeString(author)
        parcel.writeString(identifier)
        parcel.writeString(language)
        parcel.writeString(publisher)
        parcel.writeString(description)
        parcel.writeString(coverHref)
        parcel.writeByteArray(coverImage ?: byteArrayOf())
        parcel.writeTypedList(chapters)
        parcel.writeStringList(spine)
        parcel.writeMap(metadata)
        parcel.writeString(filePath)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<EpubBook> {
        override fun createFromParcel(parcel: Parcel): EpubBook = EpubBook(parcel)
        override fun newArray(size: Int): Array<EpubBook?> = arrayOfNulls(size)
    }

    /**
     * Get all chapters in reading order (flattened)
     */
    val flattenedChapters: List<EpubChapter>
        get() = flattenChapters(chapters)

    private fun flattenChapters(chapters: List<EpubChapter>): List<EpubChapter> {
        val result = mutableListOf<EpubChapter>()
        chapters.forEach { chapter ->
            result.add(chapter)
            if (chapter.children.isNotEmpty()) {
                result.addAll(flattenChapters(chapter.children))
            }
        }
        return result
    }

    /**
     * Get chapter by index in reading order
     */
    fun getChapterAt(index: Int): EpubChapter? {
        return flattenedChapters.getOrNull(index)
    }

    /**
     * Get total chapter count
     */
    val chapterCount: Int
        get() = flattenedChapters.size
}

enum class ReadingTheme {
    LIGHT, DARK, SEPIA, SYSTEM
}

enum class ScrollMode {
    VERTICAL, HORIZONTAL, PAGE
}

enum class TextAlignment {
    LEFT, CENTER, RIGHT, JUSTIFY
}

@Serializable
data class EpubSettings(
    val fontSize: Float = 16f,
    val lineHeight: Float = 1.5f,
    val margin: Float = 16f,
    val theme: ReadingTheme = ReadingTheme.SYSTEM,
    val scrollMode: ScrollMode = ScrollMode.VERTICAL,
    val fontFamily: String = "sans-serif",
    val textAlign: TextAlignment = TextAlignment.JUSTIFY,
    val brightness: Float = -1f, // -1 = system
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

enum class TtsState {
    IDLE, PLAYING, PAUSED, STOPPED, ERROR
}

data class TtsPlaybackInfo(
    val state: TtsState = TtsState.IDLE,
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String = "",
    val progress: Int = 0,
    val maxProgress: Int = 100,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val language: String = "zh-CN",
    val voiceName: String? = null,
    val playbackSpeed: Float = 1.0f
)
