package com.epubreader

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class TtsSettingsActivity : AppCompatActivity() {

    private var ttsService: TtsService? = null
    private var isBound = false
    private var availableVoices: List<TextToSpeech.Voice> = emptyList()
    private var currentVoiceIndex = 0

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
            val binder = service as TtsService.LocalBinder
            ttsService = binder.getService()
            isBound = true
            loadTtsSettings()
        }
        
        override fun onServiceDisconnected(name: android.content.ComponentName) {
            isBound = false
            ttsService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_settings)
        
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "TTS Settings"
        
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        
        bindService(Intent(this, TtsService::class.java), serviceConnection, android.content.Context.BIND_AUTO_CREATE)
        
        setupSeekBars()
        setupSpinners()
    }

    private fun setupSeekBars() {
        // Speech rate seekbar (0.5x - 2.0x)
        findViewById<android.widget.SeekBar>(R.id.seek_speech_rate).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + (progress / 100f)
                findViewById<TextView>(R.id.tv_speech_rate_value).text = String.format("%.2fx", rate)
                ttsService?.setSpeechRate(rate)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Pitch seekbar (0.5 - 2.0)
        findViewById<SeekBar>(R.id.seek_pitch).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = 0.5f + (progress / 100f)
                findViewById<TextView>(R.id.tv_pitch_value).text = String.format("%.2f", pitch)
                ttsService?.setPitch(pitch)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Playback speed seekbar (0.5x - 2.0x)
        findViewById<SeekBar>(R.id.seek_playback_speed).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + (progress / 100f)
                findViewById<TextView>(R.id.tv_playback_speed_value).text = String.format("%.2fx", speed)
                ttsService?.setPlaybackSpeed(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupSpinners() {
        // Language Spinner
        val languages = arrayOf(
            "zh-CN - Chinese (Mandarin Simplified)",
            "zh-TW - Chinese (Mandarin Traditional)",
            "zh-HK - Chinese (Cantonese)",
            "en-US - English (US)",
            "en-GB - English (UK)",
            "ja-JP - Japanese",
            "ko-KR - Korean"
        )
        
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinner_language).adapter = langAdapter
        
        // Language selection
        findViewById<Spinner>(R.id.spinner_language).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val langCode = languages[position].substringBefore(" - ")
                ttsService?.setLanguage(langCode)
                loadAvailableVoices()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Voice Spinner - will be populated when TTS initializes
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("Loading voices..."))
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinner_voice).adapter = voiceAdapter
        findViewById<Spinner>(R.id.spinner_voice).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (availableVoices.isNotEmpty() && position < availableVoices.size) {
                    val voice = availableVoices[position]
                    ttsService?.setVoice(voice.name)
                    currentVoiceIndex = position
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Test button
        findViewById<android.widget.Button>(R.id.btn_test_tts).setOnClickListener {
            ttsService?.let { service ->
                val testText = when {
                    findViewById<Spinner>(R.id.spinner_language).selectedItem.toString().startsWith("zh") -> "你好，这是中文语音测试。欢迎使用EPUB阅读器。"
                    else -> "Hello, this is a TTS test. Welcome to EPUB Reader."
                }
                service.speakText(testText, 0, "Test", emptyList())
                Toast.makeText(this, "Playing test...", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Reset button
        findViewById<android.widget.Button>(R.id.btn_reset_tts).setOnClickListener {
            ttsService?.setSpeechRate(TtsService.DEFAULT_SPEECH_RATE)
            ttsService?.setPitch(TtsService.DEFAULT_PITCH)
            ttsService?.setPlaybackSpeed(TtsService.DEFAULT_PLAYBACK_SPEED)
            ttsService?.setLanguage(TtsService.DEFAULT_LANGUAGE)
            loadTtsSettings()
            Toast.makeText(this, "TTS settings reset to defaults", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTtsSettings() {
        ttsService?.let { service ->
            findViewById<SeekBar>(R.id.seek_speech_rate).progress = ((service.getSpeechRate() - 0.5f) * 100).toInt()
            findViewById<TextView>(R.id.tv_speech_rate_value).text = String.format("%.2fx", service.getSpeechRate())
            
            findViewById<SeekBar>(R.id.seek_pitch).progress = ((service.getPitch() - 0.5f) * 100).toInt()
            findViewById<TextView>(R.id.tv_pitch_value).text = String.format("%.2f", service.getPitch())
            
            findViewById<SeekBar>(R.id.seek_playback_speed).progress = ((service.getPlaybackSpeed() - 0.5f) * 100).toInt()
            findViewById<TextView>(R.id.tv_playback_speed_value).text = String.format("%.2fx", service.getPlaybackSpeed())
            
            // Set language spinner
            val currentLang = service.getLanguage()
            val languages = resources.getStringArray(R.array.tts_languages)
            val index = languages.indexOfFirst { it.startsWith(currentLang) }
            if (index >= 0) findViewById<Spinner>(R.id.spinner_language).setSelection(index)
            
            // Populate voices
            populateVoices()
        }
    }

    private fun populateVoices() {
        ttsService?.let { service ->
            availableVoices = service.getAvailableVoices()
            
            if (availableVoices.isEmpty()) {
                val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, arrayOf("No voices available"))
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                findViewById<Spinner>(R.id.spinner_voice).adapter = adapter
            } else {
                val voiceNames = availableVoices.map { voice ->
                    val locale = voice.locale
                    "${voice.name} (${locale.toLanguageTag()}) ${if (voice.quality == TextToSpeech.Voice.QUALITY_HIGH) "⭐" else if (voice.quality == TextToSpeech.Voice.QUALITY_NORMAL) "★" else ""}"
                }.toTypedArray()
                
                val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, voiceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                findViewById<Spinner>(R.id.spinner_voice).adapter = adapter
                
                // Select current voice
                val currentVoiceName = service.getVoiceName()
                if (currentVoiceName != null) {
                    val voiceIndex = availableVoices.indexOfFirst { it.name == currentVoiceName }
                    if (voiceIndex >= 0) findViewById<Spinner>(R.id.spinner_voice).setSelection(voiceIndex)
                }
            }
        }
    }

    private fun loadAvailableVoices() {
        populateVoices()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
