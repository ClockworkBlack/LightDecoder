package com.lightdecoder.messenger

import java.net.URI

/** What the web screen is showing, judged from the page address alone. */
enum class MessengerWebState {
    /** Nothing has loaded yet. */
    LOADING,

    /** Facebook is showing its own sign-in, recovery or security-check page. */
    SIGNED_OUT,

    /** Facebook's messaging page: the conversation list or one conversation. */
    MESSAGES,

    /** Any other page of Facebook's. */
    OTHER,
}

/**
 * Address-only helpers for the web screen, and the one thing it asks of the
 * page: the computer layout.
 *
 * Where the page is. messenger.com stopped being a place to message in April
 * 2026; Meta sends it to facebook.com/messages, which is where this starts.
 * That page needs a Facebook account: people who use Messenger without one
 * have only the Messenger app.
 *
 * Nothing here touches page content, cookies, storage or request headers. The
 * only input is the address the WebView already reports to its host app.
 */
object MessengerWeb {

    /** Facebook's web messaging. messenger.com redirects here since April 2026. */
    const val START_URL = "https://www.facebook.com/messages/"

    /** Meta's own sites that the screen stays on: sign-in and messaging move between them. */
    private val ROOT_HOSTS = listOf("facebook.com", "messenger.com")

    /** True for https pages of facebook.com, messenger.com and their subdomains, and nothing else. */
    fun isMetaUrl(url: String?): Boolean {
        val uri = parse(url) ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return ROOT_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun classify(url: String?): MessengerWebState {
        if (url.isNullOrBlank() || url == "about:blank") return MessengerWebState.LOADING
        if (!isMetaUrl(url)) return MessengerWebState.OTHER
        val uri = parse(url) ?: return MessengerWebState.OTHER
        val path = uri.path.orEmpty().trimEnd('/')
        val onMessenger = uri.host.orEmpty().lowercase().let { it == "messenger.com" || it.endsWith(".messenger.com") }
        return when {
            SIGN_IN_PATHS.any { path == it || path.startsWith("$it/") } -> MessengerWebState.SIGNED_OUT
            path == "/messages" || path.startsWith("/messages/") -> MessengerWebState.MESSAGES
            // What is left of messenger.com before it redirects.
            onMessenger && (path.startsWith("/t/") || path.startsWith("/e2ee/")) -> MessengerWebState.MESSAGES
            else -> MessengerWebState.OTHER
        }
    }

    /** Host and path only, for showing under the title. Never includes the query string. */
    fun displayAddress(url: String?): String {
        val uri = parse(url) ?: return ""
        val host = uri.host ?: return ""
        return host + uri.path.orEmpty().trimEnd('/')
    }

    /**
     * The user agent that asks for the computer layout, made from the
     * WebView's own ([defaultUserAgent]) the way a browser's "Desktop site"
     * switch does: the Android device in the brackets becomes a Linux
     * computer and the "Mobile" word goes. Facebook sends phone browsers that
     * open its messages to the Play Store to install Messenger; this is what
     * a person gets round that with in any phone browser.
     *
     * What it does not do: pretend not to be a web view. The "wv" mark and
     * the real version numbers stay, so the page is told the truth about
     * what is showing it. Returns [defaultUserAgent] unchanged if it has no
     * Android device in brackets to replace.
     */
    fun desktopUserAgent(defaultUserAgent: String): String {
        val open = defaultUserAgent.indexOf('(')
        val close = defaultUserAgent.indexOf(')', open + 1)
        if (open < 0 || close < 0) return defaultUserAgent
        val device = defaultUserAgent.substring(open + 1, close)
        if (!device.contains("Android", ignoreCase = true)) return defaultUserAgent
        val webView = device.split(';').any { it.trim() == "wv" }
        val computer = if (webView) "$DESKTOP_PLATFORM; wv" else DESKTOP_PLATFORM
        val rest = defaultUserAgent.substring(close + 1).replace(MOBILE_WORD, "")
        return defaultUserAgent.substring(0, open + 1) + computer + ")" + rest
    }

    /** What a Linux computer's browser puts in the brackets. */
    const val DESKTOP_PLATFORM = "X11; Linux x86_64"

    /** The platform name that goes with [DESKTOP_PLATFORM] in the browser's client hints. */
    const val DESKTOP_HINT_PLATFORM = "Linux"

    private val MOBILE_WORD = Regex(" Mobile(?= |$)")

    private val SIGN_IN_PATHS = listOf(
        "/login", "/login.php", "/recover", "/checkpoint", "/two_step_verification", "/two_factor",
    )

    private fun parse(url: String?): URI? =
        try {
            if (url.isNullOrBlank()) null else URI(url)
        } catch (e: Exception) {
            null
        }
}
