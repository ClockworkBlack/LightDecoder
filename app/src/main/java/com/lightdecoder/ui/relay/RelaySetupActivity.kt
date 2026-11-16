package com.lightdecoder.ui.relay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.databinding.ActivityRelaySetupBinding
import com.lightdecoder.databinding.DialogPasteCookiesBinding
import com.lightdecoder.relay.CookieParser
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import com.lightdecoder.ui.relay.PairingModel.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Links LightDecoder to Google Messages on the user's phone, in four steps:
 *
 *  1. What this is, honestly: it emulates Messages for web over Google's
 *     private protocol, which Google's terms do not allow; everything stays on
 *     this phone; the user confirms on their phone; how to disconnect.
 *  2. Sign in to Google in a WebView (or paste cookies), only to obtain the
 *     cookies Messages for web needs. The WebView's cookies and storage are
 *     wiped before and after, and at the next start if the process died
 *     in between ([SignInWebData]).
 *  3. Show the emoji. The user taps the same emoji in Google Messages on their
 *     phone; that confirmation is theirs alone, nothing here answers it.
 *  4. Done.
 */
class RelaySetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRelaySetupBinding
    private lateinit var manager: RelayManager
    private val model: PairingModel by viewModels()
    private val notifications = NotificationPermission(this)

    private var webView: WebView? = null
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before any WebView of this process exists: sign-in data an earlier one left behind goes.
        SignInWebData.wipeIfDirty(this)
        super.onCreate(savedInstanceState)
        binding = ActivityRelaySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = RelayManager.get(this)

        // A debug build's private storage (the archive, the session) is open to adb: say so up front.
        binding.introDebugWarning.visibility = if (isDebuggableBuild(this)) View.VISIBLE else View.GONE
        binding.introAgree.setOnCheckedChangeListener { _, checked -> binding.introContinue.isEnabled = checked }
        binding.introContinue.isEnabled = binding.introAgree.isChecked
        // Ask for notifications first: once linked, the "connected" notification
        // with its Disconnect button is how the user sees and stops the link.
        binding.introContinue.setOnClickListener { notifications.ensureThen { model.goTo(Step.SIGN_IN) } }
        binding.signInPaste.setOnClickListener { showPasteDialog() }
        binding.signInCancel.setOnClickListener { leaveSignIn() }
        binding.confirmCancel.setOnClickListener { model.cancel() }
        binding.confirmRetry.setOnClickListener { model.pair(manager, null) }
        binding.confirmSignInAgain.setOnClickListener { model.goTo(Step.SIGN_IN) }
        binding.doneOpen.setOnClickListener {
            startActivity(Intent(this, ConversationsActivity::class.java))
            finish()
        }
        binding.doneStatus.setOnClickListener {
            startActivity(Intent(this, RelayStatusActivity::class.java))
            finish()
        }
        onBackPressedDispatcher.addCallback(this) { goBack() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.ui.collect { render(it) } }
        }
        if (savedInstanceState == null) checkAlreadyPaired()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            goBack()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        // A rotation recreates the screen mid-sign-in: keep what the user typed
        // into Google so far. Leaving for good wipes it, but only if there is
        // something to wipe: wiping loads Chromium, which a user who never got
        // to the sign-in should not pay for.
        val hadWebView = webView != null
        destroyWebView()
        if (isFinishing && (hadWebView || SignInWebData.isDirty(this))) clearWebData()
        super.onDestroy()
    }

    /**
     * An existing pairing needs no setup. Rejected cookies are the exception:
     * the pairing still exists but can no longer sign in, so the user pairs
     * again with a fresh sign-in. After an app restart the link starts out
     * "disconnected", so the last state recorded in the archive counts too.
     */
    private fun checkAlreadyPaired() {
        lifecycleScope.launch {
            val (paired, lastStatus) = withContext(Dispatchers.IO) {
                manager.isPaired() to ArchiveRepository.get(this@RelaySetupActivity).health().status
            }
            val needsNewCookies = manager.state.value.phase == RelayState.Phase.COOKIES_INVALID ||
                lastStatus == COOKIES_INVALID_STATUS
            if (paired && !needsNewCookies && model.ui.value.step == Step.INTRO) model.showAlreadyPaired()
        }
    }

    private fun goBack() {
        when (model.ui.value.step) {
            Step.SIGN_IN -> {
                val web = webView
                if (web != null && web.canGoBack()) web.goBack() else leaveSignIn()
            }
            Step.CONFIRM -> model.cancel()
            Step.INTRO, Step.DONE -> finish()
        }
    }

    // --- rendering -----------------------------------------------------------

    private fun render(ui: PairingModel.Ui) {
        binding.stepIntro.visibility = if (ui.step == Step.INTRO) View.VISIBLE else View.GONE
        binding.stepSignIn.visibility = if (ui.step == Step.SIGN_IN) View.VISIBLE else View.GONE
        binding.stepConfirm.visibility = if (ui.step == Step.CONFIRM) View.VISIBLE else View.GONE
        binding.stepDone.visibility = if (ui.step == Step.DONE) View.VISIBLE else View.GONE

        if (ui.step == Step.SIGN_IN) {
            ensureWebView()
        } else if (webView != null) {
            destroyWebView()
            clearWebData()
        }
        when (ui.step) {
            Step.CONFIRM -> renderConfirm(ui)
            Step.DONE -> renderDone(ui)
            else -> Unit
        }
    }

    private fun renderConfirm(ui: PairingModel.Ui) {
        val failed = ui.errorCode.isNotEmpty()
        val showingEmoji = ui.emoji.isNotEmpty() && !failed

        binding.confirmProgress.visibility = if (ui.starting) View.VISIBLE else View.GONE
        binding.confirmEmoji.visibility = if (showingEmoji) View.VISIBLE else View.GONE
        binding.confirmEmoji.text = ui.emoji
        binding.confirmEmoji.contentDescription = getString(R.string.link_confirm_emoji_description, ui.emoji)
        binding.confirmAccount.visibility = if (ui.account.isNotEmpty() && !failed) View.VISIBLE else View.GONE
        binding.confirmAccount.text = getString(R.string.link_confirm_account, ui.account)
        binding.confirmInstructions.setText(
            when {
                failed -> R.string.link_confirm_failed
                ui.starting -> R.string.link_confirm_starting
                else -> R.string.link_confirm_instructions
            }
        )
        binding.confirmWaiting.visibility = if (showingEmoji) View.VISIBLE else View.GONE

        binding.confirmError.visibility = if (failed) View.VISIBLE else View.GONE
        if (failed) binding.confirmError.text = RelayErrors.message(this, ui.errorCode)
        binding.confirmRetry.visibility =
            if (failed && model.hasCookies && RelayErrors.canRetryPairing(ui.errorCode)) View.VISIBLE else View.GONE
        binding.confirmSignInAgain.visibility = if (failed) View.VISIBLE else View.GONE
    }

    private fun renderDone(ui: PairingModel.Ui) {
        binding.doneTitle.setText(if (ui.alreadyPaired) R.string.link_done_already_title else R.string.link_done_title)
        binding.doneBody.setText(if (ui.alreadyPaired) R.string.link_done_already_body else R.string.link_done_body)
    }

    // --- step 2: sign-in -----------------------------------------------------

    private fun leaveSignIn() {
        destroyWebView()
        clearWebData()
        model.goTo(Step.INTRO)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) return
        // Marked before the WebView exists, so no cookie can be set without the
        // marker that has the next process start wipe it if this one dies.
        SignInWebData.webViewCreating(this)
        val web = try {
            WebView(this)
        } catch (e: RuntimeException) {
            // No usable WebView (missing, disabled, or being updated): the sign-in
            // page cannot open, but "Paste cookies" still works. Nothing is logged.
            binding.signInError.setText(R.string.link_sign_in_no_webview)
            binding.signInError.visibility = View.VISIBLE
            return
        }
        SignInWebData.webViewCreated()
        binding.signInError.setText(R.string.link_sign_in_load_failed)
        web.settings.apply {
            // Google's sign-in page does not work without JavaScript. The
            // client below only ever lets Google's own sign-in hosts load,
            // and turns JavaScript off for anything else.
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = SignInClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.signInProgress.visibility = if (newProgress in 0..99) View.VISIBLE else View.INVISIBLE
            }
        }
        binding.webContainer.addView(
            web,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        webView = web
        binding.signInError.visibility = View.GONE
        if (model.signInPrepared) {
            web.loadUrl(SIGN_IN_URL)
        } else {
            // Start from nothing: no account from an earlier attempt is reused.
            model.signInPrepared = true
            clearWebData { if (webView === web) web.loadUrl(SIGN_IN_URL) }
        }
        startPolling()
    }

    /**
     * Checks once a second whether the sign-in produced the cookies Messages
     * for web needs. OSID for messages.google.com only exists once Google has
     * redirected to Messages for web, so the sign-in must be fully finished.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(POLL_MS)
                val cookies = readSignInCookies()
                if (cookies.isNotEmpty() && CookieParser.missingRequired(cookies).isEmpty()) {
                    onSignedIn(cookies)
                    break
                }
            }
        }
    }

    /** messages.google.com first, so its OSID wins over any other site's. */
    private fun readSignInCookies(): Map<String, String> {
        val manager = CookieManager.getInstance()
        val header = COOKIE_URLS.mapNotNull { manager.getCookie(it) }.joinToString("; ")
        return CookieParser.parse(header)
    }

    private fun onSignedIn(cookies: Map<String, String>) {
        destroyWebView()
        clearWebData()
        model.pair(manager, cookies)
    }

    private fun destroyWebView() {
        pollJob?.cancel()
        pollJob = null
        val web = webView ?: return
        webView = null
        web.stopLoading()
        web.clearHistory()
        web.clearFormData()
        web.clearCache(true)
        binding.webContainer.removeView(web)
        web.destroy()
        SignInWebData.webViewDestroyed()
    }

    /** Wipes every cookie and all web storage of this app's WebViews, then runs [then]. */
    private fun clearWebData(then: (() -> Unit)? = null) = SignInWebData.wipe(this, then)

    private inner class SignInClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (isAllowedSignInUrl(request.url)) return false
            if (request.isForMainFrame) {
                Toast.makeText(this@RelaySetupActivity, R.string.link_sign_in_blocked, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            // Belt and braces: script runs only on Google's sign-in pages.
            view.settings.javaScriptEnabled = url != null && isAllowedSignInUrl(Uri.parse(url))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) binding.signInError.visibility = View.VISIBLE
        }
    }

    // --- pasted cookies ------------------------------------------------------

    private fun showPasteDialog() {
        val dialogBinding = DialogPasteCookiesBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_paste_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.link_paste_use, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Cookies are as good as a password: keep them out of screenshots and the recents preview.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pasted = dialogBinding.pasteInput.text?.toString().orEmpty()
                val cookies = CookieParser.parse(pasted)
                val missing = CookieParser.missingRequired(cookies)
                when {
                    cookies.isEmpty() -> dialogBinding.pasteLayout.error = getString(R.string.link_paste_unreadable)
                    missing.isNotEmpty() ->
                        // Cookie names are not secret; their values never appear anywhere.
                        dialogBinding.pasteLayout.error = getString(R.string.link_paste_missing, missing.joinToString(", "))
                    else -> {
                        clearClipboardIfHolds(pasted, cookies)
                        dialog.dismiss()
                        destroyWebView()
                        clearWebData()
                        model.pair(manager, cookies)
                    }
                }
            }
        }
        dialog.setOnDismissListener { dialogBinding.pasteInput.text?.clear() }
        dialog.show()
    }

    /**
     * The cookies the user pasted are as good as their password: once used,
     * they come off the clipboard too, if that is still where they came from
     * (the same text, or text that reads as the same cookies). The keyboard's
     * own clipboard history is out of reach, so the user is told about it.
     * The clip is only compared, never logged or kept.
     */
    private fun clearClipboardIfHolds(pasted: String, cookies: Map<String, String>) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        val text = runCatching {
            (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(this) }
        }.getOrNull()
        if (text.isNullOrBlank()) return
        val same = text.trim() == pasted.trim() || CookieParser.parse(text) == cookies
        if (!same) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }.onSuccess {
            Toast.makeText(this, R.string.link_paste_clipboard_cleared, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** The page Messages for web signs in with (the same one the mautrix bridge uses). */
        private const val SIGN_IN_URL =
            "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config"
        private val COOKIE_URLS = listOf("https://messages.google.com", "https://google.com")
        private const val POLL_MS = 1000L

        /** The link's state name the archive's health row keeps after Google rejected the sign-in. */
        private const val COOKIES_INVALID_STATUS = "cookies_invalid"

        /**
         * Google's sign-in flow: google.com and its subdomains, the regional
         * account hosts it may hop through (accounts.google.co.uk and so on),
         * and accounts.youtube.com, which Google's sign-in visits to set its
         * cookies. Everything else is refused.
         */
        internal fun isAllowedSignInUrl(uri: Uri): Boolean {
            if (uri.scheme == "about") return uri.toString() == "about:blank"
            if (uri.scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return host == "google.com" ||
                host.endsWith(".google.com") ||
                host == "accounts.youtube.com" ||
                REGIONAL_ACCOUNTS.matches(host)
        }

        private val REGIONAL_ACCOUNTS = Regex("accounts\\.google\\.(com?\\.)?[a-z]{2,3}")

        /**
         * A debuggable build: anyone with adb can run code as the app
         * (run-as, a debugger) and so read its private files and use its keys.
         */
        internal fun isDebuggableBuild(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
