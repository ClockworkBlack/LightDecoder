package com.lightdecoder.discord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordContainerTest {

    @Test
    fun onlyTheDiscordProcessIsTheContainer() {
        assertTrue(DiscordContainer.isDiscordProcess("com.lightdecoder:discord", "com.lightdecoder"))
        assertFalse("the main process", DiscordContainer.isDiscordProcess("com.lightdecoder", "com.lightdecoder"))
        assertFalse("another app's", DiscordContainer.isDiscordProcess("com.other:discord", "com.lightdecoder"))
        assertFalse("another process of this app", DiscordContainer.isDiscordProcess("com.lightdecoder:other", "com.lightdecoder"))
        assertFalse(DiscordContainer.isDiscordProcess(null, "com.lightdecoder"))
    }

    @Test
    fun theDataFolderIsNotTheMainProcessOne() {
        // The main process's folder is app_webview; a suffix makes it app_webview_<suffix>.
        assertNotEquals("", DiscordContainer.DATA_SUFFIX)
        assertTrue(DiscordContainer.DATA_SUFFIX.all { it.isLetterOrDigit() })
    }
}
