package com.lightdecoder.imessage.madrid

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bluebubbles.messaging.IKeyboardHandle
import com.bluebubbles.messaging.IMadridExtension
import com.bluebubbles.messaging.IMessageViewHandle
import com.bluebubbles.messaging.IViewUpdateCallback
import com.bluebubbles.messaging.MadridMessage
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The binding to an extension app: checked before it is bound, shared, and let go a little after its last use. */
@RunWith(AndroidJUnit4::class)
class MadridConnectionTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val ext = MadridApps.OPENPIGEON

    /** An extension that does nothing. */
    class Idle : IMadridExtension.Stub() {
        override fun keyboardOpened(callback: IViewUpdateCallback?, handle: IKeyboardHandle?, userCount: Int): RemoteViews? = null
        override fun keyboardClosed() {}
        override fun didTapTemplate(message: MadridMessage?, handle: IMessageViewHandle?, userCount: Int) {}
        override fun getLiveView(callback: IViewUpdateCallback?, message: MadridMessage?, handle: IMessageViewHandle?, userCount: Int): RemoteViews? = null
        override fun messageUpdated(message: MadridMessage?) {}
    }

    @Before
    fun setUp() {
        shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(ext.pkg, ext.service), Idle())
    }

    private fun bound() = shadowOf(context).boundServiceConnections.size

    private fun unbound() = shadowOf(context).unboundServiceConnections.size

    /** Runs [block] off the main thread while the main thread does its work (connections arrive there). */
    private fun <T> offMain(block: suspend () -> T): T {
        val d = CoroutineScope(Dispatchers.IO).async { block() }
        val deadline = System.currentTimeMillis() + 15_000
        while (!d.isCompleted) {
            if (System.currentTimeMillis() > deadline) fail("timed out")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return runBlocking { d.await() }
    }

    @Test
    fun anAppNotVerifiedIsNeverBound() {
        val conn = MadridConnection(context, ext, verified = { false }, onDied = {})
        assertNull(offMain { conn.acquire() })
        assertEquals(0, bound())
    }

    @Test
    fun oneBindingIsSharedAndLetGoAfterItsLastUse() {
        var checks = 0
        val conn = MadridConnection(context, ext, verified = { checks++; true }, onDied = {})
        val a = offMain { conn.acquire() }
        assertNotNull(a)
        val b = offMain { conn.acquire() }
        assertNotNull(b)
        assertEquals("bound once", 1, bound())
        assertEquals("checked before binding", 1, checks)
        var told = 0
        conn.listen { told++ }
        assertEquals("a listener hears of the connection there is", 1, told)
        conn.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals("still in use", 0, unbound())
        conn.release()
        // Released more often than acquired: nothing.
        conn.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("not at once", 0, unbound())
        // Used again within the delay: kept.
        assertNotNull(offMain { conn.acquire() })
        conn.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals("unbound once, after its last use", 1, unbound())
        // Bound again later: checked again.
        assertNotNull(offMain { conn.acquire() })
        assertEquals("bound anew (checked before each bind)", 2, checks)
        assertTrue(told >= 2)
        conn.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        // Let go again: the next use binds anew.
        assertNotNull(offMain { conn.acquire() })
        assertEquals(3, checks)
        conn.release()
        // (Robolectric reports each unbind as a disconnection, which Android does not: deaths not counted here.)
    }
}
