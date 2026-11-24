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
        val server = DirectIpc.Server { what, request, _ -> JSONObject().put("what", what).put("echo", request.getString("x")) }
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
        val server = DirectIpc.Server { _, _, _ -> throw DirectEngine.ImessageError("Apple refused the unsend: too late") }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val a = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.UNSEND, JSONObject(), 5_000) })
        assertEquals("Apple refused the unsend: too late", DirectWire.errorOf(a))
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun anErrorOnTheOtherSideComesBackAsAFailureNotTheEndOfItsProcess() {
        val server = DirectIpc.Server { what, _, _ ->
            when (what) {
                DirectWire.UPDATE -> throw StackOverflowError("deep")
                DirectWire.DOWNLOAD -> throw OutOfMemoryError("huge")
                else -> DirectWire.ok()
            }
        }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        for (what in listOf(DirectWire.UPDATE, DirectWire.DOWNLOAD)) {
            val a = await(GlobalScope.async(Dispatchers.IO) { client.call(what, JSONObject(), 5_000) })
            assertTrue(a.toString(), DirectWire.errorOf(a) != null && DirectWire.errorOf(a) != DirectIpc.Client.NO_ANSWER)
        }
        // And it goes on answering.
        assertEquals(true, await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.READ, JSONObject(), 5_000) }).optBoolean("ok"))
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun noAnswerInTimeIsAnErrorNotAHang() {
        val never = CompletableDeferred<Unit>()
        val server = DirectIpc.Server { _, _, _ -> never.await(); JSONObject() }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val a = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.SEND, JSONObject(), 300) })
        assertTrue(DirectWire.errorOf(a) == DirectIpc.Client.NO_ANSWER)
        never.complete(Unit)
        client.close()
        server.close()
    }

    /** Runs the main looper until [condition] holds. */
    private fun until(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun aRequestIsCountedUntilItsAnswerHasGoneAndTheHookRunsOnlyAfter() {
        val inHandler = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val after = java.util.Collections.synchronizedList(ArrayList<String>())
        lateinit var server: DirectIpc.Server
        server = DirectIpc.Server(afterReply = { what, request -> after += "$what visible=${request.optBoolean("visible")} counted=${server.requests.inFlight}" }) { what, _, _ ->
            if (what == DirectWire.DOWNLOAD) {
                inHandler.complete(Unit)
                release.await()
            }
            DirectWire.ok()
        }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val slow = GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.DOWNLOAD, JSONObject(), 5_000) }
        until("the request being answered") { inHandler.isCompleted }
        assertEquals("counted while it is answered", 1, server.requests.inFlight)
        assertTrue("no hook before its answer", after.isEmpty())
        release.complete(Unit)
        assertEquals(true, await(slow).optBoolean("ok"))
        until("its hook") { after.size == 1 }
        assertEquals("${DirectWire.DOWNLOAD} visible=false counted=0", after.single())
        assertEquals(0, server.requests.inFlight)
        assertEquals(true, await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.ACCOUNT, DirectWire.account(visible = true), 5_000) }).optBoolean("ok"))
        until("its hook") { after.size == 2 }
        assertEquals("${DirectWire.ACCOUNT} visible=true counted=0", after[1])
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun whileClosedForARestartARequestIsNotTakenAndAnsweredThatItDidNotGo() {
        val handled = java.util.concurrent.atomic.AtomicInteger()
        val after = java.util.concurrent.atomic.AtomicInteger()
        val server = DirectIpc.Server(afterReply = { _, _ -> after.incrementAndGet() }) { _, _, _ ->
            handled.incrementAndGet()
            DirectWire.ok()
        }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        kotlinx.coroutines.runBlocking { assertTrue(server.requests.close(0)) }
        val refused = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.SEND, JSONObject(), 5_000) })
        assertEquals(DirectIpc.Client.UNREACHABLE, DirectWire.errorOf(refused))
        assertTrue("certainly not gone through", !DirectWire.unconfirmed(refused))
        assertEquals(0, handled.get())
        assertEquals("no hook for a request not taken", 0, after.get())
        assertEquals(0, server.requests.inFlight)
        // The restart did not happen: taken again.
        server.requests.open()
        assertEquals(true, await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.SEND, JSONObject(), 5_000) }).optBoolean("ok"))
        assertEquals(1, handled.get())
        client.close()
        server.close()
    }

    @Test
    fun aRequestFromAnotherAppIsDropped() {
        val handled = java.util.concurrent.atomic.AtomicInteger()
        val server = DirectIpc.Server { _, _, _ -> handled.incrementAndGet(); DirectWire.ok() }
        val answers = java.util.concurrent.LinkedBlockingQueue<Long>()
        val thread = android.os.HandlerThread("ipc-test-replies").apply { start() }
        val replyTo = android.os.Messenger(
            android.os.Handler(thread.looper) { msg -> answers.add(msg.data.getLong(DirectWire.KEY_ID)); true },
        )
        fun ask(id: Long) = server.messenger.send(
            android.os.Message.obtain(null, DirectWire.UNSEND).apply {
                this.replyTo = replyTo
                data = android.os.Bundle().apply {
                    putLong(DirectWire.KEY_ID, id)
                    putString(DirectWire.KEY_JSON, "{}")
                }
            },
        )
        try {
            // Android stamps a Messenger's message with its sender's uid: another app's.
            org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)
            ask(1)
            assertEquals(null, answers.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals("never handled", 0, handled.get())
            // This app's other process.
            org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid())
            ask(2)
            assertEquals(2L, answers.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, handled.get())
        } finally {
            org.robolectric.shadows.ShadowBinder.reset()
            thread.quitSafely()
            server.close()
        }
    }

    @Test
    fun aReplyFromAnotherAppIsNotTaken() {
        val reply = android.os.Message.obtain(null, DirectWire.REPLY)
        reply.sendingUid = android.os.Process.myUid() + 1
        assertTrue(!DirectIpc.fromThisApp(reply))
        reply.sendingUid = android.os.Process.myUid()
        assertTrue(DirectIpc.fromThisApp(reply))
        // Not sent through a Messenger at all.
        assertTrue(!DirectIpc.fromThisApp(android.os.Message.obtain()))
    }
    // --- B4: interim progress ----------------------------------------------------------------------------

    /** A stand-in for the other process: [onRequest] gets each request and its replyTo, on its own thread. */
    private fun fakeServer(onRequest: (msg: android.os.Message, replyTo: android.os.Messenger, id: Long) -> Unit): android.os.HandlerThread {
        val thread = android.os.HandlerThread("ipc-fake-server").apply { start() }
        val messenger = android.os.Messenger(
            android.os.Handler(thread.looper) { msg ->
                val replyTo = msg.replyTo
                val id = msg.data.getLong(DirectWire.KEY_ID)
                // Copied here: the message is recycled once this returns.
                val copy = android.os.Message.obtain(msg)
                Thread { onRequest(copy, replyTo, id) }.start()
                true
            },
        )
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, DirectSinkService::class.java), messenger.binder)
        return thread
    }

    private fun message(what: Int, id: Long, json: JSONObject) = android.os.Message.obtain(null, what).apply {
        data = android.os.Bundle().apply {
            putLong(DirectWire.KEY_ID, id)
            putString(DirectWire.KEY_JSON, json.toString())
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun progressReachesOnlyTheListenerOfItsOwnRequest() {
        val release = java.util.concurrent.CountDownLatch(1)
        val server = DirectIpc.Server { _, request, progress ->
            val tag = request.getString("x")
            repeat(3) { progress(DirectWire.progress(it.toLong(), 10).put("tag", tag)) }
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            DirectWire.ok()
        }
        bind(server)
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val heardA = java.util.Collections.synchronizedList(ArrayList<String>())
        val heardB = java.util.Collections.synchronizedList(ArrayList<String>())
        val a = GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.DOWNLOAD, JSONObject().put("x", "a"), 5_000, { heardA += it.getString("tag") }) }
        val b = GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.DOWNLOAD, JSONObject().put("x", "b"), 5_000, { heardB += it.getString("tag") }) }
        until("the progress of both") { heardA.size == 3 && heardB.size == 3 }
        release.countDown()
        assertEquals(true, await(a).optBoolean("ok"))
        assertEquals(true, await(b).optBoolean("ok"))
        assertEquals(listOf("a", "a", "a"), heardA.toList())
        assertEquals(listOf("b", "b", "b"), heardB.toList())
        client.close()
        server.close()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun progressFromAnotherAppAndProgressAfterTheAnswerAreDropped() {
        val thread = fakeServer { _, replyTo, id ->
            try {
                // Another app's uid on it: never heard.
                org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)
                replyTo.send(message(DirectWire.PROGRESS, id, DirectWire.progress(1, 10).put("tag", "other app")))
                Thread.sleep(200)
            } finally {
                org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid())
            }
            replyTo.send(message(DirectWire.PROGRESS, id, DirectWire.progress(2, 10).put("tag", "ours")))
            Thread.sleep(200)
            replyTo.send(message(DirectWire.REPLY, id, DirectWire.ok()))
            // Late, after its answer: dropped.
            Thread.sleep(100)
            replyTo.send(message(DirectWire.PROGRESS, id, DirectWire.progress(10, 10).put("tag", "late")))
        }
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        try {
            val a = GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.DOWNLOAD, JSONObject(), 5_000, { heard += it.getString("tag") }) }
            assertEquals(true, await(a).optBoolean("ok"))
            Thread.sleep(400)
            assertEquals(listOf("ours"), heard.toList())
        } finally {
            org.robolectric.shadows.ShadowBinder.reset()
            client.close()
            thread.quitSafely()
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun progressKeepsAnIdleRequestWaitingButTheAbsoluteCapStillEndsIt() {
        val forever = java.util.concurrent.atomic.AtomicBoolean(true)
        val thread = fakeServer { msg, replyTo, id ->
            when (msg.what) {
                // Moves for 900 ms (each step well inside the idle time), then answers.
                DirectWire.DOWNLOAD -> {
                    repeat(9) {
                        replyTo.send(message(DirectWire.PROGRESS, id, DirectWire.progress(it.toLong(), 10)))
                        Thread.sleep(100)
                    }
                    replyTo.send(message(DirectWire.REPLY, id, DirectWire.ok()))
                }
                // Moves on and on: only the cap ends the wait.
                DirectWire.SEND -> while (forever.get()) {
                    replyTo.send(message(DirectWire.PROGRESS, id, DirectWire.progress(1, 10)))
                    Thread.sleep(100)
                }
                // Says nothing at all.
                else -> Unit
            }
        }
        val client = DirectIpc.Client(app, DirectSinkService::class.java)
        try {
            val moving = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.DOWNLOAD, JSONObject(), 10_000, null, idleMs = 400) })
            assertEquals("waited for while it moved", true, moving.optBoolean("ok"))
            val silentStart = System.currentTimeMillis()
            val silent = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.READ, JSONObject(), 10_000, null, idleMs = 300) })
            assertEquals(DirectIpc.Client.NO_ANSWER, DirectWire.errorOf(silent))
            assertTrue("ended by its idle time", System.currentTimeMillis() - silentStart < 5_000)
            val capStart = System.currentTimeMillis()
            val heard = java.util.concurrent.atomic.AtomicInteger()
            val capped = await(GlobalScope.async(Dispatchers.IO) { client.call(DirectWire.SEND, JSONObject(), 800, { heard.incrementAndGet() }, idleMs = 400) })
            assertTrue("it was heard moving", heard.get() > 0)
            assertEquals(DirectIpc.Client.NO_ANSWER, DirectWire.errorOf(capped))
            val took = System.currentTimeMillis() - capStart
            assertTrue("ended by the cap, after $took ms", took in 700..5_000)
        } finally {
            forever.set(false)
            client.close()
            thread.quitSafely()
        }
    }

    @Test
    fun aProgressMessageIsNoRequest() {
        val handled = java.util.concurrent.atomic.AtomicInteger()
        val server = DirectIpc.Server { _, _, _ -> handled.incrementAndGet(); DirectWire.ok() }
        val answers = java.util.concurrent.LinkedBlockingQueue<Long>()
        val thread = android.os.HandlerThread("ipc-test-replies2").apply { start() }
        val replyTo = android.os.Messenger(android.os.Handler(thread.looper) { msg -> answers.add(msg.data.getLong(DirectWire.KEY_ID)); true })
        try {
            server.messenger.send(message(DirectWire.PROGRESS, 7, JSONObject()).apply { this.replyTo = replyTo })
            assertEquals(null, answers.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals(0, handled.get())
        } finally {
            thread.quitSafely()
            server.close()
        }
    }
}
