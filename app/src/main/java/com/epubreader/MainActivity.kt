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

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

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
        setupNavigation()
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

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_library, R.id.nav_recent, R.id.nav_tts, R.id.nav_settings),
            drawerLayout
        )

        val toggle = androidx.appcompat.app.ActionBarDrawerToggle(
            this, drawerLayout, findViewById(R.id.toolbar),
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
            updateTtsService()
            addToLibrary(book)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load EPUB", e)
            Toast.makeText(this, "Failed to load EPUB: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            webProgressBar.visibility = View.GONE
        }
    }

    private fun displayChapter(index: Int) {
        currentBook?.getChapterAt(index)?.let { chapter ->
            currentChapterIndex = index
            val htmlContent = chapter.content
            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
            supportActionBar?.title = chapter.title
        }
    }

    private fun applyReadingSettings() {
        val js = """
            javascript:(function() {
                document.body.style.fontSize = '${epubSettings.fontSize}px';
                document.body.style.lineHeight = '${epubSettings.lineHeight}';
                document.body.style.margin = '${epubSettings.margin}px';
                document.body.style.fontFamily = '${epubSettings.fontFamily}';
                document.body.style.textAlign = '${epubSettings.textAlign.name.toLowerCase()}';
                document.body.className = document.body.className.replace(/theme-\\w+/g, '');
                document.body.classList.add('theme-${epubSettings.theme.name.toLowerCase()}');
            })()
        """
        webView.evaluateJavascript(js, null)
    }

    private fun updateChapterProgress() {
        // Update reading progress in database
    }

    private fun updateTtsService() {
        ttsService?.let { service ->
            currentBook?.let { book ->
                service.setChapters(listOf(book))
                service.setCurrentChapter(currentChapterIndex)
            }
        }
    }

    private fun addToLibrary(book: EpubBook) {
        // Add to Room database
    }

    private fun toggleTts() {
        ttsService?.togglePlayPause()
    }

    private fun stopTts() {
        ttsService?.stopSpeaking()
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

    override fun onSupportNavigateUp(): Boolean {
        return NavigationUI.navigateUp(navController, appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as TtsService.LocalBinder
            ttsService = binder.getService()
            isBound = true
            updateTtsService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            ttsService = null
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("EPUB Reader")
            .setMessage("Version 1.0\n\nA modern EPUB reader with TTS support for Chinese and English.")
            .setPositiveButton("OK", null)
            .show()
    }

    // JavaScript interface
    inner class WebAppInterface(private val context: Context) {
        @android.webkit.JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
