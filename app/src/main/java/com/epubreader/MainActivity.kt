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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    
    // WebView for EPUB content
    private lateinit var webView: WebView
    private lateinit var webProgressBar: ProgressBar
    
    // TTS Service
    private var ttsService: TtsService? = null
    private var isBound = false
    private var ttsCallbackId = -1
    private lateinit var ttsBottomSheet: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    
    // State
    private var currentBook: EpubBook? = null
    private var currentChapterIndex = 0
    private var epubSettings = EpubSettings()
    
    // Permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupDrawer()
        setupNavigation()
        setupWebView()
        setupTtsBottomSheet()
        setupFab()
        
        // Check if we have an EPUB URI from intent
        intent.data?.let { uri ->
            if (uri.toString().endsWith(".epub") || uri.toString().contains("epub")) {
                loadEpub(uri)
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun setupDrawer() {
        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_library, R.id.nav_recent, R.id.nav_tts, R.id.nav_settings),
            drawerLayout
        )
        
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> navController.navigate(R.id.nav_library)
                R.id.nav_recent -> navController.navigate(R.id.nav_recent)
                R.id.nav_tts -> navController.navigate(R.id.nav_tts)
                R.id.nav_settings -> navController.navigate(R.id.nav_settings)
                R.id.nav_about -> showAboutDialog()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = binding.webView
        webProgressBar = binding.webProgressBar
        
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
        
        // Enable Chinese font support
        settings.standardFontFamily = "sans-serif"
        settings.sansSerifFontFamily = "sans-serif"
        settings.serifFontFamily = "serif"
        settings.monospaceFontFamily = "monospace"
        settings.cursiveFontFamily = "cursive"
        settings.fantasyFontFamily = "fantasy"
        
        // User agent for better compatibility
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
        
        // Add JavaScript interface for TTS highlighting
        webView.addJavascriptInterface(WebAppInterface(this), "Android")
    }

    private fun setupTtsBottomSheet() {
        ttsBottomSheet = binding.ttsBottomSheet
        bottomSheetBehavior = BottomSheetBehavior.from(ttsBottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        
        binding.btnTtsPlayPause.setOnClickListener { toggleTts() }
        binding.btnTtsStop.setOnClickListener { stopTts() }
        binding.btnTtsPrevious.setOnClickListener { previousChapter() }
        binding.btnTtsNext.setOnClickListener { nextChapter() }
        binding.btnTtsSettings.setOnClickListener { openTtsSettings() }
    }

    private fun setupFab() {
        binding.fabTts.setOnClickListener { toggleTtsBottomSheet() }
    }

    private fun toggleTtsBottomSheet() {
        bottomSheetBehavior.state = if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            BottomSheetBehavior.STATE_HIDDEN
        } else {
            BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_OPEN_EPUB)
    }

    private fun loadEpub(uri: Uri) {
        webProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val parser = EpubParser(this@MainActivity)
                val book = parser.parseFromUri(uri)
                currentBook = book
                currentChapterIndex = 0
                
                runOnUiThread {
                    displayChapter(0)
                    updateNavHeader()
                    Toast.makeText(this@MainActivity, "Loaded: ${book.title}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load EPUB", e)
                runOnUiThread {
                    webProgressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Failed to load EPUB: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayChapter(index: Int) {
        currentBook?.getChapterAt(index)?.let { chapter ->
            currentChapterIndex = index
            val html = buildChapterHtml(chapter)
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "UTF-8",
                null
            )
            updateTtsInfo()
        }
    }

    private fun buildChapterHtml(chapter: EpubChapter): String {
        val settings = epubSettings
        val theme = when (settings.theme) {
            ReadingTheme.DARK -> "dark"
            ReadingTheme.SEPIA -> "sepia"
            else -> "light"
        }
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${chapter.title}</title>
                <style>
                    :root {
                        --bg-color: ${getBgColor(theme)};
                        --text-color: ${getTextColor(theme)};
                        --heading-color: ${getHeadingColor(theme)};
                        --link-color: ${getLinkColor(theme)};
                    }
                    body {
                        font-family: '${settings.fontFamily}', sans-serif;
                        font-size: ${settings.fontSize}px;
                        line-height: ${settings.lineHeight};
                        margin: ${settings.margin}px;
                        color: var(--text-color);
                        background-color: var(--bg-color);
                        text-align: ${settings.textAlign.name.toLowerCase()};
                        transition: background-color 0.3s, color 0.3s;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        color: var(--heading-color);
                        margin-top: 1.5em;
                        margin-bottom: 0.5em;
                        line-height: 1.3;
                    }
                    p { margin: 0.8em 0; }
                    img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
                    a { color: var(--link-color); text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    .chapter-title { font-size: 1.5em; font-weight: bold; margin-bottom: 1em; text-align: center; }
                    .tts-highlight { background-color: #fff3cd; border-radius: 2px; }
                    @media (prefers-color-scheme: dark) {
                        :root:not([data-theme="light"]) {
                            --bg-color: #121212;
                            --text-color: #e0e0e0;
                            --heading-color: #ffffff;
                            --link-color: #64b5f6;
                        }
                    }
                </style>
            </head>
            <body data-theme="${theme}">
                <div class="chapter-title">${chapter.title}</div>
                ${chapter.content}
            </body>
            </html>
        """.trimIndent()
    }

    private fun getBgColor(theme: String): String = when (theme) {
        "dark" -> "#121212"
        "sepia" -> "#fdf6e3"
        else -> "#ffffff"
    }
    
    private fun getTextColor(theme: String): String = when (theme) {
        "dark" -> "#e0e0e0"
        "sepia" -> "#3c2e1e"
        else -> "#1a1a1a"
    }
    
    private fun getHeadingColor(theme: String): String = when (theme) {
        "dark" -> "#ffffff"
        "sepia" -> "#1a1a1a"
        else -> "#1a1a1a"
    }
    
    private fun getLinkColor(theme: String): String = when (theme) {
        "dark" -> "#64b5f6"
        "sepia" -> "#8b4513"
        else -> "#0066cc"
    }

    private fun applyReadingSettings() {
        val settings = epubSettings
        val js = """
            javascript:(function() {
                document.body.style.fontSize = '${settings.fontSize}px';
                document.body.style.lineHeight = '${settings.lineHeight}';
                document.body.style.margin = '${settings.margin}px';
                document.body.style.fontFamily = '${settings.fontFamily}';
                document.body.style.textAlign = '${settings.textAlign.name.toLowerCase()}';
                document.body.className = '${settings.theme.name.toLowerCase()}';
            })()
        """
        webView.evaluateJavascript(js, null)
    }

    private fun updateChapterProgress() {
        currentBook?.let { book ->
            val progress = ((currentChapterIndex + 1) * 100 / book.chapterCount)
            // Update progress in UI
        }
    }

    private fun updateNavHeader() {
        currentBook?.let { book ->
            binding.navView.getHeaderView(0).findViewById<TextView>(R.id.tv_current_book).text = book.title
        }
    }

    private fun updateTtsInfo() {
        currentBook?.let { book ->
            if (currentChapterIndex < book.flattenedChapters.size) {
                val chapter = book.flattenedChapters[currentChapterIndex]
                binding.tvTtsInfo.text = "${chapter.title} (${currentChapterIndex + 1}/${book.chapterCount})"
            }
        }
    }

    // TTS Controls
    private fun startTts() {
        currentBook?.let { book ->
            val chapter = book.getChapterAt(currentChapterIndex)
            chapter?.let {
                val chapters = book.flattenedChapters.map { ParcelableEpubChapter(it) }
                val intent = Intent(this, TtsService::class.java).apply {
                    action = TtsService.ACTION_PLAY
                    putExtra(TtsService.EXTRA_TEXT, stripHtml(it.content))
                    putExtra(TtsService.EXTRA_CHAPTER_INDEX, currentChapterIndex)
                    putExtra(TtsService.EXTRA_CHAPTER_TITLE, it.title)
                    putParcelableArrayListExtra(TtsService.EXTRA_CHAPTERS, java.util.ArrayList(chapters))
                }
                startService(intent)
                bindTtsService()
            }
        }
    }

    private fun stripHtml(html: String): String {
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
    }

    private fun toggleTts() {
        ttsService?.let {
            if (it.isCurrentlySpeaking() && !it.isCurrentlyPaused()) {
                it.pauseSpeaking()
            } else {
                it.resumeSpeaking()
            }
            updateTtsButtons()
        }
    }

    private fun stopTts() {
        ttsService?.stopSpeaking()
        updateTtsButtons()
    }

    private fun nextChapter() {
        ttsService?.playNextChapter()
    }

    private fun previousChapter() {
        ttsService?.playPreviousChapter()
    }

    private fun openTtsSettings() {
        val intent = Intent(this, TtsSettingsActivity::class.java)
        startActivity(intent)
    }

    private fun updateTtsButtons() {
        ttsService?.let { service ->
            val isPlaying = service.isCurrentlySpeaking() && !service.isCurrentlyPaused()
            binding.btnTtsPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            binding.tvTtsStatus.text = if (isPlaying) "Playing" else if (service.isCurrentlyPaused()) "Paused" else "Stopped"
        }
    }

    private fun bindTtsService() {
        val intent = Intent(this, TtsService::class.java)
        bindService(intent, ttsConnection, Context.BIND_AUTO_CREATE)
    }

    private val ttsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as TtsService.LocalBinder
            ttsService = binder.getService()
            isBound = true
            
            ttsCallbackId = ttsService?.registerCallback(object : TtsService.TtsCallback {
                override fun onInit(success: Boolean) {
                    runOnUiThread { updateTtsButtons() }
                }
                override fun onStartSpeaking(utteranceId: String) {
                    runOnUiThread { updateTtsButtons() }
                }
                override fun onDoneSpeaking(utteranceId: String) {
                    runOnUiThread { 
                        updateTtsButtons()
                        // Auto-advance chapter in UI
                        if (currentBook != null && currentChapterIndex < currentBook!!.chapterCount - 1) {
                            currentChapterIndex++
                            displayChapter(currentChapterIndex)
                        }
                    }
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    runOnUiThread { updateTtsButtons() }
                }
                override fun onProgress(utteranceId: String, start: Int, end: Int, percent: Int) {
                    runOnUiThread { binding.ttsProgressBar.progress = percent }
                }
                override fun onChapterChanged(chapterIndex: Int, chapterTitle: String) {
                    runOnUiThread {
                        currentChapterIndex = chapterIndex
                        displayChapter(chapterIndex)
                    }
                }
                override fun onStateChanged(isSpeaking: Boolean, isPaused: Boolean) {
                    runOnUiThread { updateTtsButtons() }
                }
            }) ?: -1
        }
        
        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            ttsService = null
        }
    }

    override fun onStart() {
        super.onStart()
        if (ttsService != null && !isBound) {
            bindTtsService()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            ttsService?.unregisterCallback(ttsCallbackId)
            unbindService(ttsConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        if (isBound) {
            ttsService?.unregisterCallback(ttsCallbackId)
            unbindService(ttsConnection)
        }
        webView.destroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_EPUB && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                loadEpub(uri)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { loadEpub(it) }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> { openFilePicker(); true }
            R.id.action_toc -> { showTocDialog(); true }
            R.id.action_search -> { showSearchDialog(); true }
            R.id.action_tts -> { toggleTtsBottomSheet(); true }
            R.id.action_settings -> { navController.navigate(R.id.nav_settings); true }
            R.id.action_tts_settings -> { openTtsSettings(); true }
            R.id.action_about -> { showAboutDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun showTocDialog() {
        currentBook?.let { book ->
            val items = book.flattenedChapters.map { it.title }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Table of Contents")
                .setSingleChoiceItems(items, currentChapterIndex) { _, which ->
                    currentChapterIndex = which
                    displayChapter(which)
                    binding.drawerLayout.closeDrawers()
                }
                .show()
        }
    }

    private fun showSearchDialog() {
        // Implement search within book
        Toast.makeText(this, "Search coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("EPUB Reader")
            .setMessage("Version 1.0\n\nEPUB 2/3 Reader with TTS support for Chinese and English")
            .setPositiveButton("OK", null)
            .show()
    }

    private class WebAppInterface(private val activity: MainActivity) {
        @android.webkit.JavascriptInterface
        fun speakText(text: String) {
            activity.runOnUiThread {
                activity.startTtsFromText(text)
            }
        }
    }

    private fun startTtsFromText(text: String) {
        currentBook?.let { book ->
            val chapters = book.flattenedChapters.map { ParcelableEpubChapter(it) }
            val intent = Intent(this, TtsService::class.java).apply {
                action = TtsService.ACTION_PLAY
                putExtra(TtsService.EXTRA_TEXT, text)
                putExtra(TtsService.EXTRA_CHAPTER_INDEX, currentChapterIndex)
                putExtra(TtsService.EXTRA_CHAPTER_TITLE, book.flattenedChapters[currentChapterIndex].title)
                putParcelableArrayListExtra(TtsService.EXTRA_CHAPTERS, java.util.ArrayList(chapters))
            }
            startService(intent)
            bindTtsService()
        }
    }

    companion object {
        const val REQUEST_OPEN_EPUB = 1001
        const val PERMISSION_REQUEST_STORAGE = 1002
    }
}
