package com.epubreader

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class TtsSettingsActivity : AppCompatActivity() {

    private var ttsService: TtsService? = null
    private var isBound = false
    private val ttsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsService.LocalBinder
            ttsService = binder.getService()
            isBound = true
            loadCurrentSettings()
            loadAvailableVoices()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
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
        
        bindTtsService()
        setupViews()
    }

    private fun setupViews() {
        // Language spinner
        val languages = arrayOf(
            "zh-CN - Chinese (Mandarin Simplified)",
            "zh-TW - Chinese (Mandarin Traditional)",
            "zh-HK - Chinese (Cantonese Hong Kong)",
            "en-US - English (US)",
            "en-GB - English (UK)",
            "ja-JP - Japanese",
            "ko-KR - Korean",
            "fr-FR - French",
            "de-DE - German",
            "es-ES - Spanish",
            "it-IT - Italian",
            "pt-BR - Portuguese (Brazil)",
            "ru-RU - Russian"
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
                val voiceName = if (position == 0) null else availableVoices[position - 1].name
                ttsService?.setVoice(voiceName)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Test button
        binding.btnTestTts.setOnClickListener {
            ttsService?.speakText(
                "Hello world. 你好世界。这是语音合成测试。", 
                0, 
                "Test",
                emptyList()
            )
        }
        
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
        bindService(intent, ttsConnection, Context.BIND_AUTO_CREATE)
    }

    private fun loadCurrentSettings() {
        ttsService?.let { service ->
            binding.seekSpeechRate.progress = ((service.getSpeechRate() - 0.5f) * 100).toInt()
            binding.seekPitch.progress = ((service.getPitch() - 0.5f) * 100).toInt()
            binding.seekPlaybackSpeed.progress = ((service.getPlaybackSpeed() - 0.5f) * 100).toInt()
            
            // Set language spinner
            val lang = service.getLanguage()
            val languages = resources.getStringArray(R.array.tts_languages)
            val index = languages.indexOfFirst { it.startsWith(lang) }
            if (index >= 0) binding.spinnerLanguage.setSelection(index)
            
            updateRateDisplay()
            updatePitchDisplay()
            updateSpeedDisplay()
        }
    }

    private var availableVoices: List<TextToSpeech.Voice> = emptyList()
    
    private fun loadAvailableVoices() {
        ttsService?.let { service ->
            availableVoices = service.getAvailableVoices()
            val voiceNames = mutableListOf("Default (System)")
            availableVoices.forEach { voiceNames.add("${voice.name} (${voice.locale.toLanguageTag()})") }
            
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerVoice.adapter = adapter
            
            // Select current voice
            val currentVoice = service.getVoiceName()
            if (currentVoice != null) {
                val voiceIndex = availableVoices.indexOfFirst { it.name == currentVoice }
                if (voiceIndex >= 0) binding.spinnerVoice.setSelection(voiceIndex + 1)
            }
        }
    }

    private fun updateRateDisplay() {
        val rate = 0.5f + (binding.seekSpeechRate.progress / 100f)
        binding.tvSpeechRateValue.text = String.format("%.2fx", rate)
    }
    
    private fun updatePitchDisplay() {
        val pitch = 0.5f + (binding.seekPitch.progress / 100f)
        binding.tvPitchValue.text = String.format("%.2f", pitch)
    }
    
    private fun updateSpeedDisplay() {
        val speed = 0.5f + (binding.seekPlaybackSpeed.progress / 100f)
        binding.tvPlaybackSpeedValue.text = String.format("%.2fx", speed)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(ttsConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
