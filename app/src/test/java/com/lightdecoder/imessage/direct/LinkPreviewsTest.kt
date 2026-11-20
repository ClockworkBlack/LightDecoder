package com.lightdecoder.imessage.direct

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link previews of what the user sends ([LinkPreviews]): the first link in a message, the page's
 * OpenGraph (malformed and huge pages too), redirects and their limits, what is never fetched,
 * and the picture. No real network: an injected [LinkPreviews.Fetcher], and one test server of
 * the test's own on this machine for the real fetcher.
 */
class LinkPreviewsTest {

    private val html = "text/html; charset=utf-8"
    private val public = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

    /** A fetch policy for [fetcher]'s made-up hosts: all of them public addresses. */
    private val policy = LinkPreviews.Policy(resolve = { listOf(public) })

    /** Answers by address from [pages], recording every request. */
    private class Fake(val pages: Map<String, LinkPreviews.Response>) : LinkPreviews.Fetcher {
        val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun fetch(url: String, accept: String, maxBytes: Int, deadlineMs: Long): LinkPreviews.Response {
            asked += url
            val r = pages[url] ?: throw IOException("no such page")
            return if (r.body.size > maxBytes) LinkPreviews.Response(r.code, r.location, r.contentType, r.body.copyOf(maxBytes), truncated = true) else r
        }
    }

    private fun page(body: String, type: String? = html) = LinkPreviews.Response(200, null, type, body.toByteArray())
    private fun redirect(to: String, code: Int = 301) = LinkPreviews.Response(code, to, null, ByteArray(0))

    private val og = """<!doctype html><html><head>
        <meta property="og:title" content="A &amp; B &#8212; &quot;the&quot; story">
        <meta property='og:description' content='All about it'>
        <meta property="og:site_name" content="Example News">
        <meta property="og:image" content="/img/cover.jpg">
        <meta property="og:url" content="https://www.example.com/story">
        <title>Ignored title</title></head><body>hi</body></html>"""

