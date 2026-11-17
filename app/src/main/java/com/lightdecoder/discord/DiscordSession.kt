package com.lightdecoder.discord

import java.net.URI

/** What the web view is showing, judged from the page address alone. */
enum class DiscordSessionState {
    /** Nothing has loaded yet. */
    LOADING,

    /** Discord is showing its own sign-in or sign-up page. */
    SIGNED_OUT,

    /** Discord is showing a DM, group DM, or server channel. */
    SIGNED_IN,

    /** Any other page (landing page, challenge page, a non-Discord site). */
    OTHER,
}

/**
 * Address-only helpers. Nothing here touches page content, cookies, storage,
 * or request headers: the only input is the URL the WebView already reports
 * to its host app.
 */
object DiscordSession {

    const val START_URL = "https://discord.com/app"
    const val HOME_LINK = "https://discord.com/channels/@me"

    private const val ROOT_HOST = "discord.com"

    /** True for https://discord.com and its subdomains, and nothing else. */
    fun isDiscordUrl(url: String?): Boolean {
        val uri = parse(url) ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == ROOT_HOST || host.endsWith(".$ROOT_HOST")
    }

    fun classify(url: String?): DiscordSessionState {
        if (url.isNullOrBlank() || url == "about:blank") return DiscordSessionState.LOADING
        if (!isDiscordUrl(url)) return DiscordSessionState.OTHER
        val path = parse(url)?.path.orEmpty().trimEnd('/')
        return when {
            path == "/login" || path.startsWith("/login/") -> DiscordSessionState.SIGNED_OUT
            path == "/register" -> DiscordSessionState.SIGNED_OUT
            path.startsWith("/channels/") -> DiscordSessionState.SIGNED_IN
            else -> DiscordSessionState.OTHER
        }
    }

    /**
     * The https://discord.com/channels/... link for the conversation on screen,
     * or null when the page is not a conversation. Query and fragment are dropped.
     */
    fun channelLinkOrNull(url: String?): String? {
        if (classify(url) != DiscordSessionState.SIGNED_IN) return null
        val path = parse(url)?.path?.trimEnd('/') ?: return null
        if (!CHANNEL_PATH.matches(path)) return null
        return "https://$ROOT_HOST$path"
    }

    /** Host and path only, for showing in the header. Never includes the query string. */
    fun displayAddress(url: String?): String {
        val uri = parse(url) ?: return ""
        val host = uri.host ?: return ""
        return host + uri.path.orEmpty().trimEnd('/')
    }

    private val CHANNEL_PATH = Regex("^/channels/(@me|\\d+)(/\\d+){0,2}$")

    private fun parse(url: String?): URI? =
        try {
            if (url.isNullOrBlank()) null else URI(url)
        } catch (e: Exception) {
            null
        }
}
