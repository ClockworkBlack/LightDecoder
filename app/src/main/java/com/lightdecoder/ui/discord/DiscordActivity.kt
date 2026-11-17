package com.lightdecoder.ui.discord

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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Features
import com.lightdecoder.databinding.ActivityDiscordBinding
import com.lightdecoder.discord.DiscordLinks
import com.lightdecoder.discord.DiscordProcess
import com.lightdecoder.discord.DiscordSession
import com.lightdecoder.discord.DiscordSessionState
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ServiceUi
import com.lightdecoder.ui.chat.applySystemBarAndImePadding

/**
 * The Discord tab: Discord's website, as Discord serves it.
 *
 * It runs in its own process with its own WebView data folder
 * ([com.lightdecoder.discord.DiscordContainer]), so its cookies and site
 * storage are apart from everything the other tabs keep, and none of the other
 * tabs' runtimes exist here. This screen must not reach for them either: no
 * ChatBackend, no archive, no link manager. It only knows the other tabs'
 * names, to draw the tab strip and hand back to the conversation list.
 *
 * What it deliberately does NOT do to the page, so it stays an ordinary web
 * session driven by the person using it (see docs/discord-web-view.md):
 *
 *  - no evaluateJavascript, no addJavascriptInterface, no injected CSS or script
 *  - no shouldInterceptRequest, so no request or response is read or rewritten
 *  - no reads of cookies, localStorage, or the session token
 *  - no user-agent override: the WebView's real user agent is sent
 *  - no automatic input: sign-in, CAPTCHA, and every message come from the person
 *
 * The only thing it reads is the page address, which WebView reports to every
 * host app, to decide whether to show the sign-in banner.
 */
class DiscordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordBinding
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
        // Outside its container the page would share the other tabs' WebView
        // data folder. Refuse before any WebView exists.
        if (!DiscordProcess.contained) {
            Toast.makeText(this, R.string.discord_no_container, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding = ActivityDiscordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        setUpServiceTabs()

        binding.signInOpenApp.setOnClickListener { openInApp() }
        binding.errorOpenApp.setOnClickListener { openInApp() }
        binding.errorRetry.setOnClickListener {
            binding.errorPanel.visibility = View.GONE
            webView?.reload()
        }

        val web = binding.discordWebView
        webView = web
        configure(web)
        val restored = savedInstanceState?.let { web.restoreState(it) }
        if (restored == null) web.loadUrl(DiscordSession.START_URL)

        // Back walks Discord's own history first; the toolbar arrow always leaves.
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

    /**
     * The same strip the conversation list shows, with Discord chosen.
     * Choosing another tab goes back to the list on that tab; this screen
     * stays out of that tab's data.
     */
    private fun setUpServiceTabs() {
        val tabs = binding.services
        for (service in Features.shownServices()) {
            tabs.addTab(tabs.newTab().setText(ServiceUi.tabLabel(service)).setTag(service), false)
        }
        tabs.addTab(tabs.newTab().setText(R.string.chat_service_discord), true)
        com.lightdecoder.ui.chat.ServiceTabStrip.attachDropDown(tabs, binding.servicesMenu)
        // Discord is the last tab: keep it in view.
        tabs.post { tabs.setScrollPosition(tabs.tabCount - 1, 0f, true) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val service = tab.tag as? ChatService ?: return
                startActivity(RelayIntents.conversations(this@DiscordActivity, service))
                finish()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView) {
        web.settings.apply {
            // Discord's web app is a JavaScript app that keeps its state in DOM storage.
            javaScriptEnabled = true
            domStorageEnabled = true

            // The page cannot reach files on the phone or open extra windows.
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true

            // userAgentString is intentionally left at the WebView default.
            // See docs/discord-web-view.md, "User agent".
        }

        // Cookies persist in this process's own WebView data folder. Third-party
        // cookies are off by default in WebView; the CAPTCHA and challenge frames
        // Discord embeds come from other origins and need them.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = pageClient
        web.webChromeClient = chromeClient
    }

    private val pageClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Embedded frames (CAPTCHA, challenge) load untouched.
            if (!request.isForMainFrame) return false
            // Discord stays on this screen.
            if (DiscordSession.isDiscordUrl(request.url.toString())) return false
            // Any other site opens in the browser; other schemes are dropped.
            DiscordLinks.openInBrowser(this@DiscordActivity, request.url)
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

        // Discord is a single-page app: most navigation never fires onPageFinished.
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

        // Lets the person attach files to a message with the system picker.
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

        // Camera and microphone are not granted here. Calls belong in the Discord app.
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    private fun render(url: String?) {
        supportActionBar?.subtitle = DiscordSession.displayAddress(url)
        binding.signInBanner.visibility =
            if (DiscordSession.classify(url) == DiscordSessionState.SIGNED_OUT) View.VISIBLE else View.GONE
    }

    private fun openInApp() {
        DiscordLinks.openInDiscordApp(this, webView?.url)
    }

    // --- menu ----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.discord_actions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_discord_open_app -> openInApp()
            R.id.action_discord_reload -> webView?.reload()
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
