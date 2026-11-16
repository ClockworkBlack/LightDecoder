package com.lightdecoder.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The archive's owner, which pairing compares with the new phone before anything of it is stored. */
@RunWith(AndroidJUnit4::class)
class RelayPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RelayPrefs.setArchiveOwner(context, "", "")
    }

    @Test
    fun archiveOwnerIsUnknownUntilSet() {
        assertEquals("", RelayPrefs.archivePhone(context))
        assertEquals("", RelayPrefs.archiveAccount(context))
    }

    @Test
    fun archiveOwnerIsKeptUntilForgotten() {
        RelayPrefs.setArchiveOwner(context, "someone@example.com/1700000000", "someone@example.com")
        // Turning the link off or on, or other settings, leave it alone.
        RelayPrefs.setLiveEnabled(context, false)
        RelayPrefs.setBackfillDoneAt(context, 0)
        assertEquals("someone@example.com/1700000000", RelayPrefs.archivePhone(context))
        assertEquals("someone@example.com", RelayPrefs.archiveAccount(context))

        RelayPrefs.setArchiveOwner(context, "", "")
        assertEquals("", RelayPrefs.archivePhone(context))
        assertEquals("", RelayPrefs.archiveAccount(context))
    }
}
