package com.lightdecoder.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A person's number put on the clipboard is marked sensitive. */
@RunWith(AndroidJUnit4::class)
class SensitiveClipTest {

    @Test
    fun theClipIsMarkedSensitive() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val clip = SensitiveClip.of("Number", "+15551234567")
        assertEquals("+15551234567", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(SensitiveClip.EXTRA_IS_SENSITIVE))
        if (Build.VERSION.SDK_INT >= 33) assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, SensitiveClip.EXTRA_IS_SENSITIVE)
        // It stays marked on the clipboard.
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(clip)
        assertTrue(clipboard.primaryClip!!.description.extras.getBoolean(SensitiveClip.EXTRA_IS_SENSITIVE))
    }
}
