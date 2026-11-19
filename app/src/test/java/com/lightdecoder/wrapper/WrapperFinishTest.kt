package com.lightdecoder.wrapper

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

/** What the user is told when a wrapper job ends: nothing when it went, the failure (once) when it did not. */
@RunWith(AndroidJUnit4::class)
class WrapperFinishTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun aJobThatWentShowsNoFailure() {
        Wrapper.takeFailure()
        for (kind in Wrapper.Kind.values()) {
            Wrapper.finish(app, Wrapper.Job(kind, "chat", "Hello", "x", Intent()), Wrapper.Result(true, null, 700))
            ShadowLooper.idleMainLooper()
            assertNull("$kind went: no \"not finished\"", Wrapper.takeFailure())
        }
    }

    @Test
    fun aJobThatDidNotGoIsShownOnceByTheConversation() {
        Wrapper.takeFailure()
        val shown = ArrayList<Wrapper.Failure>()
        Wrapper.onFailure = { shown += it }
        try {
            Wrapper.finish(app, Wrapper.Job(Wrapper.Kind.UNSEND, "chat", "Hello", "", Intent()), Wrapper.Result(false, "menu", 900))
            ShadowLooper.idleMainLooper()
            assertEquals(1, shown.size)
            assertEquals("menu", shown.single().result.stoppedAt)
            assertNull("taken", Wrapper.takeFailure())
            // A look for receipts that did not work says nothing.
            Wrapper.finish(app, Wrapper.Job(Wrapper.Kind.STATUS, "chat", "", "", Intent()), Wrapper.Result(false, "no receipt shown", 900))
            ShadowLooper.idleMainLooper()
            assertEquals(1, shown.size)
        } finally {
            Wrapper.onFailure = null
        }
        assertNotNull(Wrapper.Kind.UNSEND)
    }
}
