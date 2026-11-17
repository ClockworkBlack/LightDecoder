package com.lightdecoder.ui.messenger

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityMessengerWebBinding
import com.lightdecoder.messenger.MessengerLinks
import com.lightdecoder.messenger.MessengerProcess
import com.lightdecoder.messenger.MessengerWeb
import com.lightdecoder.messenger.MessengerWebState
import com.lightdecoder.ui.chat.applySystemBarAndImePadding

/**
 * "Full history / New chat": Facebook's own web messaging page, as Facebook
 * serves it, for everything the inbox built from notifications cannot do:
 * older messages, pictures and files, starting a conversation.
 *
 * It runs in its own process with its own WebView data folder
 * ([com.lightdecoder.messenger.MessengerContainer]), so Facebook's cookies
 * and site storage are apart from everything the other tabs keep, and none
 * of the app's other code runs here: no link, no archive, and not the
 * Messenger inbox either. Nothing the page shows is copied into the app.
 *
 * What it deliberately does NOT do to the page, so that it stays an ordinary
 * web session driven by the person using it (see docs/messenger-hybrid.md):
 *
 *  - no evaluateJavascript, no addJavascriptInterface, no injected CSS or script
 *  - no shouldInterceptRequest, so no request or response is read or rewritten
 *  - no reads of cookies, localStorage, or the session
 *  - no automatic input: sign-in, security checks and every message come from the person
 *  - nothing runs while the screen is closed: no background page, no polling
 *
 * The one thing it asks of the page is the computer layout, the way a
 * browser's "Desktop site" switch does ([MessengerWeb.desktopUserAgent]),
 * because Facebook sends phone browsers that open its messages to the Play
 * Store. It still says it is a web view.
 *
 * The only thing it reads is the page address, which WebView reports to
 * every host app, to decide whether to show the sign-in note.
 */
class MessengerWebActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessengerWebBinding
    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Outside its container the page would share another tab's WebView
        // data folder. Refuse before any WebView exists.
        if (!MessengerProcess.contained) {
            Toast.makeText(this, R.string.messenger_web_no_container, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding = ActivityMessengerWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.errorOpenApp.setOnClickListener { openApp() }
        binding.errorRetry.setOnClickListener {
            binding.errorPanel.visibility = View.GONE
            webView?.reload()
        }

        val web = binding.messengerWebView
        webView = web
        configure(web)
        val restored = savedInstanceState?.let { web.restoreState(it) }
        if (restored == null) web.loadUrl(MessengerWeb.START_URL)

        // Back walks the page's own history first; the toolbar arrow always leaves.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = webView
                    if (current != null && current.canGoBack()) current.goBack() else finish()
                }
            },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView) {
        web.settings.apply {
            // Facebook's web app is a JavaScript app that keeps its state in DOM storage.
            javaScriptEnabled = true
            domStorageEnabled = true

            // The page cannot reach files or other apps' content on the phone, or open extra windows.
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true

            // The computer layout, zoomed out to fit, with pinch to zoom in.
            userAgentString = MessengerWeb.desktopUserAgent(WebSettings.getDefaultUserAgent(this@MessengerWebActivity))
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        desktopClientHints(web.settings)

        // Cookies persist in this process's own WebView data folder. Third-party
        // cookies are off by default in WebView; Facebook's sign-in moves between
        // its own sites and embeds security-check frames that need them.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = pageClient
        web.webChromeClient = chromeClient
        // A file the page offers is fetched by the browser, not saved by this app.
        web.setDownloadListener { url, _, _, _, _ ->
            runCatching { MessengerLinks.openInBrowser(this, Uri.parse(url)) }
        }
    }

    /**
     * The other half of asking for the computer layout. Besides the user
     * agent, a browser tells sites "phone or not" and its platform in
     * separate headers (client hints); left alone they would still say
     * "phone, Android" next to a user agent that says "computer". They are
     * set to what a browser's "Desktop site" switch sends. The brand list is
     * not touched: it keeps saying "Android WebView".
     */
    private fun desktopClientHints(settings: WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val current = WebSettingsCompat.getUserAgentMetadata(settings)
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder(current)
                    .setMobile(false)
                    .setPlatform(MessengerWeb.DESKTOP_HINT_PLATFORM)
                    .setPlatformVersion("")
                    .setModel("")
                    .build(),
            )
        }
    }

    private val pageClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Embedded frames (security checks) load untouched.
            if (!request.isForMainFrame) return false
            // Facebook's own sites stay on this screen.
            if (MessengerWeb.isMetaUrl(request.url.toString())) return false
            // Any other site opens in the browser; other schemes are dropped.
            MessengerLinks.openInBrowser(this@MessengerWebActivity, request.url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.progress.visibility = View.VISIBLE
            binding.errorPanel.visibility = View.GONE
            render(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progress.visibility = View.GONE
            render(url)
        }

        // A single-page app: most navigation never fires onPageFinished.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            render(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                binding.progress.visibility = View.GONE
                binding.errorPanel.visibility = View.VISIBLE
            }
        }
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.setProgressCompat(newProgress, true)
        }

        // Lets the person attach pictures and files to a message with the system picker.
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        // Camera and microphone are not granted here. Calls belong in the Messenger app.
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    private fun render(url: String?) {
        supportActionBar?.subtitle = MessengerWeb.displayAddress(url)
        val signedOut = MessengerWeb.classify(url) == MessengerWebState.SIGNED_OUT
        binding.signInBanner.visibility = if (signedOut) View.VISIBLE else View.GONE
    }

    private fun openApp() {
        if (!MessengerLinks.openMessengerApp(this)) {
            Toast.makeText(this, R.string.messenger_no_app, Toast.LENGTH_LONG).show()
        }
    }

    // --- menu ----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.messenger_web_actions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_messenger_web_reload -> webView?.reload()
            R.id.action_messenger_web_open_app -> openApp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // --- lifecycle: keep the sign-in on disk ----------------------------------

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.let {
            it.onPause()
            // Writes cookies to disk now instead of whenever WebView gets round to it.
            CookieManager.getInstance().flush()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
