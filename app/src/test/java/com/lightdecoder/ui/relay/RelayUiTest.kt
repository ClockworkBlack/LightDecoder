package com.lightdecoder.ui.relay

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Error wording, the state-to-action rules, and which pages the sign-in WebView may open. */
@RunWith(AndroidJUnit4::class)
class RelayUiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyBridgeCodeHasItsOwnExplanation() {
        val specific = RelayException.CODES - RelayException.INTERNAL
        for (code in specific) {
            assertNotEquals("code $code fell through to the generic text", R.string.link_error_internal, RelayErrors.messageRes(code))
            assertTrue(context.getString(RelayErrors.messageRes(code)).isNotBlank())
        }
        assertEquals(specific.size, specific.map { RelayErrors.messageRes(it) }.toSet().size)
        assertEquals(R.string.link_error_internal, RelayErrors.messageRes("something_new"))
    }

    @Test
    fun codesComeFromAnyThrowable() {
        assertEquals("incorrect_emoji", RelayErrors.codeOf(Exception("incorrect_emoji: a different emoji was chosen")))
        assertEquals("incorrect_emoji", RelayErrors.codeOf(RelayException("incorrect_emoji", "")))
        assertEquals("internal", RelayErrors.codeOf(IllegalStateException("boom")))
        assertTrue(RelayErrors.needsNewSignIn(RelayException.COOKIES_INVALID))
        assertFalse(RelayErrors.canRetryPairing(RelayException.NO_COOKIES))
        assertTrue(RelayErrors.canRetryPairing(RelayException.INCORRECT_EMOJI))
    }

    @Test
    fun whatEachStateOffers() {
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.UNPAIRED), paired = false))
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.LOGGED_OUT), paired = false))
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.COOKIES_INVALID), paired = true))
        assertEquals(RelayUi.Fix.USE_HERE, RelayUi.fix(RelayState(Phase.ACTIVE_ELSEWHERE), paired = true))
        assertEquals(RelayUi.Fix.CONNECT, RelayUi.fix(RelayState(Phase.DISCONNECTED), paired = true))
        assertEquals(RelayUi.Fix.STATUS, RelayUi.fix(RelayState(Phase.ERROR, "internal"), paired = true))
        assertEquals(RelayUi.Fix.NONE, RelayUi.fix(RelayState(Phase.CONNECTED), paired = true))
        assertEquals(RelayUi.Fix.NONE, RelayUi.fix(RelayState(Phase.RECONNECTING), paired = true))
        // Every phase has a title and a body.
        for (phase in Phase.entries) {
            assertTrue(context.getString(RelayUi.title(phase)).isNotBlank())
            assertTrue(context.getString(RelayUi.body(phase)).isNotBlank())
        }
    }

    @Test
    fun signInWebViewOnlyOpensGoogleSignIn() {
        val allowed = listOf(
            "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config",
            "https://messages.google.com/web/config",
            "https://google.com/",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://accounts.google.co.uk/accounts/SetSID",
            "https://accounts.google.de/accounts/SetSID",
            "about:blank",
        )
        val refused = listOf(
            "http://accounts.google.com/",
            "https://evil.example/",
            "https://google.com.evil.example/",
            "https://accounts.google.com.evil.example/",
            "https://notgoogle.com/",
            "https://www.youtube.com/",
            "javascript:alert(1)",
            "file:///data/data/com.lightdecoder/files/relay/session.bin",
            "intent://scan#Intent;end",
        )
        allowed.forEach { assertTrue(it, RelaySetupActivity.isAllowedSignInUrl(Uri.parse(it))) }
        refused.forEach { assertFalse(it, RelaySetupActivity.isAllowedSignInUrl(Uri.parse(it))) }
    }
}