    @Test
    fun theFirstWebAddressInAMessageIsTheOneWithoutASentencesEnd() {
        assertEquals("https://example.com/a", LinkPreviews.firstUrl("see https://example.com/a. And http://other.org"))
        assertEquals("https://example.com/p", LinkPreviews.firstUrl("(https://example.com/p)"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", LinkPreviews.firstUrl("read https://en.wikipedia.org/wiki/Foo_(bar)!"))
        assertEquals("http://example.com/x?y=1&z=2", LinkPreviews.firstUrl("HTTP: http://example.com/x?y=1&z=2"))
        assertNull(LinkPreviews.firstUrl("ftp://example.com/file and javascript:alert(1)"))
        assertNull(LinkPreviews.firstUrl("no link here, just example.com"))
        assertNull(LinkPreviews.firstUrl("https://" + "a".repeat(LinkPreviews.MAX_URL_CHARS) + ".com"))
        assertNull(LinkPreviews.firstUrl("https://user:pass@example.com/"))
    }

    @Test
    fun aPagesOpenGraphIsItsPreview() {
        val p = LinkPreviews.parse(og, "https://example.com/s?id=1")!!
        assertEquals("A & B — \"the\" story", p.title)
        assertEquals("All about it", p.summary)
        assertEquals("Example News", p.site)
        assertEquals("https://example.com/img/cover.jpg", p.imageUrl)
        assertEquals("https://www.example.com/story", p.url)
    }

    @Test
    fun withoutOpenGraphTwitterThenTheTitleAndDescriptionDo() {
        val twitter = """<meta name="twitter:title" content="Tw"><meta name="twitter:description" content="Desc"><meta name="twitter:image" content="https://cdn.example.com/t.png">"""
        val t = LinkPreviews.parse(twitter, "https://example.com/")!!
        assertEquals("Tw", t.title)
        assertEquals("Desc", t.summary)
        assertEquals("https://cdn.example.com/t.png", t.imageUrl)
        assertEquals("example.com", t.site)
        val plain = LinkPreviews.parse("<html><head><title>  Just\n a   title </title><meta name=description content=Plain></head></html>", "https://www.example.org/a")!!
        assertEquals("Just a title", plain.title)
        assertEquals("Plain", plain.summary)
        assertEquals("example.org", plain.site)
        assertNull(plain.imageUrl)
        // Nothing to show: no preview.
        assertNull(LinkPreviews.parse("<html><body>nothing</body></html>", "https://example.com/"))
    }

    @Test
    fun aMalformedOrHugePageGivesWhatCanBeReadAndNoMore() {
        // Unclosed tags, attributes in any order and case, a picture by another scheme.
        val messy = """<HEAD><META CONTENT="Messy" PROPERTY="OG:TITLE"<meta property="og:image" content="javascript:alert(1)"><title>x"""
        val m = LinkPreviews.parse(messy, "https://example.com/")!!
        assertEquals("Messy", m.title)
        assertNull("only a web picture", m.imageUrl)
        // A long title is cut, with no tags or control characters in it.
        val long = LinkPreviews.parse("<meta property=\"og:title\" content=\"&lt;b&gt;${"word ".repeat(300)}\u0007\">", "https://example.com/")!!
        assertTrue(long.title.length <= LinkPreviews.MAX_TITLE_CHARS + 1)
        assertFalse(long.title.contains('<'))
        assertFalse(long.title.contains('\u0007'))
        assertTrue(long.title.startsWith("word word"))
        // One longer than a tag is read up to: no title at all, rather than all of it.
        assertNull(LinkPreviews.parse("<meta property=\"og:title\" content=\"${"x".repeat(5000)}\">", "https://example.com/"))
        // Entities that are no characters stay as they are.
        assertEquals("&#0; &bogus; &#xD800;", LinkPreviews.parse("<title>&#0; &bogus; &#xD800;</title>", "https://example.com/")!!.title)
        // A page of half a megabyte of nothing reads quickly, and finds nothing.
        val start = System.nanoTime()
        assertNull(LinkPreviews.parse("<div ".repeat(100_000), "https://example.com/"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    @Test
    fun aPreviewIsTheFetchedPageAndItsPicture() {
        val picture = byteArrayOf(1, 2, 3, 4)
        val fake = Fake(
            mapOf(
                "https://example.com/s" to page(og),
                "https://example.com/img/cover.jpg" to LinkPreviews.Response(200, null, "image/jpeg", picture),
            ),
        )
        val preview = LinkPreviews.fetch("https://example.com/s", fake, policy)!!
        assertEquals("https://example.com/s", preview.originalUrl)
        assertEquals("A & B — \"the\" story", preview.page.title)
        assertArrayEquals(picture, preview.image)
        assertEquals("image/jpeg", preview.imageMime)
        assertEquals(listOf("https://example.com/s", "https://example.com/img/cover.jpg"), fake.asked)
    }

    @Test
    fun aPictureThatIsNoPictureOrTooLargeIsLeftOut() {
        val notImage = Fake(mapOf("https://example.com/s" to page(og), "https://example.com/img/cover.jpg" to page("<html>", "text/html")))
        val a = LinkPreviews.fetch("https://example.com/s", notImage, policy)!!
        assertNull(a.image)
        assertEquals("All about it", a.page.summary)
        val huge = Fake(
            mapOf(
                "https://example.com/s" to page(og),
                "https://example.com/img/cover.jpg" to LinkPreviews.Response(200, null, "image/png", ByteArray(LinkPreviews.MAX_IMAGE_BYTES + 10)),
            ),
        )
        assertNull(LinkPreviews.fetch("https://example.com/s", huge, policy)!!.image)
    }

    @Test
    fun redirectsAreFollowedThreeTimesAtMostAndOnlyToWebAddresses() {
        val three = Fake(
            mapOf(
                "https://a.example.com/" to redirect("https://b.example.com/x"),
                "https://b.example.com/x" to redirect("/y", 302),
                "https://b.example.com/y" to redirect("https://c.example.com/z", 307),
                "https://c.example.com/z" to page("<title>Arrived</title>"),
            ),
        )
        val p = LinkPreviews.fetch("https://a.example.com/", three, policy)!!
        assertEquals("Arrived", p.page.title)
        assertEquals("https://c.example.com/z", p.page.url)
        val four = Fake(
            mapOf(
                "https://a.example.com/" to redirect("https://b.example.com/"),
                "https://b.example.com/" to redirect("https://c.example.com/"),
                "https://c.example.com/" to redirect("https://d.example.com/"),
                "https://d.example.com/" to redirect("https://e.example.com/"),
                "https://e.example.com/" to page("<title>Too far</title>"),
            ),
        )
        assertNull(LinkPreviews.fetch("https://a.example.com/", four, policy))
        assertEquals(4, four.asked.size)
        for (to in listOf("file:///data/data/x", "javascript:alert(1)", "intent://x#Intent;end", "ftp://example.com/")) {
            val odd = Fake(mapOf("https://a.example.com/" to redirect(to), to to page("<title>no</title>")))
            assertNull(to, LinkPreviews.fetch("https://a.example.com/", odd, policy))
            assertEquals(to, listOf("https://a.example.com/"), odd.asked)
        }
        // A redirect with nowhere to go.
        assertNull(LinkPreviews.fetch("https://a.example.com/", Fake(mapOf("https://a.example.com/" to LinkPreviews.Response(302, null, null, ByteArray(0)))), policy))
    }

    @Test
    fun anHttpAddressIsAskedForOverHttps() {
        val fake = Fake(mapOf("https://example.com/plain" to page("<title>Secure</title>")))
        assertEquals("Secure", LinkPreviews.fetch("http://example.com/plain", fake, policy)!!.page.title)
        assertEquals(listOf("https://example.com/plain"), fake.asked)
        // Its own port 80 is https' own.
        assertEquals("https://example.com/", LinkPreviews.allowed("http://example.com:80", policy))
        assertEquals("https://example.com:8080/a?b=c", LinkPreviews.allowed("http://example.com:8080/a?b=c#frag", policy))
    }

    @Test
    fun onlyAnHtmlPageIsRead() {
        val fake = Fake(mapOf("https://example.com/f" to page("<title>x</title>", "application/json"), "https://example.com/g" to page("<title>x</title>", null)))
        assertNull(LinkPreviews.fetch("https://example.com/f", fake, policy))
        assertNull(LinkPreviews.fetch("https://example.com/g", fake, policy))
        val error = Fake(mapOf("https://example.com/h" to LinkPreviews.Response(404, null, html, "<title>Not found</title>".toByteArray())))
        assertNull(LinkPreviews.fetch("https://example.com/h", error, policy))
        // A page larger than allowed is read only as far as the limit: what is in it before still counts.
        val big = "<title>Big</title>" + " ".repeat(LinkPreviews.MAX_PAGE_BYTES * 2) + "<meta property=\"og:title\" content=\"Too late\">"
        assertEquals("Big", LinkPreviews.fetch("https://example.com/big", Fake(mapOf("https://example.com/big" to page(big))), policy)!!.page.title)
    }

    @Test
    fun noHostOnThisPhoneOrItsNetworkIsEverFetched() {
        val strict = LinkPreviews.Policy()
        for (url in listOf(
            "https://localhost/", "https://127.0.0.1/", "https://10.0.0.1/", "https://192.168.1.1/", "https://172.16.5.4/",
            "https://169.254.169.254/latest/meta-data", "https://[::1]/", "https://[fd00::1]/", "https://router/", "https://printer.local/",
            "https://0.0.0.0/", "https://100.64.0.1/",
        )) {
            assertNull(url, LinkPreviews.allowed(url, strict))
        }
        // A public name that stands for a private address: refused too.
        val rebound = LinkPreviews.Policy(resolve = { listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 0, 1))) })
        assertNull(LinkPreviews.allowed("https://evil.example.com/", rebound))
        val fake = Fake(mapOf("https://evil.example.com/" to page("<title>x</title>")))
        assertNull(LinkPreviews.fetch("https://evil.example.com/", fake, rebound))
        assertTrue("nothing asked", fake.asked.isEmpty())
        // A public page that redirects into the network: stopped there.
        val inward = Fake(mapOf("https://example.com/" to redirect("https://192.168.0.1/admin")))
        assertNull(LinkPreviews.fetch("https://example.com/", inward, policy))
        assertEquals(listOf("https://example.com/"), inward.asked)
        // A name that cannot be looked up is no preview.
        assertNull(LinkPreviews.allowed("https://example.com/", LinkPreviews.Policy(resolve = { throw java.net.UnknownHostException("x") })))
        assertNotNull(LinkPreviews.allowed("https://example.com/", policy))
    }

    @Test
    fun theWholeFetchStopsWhenItsTimeIsUp() {
        var now = 0L
        val slow = object : LinkPreviews.Fetcher {
            override fun fetch(url: String, accept: String, maxBytes: Int, deadlineMs: Long): LinkPreviews.Response {
                now += 3_000
                return redirect("https://example.com/next${now}")
            }
        }
        val timed = LinkPreviews.Policy(resolve = { listOf(public) }, now = { now })
        assertNull(LinkPreviews.fetch("https://example.com/", slow, timed))
        assertTrue(now <= LinkPreviews.BUDGET_MS + 3_000)
    }

    // --- the real fetcher, against a server of this test's own -----------------------------------

    private var server: ServerSocket? = null
    private val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    @After
    fun stop() {
        server?.close()
    }

    /** A tiny HTTP server on this machine answering [answer] for each request's path (its request kept in [requests]). */
    private fun serve(answer: (String) -> ByteArray): Int {
        val socket = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        server = socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client: Socket = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) {
                    client.use { c ->
                        val head = StringBuilder()
                        val input = c.getInputStream()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) break
                            head.append(b.toChar())
                        }
                        requests += head.toString()
                        val path = head.lineSequence().first().split(' ').getOrElse(1) { "/" }
                        try {
                            c.getOutputStream().apply {
                                write(answer(path))
                                flush()
                            }
                        } catch (e: IOException) {
                            // The fetcher stopped reading (the page was large enough).
                        }
                    }
                }
            }
        }
        return socket.localPort
    }

    private fun http(status: String, headers: String, body: ByteArray = ByteArray(0)): ByteArray =
        ("HTTP/1.1 $status\r\n$headers" + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body

    @Test
    fun theRealFetcherFollowsARedirectReadsThePageAndSendsNoCookieOrReferrer() {
        val port = serve { path ->
            when (path) {
                "/start" -> http("301 Moved Permanently", "Location: /page\r\n")
                "/page" -> http("200 OK", "Content-Type: text/html; charset=utf-8\r\nSet-Cookie: id=1\r\n", og.replace("/img/cover.jpg", "http://127.0.0.1:${server!!.localPort}/pic").toByteArray())
                "/pic" -> http("200 OK", "Content-Type: image/png\r\n", byteArrayOf(9, 8, 7))
                else -> http("404 Not Found", "")
            }
        }
        val local = LinkPreviews.Policy(upgradeToHttps = false, allowLocalHosts = true)
        val preview = LinkPreviews.fetch("http://127.0.0.1:$port/start", LinkPreviews.HttpFetcher(), local)!!
        assertEquals("A & B — \"the\" story", preview.page.title)
        assertArrayEquals(byteArrayOf(9, 8, 7), preview.image)
        assertEquals("image/png", preview.imageMime)
        val asked = requests.toList()
        assertEquals(3, asked.size)
        for (r in asked) {
            assertFalse(r, r.contains("Cookie:", ignoreCase = true))
            assertFalse(r, r.contains("Referer:", ignoreCase = true))
            assertTrue(r, r.contains("User-Agent: ${LinkPreviews.USER_AGENT}"))
        }
    }

    @Test
    fun theRealFetcherReadsAHugePageOnlyAsFarAsTheLimit() {
        val body = ("<title>Huge</title>" + "x".repeat(LinkPreviews.MAX_PAGE_BYTES * 3)).toByteArray()
        val port = serve { http("200 OK", "Content-Type: text/html\r\n", body) }
        val fetched = LinkPreviews.HttpFetcher().fetch("http://127.0.0.1:$port/", "text/html", LinkPreviews.MAX_PAGE_BYTES, System.currentTimeMillis() + 5_000)
        assertEquals(LinkPreviews.MAX_PAGE_BYTES, fetched.body.size)
        assertTrue(fetched.truncated)
        val local = LinkPreviews.Policy(upgradeToHttps = false, allowLocalHosts = true)
        assertEquals("Huge", LinkPreviews.fetch("http://127.0.0.1:$port/", LinkPreviews.HttpFetcher(), local)!!.page.title)
    }

    // --- what a page names as its own address, and addresses in another's dress ----------------------

    @Test
    fun aPagesOwnAddressIsTheCardsOnlyOnItsOwnSiteOverHttps() {
        fun own(ogUrl: String, pageUrl: String) =
            LinkPreviews.parse("<meta property=\"og:title\" content=\"T\"><meta property=\"og:url\" content=\"$ogUrl\">", pageUrl)!!.url
        // Its own site (another name on it too): the card opens it.
        assertEquals("https://www.example.com/story", own("https://www.example.com/story", "https://example.com/s"))
        assertEquals("https://m.example.com/a", own("https://m.example.com/a", "https://news.example.com/x"))
        assertEquals("https://news.bbc.co.uk/1", own("https://news.bbc.co.uk/1", "https://www.bbc.co.uk/x"))
        assertEquals("https://example.com/rel", own("/rel", "https://example.com/s"))
        // Another site, one only like it, or not over https: the card opens the page the link is at.
        assertEquals("https://example.com/s", own("https://evil.example.net/phish", "https://example.com/s"))
        assertEquals("https://example.com/s", own("https://example.com.evil.net/", "https://example.com/s"))
        assertEquals("https://www.bbc.co.uk/x", own("https://evil.co.uk/", "https://www.bbc.co.uk/x"))
        assertEquals("https://example.com/s", own("http://example.com/plain", "https://example.com/s"))
        assertEquals("https://example.com/s", own("javascript:alert(1)", "https://example.com/s"))
        // After redirects, the page's own: where the link really went.
        val moved = Fake(
            mapOf(
                "https://short.example/x" to redirect("https://target.example.org/page"),
                "https://target.example.org/page" to page("<meta property=\"og:title\" content=\"T\"><meta property=\"og:url\" content=\"https://bank.example.com/\">"),
            ),
        )
        assertEquals("https://target.example.org/page", LinkPreviews.fetch("https://short.example/x", moved, policy)!!.page.url)
    }

    @Test
    fun aSitesRegistrableDomainIsNearEnoughItsLastTwoOrThreeNames() {
        assertEquals("example.com", LinkPreviews.siteOf("a.b.example.com"))
        assertEquals("example.com", LinkPreviews.siteOf("EXAMPLE.com."))
        assertEquals("bbc.co.uk", LinkPreviews.siteOf("news.bbc.co.uk"))
        assertEquals("example.com.au", LinkPreviews.siteOf("www.example.com.au"))
        assertEquals("co.uk", LinkPreviews.siteOf("co.uk"))
        assertEquals("93.184.216.34", LinkPreviews.siteOf("93.184.216.34"))
        assertTrue(LinkPreviews.sameSite("https://a.example.com/", "https://b.example.com/x"))
        assertFalse(LinkPreviews.sameSite("https://example.com/", "https://example.org/"))
        assertFalse(LinkPreviews.sameSite("https://93.184.216.34/", "https://93.184.216.35/"))
    }

    @Test
    fun anIpv4AddressInIpv6DressIsCheckedAsThatAddress() {
        fun local(literal: String) = LinkPreviews.isLocal(InetAddress.getByName(literal))
        // NAT64's well-known prefix, IPv4-compatible, 6to4, mapped: the IPv4 address in them counts.
        for (inside in listOf(
            "64:ff9b::7f00:1", "64:ff9b::a00:1", "64:ff9b::c0a8:101", "64:ff9b::a9fe:a9fe",
            "::7f00:1", "::c0a8:1", "::a00:5",
            "2002:7f00:1::1", "2002:c0a8:101::", "2002:a00:1:1::1", "2002:a9fe:a9fe::1",
            // The local-use NAT64 prefix, wherever its translator puts the IPv4 address.
            "64:ff9b:1::c0a8:101", "64:ff9b:1:c0a8:1:100::", "64:ff9b:1:7f:0:1::",
        )) {
            assertTrue(inside, local(inside))
        }
        // A public IPv4 address in them, or a public IPv6 address: not local.
        for (outside in listOf("64:ff9b::5db8:d822", "::5db8:d822", "2002:5db8:d822::1", "2606:2800:220:1:248:1893:25c8:1946")) {
            assertFalse(outside, local(outside))
        }
        // Refused as a link's host, written so.
        val strict = LinkPreviews.Policy()
        for (url in listOf("https://[64:ff9b::7f00:1]/", "https://[::c0a8:1]/", "https://[2002:c0a8:101::1]/", "https://[64:ff9b:1::a00:1]/")) {
            assertNull(url, LinkPreviews.allowed(url, strict))
        }
        // A public name that stands for one: refused too.
        val dressed = LinkPreviews.Policy(resolve = { listOf(InetAddress.getByName("64:ff9b::a9fe:a9fe")) })
        assertNull(LinkPreviews.allowed("https://metadata.example.com/", dressed))
    }

    // --- the connection itself: made only where it was checked, and in time ------------------------------

    /** A server of this test's own whose connections [talk] answers as they come; what each sent is kept in [received]. */
    private val received: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())

    private fun serveRaw(talk: (java.io.OutputStream) -> Unit): Int {
        val socket = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        server = socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client: Socket = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) {
                    client.use { c ->
                        thread(isDaemon = true) {
                            try {
                                received += c.getInputStream().readBytes()
                            } catch (e: IOException) {
                                // Closed under it.
                            }
                        }
                        try {
                            talk(c.getOutputStream())
                        } catch (e: IOException) {
                            // The fetcher went.
                        } catch (e: InterruptedException) {
                            // Ended.
                        }
                    }
                }
            }
        }
        return socket.localPort
    }

    @Test
    fun anHttpsConnectionIsMadeOnlyToAnAddressCheckedAsItIsMade() {
        val default = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val port = serveRaw { Thread.sleep(2_000) }
        val guarded = LinkPreviews.GuardedSockets(default, LinkPreviews::isLocal)
        // The connection made to this machine (as a name that changed its answer would make it): closed,
        // nothing sent over it.
        val raw = Socket(InetAddress.getLoopbackAddress(), port)
        assertTrue(runCatching { guarded.createSocket(raw, "example.com", 443, true) }.exceptionOrNull() is IOException)
        assertTrue(raw.isClosed)
        assertTrue(runCatching { guarded.createSocket("127.0.0.1", port) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { guarded.createSocket(InetAddress.getLoopbackAddress(), port) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { guarded.createSocket(InetAddress.getLoopbackAddress(), port, null, 0) }.exceptionOrNull() is IOException)
        // No socket to connect later, unchecked: the connection makes its own, then has it checked.
        val unconnected = runCatching { guarded.createSocket() }.exceptionOrNull()
        assertTrue(unconnected is java.net.SocketException && unconnected.cause is UnsupportedOperationException)
        // An address let through: TLS over the connection as it was made.
        val allowed = Socket(InetAddress.getLoopbackAddress(), port)
        val tls = LinkPreviews.GuardedSockets(default) { false }.createSocket(allowed, "example.com", 443, true)
        assertTrue(tls is javax.net.ssl.SSLSocket)
        tls.close()
    }

    @Test
    fun theRealFetcherSendsNothingOverAConnectionToAnAddressNotAllowed() {
        val port = serveRaw { Thread.sleep(2_000) }
        // Over https to this machine (as a public name that changed its answer would lead it): refused
        // before anything goes over the connection, not even TLS's first words.
        val failed = runCatching { LinkPreviews.HttpFetcher().fetch("https://127.0.0.1:$port/", "text/html", 1_000, System.currentTimeMillis() + 5_000) }
        assertTrue(failed.exceptionOrNull().toString(), failed.exceptionOrNull() is IOException)
        val deadline = System.currentTimeMillis() + 3_000
        while (received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("a connection was made", received.isNotEmpty())
        assertTrue("nothing sent", received.all { it.isEmpty() })
    }

    @Test
    fun aSiteThatSendsItsHeadersSlowlyIsCutOffWhenTheTimeIsUp() {
        // A byte now and then: each read is in time, the answer never ends.
        val port = serveRaw { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n".toByteArray())
            repeat(2_000) {
                out.write("X-Slow: y\r\n".toByteArray())
                out.flush()
                Thread.sleep(50)
            }
        }
        val start = System.currentTimeMillis()
        val result = runCatching { LinkPreviews.HttpFetcher().fetch("http://127.0.0.1:$port/", "text/html", 1_000, start + 800) }
        val took = System.currentTimeMillis() - start
        assertTrue(result.toString(), result.isFailure)
        assertTrue("stopped at its time, took $took ms", took < 4_000)
    }
}
