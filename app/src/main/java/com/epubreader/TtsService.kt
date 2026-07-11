package com.epubreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "TtsService"
        const val CHANNEL_ID = "tts_playback_channel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_PLAY = "com.epubreader.tts.PLAY"
        const val ACTION_PAUSE = "com.epubreader.tts.PAUSE"
        const val ACTION_STOP = "com.epubreader.tts.STOP"
        const val ACTION_NEXT = "com.epubreader.tts.NEXT"
        const val ACTION_PREVIOUS = "com.epubreader.tts.PREVIOUS"
        const val ACTION_TOGGLE = "com.epubreader.tts.TOGGLE"
        
        const val EXTRA_TEXT = "tts_text"
        const val EXTRA_CHAPTER_INDEX = "tts_chapter_index"
        const val EXTRA_CHAPTER_TITLE = "tts_chapter_title"
        const val EXTRA_CHAPTERS = "tts_chapters"
        const val EXTRA_SPEECH_RATE = "tts_speech_rate"
        const val EXTRA_PITCH = "tts_pitch"
        const val EXTRA_LANGUAGE = "tts_language"
        const val EXTRA_VOICE_NAME = "tts_voice_name"
        const val EXTRA_PLAYBACK_SPEED = "tts_playback_speed"
        
        const val PREFS_NAME = "tts_prefs"
        const val PREF_SPEECH_RATE = "speech_rate"
        const val PREF_PITCH = "pitch"
        const val PREF_LANGUAGE = "language"
        const val PREF_VOICE_NAME = "voice_name"
        const val PREF_PLAYBACK_SPEED = "playback_speed"
        const val PREF_CURRENT_CHAPTER = "current_chapter"
        const val PREF_CURRENT_POSITION = "current_position"
        
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_LANGUAGE = "zh-CN"
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isSpeaking = false
    private var isPaused = false
    
    private var currentText = ""
    private var currentChapterIndex = 0
    private var currentChapterTitle = ""
    private var chapters: List<EpubChapter> = emptyList()
    
    private var speechRate = DEFAULT_SPEECH_RATE
    private var pitch = DEFAULT_PITCH
    private var language = DEFAULT_LANGUAGE
    private var voiceName: String? = null
    private var playbackSpeed = DEFAULT_PLAYBACK_SPEED
    
    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val callbacks = ConcurrentHashMap<Int, TtsCallback>()
    private var callbackIdCounter = 0
    
    inner class LocalBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    interface TtsCallback {
        fun onInit(success: Boolean)
        fun onStartSpeaking(utteranceId: String)
        fun onDoneSpeaking(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int)
        fun onProgress(utteranceId: String, start: Int, end: Int, percent: Int)
        fun onChapterChanged(chapterIndex: Int, chapterTitle: String)
        fun onStateChanged(isSpeaking: Boolean, isPaused: Boolean)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        loadPreferences()
        createNotificationChannel()
        initTts()
        acquireWakeLock()
    }

    private fun loadPreferences() {
        speechRate = prefs.getFloat(PREF_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        pitch = prefs.getFloat(PREF_PITCH, DEFAULT_PITCH)
        language = prefs.getString(PREF_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        voiceName = prefs.getString(PREF_VOICE_NAME)
        playbackSpeed = prefs.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED)
        currentChapterIndex = prefs.getInt(PREF_CURRENT_CHAPTER, 0)
    }

    private fun savePreferences() {
        prefs.edit()
            .putFloat(PREF_SPEECH_RATE, speechRate)
            .putFloat(PREF_PITCH, pitch)
            .putString(PREF_LANGUAGE, language)
            .putString(PREF_VOICE_NAME, voiceName)
            .putFloat(PREF_PLAYBACK_SPEED, playbackSpeed)
            .putInt(PREF_CURRENT_CHAPTER, currentChapterIndex)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Text-to-Speech playback controls"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                isSpeaking = true
                isPaused = false
                callbacks.values.forEach { it.onStartSpeaking(utteranceId) }
                callbacks.values.forEach { it.onStateChanged(true, false) }
                updateNotification()
            }

            override fun onDone(utteranceId: String) {
                isSpeaking = false
                isPaused = false
                callbacks.values.forEach { it.onDoneSpeaking(utteranceId) }
                callbacks.values.forEach { it.onStateChanged(false, false) }
                
                if (currentChapterIndex < chapters.size - 1) {
                    playNextChapter()
                } else {
                    stopSelf()
                }
                updateNotification()
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                isSpeaking = false
                isPaused = false
                callbacks.values.forEach { it.onError(utteranceId, errorCode) }
                callbacks.values.forEach { it.onStateChanged(false, false) }
                Log.e(TAG, "TTS Error: $errorCode")
                updateNotification()
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val percent = if (currentText.isNotEmpty()) {
                    (start * 100 / currentText.length).coerceIn(0, 100)
                } else 0
                callbacks.values.forEach { it.onProgress(utteranceId, start, end, percent) }
            }
        })
    }

    private fun acquireWakeLock() {
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TtsService::WakeLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            val locale = Locale.forLanguageTag(language)
            tts?.setLanguage(locale)
            voiceName?.let { vName ->
                tts?.voices?.firstOrNull { it.name == vName }?.let { voice ->
                    tts?.setVoice(voice)
                }
            }
            tts?.speechRate = speechRate * playbackSpeed
            tts?.pitch = pitch
            callbacks.values.forEach { it.onInit(true) }
        } else {
            isInitialized = false
            callbacks.values.forEach { it.onInit(false) }
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> resumeSpeaking()
                ACTION_PAUSE -> pauseSpeaking()
                ACTION_STOP -> stopSpeaking()
                ACTION_NEXT -> playNextChapter()
                ACTION_PREVIOUS -> playPreviousChapter()
                ACTION_TOGGLE -> togglePlayPause()
            }
        }
        
        intent?.let { i ->
            i.getStringExtra(EXTRA_TEXT)?.let { text ->
                currentText = text
                currentChapterIndex = i.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
                currentChapterTitle = i.getStringExtra(EXTRA_CHAPTER_TITLE) ?: ""
                chapters = i.getParcelableArrayListExtra(EXTRA_CHAPTERS)?.map { it as EpubChapter } ?: emptyList()
                speakCurrentChapter()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    fun setChapters(chapters: List<EpubBook>) {
        this.chapters = chapters.flatMap { it.flattenedChapters }
    }

    fun setCurrentChapter(index: Int) {
        if (index in chapters.indices) {
            currentChapterIndex = index
            currentChapterTitle = chapters[index].title
            currentText = chapters[index].content
        }
    }

    fun speakText(text: String, chapterIndex: Int, chapterTitle: String, allChapters: List<EpubChapter>) {
        currentText = text
        currentChapterIndex = chapterIndex
        currentChapterTitle = chapterTitle
        chapters = allChapters
        speakCurrentChapter()
    }

    private fun speakCurrentChapter() {
        if (currentText.isBlank()) return
        
        val utteranceId = "chapter_${currentChapterIndex}_${System.currentTimeMillis()}"
        
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        
        tts?.speak(currentText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        callbacks.values.forEach { it.onChapterChanged(currentChapterIndex, currentChapterTitle) }
    }

    fun pauseSpeaking() {
        if (isSpeaking && !isPaused) {
            tts?.pause()
            isPaused = true
            callbacks.values.forEach { it.onStateChanged(isSpeaking, isPaused) }
            updateNotification()
        }
    }

    fun resumeSpeaking() {
        if (isPaused) {
            tts?.resume()
            isPaused = false
            callbacks.values.forEach { it.onStateChanged(isSpeaking, isPaused) }
            updateNotification()
        } else if (currentText.isNotEmpty() && !isSpeaking) {
            speakCurrentChapter()
        }
    }

    fun togglePlayPause() {
        if (isSpeaking && !isPaused) {
            pauseSpeaking()
        } else {
            resumeSpeaking()
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        currentText = ""
        isSpeaking = false
        isPaused = false
        callbacks.values.forEach { it.onStateChanged(false, false) }
        updateNotification()
        stopForeground(true)
    }

    fun playNextChapter() {
        if (currentChapterIndex < chapters.size - 1) {
            currentChapterIndex++
            currentChapterTitle = chapters[currentChapterIndex].title
            currentText = chapters[currentChapterIndex].content
            savePreferences()
            callbacks.values.forEach { it.onChapterChanged(currentChapterIndex, currentChapterTitle) }
            speakCurrentChapter()
        } else {
            stopSpeaking()
        }
    }

    fun playPreviousChapter() {
        if (currentChapterIndex > 0) {
            currentChapterIndex--
            currentChapterTitle = chapters[currentChapterIndex].title
            currentText = chapters[currentChapterIndex].content
            savePreferences()
            callbacks.values.forEach { it.onChapterChanged(currentChapterIndex, currentChapterTitle) }
            speakCurrentChapter()
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.1f, 3.0f)
        tts?.speechRate = speechRate * playbackSpeed
        savePreferences()
    }

    fun setPitch(pitchValue: Float) {
        pitch = pitchValue.coerceIn(0.1f, 2.0f)
        tts?.pitch = pitch
        savePreferences()
    }

    fun setLanguage(lang: String) {
        language = lang
        val locale = Locale.forLanguageTag(lang)
        tts?.setLanguage(locale)
        savePreferences()
    }

    fun setVoice(voice: String?) {
        voiceName = voice
        voice?.let { vName ->
            tts?.voices?.firstOrNull { it.name == vName }?.let { v ->
                tts?.setVoice(v)
            }
        }
        savePreferences()
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 3.0f)
        tts?.speechRate = speechRate * playbackSpeed
        savePreferences()
    }

    fun getAvailableVoices(): List<TextToSpeech.Voice> {
        return tts?.voices?.toList() ?: emptyList()
    }

    fun registerCallback(callback: TtsCallback): Int {
        val id = callbackIdCounter++
        callbacks[id] = callback
        if (isInitialized) {
            callback.onInit(true)
        }
        return id
    }

    fun unregisterCallback(id: Int) {
        callbacks.remove(id)
    }

    fun isTtsInitialized(): Boolean = isInitialized
    fun isCurrentlySpeaking(): Boolean = isSpeaking
    fun isCurrentlyPaused(): Boolean = isPaused
    fun getCurrentChapterIndex(): Int = currentChapterIndex
    fun getCurrentChapterTitle(): String = currentChapterTitle
    fun getCurrentText(): String = currentText
    fun getChapters(): List<EpubChapter> = chapters
    fun getSpeechRate(): Float = speechRate
    fun getPitch(): Float = pitch
    fun getLanguage(): String = language
    fun getVoiceName(): String? = voiceName
    fun getPlaybackSpeed(): Float = playbackSpeed

    private fun updateNotification() {
        val notification = buildNotification()
        if (isSpeaking || isPaused) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            notificationManager.cancel(NOTIFICATION_ID)
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val playPauseIcon = if (isSpeaking && !isPaused) 
            android.R.drawable.ic_media_pause 
        else 
            android.R.drawable.ic_media_play
        
        val playPauseAction = if (isSpeaking && !isPaused) ACTION_PAUSE else ACTION_PLAY
        val playPauseLabel = if (isSpeaking && !isPaused) "Pause" else "Play"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPausePending = PendingIntent.getService(
            this, 1, Intent(this, TtsService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this, 2, Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextPending = PendingIntent.getService(
            this, 3, Intent(this, TtsService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevPending = PendingIntent.getService(
            this, 4, Intent(this, TtsService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val progress = if (currentText.isNotEmpty()) 50 else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentChapterTitle.ifEmpty { "TTS Player" })
            .setContentText(if (isSpeaking) "Playing..." else if (isPaused) "Paused" else "Stopped")
            .setContentIntent(pendingIntent)
            .setOngoing(isSpeaking || isPaused)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(100, progress, currentText.isNotEmpty())
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
            .addAction(playPauseIcon, playPauseLabel, playPausePending)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
            .addAction(android.R.drawable.ic_media_stop, "Stop", stopPending)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
