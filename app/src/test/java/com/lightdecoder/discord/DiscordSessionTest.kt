package com.lightdecoder.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordSessionTest {

    @Test
    fun signInPagesAreSignedOut() {
        assertEquals(DiscordSessionState.SIGNED_OUT, DiscordSession.classify("https://discord.com/login"))
        assertEquals(
            DiscordSessionState.SIGNED_OUT,
            DiscordSession.classify("https://discord.com/login?redirect_to=%2Fchannels%2F%40me"),
        )
        assertEquals(DiscordSessionState.SIGNED_OUT, DiscordSession.classify("https://discord.com/register"))
    }

    @Test
    fun conversationsAreSignedIn() {
        assertEquals(DiscordSessionState.SIGNED_IN, DiscordSession.classify("https://discord.com/channels/@me"))
        assertEquals(DiscordSessionState.SIGNED_IN, DiscordSession.classify("https://discord.com/channels/@me/123"))
        assertEquals(DiscordSessionState.SIGNED_IN, DiscordSession.classify("https://discord.com/channels/111/222"))
    }

    @Test
    fun everythingElseIsOtherOrLoading() {
        assertEquals(DiscordSessionState.LOADING, DiscordSession.classify(null))
        assertEquals(DiscordSessionState.LOADING, DiscordSession.classify("about:blank"))
        assertEquals(DiscordSessionState.OTHER, DiscordSession.classify("https://discord.com/app"))
        assertEquals(DiscordSessionState.OTHER, DiscordSession.classify("https://example.com/channels/1/2"))
        assertEquals(DiscordSessionState.OTHER, DiscordSession.classify("http://discord.com/channels/@me"))
    }

    @Test
    fun onlyRealDiscordHostsCount() {
        assertTrue(DiscordSession.isDiscordUrl("https://discord.com/app"))
        assertTrue(DiscordSession.isDiscordUrl("https://canary.discord.com/app"))
        assertFalse(DiscordSession.isDiscordUrl("https://discord.com.evil.example/login"))
        assertFalse(DiscordSession.isDiscordUrl("https://notdiscord.com/login"))
        assertFalse(DiscordSession.isDiscordUrl("https://evil.example/?u=https://discord.com"))
        assertFalse(DiscordSession.isDiscordUrl("intent://discord.com/#Intent;end"))
    }

    @Test
    fun channelLinksDropQueryAndRejectOddPaths() {
        assertEquals(
            "https://discord.com/channels/111/222",
            DiscordSession.channelLinkOrNull("https://discord.com/channels/111/222?x=1#frag"),
        )
        assertEquals(
            "https://discord.com/channels/@me/123",
            DiscordSession.channelLinkOrNull("https://discord.com/channels/@me/123/"),
        )
        assertNull(DiscordSession.channelLinkOrNull("https://discord.com/login"))
        assertNull(DiscordSession.channelLinkOrNull("https://discord.com/channels/abc/def"))
    }

    @Test
    fun displayAddressNeverShowsTheQuery() {
        assertEquals(
            "discord.com/login",
            DiscordSession.displayAddress("https://discord.com/login?redirect_to=%2Fchannels%2F%40me"),
        )
    }
}
