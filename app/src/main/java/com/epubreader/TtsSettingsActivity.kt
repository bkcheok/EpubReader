package com.epubreader

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.epubreader.databinding.ActivityTtsSettingsBinding
import java.util.Locale

class TtsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTtsSettingsBinding
    private var ttsService: TtsService? = null
    private var isBound = false
    private var availableVoices: List<TextToSpeech.Voice> = emptyList()
    private var currentVoiceIndex = 0

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as TtsService.LocalBinder
            ttsService = binder.getService()
            isBound = true
            loadTtsSettings()
        }
        
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBound = false
            ttsService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTtsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "TTS Settings"
        
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        bindTtsService()
        setupViews()
    }

    private fun setupViews() {
        // Language spinner
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
        binding.spinnerLanguage.adapter = langAdapter
        
        // Voice spinner will be populated when TTS initializes
        
        // Speech rate seekbar (0.5x - 2.0x)
        binding.seekSpeechRate.max = 150 // 0.5 to 2.0 in steps of 0.01
        binding.seekSpeechRate.progress = 100 // 1.0
        binding.seekSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + (progress / 100f)
                binding.tvSpeechRateValue.text = String.format("%.2fx", rate)
                ttsService?.setSpeechRate(rate)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Pitch seekbar (0.5 - 2.0)
        binding.seekPitch.max = 150
        binding.seekPitch.progress = 100
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = 0.5f + (progress / 100f)
                binding.tvPitchValue.text = String.format("%.2f", pitch)
                ttsService?.setPitch(pitch)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Playback speed seekbar (0.5x - 2.0x)
        binding.seekPlaybackSpeed.max = 150
        binding.seekPlaybackSpeed.progress = 100
        binding.seekPlaybackSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + (progress / 100f)
                binding.tvPlaybackSpeedValue.text = String.format("%.2fx", speed)
                ttsService?.setPlaybackSpeed(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Language selection
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val langCode = languages[position].substringBefore(" - ")
                ttsService?.setLanguage(langCode)
                loadAvailableVoices()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Voice selection
        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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
        binding.btnTestTts.setOnClickListener { testTts() }
        
        // Reset button
        binding.btnResetTts.setOnClickListener {
            ttsService?.setSpeechRate(TtsService.DEFAULT_SPEECH_RATE)
            ttsService?.setPitch(TtsService.DEFAULT_PITCH)
            ttsService?.setPlaybackSpeed(TtsService.DEFAULT_PLAYBACK_SPEED)
            ttsService?.setLanguage(TtsService.DEFAULT_LANGUAGE)
            loadCurrentSettings()
            Toast.makeText(this, "TTS settings reset to defaults", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindTtsService() {
        val intent = Intent(this, TtsService::class.java)
        bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)
    }

    private fun loadTtsSettings() {
        ttsService?.let { service ->
            binding.seekSpeechRate.progress = ((service.getSpeechRate() - 0.5f) * 100).toInt()
            binding.tvSpeechRateValue.text = String.format("%.2fx", service.getSpeechRate())
            
            binding.seekPitch.progress = ((service.getPitch() - 0.5f) * 100).toInt()
            binding.tvPitchValue.text = String.format("%.2f", service.getPitch())
            
            binding.seekPlaybackSpeed.progress = ((service.getPlaybackSpeed() - 0.5f) * 100).toInt()
            binding.tvPlaybackSpeedValue.text = String.format("%.1fx", service.getPlaybackSpeed())
            
            // Set language spinner
            val lang = service.getLanguage()
            val languages = resources.getStringArray(R.array.tts_languages)
            val index = languages.indexOfFirst { it.startsWith(lang) }
            if (index >= 0) binding.spinnerLanguage.setSelection(index)
            
            // Populate voices
            populateVoices()
        }
    }

    private var availableVoices: List<TextToSpeech.Voice> = emptyList()
    
    private fun populateVoices() {
        ttsService?.let { service ->
            availableVoices = service.getAvailableVoices()
            
            if (availableVoices.isEmpty()) {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("No voices available"))
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerVoice.adapter = adapter
            } else {
                val voiceNames = availableVoices.map { voice ->
                    val locale = voice.locale
                    "${voice.name} (${locale.toLanguageTag()}) ${if (voice.quality == TextToSpeech.Voice.QUALITY_HIGH) "⭐" else if (voice.quality == TextToSpeech.Voice.QUALITY_NORMAL) "★" else ""}"
                }.toTypedArray()
                
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerVoice.adapter = adapter
                
                // Select current voice
                val currentVoiceName = service.getVoiceName()
                if (currentVoiceName != null) {
                    val voiceIndex = availableVoices.indexOfFirst { it.name == currentVoiceName }
                    if (voiceIndex >= 0) binding.spinnerVoice.setSelection(voiceIndex)
                }
            }
        }
    }

    private fun loadAvailableVoices() {
        ttsService?.let { service ->
            availableVoices = service.getAvailableVoices()
            
            if (availableVoices.isEmpty()) {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("No voices available"))
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerVoice.adapter = adapter
            } else {
                val voiceNames = availableVoices.map { voice ->
                    val locale = voice.locale
                    "${voice.name} (${locale.toLanguageTag()}) ${if (voice.quality == TextToSpeech.Voice.QUALITY_HIGH) "⭐" else if (voice.quality == TextToSpeech.Voice.QUALITY_NORMAL) "★" else ""}"
                }.toTypedArray()
                
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerVoice.adapter = adapter
                
                // Select current voice
                val currentVoiceName = service.getVoiceName()
                if (currentVoiceName != null) {
                    val voiceIndex = availableVoices.indexOfFirst { it.name == currentVoiceName }
                    if (voiceIndex >= 0) binding.spinnerVoice.setSelection(voiceIndex)
                }
            }
        }
    }

    private fun testTts() {
        ttsService?.let { service ->
            val testText = when {
                binding.spinnerLanguage.selectedItem.toString().startsWith("zh") -> "你好，这是中文语音测试。欢迎使用EPUB阅读器。"
                else -> "Hello, this is a TTS test. Welcome to EPUB Reader."
            }
            service.speakText(testText, 0, "Test", emptyList())
            Toast.makeText(this, "Playing test...", Toast.LENGTH_SHORT).show()
        }
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
