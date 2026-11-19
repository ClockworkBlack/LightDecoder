package com.lightdecoder.imessage

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
class ImessageIpcTest {

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

    private fun bind(server: ImessageIpc.Server) {
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, ImessageSinkService::class.java), server.messenger.binder,
        )
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun aRequestIsAnsweredWithItsOwnReply() {
        val server = ImessageIpc.Server { what, request -> JSONObject().put("what", what).put("echo", request.getString("x")) }
        bind(server)
        val client = ImessageIpc.Client(app, ImessageSinkService::class.java)
        val answers = (1..5).map { i -> GlobalScope.async(Dispatchers.IO) { client.call(ImessageWire.UPDATE, JSONObject().put("x", "n$i"), 5_000) } }
        answers.forEachIndexed { i, d ->
            val a = await(d)
            assertEquals(ImessageWire.UPDATE, a.getInt("what"))
            assertEquals("n${i + 1}", a.getString("echo"))
        }
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun aFailureOnTheOtherSideComesBackAsAnError() {
        val server = ImessageIpc.Server { _, _ -> throw ImessageEngine.ImessageError("Apple refused the unsend: too late") }
        bind(server)
        val client = ImessageIpc.Client(app, ImessageSinkService::class.java)
        val a = await(GlobalScope.async(Dispatchers.IO) { client.call(ImessageWire.UNSEND, JSONObject(), 5_000) })
        assertEquals("Apple refused the unsend: too late", ImessageWire.errorOf(a))
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun noAnswerInTimeIsAnErrorNotAHang() {
        val never = CompletableDeferred<Unit>()
        val server = ImessageIpc.Server { _, _ -> never.await(); JSONObject() }
        bind(server)
        val client = ImessageIpc.Client(app, ImessageSinkService::class.java)
        val a = await(GlobalScope.async(Dispatchers.IO) { client.call(ImessageWire.SEND, JSONObject(), 300) })
        assertTrue(ImessageWire.errorOf(a) == ImessageIpc.Client.NO_ANSWER)
        never.complete(Unit)
        client.close()
        server.close()
    }
}
