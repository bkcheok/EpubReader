package com.epubreader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webProgressBar: ProgressBar

    private var ttsService: TtsService? = null
    private var isBound = false
    private var ttsCallbackId = -1
    private lateinit var ttsBottomSheet: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private var currentBook: EpubBook? = null
    private var currentChapterIndex = 0
    private var epubSettings = EpubSettings()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadEpub(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupToolbar()
        setupDrawer()
        setupWebView()
        setupTtsBottomSheet()
        setupFab()

        intent.data?.let { uri ->
            if (uri.toString().endsWith(".epub") || uri.toString().contains("epub")) {
                loadEpub(uri)
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun setupDrawer() {
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view)

        val toggle = androidx.appcompat.app.ActionBarDrawerToggle(
            this, drawerLayout, findViewById(R.id.toolbar),
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> openFilePicker()
                R.id.nav_recent -> Toast.makeText(this, "Recent books", Toast.LENGTH_SHORT).show()
                R.id.nav_tts -> openTtsSettings()
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_about -> showAboutDialog()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.web_view)
        webProgressBar = findViewById(R.id.web_progress_bar)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        settings.standardFontFamily = "sans-serif"
        settings.sansSerifFontFamily = "sans-serif"
        settings.serifFontFamily = "serif"
        settings.monospaceFontFamily = "monospace"
        settings.cursiveFontFamily = "cursive"
        settings.fantasyFontFamily = "fantasy"

        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("epub://") || url.startsWith("file://")) {
                    return false
                }
                if (url.startsWith("http")) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).also { startActivity(it) }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                webProgressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                webProgressBar.visibility = View.GONE
                applyReadingSettings()
                updateChapterProgress()
            }

            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError) {
                webProgressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                webProgressBar.progress = newProgress
            }
        }

        webView.addJavascriptInterface(WebAppInterface(this), "Android")
    }

    private fun setupTtsBottomSheet() {
        ttsBottomSheet = findViewById(R.id.tts_bottom_sheet)
        bottomSheetBehavior = BottomSheetBehavior.from(ttsBottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        findViewById<ImageButton>(R.id.btn_tts_play_pause).setOnClickListener { toggleTts() }
        findViewById<ImageButton>(R.id.btn_tts_stop).setOnClickListener { stopTts() }
        findViewById<ImageButton>(R.id.btn_tts_previous).setOnClickListener { previousChapter() }
        findViewById<ImageButton>(R.id.btn_tts_next).setOnClickListener { nextChapter() }
        findViewById<ImageButton>(R.id.btn_tts_settings).setOnClickListener { openTtsSettings() }
    }

    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.fab_tts).setOnClickListener { toggleTtsBottomSheet() }
    }

    private fun toggleTtsBottomSheet() {
        bottomSheetBehavior.state = if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            BottomSheetBehavior.STATE_HIDDEN
        } else {
            BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun openFilePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                filePickerLauncher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                filePickerLauncher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun loadEpub(uri: Uri) {
        webProgressBar.visibility = View.VISIBLE
        
        val parser = EpubParser(this)
        try {
            val book = parser.parseFromUri(uri)
            currentBook = book
            currentChapterIndex = 0
            displayChapter(0)
            supportActionBar?.title = book.title
            
            // Bind to TTS service
            if (!isBound) {
                bindService(Intent(this, TtsService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
                isBound = true
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading EPUB", e)
            Toast.makeText(this, "Error loading EPUB: ${e.message}", Toast.LENGTH_LONG).show()
            webProgressBar.visibility = View.GONE
        }
    }

    private fun displayChapter(index: Int) {
        currentBook?.let { book ->
            if (index in book.chapters.indices) {
                currentChapterIndex = index
                val chapter = book.chapters[index]
                webView.loadDataWithBaseURL("epub://", chapter.content, "text/html", "UTF-8", null)
                supportActionBar?.subtitle = chapter.title
            }
        }
    }

    private fun applyReadingSettings() {
        val settings = webView.settings
        settings.textZoom = (epubSettings.fontSize * 6.25).toInt()
        
        val js = """
            javascript:(function() {
                document.body.style.fontSize = '${epubSettings.fontSize}px';
                document.body.style.lineHeight = '${epubSettings.lineHeight}';
                document.body.style.margin = '${epubSettings.margin}px';
                document.body.style.fontFamily = '${epubSettings.fontFamily}';
                document.body.style.textAlign = '${epubSettings.textAlign.toString().toLowerCase()}';
                document.body.classList.remove('dark', 'sepia');
                when ('${epubSettings.theme}') {
                    'DARK' -> document.body.classList.add('dark')
                    'SEPIA' -> document.body.classList.add('sepia')
                }
            })()
        """
        webView.evaluateJavascript(js) { _ -> }
    }

    private fun updateChapterProgress() {
        // Update reading progress in database
    }

    private fun toggleTts() {
        ttsService?.let { service ->
            if (service.isCurrentlySpeaking() && !service.isCurrentlyPaused()) {
                service.pauseSpeaking()
                updateTtsButtons(false)
            } else {
                service.resumeSpeaking()
                updateTtsButtons(true)
            }
        }
    }

    private fun stopTts() {
        ttsService?.stopSpeaking()
        updateTtsButtons(false)
    }

    private fun previousChapter() {
        ttsService?.playPreviousChapter()
    }

    private fun nextChapter() {
        ttsService?.playNextChapter()
    }

    private fun openTtsSettings() {
        startActivity(Intent(this, TtsSettingsActivity::class.java))
    }

    private fun updateTtsButtons(isPlaying: Boolean) {
        val playPauseBtn = findViewById<ImageButton>(R.id.btn_tts_play_pause)
        playPauseBtn.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About EPUB Reader")
            .setMessage("EPUB Reader with TTS support for English and Chinese.\nVersion 1.0")
            .setPositiveButton("OK", null)
            .show()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as TtsService.LocalBinder
            ttsService = binder.getService()
            
            ttsCallbackId = ttsService?.registerCallback(object : TtsService.TtsCallback {
                override fun onInit(success: Boolean) {
                    if (success) {
                        currentBook?.let { book ->
                            ttsService?.setChapters(listOf(book))
                            ttsService?.setCurrentChapter(currentChapterIndex)
                        }
                    }
                }
                override fun onStartSpeaking(utteranceId: String) {
                    runOnUiThread { updateTtsButtons(true) }
                }
                override fun onDoneSpeaking(utteranceId: String) {
                    runOnUiThread { updateTtsButtons(false) }
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    runOnUiThread { updateTtsButtons(false) }
                }
                override fun onProgress(utteranceId: String, start: Int, end: Int, percent: Int) {}
                override fun onChapterChanged(chapterIndex: Int, chapterTitle: String) {
                    runOnUiThread { 
                        currentChapterIndex = chapterIndex
                        displayChapter(chapterIndex)
                    }
                }
                override fun onStateChanged(isSpeaking: Boolean, isPaused: Boolean) {
                    runOnUiThread { updateTtsButtons(isSpeaking && !isPaused) }
                }
            }) ?: -1
        }

        override fun onServiceDisconnected(name: ComponentName) {
            ttsService = null
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (isBound) {
            ttsService?.unregisterCallback(ttsCallbackId)
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    class WebAppInterface(private val activity: MainActivity) {
        @android.webkit.JavascriptInterface
        fun scrollToProgress(progress: Float) {
            activity.runOnUiThread {
                activity.currentBook?.let { book ->
                    val progress = book.chapters.getOrNull(activity.currentChapterIndex)
                    // Save progress
                }
            }
        }
    }
}
