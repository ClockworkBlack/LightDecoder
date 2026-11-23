package com.lightdecoder.imessage.facetime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.imessage.FaceTimeCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * FaceTime's notifications: the ringing one (a call notification that rings through its channel,
 * opens the call screen over the lock screen and names only the caller), the in-call one (Hang up)
 * and the missed one (a tap opens the chat); and that every button of theirs is an explicit,
 * unchangeable intent to a component of this app.
 */
@RunWith(AndroidJUnit4::class)
class FaceTimeNoticesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun call(video: Boolean, phase: Phase = Phase.RINGING_IN, outgoing: Boolean = false, activeSince: Long = 0) = Call(
        guid = GUID, peer = ALICE, name = "Alice Martin", video = video, outgoing = outgoing, phase = phase, startedMs = 1_700_000_000_000,
        activeSinceMs = activeSince, conversationId = CHAT,
    )

    @After
    fun tearDown() {
        FaceTimeCalls.installForTesting(null)
        scope.cancel()
        nm.cancelAll()
    }

    /** Explicit (this app's own component, by class) and unchangeable. */
    private fun assertOurs(intent: PendingIntent, component: Class<*>) {
        val shadow = shadowOf(intent)
        assertTrue("immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertFalse("not mutable", shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(component.name, shadow.savedIntent.component?.className)
        assertEquals(app.packageName, shadow.savedIntent.component?.packageName)
    }

    @Test
    fun theRingingNoticeIsACallNotificationThatRingsAndOpensTheCallScreen() {
        val n = FaceTimeNotices.incoming(app, call(video = true))
        // Its channel rings (the phone's ringtone, as a ringtone) and vibrates, and may show on the lock screen.
        val channel = nm.getNotificationChannel(FaceTimeNotices.CHANNEL_INCOMING)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        assertEquals("Incoming FaceTime calls", channel.name.toString())
        assertEquals(FaceTimeNotices.CHANNEL_INCOMING, n.channelId)
        // A call notification, kept up and ringing on until it is taken down.
        assertEquals(Notification.CallStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("an incoming call's", 1, n.extras.getInt(Notification.EXTRA_CALL_TYPE))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO))
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("rings until answered", n.flags and Notification.FLAG_INSISTENT != 0)
        assertFalse(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue("gone by itself should the call engine be", n.timeoutAfter in CallRules.RING_IN_MS..CallRules.RING_IN_MS + 10_000)
        // Who is calling, and that it is FaceTime: nothing of any chat.
        assertEquals("Alice Martin", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Incoming FaceTime Video", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // On a locked phone that hides what notices say: that a call rings, with no name, no address and no picture.
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertEquals("Incoming FaceTime call", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(public.getLargeIcon())
        assertTrue(public.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java).isNullOrEmpty())
        assertFalse(public.extras.toString().let { it.contains("Alice") || it.contains("5551234567") })
        // With the screen off or locked, the call screen itself; a tap opens it too. It only shows the call.
        assertOurs(n.fullScreenIntent, FaceTimeCallActivity::class.java)
        assertEquals(FaceTimeCallActivity.ACTION_SHOW, shadowOf(n.fullScreenIntent).savedIntent.action)
        assertTrue(shadowOf(n.fullScreenIntent).isActivityIntent)
        assertOurs(n.contentIntent, FaceTimeCallActivity::class.java)
        // Answer opens the call screen (which asks for the microphone and joins from a screen in front).
        val answer = n.extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)!!
        assertOurs(answer, FaceTimeCallActivity::class.java)
        assertTrue(shadowOf(answer).isActivityIntent)
        assertEquals(FaceTimeCallActivity.ACTION_ANSWER, shadowOf(answer).savedIntent.action)
        assertEquals(GUID, shadowOf(answer).savedIntent.getStringExtra(FaceTimeCallActivity.EXTRA_GUID))
        assertTrue(shadowOf(answer).savedIntent.getBooleanExtra(FaceTimeCallActivity.EXTRA_WITH_CAMERA, false))
        // Decline needs no screen: a broadcast to the app's own receiver.
        val decline = n.extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)!!
        assertOurs(decline, FaceTimeActionReceiver::class.java)
        assertTrue(shadowOf(decline).isBroadcastIntent)
        assertEquals(FaceTimeActionReceiver.ACTION_DECLINE, shadowOf(decline).savedIntent.action)
        // A video call can also be taken with sound only.
        val audioOnly = n.actions.single { it.title.toString() == "Audio only" }.actionIntent
        assertOurs(audioOnly, FaceTimeCallActivity::class.java)
        assertFalse(shadowOf(audioOnly).savedIntent.getBooleanExtra(FaceTimeCallActivity.EXTRA_WITH_CAMERA, true))
        // The three are three: none stands in for another.
        assertTrue(answer != audioOnly && answer != n.fullScreenIntent && audioOnly != n.fullScreenIntent)
    }

    @Test
    fun anAudioCallsRingingNoticeHasNoAudioOnlyButton() {
        val n = FaceTimeNotices.incoming(app, call(video = false).copy(name = ""))
        assertEquals("Incoming FaceTime Audio", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(n.extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO))
        assertTrue(n.actions.orEmpty().none { it.title.toString() == "Audio only" })
        assertEquals("an unknown caller is shown by their address", "+15551234567", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val answer = n.extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)!!
        assertFalse(shadowOf(answer).savedIntent.getBooleanExtra(FaceTimeCallActivity.EXTRA_WITH_CAMERA, true))
    }

    @Test
    fun theRingingNoticeIsPostedOnceAndTakenDown() {
        val poster = FaceTimeNotices.Poster(app)
        poster.showIncoming(call(video = false))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        poster.showIncoming(call(video = false))
        assertEquals("the same notice, not another", 1, shadowOf(nm).allNotifications.size)
        poster.hideIncoming(GUID)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun theInCallNoticeHasHangUpAndCountsTheTimeOnceJoined() {
        val calling = FaceTimeNotices.ongoing(app, call(video = false, phase = Phase.RINGING_OUT, outgoing = true))
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(FaceTimeNotices.CHANNEL_ONGOING).importance)
        assertNull("no sound of its own", nm.getNotificationChannel(FaceTimeNotices.CHANNEL_ONGOING).sound)
        assertEquals(FaceTimeNotices.CHANNEL_ONGOING, calling.channelId)
        assertEquals("Calling…", calling.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(calling.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("an ongoing call's", 2, calling.extras.getInt(Notification.EXTRA_CALL_TYPE))
        assertEquals(Notification.CATEGORY_CALL, calling.category)
        assertTrue(calling.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val hangUp = calling.extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)!!
        assertOurs(hangUp, FaceTimeActionReceiver::class.java)
        assertTrue(shadowOf(hangUp).isBroadcastIntent)
        assertEquals(FaceTimeActionReceiver.ACTION_HANG_UP, shadowOf(hangUp).savedIntent.action)
        assertEquals(GUID, shadowOf(hangUp).savedIntent.getStringExtra(FaceTimeActionReceiver.EXTRA_GUID))
        // A tap goes back to the call.
        assertOurs(calling.contentIntent, FaceTimeCallActivity::class.java)
        assertEquals(FaceTimeCallActivity.ACTION_SHOW, shadowOf(calling.contentIntent).savedIntent.action)
        // Joined: the kind of call, and its time counted from when it was joined.
        val active = FaceTimeNotices.ongoing(app, call(video = true, phase = Phase.ACTIVE, activeSince = 1_700_000_050_000))
        assertEquals("FaceTime Video", active.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(active.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(1_700_000_050_000, active.`when`)
        assertEquals("Connecting…", FaceTimeNotices.ongoing(app, call(false, Phase.CONNECTING)).extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // Left while the library was still joining it: the notice stays, and says so, without a clock.
        val ending = FaceTimeNotices.ongoing(app, call(video = true, phase = Phase.ACTIVE, activeSince = 1_700_000_050_000), ending = true)
        assertEquals("Ending…", ending.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(ending.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        // On a locked phone that hides what notices say: that there is a call, not with whom.
        for (n in listOf(calling, active, ending)) {
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals("FaceTime call", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertNull(n.publicVersion.getLargeIcon())
            assertFalse(n.publicVersion.extras.toString().let { it.contains("Alice") || it.contains("5551234567") })
        }
    }

    @Test
    fun aMissedCallsNoticeOpensTheChatAndSaysNoNameOnTheLockScreen() {
        FaceTimeNotices.postMissed(app, "Alice Martin", video = true, conversationId = CHAT, atMs = 1_700_000_000_000, quiet = false)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(FaceTimeNotices.CHANNEL_MISSED, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(FaceTimeNotices.CHANNEL_MISSED).importance)
        assertEquals("Alice Martin", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Missed FaceTime Video", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_MISSED_CALL, n.category)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(1_700_000_000_000, n.`when`)
        // A tap opens the chat (the main process's screen, which never shows over the lock screen).
        assertOurs(n.contentIntent, ThreadActivity::class.java)
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(CHAT, open.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        assertEquals(ChatService.IMESSAGE, RelayIntents.serviceOf(open))
        // On the lock screen: that a call was missed, not from whom.
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("FaceTime", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Missed FaceTime Video", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("it may sound", Notification.GROUP_ALERT_ALL, n.groupAlertBehavior)
        // Another from the same chat takes its place; one from another chat is its own; a quiet one makes no sound.
        FaceTimeNotices.postMissed(app, "Alice Martin", video = false, conversationId = CHAT, atMs = 1_700_000_100_000, quiet = true)
        FaceTimeNotices.postMissed(app, "Bob", video = false, conversationId = "imsg:tel:+15557654321", atMs = 1_700_000_200_000, quiet = false)
        val shown = shadowOf(nm).allNotifications
        assertEquals(2, shown.size)
        val again = shown.single { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Alice Martin" }
        assertEquals("Missed FaceTime Audio", again.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("silenced", Notification.GROUP_ALERT_SUMMARY, again.groupAlertBehavior)
        // Each chat's opens that chat.
        assertTrue(shown.map { shadowOf(it.contentIntent).savedIntent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID) }.toSet().size == 2)
        // Read: its notice goes.
        FaceTimeNotices.cancelMissed(app, CHAT)
        assertEquals("Bob", shadowOf(nm).allNotifications.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun theNoticesButtonsCanOnlyEndACall() {
        val bridge = FakeBridge()
        val notices = FakeNotices()
        val natives = FakeNatives()
        val calls = FaceTimeCalls(
            app, bridge, natives, FakeMain(), notices, FakeForeground(), FakeAudio(), FakeCamera(), FakeRingback(),
            settings = { FaceTimeSettings() }, phoneBusy = { false }, scope = scope,
        )
        FaceTimeCalls.installForTesting(calls)
        bridge.incoming(GUID)
        calls.onEvent(uniffi.rust_bridge.FaceTimeEvent.JoinEvent(GUID, 1uL, ALICE, true))
        waitFor("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        FaceTimeNotices.Poster(app).showIncoming(calls.state.value!!)
        val receiver = FaceTimeActionReceiver()
        // What is no call's id does nothing; nor does an action it does not know.
        receiver.onReceive(app, FaceTimeActionReceiver.intent(app, FaceTimeActionReceiver.ACTION_DECLINE, "not-a-call"))
        receiver.onReceive(app, Intent(app, FaceTimeActionReceiver::class.java).setAction(FaceTimeActionReceiver.ACTION_DECLINE))
        receiver.onReceive(app, FaceTimeActionReceiver.intent(app, "com.lightdecoder.facetime.action.ANSWER", GUID))
        Thread.sleep(100)
        assertEquals(Phase.RINGING_IN, calls.state.value?.phase)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertTrue("nothing a broadcast does joins a call", natives.made.isEmpty())
        // Decline: declined, and the notice is down at once.
        receiver.onReceive(app, FaceTimeActionReceiver.intent(app, FaceTimeActionReceiver.ACTION_DECLINE, GUID))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        waitFor("the decline") { calls.state.value == null && bridge.log.contains("declineFacetime:$GUID") }
        assertTrue(natives.made.isEmpty())
    }

    @Test
    fun hangUpFromTheNoticeEndsTheCall() {
        val bridge = FakeBridge()
        val natives = FakeNatives()
        val calls = FaceTimeCalls(
            app, bridge, natives, FakeMain(), FakeNotices(), FakeForeground(), FakeAudio(), FakeCamera(), FakeRingback(),
            settings = { FaceTimeSettings() }, phoneBusy = { false }, scope = scope,
        )
        FaceTimeCalls.installForTesting(calls)
        calls.place(ALICE, "Alice", video = false, from = null, conversationId = null)
        waitFor("the placed call") { calls.state.value?.phase == Phase.RINGING_OUT && bridge.log.toList().any { it.startsWith("createFacetime") } }
        val guid = calls.state.value!!.guid
        // Some other call's Hang up is not this one's.
        FaceTimeActionReceiver().onReceive(app, FaceTimeActionReceiver.intent(app, FaceTimeActionReceiver.ACTION_HANG_UP, GUID))
        Thread.sleep(100)
        assertEquals(Phase.RINGING_OUT, calls.state.value?.phase)
        FaceTimeActionReceiver().onReceive(app, FaceTimeActionReceiver.intent(app, FaceTimeActionReceiver.ACTION_HANG_UP, guid))
        waitFor("the call taken back") { calls.state.value == null && bridge.log.contains("cancelFacetime:$guid") }
    }

    @Test
    fun withoutACallEngineTheButtonsDoNothing() {
        FaceTimeCalls.installForTesting(null)
        FaceTimeNotices.Poster(app).showIncoming(call(video = false))
        FaceTimeActionReceiver().onReceive(app, FaceTimeActionReceiver.intent(app, FaceTimeActionReceiver.ACTION_DECLINE, GUID))
        assertTrue("the notice of a call that is no longer known still goes", shadowOf(nm).allNotifications.isEmpty())
        assertNull("and no call engine was made for it", FaceTimeCalls.peek())
        FaceTimeActionReceiver().onReceive(app, FaceTimeActionReceiver.intent(app, FaceTimeActionReceiver.ACTION_HANG_UP, GUID))
        assertNull(FaceTimeCalls.peek())
    }

    @Test
    fun theInCallServicesKindsFollowThePermissions() {
        assertEquals("neither permission: neither kind", 0, FaceTimeCallService.kinds(app))
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, FaceTimeCallService.kinds(app))
        shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        assertEquals(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            FaceTimeCallService.kinds(app),
        )
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(5)
        }
        org.junit.Assert.fail("timed out waiting for $what")
    }

    private companion object {
        const val GUID = "BBBBBBBB-0000-4000-8000-000000000002"
        const val CHAT = "imsg:tel:+15551234567"
    }
}
