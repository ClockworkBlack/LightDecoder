package com.lightdecoder.imessage.direct

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.charset.Charset

/**
 * Rich link previews for links the user sends (iMessage's "LPLinkMetadata"), fetched in the main
 * process before the message goes, as an iPhone does: the page's OpenGraph title, summary, site
 * and picture (`og:`, then `twitter:` and `<title>`), the picture fetched too.
 *
 * Fetched as privately as a preview can be: http and https only (an http address is asked for
 * over https: the app sends nothing in the clear), at most [MAX_REDIRECTS] redirects that stay
 * http(s), never a host on this phone or its local network, no cookies, no referrer, a generic
 * User-Agent, [BUDGET_MS] in all; a page read only as far as [MAX_PAGE_BYTES] and only if it is
 * HTML, a picture only up to [MAX_IMAGE_BYTES] and only if it says it is one. No address is ever
 * logged. The network is passed in ([Fetcher]) so all of it is tested without one.
 *
 * Pure apart from [HttpFetcher]. Tested in LinkPreviewsTest.
 */
object LinkPreviews {

    const val MAX_REDIRECTS = 3
    const val MAX_PAGE_BYTES = 512 * 1024
    const val MAX_IMAGE_BYTES = 1024 * 1024

    /** The whole fetch (page, redirects and picture) is given this long. */
    const val BUDGET_MS = 8_000L

    /** The longest title, summary and site name kept. */
    const val MAX_TITLE_CHARS = 300
    const val MAX_SUMMARY_CHARS = 500
    const val MAX_SITE_CHARS = 100

    /** The longest address a preview is made for (or names its picture by). */
    const val MAX_URL_CHARS = 2048

    /** A User-Agent that names no app and no phone: the reduced one browsers send. */
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** One answer from the network: its status, where it redirects, its type and what was read of it ([truncated] when more was left). */
    class Response(val code: Int, val location: String?, val contentType: String?, val body: ByteArray, val truncated: Boolean = false)

    /** One request, not following redirects, reading at most [maxBytes] of the answer, given until [deadlineMs] (a clock of [Policy.now]). */
    fun interface Fetcher {
        @Throws(IOException::class)
        fun fetch(url: String, accept: String, maxBytes: Int, deadlineMs: Long): Response
    }

    /**
     * How a fetch is allowed to go: [upgradeToHttps] (on a phone: the app sends nothing in the
     * clear), [allowLocalHosts] (tests' own server only), the clock, and how a host's addresses are
     * looked up (to keep local ones out).
     */
    class Policy(
        val upgradeToHttps: Boolean = true,
        val allowLocalHosts: Boolean = false,
        val now: () -> Long = System::currentTimeMillis,
        val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    )

    /** What a page says of itself: its address (after redirects, or its own `og:url`), title, summary, site name and picture's address. */
    data class Page(val url: String, val title: String, val summary: String, val site: String, val imageUrl: String?)

    /** A preview: the [page] for the link as typed ([originalUrl]), and its picture's bytes and type if it has one. */
    class Preview(val originalUrl: String, val page: Page, val image: ByteArray?, val imageMime: String?)

    /**
     * The first web address in [text] that a preview is made for (http or https), without the
     * punctuation a sentence puts after it; null when it has none.
     */
    fun firstUrl(text: String): String? {
        val m = URL_IN_TEXT.find(text) ?: return null
        var url = m.value
        // A sentence's end, or the closing bracket of a link put in brackets.
        while (url.isNotEmpty() && url.last() in TRAILING) {
            if (url.last() == ')' && url.count { it == '(' } >= url.count { it == ')' }) break
            url = url.dropLast(1)
        }
        if (url.length > MAX_URL_CHARS) return null
        return url.takeIf { webUrl(it) != null }
    }

    /** [url] as a web address, or null when it is none (another scheme, no host, badly written). */
    fun webUrl(url: String): URI? = try {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase()
        if ((scheme == "http" || scheme == "https") && !u.host.isNullOrBlank() && u.userInfo == null) u else null
    } catch (e: URISyntaxException) {
        null
    }

