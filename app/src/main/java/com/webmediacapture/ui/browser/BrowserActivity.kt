package com.webmediacapture.ui.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.webmediacapture.R
import com.webmediacapture.WebMediaCaptureApp
import com.webmediacapture.browser.BrowserWebChromeClient
import com.webmediacapture.browser.BrowserWebViewClient
import com.webmediacapture.browser.MediaProbeBridge
import com.webmediacapture.browser.PageSession
import com.webmediacapture.browser.WebViewController
import com.webmediacapture.database.DownloadEntity
import com.webmediacapture.database.DownloadState
import com.webmediacapture.database.HistoryEntity
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.MediaVariant
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.network.CookieBridge
import com.webmediacapture.ui.media.MediaLabels
import com.webmediacapture.util.AppSettings
import com.webmediacapture.util.ByteFormat
import kotlinx.coroutines.launch
import java.io.File

class BrowserActivity : AppCompatActivity() {
    private val viewModel: BrowserViewModel by viewModels()
    private val app by lazy { application as WebMediaCaptureApp }
    private val session = PageSession()
    private val cookies = CookieBridge()
    private lateinit var controller: WebViewController
    private lateinit var address: EditText
    private lateinit var homeAddress: EditText
    private lateinit var progress: ProgressBar
    private lateinit var chrome: View
    private lateinit var panelHome: View
    private lateinit var panelMedia: View
    private lateinit var panelQueue: View
    private lateinit var panelLibrary: View
    private lateinit var panelSettings: View
    private lateinit var mediaList: LinearLayout
    private lateinit var mediaEmpty: View
    private lateinit var mediaTitle: TextView
    private lateinit var queueList: LinearLayout
    private lateinit var queueEmpty: View
    private lateinit var libraryList: LinearLayout
    private lateinit var libraryEmpty: View
    private lateinit var historyList: LinearLayout
    private lateinit var historyEmpty: View
    private lateinit var mediaButton: ExtendedFloatingActionButton
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var browserUserAgent: String
    private var candidates: List<MediaCandidate> = emptyList()
    private var browseOnPage = false
    private var showingMedia = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        bindViews()
        controller = WebViewController(findViewById(R.id.webview))
        controller.configure()
        browserUserAgent = controller.userAgent
        controller.webView.addJavascriptInterface(
            MediaProbeBridge(app.requestObserver, session) { browserUserAgent },
            MEDIA_PROBE_BRIDGE,
        )
        controller.webView.webViewClient = BrowserWebViewClient(
            observer = app.requestObserver,
            cookies = cookies,
            session = session,
            userAgent = browserUserAgent,
            onPageChanged = {
                viewModel.startSession(it.id)
                address.setText(it.url)
                // #region agent log
                com.webmediacapture.util.AgentDebugLog.emit(
                    "A",
                    "BrowserActivity.kt:onPageChanged",
                    "page",
                    mapOf("url" to com.webmediacapture.util.AgentDebugLog.safeUrl(it.url), "sid" to it.id.take(8)),
                )
                // #endregion
                if (isHttpUrl(it.url)) {
                    browseOnPage = true
                    showBrowsePage()
                }
            },
            onPageFinished = { url ->
                installMediaProbe(url)
                viewModel.maybeAnalyze(session.current().id, url, cookies.contextFor(url, url, browserUserAgent))
            },
        )
        controller.webView.webChromeClient = BrowserWebChromeClient(
            onProgress = { value ->
                progress.progress = value
                progress.visibility = if (value in 1..99 && chrome.isVisible) View.VISIBLE else View.GONE
            },
            onTitle = { value ->
                title = value ?: getString(R.string.app_name)
                session.setTitle(value)
                viewModel.setPageTitle(value)
            },
            onIcon = { },
        )
        bindClicks()
        registerServiceWorkerObserver()
        observeState()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
        if (savedInstanceState != null) controller.webView.restoreState(savedInstanceState)
        showHome()
        requestNotificationPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        controller.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        controller.webView.removeJavascriptInterface(MEDIA_PROBE_BRIDGE)
        controller.destroy()
        super.onDestroy()
    }

    private fun bindViews() {
        chrome = findViewById(R.id.chrome)
        address = findViewById(R.id.address)
        homeAddress = findViewById(R.id.home_address)
        progress = findViewById(R.id.progress)
        panelHome = findViewById(R.id.panel_home)
        panelMedia = findViewById(R.id.panel_media)
        panelQueue = findViewById(R.id.panel_queue)
        panelLibrary = findViewById(R.id.panel_library)
        panelSettings = findViewById(R.id.panel_settings)
        mediaList = findViewById(R.id.media_list)
        mediaEmpty = findViewById(R.id.media_empty)
        mediaTitle = findViewById(R.id.media_title)
        queueList = findViewById(R.id.queue_list)
        queueEmpty = findViewById(R.id.queue_empty)
        libraryList = findViewById(R.id.library_list)
        libraryEmpty = findViewById(R.id.library_empty)
        historyList = findViewById(R.id.home_history_list)
        historyEmpty = findViewById(R.id.home_history_empty)
        mediaButton = findViewById(R.id.fab_media)
        bottomNav = findViewById(R.id.bottom_nav)
    }

    private fun bindClicks() {
        findViewById<View>(R.id.button_home).setOnClickListener { showHome() }
        findViewById<View>(R.id.button_back).setOnClickListener { if (!controller.goBack()) showHome() }
        findViewById<View>(R.id.button_forward).setOnClickListener { controller.goForward() }
        findViewById<View>(R.id.button_reload).setOnClickListener { controller.reload() }
        findViewById<View>(R.id.button_analyze).setOnClickListener { analyzePage() }
        findViewById<View>(R.id.button_go).setOnClickListener { go(address.text.toString()) }
        findViewById<View>(R.id.button_home_go).setOnClickListener { go(homeAddress.text.toString()) }
        address.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) go(address.text.toString())
            true
        }
        homeAddress.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) go(homeAddress.text.toString())
            true
        }
        mediaButton.setOnClickListener { showMedia() }
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browse -> {
                    if (showingMedia) showMedia() else if (browseOnPage) showBrowsePage() else showHome()
                    true
                }
                R.id.nav_queue -> {
                    showQueue(); true
                }
                R.id.nav_library -> {
                    showLibrary(); true
                }
                R.id.nav_settings -> {
                    showSettings(); true
                }
                else -> false
            }
        }
        val ytdlp = findViewById<SwitchMaterial>(R.id.settings_ytdlp)
        ytdlp.isChecked = AppSettings.autoYtDlp(this)
        ytdlp.setOnCheckedChangeListener { _, checked -> AppSettings.setAutoYtDlp(this, checked) }
        findViewById<View>(R.id.settings_clear_history).setOnClickListener {
            lifecycleScope.launch {
                app.database.history().clear()
                Snackbar.make(bottomNav, R.string.settings_cleared, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.candidates.collect {
                        candidates = it
                        mediaButton.text = getString(R.string.media_count, it.size)
                        // #region agent log
                        com.webmediacapture.util.AgentDebugLog.emit(
                            "B",
                            "BrowserActivity.kt:ui",
                            "candidates",
                            mapOf(
                                "count" to it.size,
                                "fab" to mediaButton.isVisible,
                                "mediaPanel" to panelMedia.isVisible,
                                "urls" to it.joinToString(",") { c -> com.webmediacapture.util.AgentDebugLog.safeUrl(c.mediaUrl) },
                                "durs" to it.joinToString(",") { c -> (c.durationSec ?: -1.0).toString() },
                            ),
                        )
                        // #endregion
                        if (panelMedia.isVisible) bindMedia()
                    }
                }
                launch { viewModel.downloadTasks.collect { bindQueue(it); bindLibrary(it) } }
                launch { viewModel.history.collect(::bindHistory) }
            }
        }
    }

    private fun go(raw: String, label: String = raw.trim()) {
        val target = AddressInput.destination(raw, getString(R.string.search_engine_url)) ?: return
        recordHistory(target, label)
        controller.load(target)
        showBrowsePage()
    }

    private fun analyzePage() {
        val state = session.current()
        viewModel.analyzePage(state.id, state.url, cookies.contextFor(state.url, state.url, browserUserAgent))
        showMedia()
    }

    private fun showHome() {
        showingMedia = false
        chrome.isVisible = false
        progress.isVisible = false
        mediaButton.isVisible = false
        panelHome.isVisible = true
        panelMedia.isVisible = false
        panelQueue.isVisible = false
        panelLibrary.isVisible = false
        panelSettings.isVisible = false
        bottomNav.menu.findItem(R.id.nav_browse).isChecked = true
    }

    private fun showBrowsePage() {
        showingMedia = false
        chrome.isVisible = true
        mediaButton.isVisible = true
        panelHome.isVisible = false
        panelMedia.isVisible = false
        panelQueue.isVisible = false
        panelLibrary.isVisible = false
        panelSettings.isVisible = false
        bottomNav.menu.findItem(R.id.nav_browse).isChecked = true
    }

    private fun showMedia() {
        showingMedia = true
        chrome.isVisible = browseOnPage
        mediaButton.isVisible = false
        panelHome.isVisible = false
        panelMedia.isVisible = true
        panelQueue.isVisible = false
        panelLibrary.isVisible = false
        panelSettings.isVisible = false
        bindMedia()
        bottomNav.menu.findItem(R.id.nav_browse).isChecked = true
    }

    private fun showQueue() {
        showingMedia = false
        chrome.isVisible = false
        progress.isVisible = false
        mediaButton.isVisible = false
        panelHome.isVisible = false
        panelMedia.isVisible = false
        panelQueue.isVisible = true
        panelLibrary.isVisible = false
        panelSettings.isVisible = false
    }

    private fun showLibrary() {
        showingMedia = false
        chrome.isVisible = false
        progress.isVisible = false
        mediaButton.isVisible = false
        panelHome.isVisible = false
        panelMedia.isVisible = false
        panelQueue.isVisible = false
        panelLibrary.isVisible = true
        panelSettings.isVisible = false
    }

    private fun showSettings() {
        showingMedia = false
        chrome.isVisible = false
        progress.isVisible = false
        mediaButton.isVisible = false
        panelHome.isVisible = false
        panelMedia.isVisible = false
        panelQueue.isVisible = false
        panelLibrary.isVisible = false
        panelSettings.isVisible = true
    }

    private fun handleBack() {
        when {
            panelSettings.isVisible || panelQueue.isVisible || panelLibrary.isVisible -> showHome()
            panelMedia.isVisible -> if (browseOnPage) showBrowsePage() else showHome()
            chrome.isVisible && controller.goBack() -> Unit
            chrome.isVisible -> showHome()
            else -> finish()
        }
    }

    private fun bindMedia() {
        mediaTitle.text = getString(R.string.found_media_count, candidates.size)
        mediaEmpty.isVisible = candidates.isEmpty()
        mediaList.removeAllViews()
        candidates.forEach { candidate -> mediaList.addView(mediaRow(candidate)) }
    }

    private fun mediaRow(candidate: MediaCandidate): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        column.addView(TextView(this).apply {
            text = MediaLabels.summary(candidate)
            textSize = 17f
            setTextColor(getColor(R.color.on_surface))
        })
        column.addView(TextView(this).apply {
            text = candidate.title ?: candidate.mediaUrl.substringAfterLast('/').substringBefore('?').ifBlank { getString(R.string.media_fallback_title) }
            setTextColor(getColor(R.color.on_surface_variant))
        })
        if (candidate.type == MediaType.DRM_PROTECTED) {
            column.addView(TextView(this).apply {
                text = getString(R.string.media_drm_protected)
                setPadding(0, dp(8), 0, 0)
                setTextColor(getColor(R.color.error))
            })
            return column
        }
        column.addView(MaterialButton(this).apply {
            text = getString(if (candidate.variants.isEmpty()) R.string.download else R.string.download_best_quality)
            setOnClickListener { enqueue(candidate, null) }
        })
        candidate.variants.sortedWith(compareByDescending<MediaVariant> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 }).forEach { variant ->
            column.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.variant_download, MediaLabels.variantLabel(variant, candidate.type))
                setOnClickListener { enqueue(candidate, variant) }
            })
        }
        return column
    }

    private fun enqueue(candidate: MediaCandidate, variant: MediaVariant?) {
        viewModel.download(candidate, variant)
        Snackbar.make(bottomNav, R.string.download_queued, Snackbar.LENGTH_SHORT)
            .setAction(R.string.tab_queue) { bottomNav.selectedItemId = R.id.nav_queue }
            .show()
        showQueue()
        bottomNav.selectedItemId = R.id.nav_queue
    }

    private fun bindQueue(items: List<DownloadEntity>) {
        queueEmpty.isVisible = items.isEmpty()
        queueList.removeAllViews()
        items.forEach { task -> queueList.addView(queueRow(task)) }
    }

    private fun queueRow(task: DownloadEntity): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        column.addView(TextView(this).apply {
            text = task.title ?: task.mediaUrl.substringAfterLast('/').substringBefore('?')
            textSize = 17f
            setTextColor(getColor(R.color.on_surface))
        })
        column.addView(TextView(this).apply {
            text = queueDetails(task)
            textSize = 11f
            setTextColor(getColor(R.color.on_surface_variant))
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        if (task.state in setOf(DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.MERGING, DownloadState.PAUSED)) {
            column.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 10_000
                isIndeterminate = task.state == DownloadState.PREPARING && task.progressPercent <= 0
                progress = (task.progressPercent * 100.0).toInt().coerceIn(0, 10_000)
                progressTintList = ColorStateList.valueOf(getColor(R.color.blue))
                progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.outline))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(10),
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                }
            })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (task.state in setOf(DownloadState.PENDING, DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.MERGING)) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.download_pause)
                setOnClickListener { viewModel.pauseDownload(task.id) }
            })
        }
        if (task.state in setOf(DownloadState.PAUSED, DownloadState.FAILED)) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.download_resume)
                setOnClickListener { viewModel.resumeDownload(task.id) }
            })
        }
        if (task.state !in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED)) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.download_cancel)
                setOnClickListener { viewModel.cancelDownload(task.id) }
            })
        }
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.library_delete)
            setOnClickListener { viewModel.deleteDownload(task.id) }
        })
        column.addView(actions)
        return column
    }

    private fun bindLibrary(items: List<DownloadEntity>) {
        val completed = items.filter { it.state == DownloadState.COMPLETED }
        libraryEmpty.isVisible = completed.isEmpty()
        libraryList.removeAllViews()
        completed.forEach { task -> libraryList.addView(libraryRow(task)) }
    }

    private fun libraryRow(task: DownloadEntity): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        column.addView(TextView(this).apply {
            text = task.title ?: task.mediaUrl.substringAfterLast('/').substringBefore('?')
            textSize = 17f
            setTextColor(getColor(R.color.on_surface))
        })
        column.addView(TextView(this).apply {
            text = task.outputPath ?: getString(R.string.library_missing)
            setTextColor(getColor(R.color.on_surface_variant))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this).apply {
            text = getString(R.string.library_open)
            setOnClickListener { openFile(task.outputPath) }
        })
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.library_delete)
            setOnClickListener { viewModel.deleteDownload(task.id) }
        })
        column.addView(actions)
        return column
    }

    private fun bindHistory(items: List<HistoryEntity>) {
        val unique = items.distinctBy { it.url }.take(12)
        historyEmpty.isVisible = unique.isEmpty()
        historyList.removeAllViews()
        unique.forEach { entry ->
            historyList.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = entry.title?.takeIf { it.isNotBlank() && it != getString(R.string.app_name) } ?: entry.url
                isAllCaps = false
                setOnClickListener { go(entry.url, entry.title?.takeIf { it.isNotBlank() } ?: entry.url) }
            })
        }
    }

    private fun openFile(path: String?) {
        val file = path?.let(::File)
        if (file == null || !file.exists()) {
            Snackbar.make(bottomNav, R.string.library_missing, Snackbar.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun registerServiceWorkerObserver() {
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                val state = session.current()
                app.requestObserver.observe(
                    ObservedRequest(
                        url = request.url.toString(),
                        method = request.method,
                        requestContext = cookies.contextFor(request.url.toString(), state.url, browserUserAgent, request.requestHeaders),
                        pageUrl = state.url,
                        pageSessionId = state.id,
                        title = state.title,
                    ),
                )
                return com.webmediacapture.browser.GatewayPeek.forward(
                    request, cookies, browserUserAgent, app.requestObserver, state,
                )
            }
        })
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun installMediaProbe(url: String) {
        if (!isHttpUrl(url)) return
        controller.webView.evaluateJavascript(MEDIA_PROBE_SCRIPT, null)
    }

    private fun recordHistory(url: String, label: String) {
        if (!isHttpUrl(url)) return
        app.appScope.launch {
            app.database.history().insert(HistoryEntity(url = url, title = label.ifBlank { url }))
        }
    }

    private fun queueDetails(task: DownloadEntity): String {
        val state = downloadStateLabel(task.state)
        val active = task.state in setOf(DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.MERGING)
        val downloaded = ByteFormat.format(task.bytesDownloaded)
        val total = task.totalBytes?.takeIf { it > 0 }?.let(ByteFormat::format)
        if (active) {
            val speed = ByteFormat.format(task.speedBps)
            return if (total != null) {
                getString(R.string.download_task_progress, state, downloaded, total, speed, task.progressPercent)
            } else {
                getString(R.string.download_task_progress_unknown, state, downloaded, speed, task.progressPercent)
            }
        }
        return if (total != null) {
            getString(R.string.download_task_size, state, downloaded, total)
        } else {
            getString(R.string.download_task_size_unknown, state, downloaded)
        }
    }

    private fun downloadStateLabel(state: DownloadState) = when (state) {
        DownloadState.PENDING -> getString(R.string.download_state_pending)
        DownloadState.PREPARING -> getString(R.string.download_preparing)
        DownloadState.DOWNLOADING -> getString(R.string.download_state_downloading)
        DownloadState.PAUSED -> getString(R.string.download_state_paused)
        DownloadState.MERGING -> getString(R.string.download_merging)
        DownloadState.COMPLETED -> getString(R.string.download_state_completed)
        DownloadState.FAILED -> getString(R.string.download_state_failed)
        DownloadState.CANCELLED -> getString(R.string.download_state_cancelled)
    }

    private fun isHttpUrl(value: String) = value.startsWith("https://", true) || value.startsWith("http://", true)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MEDIA_PROBE_BRIDGE = "WebMediaCaptureMediaProbe"

        private const val MEDIA_PROBE_SCRIPT = """
            (function() {
              if (window.__wmcDomProbeInstalled || !window.WebMediaCaptureMediaProbe) return;
              window.__wmcDomProbeInstalled = true;
              var seen = new Set();
              var mediaSuffix = /\.(m3u8|mpd|mp4|m4v|webm|mov|mkv|mp3|m4a|aac|ogg)(\?|$)/i;
              var adMark = /(\bads?\b|advert|sponsor|promo|preroll|midroll|postroll|ima-|vast|vpaid|google_ads|gpt-|player-ad|ad-slot|adbox|广告|推广|banner|interstitial|splash)/i;
              var pipMark = /(pip|picture-in-picture|float|floating|overlay|mini-player|小窗|悬浮|置顶|直播推荐|live-recommend|live-overlay|fancybox|lightbox|modal)/i;
              function visibleArea(el) {
                var r = el.getBoundingClientRect();
                var w = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                var h = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                return w * h;
              }
              function isAdNode(el) {
                for (var n = el; n && n !== document.documentElement; n = n.parentElement) {
                  var label = ((n.id || '') + ' ' + (n.className || '') + ' ' + (n.getAttribute('aria-label') || '')).toLowerCase();
                  if (adMark.test(label)) return true;
                }
                try {
                  if (window.frameElement) {
                    var f = window.frameElement.getBoundingClientRect();
                    if (f.width * f.height < window.innerWidth * window.innerHeight * 0.2) return true;
                  }
                } catch (ignore) { }
                return false;
              }
              function mediaParent(el) {
                if (el.tagName === 'SOURCE') return el.parentElement;
                return el;
              }
              function mainElement() {
                var nodes = document.querySelectorAll('video,audio');
                var best = null, bestArea = 0;
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  if (isAdNode(el)) continue;
                  var area = visibleArea(el);
                  if (area > bestArea) { best = el; bestArea = area; }
                }
                return best;
              }
              function isFloatingOverlay(el) {
                for (var n = el; n && n !== document.documentElement; n = n.parentElement) {
                  var label = ((n.id || '') + ' ' + (n.className || '') + ' ' + (n.getAttribute('aria-label') || '')).toLowerCase();
                  if (pipMark.test(label)) return true;
                  var st = getComputedStyle(n);
                  if (st && st.position === 'fixed') {
                    var r = n.getBoundingClientRect();
                    var small = r.width * r.height < window.innerWidth * window.innerHeight * 0.35;
                    var overlapped = r.left > 5 || r.top > 5 || r.right < window.innerWidth - 5 || r.bottom < window.innerHeight - 5;
                    if (small && overlapped) return true;
                  }
                }
                return false;
              }
              function roleOf(el) {
                var host = mediaParent(el);
                if (!host) return 'unknown';
                if (isAdNode(host)) return 'ad';
                if (isFloatingOverlay(host)) return 'overlay';
                var main = mainElement();
                if (main && host !== main) return 'overlay';
                if (main && host === main) return 'main';
                return 'unknown';
              }
              function pageTitle() {
                function text(v) { return (v || '').replace(/\s+/g, ' ').trim(); }
                var nodes = document.querySelectorAll('script[type="application/ld+json"]');
                for (var i = 0; i < nodes.length; i++) {
                  try {
                    var data = JSON.parse(nodes[i].textContent);
                    var list = Array.isArray(data) ? data : (data && data['@graph'] ? data['@graph'] : [data]);
                    for (var j = 0; j < list.length; j++) {
                      var type = String((list[j] && list[j]['@type']) || '');
                      if (/VideoObject|Movie|TVEpisode/i.test(type) && list[j].name) {
                        var n = text(list[j].name);
                        if (n.length > 1) return n;
                      }
                    }
                  } catch (ignore) { }
                }
                var og = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
                var ogt = og && text(og.getAttribute('content'));
                if (ogt && ogt.length > 1) return ogt;
                var h1 = document.querySelector('h1');
                var h1t = h1 && text(h1.textContent);
                if (h1t && h1t.length > 1 && h1t.length < 180) return h1t;
                return text(document.title);
              }
              function videoTitle(el) {
                function text(v) { return (v || '').replace(/\s+/g, ' ').trim(); }
                var host = el ? mediaParent(el) : null;
                var local = host && text(host.getAttribute('title') || host.getAttribute('aria-label') || host.getAttribute('data-title'));
                if (local && local.length > 1 && local.length < 180) return local;
                return pageTitle();
              }
              function report(raw, mime, role, el) {
                if (!raw || raw.indexOf('blob:') === 0) return;
                var target;
                try { target = new URL(raw, document.baseURI).href; } catch (ignore) { return; }
                if (!/^https?:/i.test(target)) return;
                var width = el && el.videoWidth ? String(el.videoWidth) : '';
                var height = el && el.videoHeight ? String(el.videoHeight) : '';
                var duration = '';
                if (el && isFinite(el.duration) && el.duration > 0) duration = String(el.duration);
                var marker = target + '|' + (mime || '') + '|' + (role || '') + '|' + duration;
                if (seen.has(marker)) return;
                seen.add(marker);
                window.WebMediaCaptureMediaProbe.report(target, mime || '', role || '', width, height, duration, videoTitle(el));
              }
              function inspect(element) {
                var host = mediaParent(element);
                var role = roleOf(element);
                report(host.currentSrc || host.src || element.src, element.type || '', role, host);
                var sources = host.querySelectorAll ? host.querySelectorAll('source') : [];
                for (var index = 0; index < sources.length; index++) report(sources[index].src, sources[index].type || '', role, host);
                if (host.addEventListener && !host.__wmcMeta) {
                  host.__wmcMeta = true;
                  host.addEventListener('loadedmetadata', function() { inspect(host); });
                }
              }
              function inspectTree(root) {
                if (root.matches && root.matches('video,audio,source')) inspect(root);
                var elements = root.querySelectorAll ? root.querySelectorAll('video,audio,source') : [];
                for (var index = 0; index < elements.length; index++) inspect(elements[index]);
              }
              inspectTree(document);
              new MutationObserver(function(records) {
                records.forEach(function(record) {
                  for (var index = 0; index < record.addedNodes.length; index++) inspectTree(record.addedNodes[index]);
                });
              }).observe(document.documentElement, { childList: true, subtree: true });
              function elementForUrl(url) {
                var nodes = document.querySelectorAll('video,audio,source');
                for (var i = 0; i < nodes.length; i++) {
                  var src = nodes[i].currentSrc || nodes[i].src;
                  if (src && src.split('#')[0] === url.split('#')[0]) return nodes[i];
                }
                return null;
              }
              function inspectResource(entry) {
                if (entry.initiatorType !== 'video' && entry.initiatorType !== 'audio' && !mediaSuffix.test(entry.name)) return;
                var el = elementForUrl(entry.name);
                report(entry.name, '', el ? roleOf(el) : 'unknown', el ? mediaParent(el) : null);
              }
              performance.getEntriesByType('resource').forEach(inspectResource);
              try {
                new PerformanceObserver(function(list) { list.getEntries().forEach(inspectResource); })
                  .observe({ type: 'resource', buffered: true });
              } catch (ignore) { }
            })();
        """
    }
}
