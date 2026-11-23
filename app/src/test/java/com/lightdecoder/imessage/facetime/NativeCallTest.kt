package com.lightdecoder.imessage.facetime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guards around the library's call functions ([NativeCall]): the inputs that take its process
 * down never reach it (a call that is not in its list, a session handle used twice, an orientation
 * outside 0..3, a join before its addresses were told), nothing it throws gets out, and nothing is
 * called on a call that was left.
 */
class NativeCallTest {

    private val bridge = FakeBridge()
    private val native = FakeNative()
    private val call = NativeCall(GUID, native, bridge)

    private fun start(c: NativeCall = call): NativeCall.Start = runBlocking { c.start() }

    @Test
    fun aJoinTellsTheAddressesChecksTheCallIsThereAndUsesAFreshHandleOnce() {
        bridge.incoming(GUID)
        native.isVideo = true
        assertEquals(NativeCall.Start.Up(video = true), start())
        // In this order: the addresses, the look into the call list, the handle, the join.
        assertEquals(listOf("setLocalInterfaces", "sessions", "handle:1000"), bridge.log.toList())
        assertEquals(listOf("createAvc:$GUID"), native.calls.toList())
        assertEquals("the handle made is the one used", bridge.handlesMade.toList(), native.handlesUsed.toList())
        assertTrue(call.isUp)
    }

    @Test
    fun aCallThatIsNotInTheLibrarysListIsNeverJoined() {
        bridge.incoming(OTHER)
        assertEquals(NativeCall.Start.Gone, start())
        assertTrue("the library's join was never called", native.calls.isEmpty())
        assertTrue("and no handle was made for it", bridge.handlesMade.isEmpty())
        assertFalse(call.isUp)
        // Nothing to leave either: it is the caller's to take back or decline.
        assertEquals(NativeCall.Leave.NEVER_JOINED, call.hangUp())
    }

