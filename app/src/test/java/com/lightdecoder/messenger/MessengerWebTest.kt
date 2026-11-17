package com.lightdecoder.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web screen's address rules and its "computer layout" request. Plain JVM: no Android needed. */
class MessengerWebTest {

    @Test
    fun itStartsOnFacebooksMessagesNotOnTheRetiredMessengerSite() {
        assertTrue(MessengerWeb.START_URL.startsWith("https://www.facebook.com/messages"))
        assertEquals(MessengerWebState.MESSAGES, MessengerWeb.classify(MessengerWeb.START_URL))
    }

    @Test
    fun onlyMetasOwnHttpsSitesStayOnTheScreen() {
        assertTrue(MessengerWeb.isMetaUrl("https://www.facebook.com/messages/t/123"))
        assertTrue(MessengerWeb.isMetaUrl("https://facebook.com/login.php"))
        assertTrue(MessengerWeb.isMetaUrl("https://www.messenger.com/"))
        assertTrue(MessengerWeb.isMetaUrl("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com"))
        assertFalse("not https", MessengerWeb.isMetaUrl("http://www.facebook.com/messages"))
        assertFalse("a look-alike host", MessengerWeb.isMetaUrl("https://facebook.com.example.org/messages"))
        assertFalse("a look-alike host", MessengerWeb.isMetaUrl("https://notfacebook.com/messages"))
        assertFalse("another site", MessengerWeb.isMetaUrl("https://example.com/?next=https://www.facebook.com/"))
        assertFalse("another scheme", MessengerWeb.isMetaUrl("intent://www.facebook.com/#Intent;end"))
        assertFalse(MessengerWeb.isMetaUrl("fb-messenger://threads"))
        assertFalse(MessengerWeb.isMetaUrl(null))
        assertFalse(MessengerWeb.isMetaUrl("not a url"))
    }

    @Test
    fun theAddressTellsSignInFromMessages() {
        assertEquals(MessengerWebState.LOADING, MessengerWeb.classify(null))
        assertEquals(MessengerWebState.LOADING, MessengerWeb.classify("about:blank"))
        assertEquals(MessengerWebState.SIGNED_OUT, MessengerWeb.classify("https://www.facebook.com/login.php?next=https%3A%2F%2Fwww.facebook.com%2Fmessages%2F"))
        assertEquals(MessengerWebState.SIGNED_OUT, MessengerWeb.classify("https://www.facebook.com/login/?next=x"))
        // A login code or approval: the page where "approve in the Facebook app" helps.
        assertEquals(MessengerWebState.CHECK, MessengerWeb.classify("https://www.facebook.com/checkpoint/1501092823525282/"))
        assertEquals(MessengerWebState.CHECK, MessengerWeb.classify("https://www.facebook.com/two_step_verification/two_factor/"))
        assertEquals(MessengerWebState.CHECK, MessengerWeb.classify("https://www.facebook.com/two_step_verification/authentication/?encrypted_context=x"))
        assertEquals(MessengerWebState.CHECK, MessengerWeb.classify("https://www.facebook.com/auth_platform/afad/?apc=x"))
        assertEquals(MessengerWebState.MESSAGES, MessengerWeb.classify("https://www.facebook.com/messages"))
        assertEquals(MessengerWebState.MESSAGES, MessengerWeb.classify("https://www.facebook.com/messages/t/100012345"))
        assertEquals(MessengerWebState.MESSAGES, MessengerWeb.classify("https://www.facebook.com/messages/e2ee/t/7654321"))
        assertEquals(MessengerWebState.MESSAGES, MessengerWeb.classify("https://www.messenger.com/t/100012345"))
        assertEquals("another page of Facebook's", MessengerWebState.OTHER, MessengerWeb.classify("https://www.facebook.com/marketplace/"))
        assertEquals("a name that only starts like it", MessengerWebState.OTHER, MessengerWeb.classify("https://www.facebook.com/messagesfoo"))
        assertEquals(MessengerWebState.OTHER, MessengerWeb.classify("https://example.com/messages/"))
    }

    @Test
    fun theShownAddressNeverCarriesTheQuery() {
        assertEquals("www.facebook.com/messages/t/1", MessengerWeb.displayAddress("https://www.facebook.com/messages/t/1/?token=secret#x"))
        assertEquals("www.facebook.com/login.php", MessengerWeb.displayAddress("https://www.facebook.com/login.php?next=abc"))
        assertEquals("", MessengerWeb.displayAddress(null))
    }

    @Test
    fun theComputerLayoutIsAskedForWithoutHidingTheWebView() {
        val phone = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.002; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36"
        val asked = MessengerWeb.desktopUserAgent(phone)
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/130.0.6723.58 Safari/537.36",
            asked,
        )
        assertFalse("no phone left in it", asked.contains("Android") || asked.contains("Mobile") || asked.contains("Pixel"))
        assertTrue("still says it is a web view", asked.contains("; wv)"))
        assertTrue("the real engine version stays", asked.contains("Chrome/130.0.6723.58"))
    }

    @Test
    fun theReducedUserAgentOfNewerAndroidIsHandledToo() {
        val reduced = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/142.0.0.0 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/142.0.0.0 Safari/537.36",
            MessengerWeb.desktopUserAgent(reduced),
        )
    }

    @Test
    fun aUserAgentThatIsNotAnAndroidOneIsLeftAlone() {
        val tablet = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        assertEquals(tablet, MessengerWeb.desktopUserAgent(tablet))
        assertEquals("odd", MessengerWeb.desktopUserAgent("odd"))
        assertEquals("", MessengerWeb.desktopUserAgent(""))
    }
}