    /**
     * The preview for [url]: its page fetched with [fetcher] (following redirects, see the rules
     * above) and read ([parse]), then its picture. Null when there is none to make (not a web page,
     * not HTML, nothing on it, too slow, refused): the message then goes without one.
     */
    fun fetch(url: String, fetcher: Fetcher, policy: Policy = Policy()): Preview? {
        val start = policy.now()
        val deadline = start + BUDGET_MS
        val page = fetchPage(url, fetcher, policy, deadline) ?: return null
        val image = page.imageUrl?.let { fetchImage(it, fetcher, policy, deadline) }
        return Preview(url, page, image?.first, image?.second)
    }

    private fun fetchPage(url: String, fetcher: Fetcher, policy: Policy, deadline: Long): Page? {
        val (finalUrl, response) = follow(url, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", MAX_PAGE_BYTES, fetcher, policy, deadline) ?: return null
        if (response.code !in 200..299 || !isHtml(response.contentType)) return null
        val html = String(response.body, charsetOf(response.contentType, response.body))
        return parse(html, finalUrl)
    }

    private fun fetchImage(url: String, fetcher: Fetcher, policy: Policy, deadline: Long): Pair<ByteArray, String>? {
        val (_, response) = follow(url, "image/*", MAX_IMAGE_BYTES, fetcher, policy, deadline) ?: return null
        val type = response.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        // Whole, and a picture by its own say.
        if (response.code !in 200..299 || response.truncated || response.body.isEmpty() || !type.startsWith("image/")) return null
        return response.body to type
    }

    /**
     * Fetches [url], following at most [MAX_REDIRECTS] redirects, each to an http(s) address that is
     * no local host; the address it ended at and its answer, or null when it went wrong anywhere.
     */
    private fun follow(url: String, accept: String, maxBytes: Int, fetcher: Fetcher, policy: Policy, deadline: Long): Pair<String, Response>? {
        var current = webUrl(url)?.toString() ?: return null
        for (hop in 0..MAX_REDIRECTS) {
            if (policy.now() >= deadline) return null
            val target = allowed(current, policy) ?: return null
            val response = try {
                fetcher.fetch(target, accept, maxBytes, deadline)
            } catch (e: IOException) {
                return null
            } catch (e: RuntimeException) {
                // A malformed answer, or a request the platform refused (SecurityException).
                return null
            }
            if (response.code in REDIRECTS) {
                if (hop == MAX_REDIRECTS) return null
                val next = response.location?.let { resolve(target, it) } ?: return null
                current = next
                continue
            }
            return target to response
        }
        return null
    }

    /**
     * [url] as it is fetched under [policy]: http(s) only (http asked for over https on a phone),
     * at most [MAX_URL_CHARS], and never a host on this phone or its network (by name, or by any of
     * the addresses its name stands for). Null when it may not be fetched.
     */
    internal fun allowed(url: String, policy: Policy): String? {
        if (url.length > MAX_URL_CHARS) return null
        val u = webUrl(url) ?: return null
        val http = u.scheme.equals("http", ignoreCase = true)
        val scheme = if (policy.upgradeToHttps) "https" else u.scheme.lowercase()
        // An http address's own port 80 is https' 443 once it is asked for over https.
        val port = if (http && scheme == "https" && u.port == 80) -1 else u.port
        val asked = buildString {
            append(scheme).append("://").append(u.host)
            if (port != -1) append(':').append(port)
            append(u.rawPath?.ifEmpty { "/" } ?: "/")
            u.rawQuery?.let { append('?').append(it) }
        }
        if (!policy.allowLocalHosts) {
            val host = u.host.trim('[', ']').lowercase()
            if (localName(host)) return null
            val addresses = try {
                // An address written as such is that address (no lookup); a name, the ones it stands for.
                if (IPV4_LITERAL.matches(host) || ':' in host) listOf(InetAddress.getByName(host)) else policy.resolve(IDN.toASCII(host))
            } catch (e: Exception) {
                return null
            }
            if (addresses.isEmpty() || addresses.any(::isLocal)) return null
        }
        return asked
    }

    /** Whether [host] names this phone or a local network by its name alone. */
    private fun localName(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".lan") || host.endsWith(".home.arpa") || !host.contains('.') && !host.contains(':')

    /** Whether [address] is on this phone or a local or private network (loopback, link-local, private ranges, multicast, unspecified). */
    internal fun isLocal(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isAnyLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val b = address.address
        return when (address) {
            is Inet4Address -> {
                val a0 = b[0].toInt() and 0xff
                val a1 = b[1].toInt() and 0xff
                // 0/8, carrier-grade NAT 100.64/10, 169.254/16 (above), 192.0.0/24, benchmarking 198.18/15, 240/4.
                a0 == 0 || (a0 == 100 && a1 in 64..127) || (a0 == 192 && a1 == 0 && (b[2].toInt() and 0xff) == 0) ||
                    (a0 == 198 && a1 in 18..19) || a0 >= 240
            }
            is Inet6Address -> {
                val first = b[0].toInt() and 0xff
                // Unique local fc00::/7; an IPv4 address in IPv6 dress checked as one.
                (first and 0xfe) == 0xfc || mappedV4(b)?.let(::isLocal) == true
            }
            else -> true
        }
    }

    private fun mappedV4(b: ByteArray): InetAddress? {
        if (b.size != 16) return null
        for (i in 0 until 10) if (b[i].toInt() != 0) return null
        if ((b[10].toInt() and 0xff) != 0xff || (b[11].toInt() and 0xff) != 0xff) return null
        return InetAddress.getByAddress(b.copyOfRange(12, 16))
    }

    /** [location] (a redirect's target) against the address it came from. */
    private fun resolve(base: String, location: String): String? = try {
        URI(base).resolve(URI(location.trim())).toString()
    } catch (e: URISyntaxException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun isHtml(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == "text/html" || type == "application/xhtml+xml"
    }

    /** The page's character set: its header's, else its `<meta charset>`, else UTF-8. */
    private fun charsetOf(contentType: String?, body: ByteArray): Charset {
        val named = contentType?.let { Regex("charset=\"?([A-Za-z0-9._:-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?: Regex("<meta[^>]{0,200}charset=[\"']?([A-Za-z0-9._:-]+)", RegexOption.IGNORE_CASE)
                .find(String(body, 0, minOf(body.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return try {
            named?.let { Charset.forName(it) } ?: Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    /**
     * What the HTML [html] of the page at [pageUrl] says of itself: OpenGraph first (`og:title`,
     * `og:description`, `og:site_name`, `og:image`, `og:url`), then Twitter's cards, then the plain
     * `<title>` and `description`. Null when it names neither a title nor a summary.
     */
    fun parse(html: String, pageUrl: String): Page? {
        val head = html.take(MAX_PAGE_BYTES)
        val meta = HashMap<String, String>()
        for (tag in META_TAG.findAll(head)) {
            val attrs = attributes(tag.value)
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.trim()?.lowercase() ?: continue
            val content = attrs["content"] ?: continue
            meta.putIfAbsent(key, content)
        }
        val titleTag = TITLE_TAG.find(head)?.groupValues?.get(1)
        val title = clean(meta["og:title"] ?: meta["twitter:title"] ?: titleTag ?: "", MAX_TITLE_CHARS)
        val summary = clean(meta["og:description"] ?: meta["twitter:description"] ?: meta["description"] ?: "", MAX_SUMMARY_CHARS)
        if (title.isBlank() && summary.isBlank()) return null
        val site = clean(meta["og:site_name"] ?: "", MAX_SITE_CHARS).ifBlank { hostOf(pageUrl) }
        val image = (meta["og:image:secure_url"] ?: meta["og:image"] ?: meta["og:image:url"] ?: meta["twitter:image"] ?: meta["twitter:image:src"])
            ?.let { entities(it).trim() }?.let { resolve(pageUrl, it) }?.takeIf { it.length <= MAX_URL_CHARS && webUrl(it) != null }
        val own = meta["og:url"]?.let { entities(it).trim() }?.let { resolve(pageUrl, it) }?.takeIf { it.length <= MAX_URL_CHARS && webUrl(it) != null }
        return Page(own ?: pageUrl, title, summary, site, image)
    }

    /** The site a page is on, as a card names it: its host without "www.". */
    fun hostOf(url: String): String = webUrl(url)?.host.orEmpty().removePrefix("www.")

    /** A tag's attributes, by lower-case name (the first of each). */
    private fun attributes(tag: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ATTRIBUTE.findAll(tag)) {
            val name = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
            out.putIfAbsent(name, value)
        }
        return out
    }

    /** [text] as a card shows it: entities read, then tags and control characters out, white space as single spaces, at most [max]. */
    private fun clean(text: String, max: Int): String {
        val t = entities(text).replace(TAGS, " ").filter { !it.isISOControl() || it.isWhitespace() }.replace(SPACES, " ").trim()
        if (t.length <= max) return t
        val end = if (Character.isHighSurrogate(t[max - 1])) max - 1 else max
        return t.substring(0, end).trimEnd() + "…"
    }

    /** HTML's character references read: the common names, and numbers (decimal and hex). */
    private fun entities(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[body.lowercase()]
            } ?: m.value
        }
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private val IPV4_LITERAL = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private const val TRAILING = ".,;:!?)]}'\"»”’"
    private val URL_IN_TEXT = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("<meta\\b[^>]{0,2000}>", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("<title\\b[^>]{0,200}>([^<]{0,2000})</title>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("([A-Za-z_:][-A-Za-z0-9_:.]{0,40})\\s*=\\s*(?:\"([^\"]{0,4000})\"|'([^']{0,4000})'|([^\\s\"'>]{1,4000}))")
    private val TAGS = Regex("<[^>]{0,200}>")
    private val SPACES = Regex("\\s+")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z]{2,8});")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·", "copy" to "©",
        "reg" to "®", "trade" to "™", "laquo" to "«", "raquo" to "»", "bull" to "•",
    )

    /**
     * The network as a phone has it: one [HttpURLConnection] per request, redirects not followed
     * (they are, above, by the rules), no caches, no cookies (the app sets no cookie handler), no
     * referrer, a generic User-Agent, its connect and read times what is left of the budget.
     */
    class HttpFetcher(private val now: () -> Long = System::currentTimeMillis) : Fetcher {
        override fun fetch(url: String, accept: String, maxBytes: Int, deadlineMs: Long): Response {
            val left = (deadlineMs - now()).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = left
                c.readTimeout = left
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", accept)
                c.setRequestProperty("Accept-Language", "en")
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                val type = c.contentType
                if (code in REDIRECTS || code !in 200..299) return Response(code, location, type, ByteArray(0))
                // Said to be too large: not read at all.
                if (c.contentLengthLong > maxBytes && !accept.startsWith("text/")) return Response(code, location, type, ByteArray(0), truncated = true)
                val (body, more) = (c.inputStream).use { readCapped(it, maxBytes, deadlineMs) }
                return Response(code, location, type, body, more)
            } finally {
                c.disconnect()
            }
        }

        /** At most [max] bytes of [input] (and whether more was left), stopped at [deadlineMs]. */
        private fun readCapped(input: InputStream, max: Int, deadlineMs: Long): Pair<ByteArray, Boolean> {
            val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                if (now() > deadlineMs) throw IOException("link preview: out of time")
                val n = input.read(buffer, 0, minOf(buffer.size, max + 1 - out.size()))
                if (n < 0) return out.toByteArray() to false
                out.write(buffer, 0, n)
                if (out.size() > max) return out.toByteArray().copyOf(max) to true
            }
        }
    }
}