    @Test
    fun withoutASessionThereIsNoJoin() {
        bridge.incoming(GUID)
        bridge.noSession = true
        assertEquals(NativeCall.Start.Failed, start())
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun whateverTheJoinThrowsIsAFailedStartNeverMore() {
        bridge.incoming(GUID)
        for (thrown in listOf(IllegalStateException("x"), UnsatisfiedLinkError("no such function"), OutOfMemoryError("x"))) {
            val n = FakeNative().apply { throwOnCreate = thrown }
            val c = NativeCall(GUID, n, bridge)
            assertEquals(thrown::class.simpleName, NativeCall.Start.Failed, start(c))
            assertFalse(c.isUp)
            assertEquals(NativeCall.Leave.NEVER_JOINED, c.hangUp())
            assertEquals("nothing to destroy", listOf("createAvc:$GUID"), n.calls.toList())
        }
        // A join that says it did nothing (0) is a failed one.
        val zero = FakeNative().apply { avc = 0 }
        assertEquals(NativeCall.Start.Failed, start(NativeCall(GUID, zero, bridge)))
    }

    @Test
    fun aBridgeThatThrowsIsAFailedStartAndTheJoinIsNeverReached() {
        val failing = object : CallBridge by bridge {
            override suspend fun sessions(): List<CallSessionInfo> = throw IllegalStateException("not connected")
        }
        assertEquals(NativeCall.Start.Failed, start(NativeCall(GUID, native, failing)))
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun aSessionHandleIsNeverUsedTwice() {
        bridge.incoming(GUID)
        assertTrue(start() is NativeCall.Start.Up)
        // The same call object started again joins nothing and makes no handle.
        assertEquals(NativeCall.Start.Failed, start())
        assertEquals(1, bridge.handlesMade.size)
        assertEquals(1, native.handlesUsed.size)
        // Another call (after this one) gets a handle of its own.
        call.hangUp()
        val second = FakeNative()
        assertTrue(start(NativeCall(GUID, second, bridge)) is NativeCall.Start.Up)
        assertEquals(2, bridge.handlesMade.size)
        assertEquals(bridge.handlesMade.toSet().size, bridge.handlesMade.size)
        assertEquals(listOf(bridge.handlesMade[1]), second.handlesUsed.toList())
    }

    @Test
    fun hungUpBeforeTheJoinNothingIsJoined() {
        bridge.incoming(GUID)
        assertEquals(NativeCall.Leave.NEVER_JOINED, call.hangUp())
        assertEquals(NativeCall.Start.Ended(wasJoined = false), start())
        assertTrue(native.calls.isEmpty())
        assertTrue(bridge.handlesMade.isEmpty())
    }

    @Test
    fun hungUpWhileItJoinsWhatTheJoinMadeIsLeftAtOnce() {
        bridge.incoming(GUID)
        native.gate = CountDownLatch(1)
        runBlocking {
            val joining = async(Dispatchers.IO) { call.start() }
            assertTrue("the join began", native.entered.await(5, TimeUnit.SECONDS))
            assertEquals(NativeCall.Leave.JOINING, call.hangUp())
            assertEquals("nothing to destroy yet", listOf("createAvc:$GUID"), native.calls.toList())
            native.gate!!.countDown()
            assertEquals(NativeCall.Start.Ended(wasJoined = true), joining.await())
        }
        assertEquals(listOf("createAvc:$GUID", "destroyAvc:77"), native.calls.toList())
        assertFalse(call.isUp)
        // Nothing reaches the library for that call any more.
        call.microphone(true)
        call.camera(true)
        call.refreshSurfaces()
        assertEquals(2, native.calls.size)
    }

    @Test
    fun hungUpWhileAJoinFailsItIsStillTheCallersToTakeBack() {
        bridge.incoming(GUID)
        native.gate = CountDownLatch(1)
        native.avc = 0
        runBlocking {
            val joining = async(Dispatchers.IO) { call.start() }
            assertTrue(native.entered.await(5, TimeUnit.SECONDS))
            call.hangUp()
            native.gate!!.countDown()
            assertEquals(NativeCall.Start.Ended(wasJoined = false), joining.await())
        }
        assertEquals(listOf("createAvc:$GUID"), native.calls.toList())
    }

    @Test
    fun anOrientationIsAlwaysWithinWhatTheLibrarySurvives() {
        bridge.incoming(GUID)
        start()
        for (value in listOf(-7, -1, 0, 1, 2, 3, 4, 90, Int.MAX_VALUE, Int.MIN_VALUE)) call.orientation(value)
        val sent = native.calls.filter { it.startsWith("updateOrientation") }.map { it.substringAfterLast(':').toInt() }
        assertEquals(listOf(0, 0, 0, 1, 2, 3, 3, 3, 3, 0), sent)
        // The table from the screen's rotation: front camera 1, 3, 0, 2; back camera 0, 3, 1, 2.
        assertEquals(listOf(1, 3, 0, 2), (0..3).map { CallOrientation.forCamera(it, front = true) })
        assertEquals(listOf(0, 3, 1, 2), (0..3).map { CallOrientation.forCamera(it, front = false) })
        // And whatever a rotation is said to be.
        for (rotation in listOf(-5, 4, 270, Int.MAX_VALUE)) {
            assertTrue(CallOrientation.forCamera(rotation, true) in 0..3)
            assertTrue(CallOrientation.forCamera(rotation, false) in 0..3)
        }
    }

    @Test
    fun theJoinedCallIsDestroyedOnceAndNeverUsedAfter() {
        bridge.incoming(GUID)
        start()
        call.microphone(false)
        call.camera(true)
        call.refreshSurfaces()
        assertEquals(NativeCall.Leave.DESTROYED, call.hangUp())
        // Again: nothing more.
        assertEquals(NativeCall.Leave.NEVER_JOINED, call.hangUp())
        call.microphone(true)
        call.camera(false)
        call.orientation(1)
        call.refreshSurfaces()
        assertEquals(
            listOf("createAvc:$GUID", "enableMicrophone:77:false", "enableCamera:77:true:-1", "refreshSurfaces:77", "destroyAvc:77"),
            native.calls.toList(),
        )
        assertFalse(call.isUp)
    }

    @Test
    fun aCommandBeforeTheJoinIsNotSentAndOneThatThrowsDoesNotGetOut() {
        call.microphone(true)
        call.orientation(2)
        assertTrue(native.calls.isEmpty())
        bridge.incoming(GUID)
        val throwing = object : CallNative by native {
            override fun enableMicrophone(avc: Long, enabled: Boolean) = throw IllegalStateException("x")
            override fun destroyAvc(avc: Long) = throw UnsatisfiedLinkError("x")
        }
        val c = NativeCall(GUID, throwing, bridge)
        assertTrue(start(c) is NativeCall.Start.Up)
        c.microphone(true)
        assertEquals(NativeCall.Leave.DESTROYED, c.hangUp())
    }

    private companion object {
        const val GUID = "AAAAAAAA-0000-4000-8000-000000000001"
        const val OTHER = "CCCCCCCC-0000-4000-8000-000000000003"
    }
}
