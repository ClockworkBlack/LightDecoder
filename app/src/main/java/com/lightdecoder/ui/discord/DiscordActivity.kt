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
        // No back arrow: the switcher and the system's Back are the ways out.
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        // The tab strip is the bar's content: no title or address line takes the page's room.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        setUpServiceTabs()
        com.lightdecoder.discord.DiscordWarmup.markUsed(this)

        binding.signInOpenApp.setOnClickListener { openInApp() }
        binding.signInQr.setOnClickListener { startQr() }
        binding.qrClose.setOnClickListener { stopQr() }
        binding.qrApprove.setOnClickListener {
            val link = qrLink ?: return@setOnClickListener
            val shown = getString(
                if (com.lightdecoder.discord.DiscordQr.approveInDiscordApp(this, link)) R.string.discord_qr_after_app else R.string.discord_qr_no_app
            )
            Toast.makeText(this, shown, Toast.LENGTH_LONG).show()
        }
        binding.pageFrame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) binding.pageFrame.post { applyPageWidth() }
        }
        binding.errorOpenApp.setOnClickListener { openInApp() }
        binding.errorRetry.setOnClickListener {
            binding.errorPanel.visibility = View.GONE
            webView?.reload()
        }

        // The page view is kept between visits (DiscordPage): the same page, where it was left.
        val (web, fresh) = DiscordPage.obtain(this)
        webView = web
        web.scaleX = 1f
        web.scaleY = 1f
        binding.pageFrame.addView(
            web, 0,
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        configure(web)
        binding.pageFrame.post { applyPageWidth() }
        if (fresh) {
            val restored = savedInstanceState?.let { web.restoreState(it) }
            if (restored == null) web.loadUrl(DiscordSession.START_URL)
        } else {
            render(web.url)
        }

        // Back walks Discord's own history first; the toolbar arrow always leaves.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = webView
                    if (current != null && current.canGoBack()) {
                        current.goBack()
                    } else {
                        finish()
                        com.lightdecoder.ui.chat.ServiceTabStrip.fadeOnClose(this@DiscordActivity)
                    }
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
        // The same order as the conversation list: Messages, Discord, then the other services.
        Features.shownServices().forEachIndexed { index, service ->
            tabs.addTab(tabs.newTab().setText(ServiceUi.tabLabel(service)).setTag(service), false)
            if (index == 0) tabs.addTab(tabs.newTab().setText(R.string.chat_service_discord), true)
        }
        if (tabs.tabCount == 0) tabs.addTab(tabs.newTab().setText(R.string.chat_service_discord), true)
        val discord = getString(R.string.chat_service_discord)
        com.lightdecoder.ui.chat.ServiceTabStrip.attachSwitcher(tabs, binding.root.findViewById(R.id.servicesMenu)) { discord }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val service = tab.tag as? ChatService ?: return
                // Back to the conversation list underneath (single top: it switches to the app
                // asked for in place, instead of being made again), with a quick fade.
                com.lightdecoder.ui.chat.ServiceTabStrip.open(
                    this@DiscordActivity,
                    RelayIntents.conversations(this@DiscordActivity, service).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
                com.lightdecoder.ui.chat.ServiceTabStrip.fadeOnClose(this@DiscordActivity)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView) {
        // The app's own dark or light surface behind the page: no white flash while it loads.
        web.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(web, com.google.android.material.R.attr.colorSurface))
        web.settings.apply {
            // Discord's web app is a JavaScript app that keeps its state in DOM storage.
            javaScriptEnabled = true
            domStorageEnabled = true

            // The page cannot reach files on the phone or open extra windows.
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            // Draws the parts of the page just off screen ahead of time: lists scroll and
            // channels appear without blank patches (costs some memory, which the Fold has).
            offscreenPreRaster = true
            // Keep the cache Discord's files are served from (the default, said for clarity).
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

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

        // The page's renderer gets the phone's attention while the tab is on screen.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
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

        // The page's renderer died (Android took its memory back, or it crashed). Left
        // unhandled that ends the whole Discord process; instead the page is made anew.
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                webView = null
                DiscordPage.discard(view)
                recreate()
            }
            return true
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
        val state = DiscordSession.classify(url)
        signedOut = state == DiscordSessionState.SIGNED_OUT
        binding.signInBanner.visibility = if (signedOut && !qrMode) View.VISIBLE else View.GONE
        // Signed in with the code: back to the phone-size page.
        if (qrMode && state == DiscordSessionState.SIGNED_IN) {
            stopQr()
            Toast.makeText(this, R.string.discord_qr_signed_in, Toast.LENGTH_SHORT).show()
        }
        invalidateOptionsMenu()
    }

    // --- sign in with a QR code ----------------------------------------------------

    private var signedOut = false
    private var qrMode = false
    private var qrLink: String? = null
    private var qrBusy = false
    private val qrHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val qrTick = object : Runnable {
        override fun run() {
            if (!qrMode) return
            lookForQr()
            qrHandler.postDelayed(this, QR_LOOK_MS)
        }
    }

    /**
     * The page gets a computer-size window (scaled down to fit), where
     * Discord's sign-in page shows its QR code, and the code is looked for.
     */
    private fun startQr() {
        qrMode = true
        qrLink = null
        binding.signInBanner.visibility = View.GONE
        binding.qrPanel.visibility = View.VISIBLE
        binding.qrStatus.setText(R.string.discord_qr_looking)
        binding.qrImage.visibility = View.GONE
        binding.qrApprove.isEnabled = false
        applyPageWidth()
        qrHandler.removeCallbacks(qrTick)
        qrHandler.postDelayed(qrTick, QR_LOOK_MS)
    }

    private fun stopQr() {
        qrMode = false
        qrLink = null
        qrHandler.removeCallbacks(qrTick)
        binding.qrPanel.visibility = View.GONE
        binding.signInBanner.visibility = if (signedOut) View.VISIBLE else View.GONE
        applyPageWidth()
    }

    /** The page view at the phone's width, or (for the QR code) a computer's, scaled to fit. */
    private fun applyPageWidth() {
        val web = webView ?: return
        val frame = binding.pageFrame
        if (frame.width == 0 || frame.height == 0) return
        val params = web.layoutParams
        val density = resources.displayMetrics.density
        val widthDp = (frame.width / density).toInt()
        // The size chosen for this kind of screen (folded or unfolded); a computer's for the QR code.
        val fit = com.lightdecoder.discord.DiscordFit.fit(
            widthDp, com.lightdecoder.discord.DiscordFit.pageSize(this, com.lightdecoder.discord.DiscordFit.isWide(widthDp)),
        )
        val cssWidth = if (qrMode) com.lightdecoder.discord.DiscordQr.DESKTOP_WIDTH_CSS else fit.cssWidth
        val textZoom = if (qrMode) 100 else fit.textZoom
        if (web.settings.textZoom != textZoom) web.settings.textZoom = textZoom
        if (cssWidth > widthDp) {
            val wide = com.lightdecoder.discord.DiscordQr.wide(frame.width, frame.height, density, cssWidth)
            params.width = wide.width
            params.height = wide.height
            web.pivotX = 0f
            web.pivotY = 0f
            web.scaleX = wide.scale
            web.scaleY = wide.scale
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            web.scaleX = 1f
            web.scaleY = 1f
        }
        web.layoutParams = params
    }

    /** The sizes to choose from for the screen as it is now (folded or unfolded), the current one marked. */
    private fun choosePageSize() {
        val widthDp = (binding.pageFrame.width / resources.displayMetrics.density).toInt()
        val wide = com.lightdecoder.discord.DiscordFit.isWide(widthDp)
        val sizes = com.lightdecoder.discord.DiscordFit.PageSize.entries
        val labels = sizes.map { size ->
            when {
                size == com.lightdecoder.discord.DiscordFit.PageSize.AUTO ->
                    getString(if (wide) R.string.discord_size_auto_wide else R.string.discord_size_auto_narrow)
                size.percent > 100 -> getString(R.string.discord_size_text, size.percent)
                size.percent == 100 -> getString(R.string.discord_size_normal)
                else -> getString(R.string.discord_size_smaller, size.percent)
            }
        }.toTypedArray()
        val current = sizes.indexOf(com.lightdecoder.discord.DiscordFit.pageSize(this, wide))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (wide) R.string.discord_page_size_wide else R.string.discord_page_size_narrow)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                com.lightdecoder.discord.DiscordFit.setPageSize(this, wide, sizes[which])
                applyPageWidth()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Folded or unfolded, turned, or split with another app: the page is fitted again, in place. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.pageFrame.post { applyPageWidth() }
    }

    /** Copies what the page view shows on screen and looks for Discord's QR code in it. */
    private fun lookForQr() {
        val web = webView ?: return
        if (qrBusy || web.width == 0 || !web.isShown) return
        val at = IntArray(2)
        web.getLocationInWindow(at)
        val shownWidth = (web.width * web.scaleX).toInt().coerceAtMost(binding.pageFrame.width)
        val shownHeight = (web.height * web.scaleY).toInt().coerceAtMost(binding.pageFrame.height)
        if (shownWidth <= 0 || shownHeight <= 0) return
        val area = android.graphics.Rect(at[0], at[1], at[0] + shownWidth, at[1] + shownHeight)
        val copy = Bitmap.createBitmap(shownWidth, shownHeight, Bitmap.Config.ARGB_8888)
        qrBusy = true
        try {
            android.view.PixelCopy.request(window, area, copy, { result ->
                if (result != android.view.PixelCopy.SUCCESS) {
                    qrBusy = false
                    return@request
                }
                Thread {
                    val link = runCatching { com.lightdecoder.discord.DiscordQr.find(copy) }.getOrNull()
                    qrHandler.post {
                        qrBusy = false
                        if (qrMode && link != null && link != qrLink) showQr(link)
                    }
                }.start()
            }, qrHandler)
        } catch (e: IllegalArgumentException) {
            qrBusy = false
        }
    }

    private fun showQr(link: String) {
        qrLink = link
        binding.qrStatus.setText(R.string.discord_qr_found)
        val side = (200 * resources.displayMetrics.density).toInt()
        binding.qrImage.setImageBitmap(com.lightdecoder.discord.DiscordQr.draw(link, side))
        binding.qrImage.visibility = View.VISIBLE
        binding.qrApprove.isEnabled = true
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
            R.id.action_discord_qr -> if (qrMode) stopQr() else startQr()
            R.id.action_discord_page_size -> choosePageSize()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // --- lifecycle: keep the sign-in on disk ----------------------------------

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_discord_qr)?.isVisible = signedOut || qrMode
        return super.onPrepareOptionsMenu(menu)
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

    private companion object {
        /** How often the screen is looked at for the QR code (it changes every two minutes). */
        const val QR_LOOK_MS = 1_500L
    }

    override fun onDestroy() {
        qrHandler.removeCallbacksAndMessages(null)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView?.let { web ->
            // The page stays loaded for the next visit; this screen's hooks into it are taken off.
            if (web.parent === binding.pageFrame) {
                web.webViewClient = WebViewClient()
                web.webChromeClient = null
                DiscordPage.release(web, binding.pageFrame, applicationContext)
            }
        }
        webView = null
        super.onDestroy()
    }
}
