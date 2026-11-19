package com.lightdecoder.imessage.direct

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The request/reply channel between the two processes, over a bound service's Messenger. */
@RunWith(AndroidJUnit4::class)
class DirectIpcTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Runs the main looper (where Android delivers onServiceConnected) until [d] finishes. */
    private fun <T> await(d: Deferred<T>): T {
        val until = System.currentTimeMillis() + 10_000
        while (!d.isCompleted && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        return d.getCompleted()
    }

    private fun bind(server: DirectIpc.Server) {
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, DirectSinkService::class.java), server.messenger.binder,
        )
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun aRequestIsAnsweredWithItsOwnReply() {
        val server = DirectIpc.Server { what, request -> JSONObject().put("what", what).put("echo", request.getString("x")) }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val answers = (1..5).map { i -> GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.UPDATE, JSONObject().put("x", "n$i"), 5_000) } }
        answers.forEachIndexed { i, d ->
            val a = await(d)
            assertEquals(DirectWire.UPDATE, a.getInt("what"))
            assertEquals("n${i + 1}", a.getString("echo"))
        }
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun aFailureOnTheOtherSideComesBackAsAnError() {
        val server = DirectIpc.Server { _, _ -> throw DirectEngine.ImessageError("Apple refused the unsend: too late") }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val a = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.UNSEND, JSONObject(), 5_000) })
        assertEquals("Apple refused the unsend: too late", DirectWire.errorOf(a))
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun noAnswerInTimeIsAnErrorNotAHang() {
        val never = CompletableDeferred<Unit>()
        val server = DirectIpc.Server { _, _ -> never.await(); JSONObject() }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val a = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.SEND, JSONObject(), 300) })
        assertTrue(DirectWire.errorOf(a) == DirectIpc.Client.NO_ANSWER)
        never.complete(Unit)
        client.close()
        server.close()
    }
}
