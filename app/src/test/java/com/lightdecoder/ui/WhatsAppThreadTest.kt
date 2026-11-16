package com.lightdecoder.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.chat.ContactsAdapter
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.NewConversationActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.TimeFormat
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.voice.MessageReader
import com.lightdecoder.ui.voice.VoicePrefs
import com.lightdecoder.ui.wa.WaSetupActivity
import com.lightdecoder.ui.wa.WaStatusActivity
import com.lightdecoder.wa.WaErrors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * A WhatsApp conversation on the thread screen (and the screens around it):
 * it reads WhatsApp's archive and acts through WhatsApp's link, says who is
 * online under its title, offers only what WhatsApp can do, and an intent
 * that names a service opens that service's conversation.
 */
@RunWith(AndroidJUnit4::class)
class WhatsAppThreadTest {

    private lateinit var two: TwoServicesEnv
    private val messages = ChatService.MESSAGES
    private val whatsApp = ChatService.WHATSAPP
    private val carol = "15550100@s.whatsapp.net"

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        ThreadActivity.readerUnavailableShown = false
    }

    @After
    fun tearDown() {
        MessageReader.factory = null
        ThreadActivity.readerUnavailableShown = false
        VoicePrefs.setReadNewAloud(two.app, false)
        two.tearDown()
    }

    // --- presence -----------------------------------------------------------------------------

    @Test
    fun aWhatsAppThreadSaysWhoIsOnlineUnderItsTitle() {
        two.env.pairedManager()
        two.seedConversation(whatsApp, carol, "Carol", number = "+1 555-0100")
        two.seedIncoming(whatsApp, "w1", carol, "Hello from WhatsApp")
        val controller = open(carol, whatsApp)
        try {
            val activity = controller.get()
            two.waitUntil("WhatsApp's messages") { shownIds(activity) == listOf("w1") }
            assertEquals("Carol", activity.supportActionBar?.title?.toString())
            // In front: WhatsApp is told, and asked about the other person.
            assertEquals(listOf(true), two.wa.visibility.toList())
            assertEquals(setOf(carol), two.wa.watched.toSet())
            assertEquals(carol, MessageNotifier.visibleConversationId(whatsApp))
            assertNull("not a Google Messages conversation", MessageNotifier.visibleConversationId(messages))
            // Nothing known about her yet: her number, as for any conversation.
            assertEquals("+1 555-0100", subtitle(activity))

            two.wa.presenceFlow.value = mapOf(carol to Presence(online = true, lastSeenMs = 0))
            two.waitUntil("online") { subtitle(activity) == activity.getString(R.string.chat_presence_online) }

            two.wa.typingFlow.value = mapOf(carol to setOf("Carol"))
            two.waitUntil("typing") { subtitle(activity) == activity.getString(R.string.chat_presence_typing) }
            assertEquals("said once, under the title", View.GONE, activity.findViewById<View>(R.id.typingText).visibility)

            val lastSeen = System.currentTimeMillis() - 60_000
            two.wa.typingFlow.value = emptyMap()
            two.wa.presenceFlow.value = mapOf(carol to Presence(online = false, lastSeenMs = lastSeen))
            two.waitUntil("last seen") { subtitle(activity) == TimeFormat.lastSeen(activity, lastSeen) }
            assertTrue(subtitle(activity)!!.startsWith("last seen "))

            // Offline but WhatsApp did not say since when: nothing to claim.
            two.wa.presenceFlow.value = mapOf(carol to Presence(online = false, lastSeenMs = 0))
            two.waitUntil("her number again") { subtitle(activity) == "+1 555-0100" }

            // What a link that is not connected last said may be stale: it is not shown ...
            two.wa.presenceFlow.value = mapOf(carol to Presence(online = true, lastSeenMs = 0))
            two.waitUntil("online again") { subtitle(activity) == activity.getString(R.string.chat_presence_online) }
            two.wa.stateFlow.value = RelayState(RelayState.Phase.DISCONNECTED)
            two.waitUntil("not claimed while offline") { subtitle(activity) == "+1 555-0100" }
            // ... and once connected again WhatsApp is asked again.
            val asked = two.wa.watched.size
            two.wa.stateFlow.value = RelayState(RelayState.Phase.CONNECTED)
            two.waitUntil("asked again") { two.wa.watched.size > asked }
            two.waitUntil("online once more") { subtitle(activity) == activity.getString(R.string.chat_presence_online) }

            // Out of sight: WhatsApp no longer shows this account as online.
            controller.pause().stop()
            assertEquals(listOf(true, false), two.wa.visibility.toList())
            assertNull(MessageNotifier.visibleConversationId(whatsApp))
            controller.start().resume()
            assertEquals(listOf(true, false, true), two.wa.visibility.toList())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aWhatsAppGroupSaysWhoIsTypingAboveTheComposer() {
        two.env.pairedManager()
        val group = "120363000000000000@g.us"
        two.waRepo.upsertConversation(
            ConversationDto(
                id = group, name = "Hiking", isGroup = true, folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = carol, name = "Carol"),
                    ParticipantDto(id = "15550101@s.whatsapp.net", name = "Dora"),
                ),
            )
        )
        val controller = open(group, whatsApp)
        try {
            val activity = controller.get()
            val people = activity.resources.getQuantityString(R.plurals.chat_people, 3, 3)
            two.waitUntil("the group's line") { subtitle(activity) == people }
            two.wa.presenceFlow.value = mapOf(carol to Presence(online = true, lastSeenMs = 0))
            two.wa.typingFlow.value = mapOf(group to setOf("Dora"))
            two.waitUntil("who is typing") { activity.findViewById<View>(R.id.typingText).isShown }
            assertEquals(activity.getString(R.string.chat_typing_one, "Dora"), activity.findViewById<TextView>(R.id.typingText).text.toString())
            assertEquals("a group has no one person to be online", people, subtitle(activity))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- the right service ----------------------------------------------------------------------

    @Test
    fun anIntentOpensTheConversationOfTheServiceItNames() {
        val (manager, fake) = two.env.pairedManager()
        fake.goOnline()
        two.env.eventually(manager, what = "Messages online") { manager.state.value.isOnline }
        // The same id in both services: two different conversations.
        two.seedConversation(messages, "c1", "Alice by text")
        two.seedIncoming(messages, "m1", "c1", "From Google Messages")
        two.seedConversation(whatsApp, "c1", "Carol on WhatsApp")
        two.seedIncoming(whatsApp, "w1", "c1", "From WhatsApp")

        // The intent a WhatsApp notification carries.
        val controller = open("c1", whatsApp)
        try {
            val activity = controller.get()
            two.waitUntil("WhatsApp's conversation") { shownIds(activity) == listOf("w1") }
            assertEquals("Carol on WhatsApp", activity.supportActionBar?.title?.toString())
            assertEquals("c1", MessageNotifier.visibleConversationId(whatsApp))
            assertNull(MessageNotifier.visibleConversationId(messages))

            // A message for the same id in the other archive is not this conversation's.
            two.seedIncoming(messages, "m2", "c1", "Also from Google Messages", ts = 3_000)
            two.settle()
            assertEquals(listOf("w1"), shownIds(activity))

            val input = activity.findViewById<EditText>(R.id.input)
            assertEquals(activity.getString(R.string.chat_wa_compose_hint), input.hint.toString())
            input.setText("half a reply for Carol")

            // Then the Google Messages notification for its "c1" is tapped (no service named: Google Messages).
            controller.newIntent(RelayIntents.thread(two.app, "c1").setFlags(0))
            assertEquals("what was typed for Carol goes nowhere", "", input.text.toString())
            two.waitUntil("Google Messages' conversation") { shownIds(activity) == listOf("m1", "m2") }
            assertEquals("Alice by text", activity.supportActionBar?.title?.toString())
            assertEquals(activity.getString(R.string.chat_compose_hint), input.hint.toString())
            assertEquals("WhatsApp's screen is gone", listOf(true, false), two.wa.visibility.toList())
            assertEquals("c1", MessageNotifier.visibleConversationId(messages))
            assertNull(MessageNotifier.visibleConversationId(whatsApp))

            // What is sent now goes through Google Messages, not WhatsApp.
            input.setText("for Alice")
            activity.findViewById<View>(R.id.send).performClick()
            two.waitUntil("sent through Google Messages") { fake.sent.any { it.text == "for Alice" && it.conversationId == "c1" } }
            assertTrue("nothing through WhatsApp: ${two.wa.calls}", two.wa.texts.isEmpty())

            // And back, by WhatsApp's notification.
            controller.newIntent(RelayIntents.thread(two.app, "c1", service = whatsApp).setFlags(0))
            two.waitUntil("WhatsApp's conversation again") { shownIds(activity) == listOf("w1") }
            assertEquals("Carol on WhatsApp", activity.supportActionBar?.title?.toString())
            input.setText("for Carol")
            activity.findViewById<View>(R.id.send).performClick()
            two.waitUntil("sent through WhatsApp") { two.wa.texts.toList() == listOf("c1" to "for Carol") }
            assertEquals(1, fake.sent.size)

            // Up leads to the conversation list, on this conversation's service.
            activity.onOptionsItemSelected(RoboMenuItem(android.R.id.home))
            val up = shadowOf(activity).nextStartedActivity
            assertEquals(ConversationsActivity::class.java.name, up.component?.className)
            assertEquals(whatsApp, RelayIntents.serviceOf(up))
            assertTrue(up.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aGoogleMessagesThreadIsLeftAsItWas() {
        val (manager, fake) = two.env.pairedManager()
        fake.goOnline()
        two.env.eventually(manager, what = "Messages online") { manager.state.value.isOnline }
        two.seedConversation(messages, "c1", "Alice", number = "+1 555-0199")
        two.seedIncoming(messages, "m1", "c1", "Hi")
        // WhatsApp knows a person by the same id; that is nothing to Google Messages.
        two.wa.presenceFlow.value = mapOf("c1" to Presence(online = true, lastSeenMs = 0), "p1" to Presence(online = true, lastSeenMs = 0))
        val controller = open("c1", messages)
        try {
            val activity = controller.get()
            two.waitUntil("the conversation") { shownIds(activity) == listOf("m1") }
            two.settle()
            assertEquals("+1 555-0199", subtitle(activity))
            assertTrue("WhatsApp was not told a thing: ${two.wa.visibility} ${two.wa.watched}", two.wa.visibility.isEmpty() && two.wa.watched.isEmpty())
            assertEquals("c1", MessageNotifier.visibleConversationId(messages))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- only what the service can do -----------------------------------------------------------

    @Test
    fun deletingAMessageOffersForEveryoneOnlyForOnesOwn() {
        two.env.pairedManager()
        two.seedConversation(whatsApp, carol, "Carol")
        two.seedIncoming(whatsApp, "w1", carol, "Hers")
        two.seedOutgoing(whatsApp, "w2", carol, "Mine")
        val controller = open(carol, whatsApp)
        try {
            val activity = controller.get()
            two.waitUntil("both messages") { shownIds(activity) == listOf("w1", "w2") }
            val hers = two.waRepo.message("w1")!!
            val mine = two.waRepo.message("w2")!!

            // One's own message: for everyone, or only for oneself.
            var question = askToDelete(activity, mine)
            assertTrue(two.hasText(question.window!!.decorView, activity.getString(R.string.chat_delete_message_choice)))
            assertEquals(activity.getString(R.string.chat_delete_for_everyone), question.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            val forMe = question.getButton(AlertDialog.BUTTON_NEUTRAL)
            assertEquals(activity.getString(R.string.chat_delete_for_me), forMe.text.toString())
            forMe.performClick()
            two.waitUntil("deleted for me") { "deleteMessage:w2" in two.wa.calls }

            // Someone else's: only for oneself.
            question = askToDelete(activity, hers)
            assertTrue(two.hasText(question.window!!.decorView, activity.getString(R.string.chat_delete_message_for_me_question)))
            assertEquals(activity.getString(R.string.chat_delete_for_me), question.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertFalse("no \"for everyone\"", question.getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
            question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            two.waitUntil("hers deleted for me") { "deleteMessage:w1" in two.wa.calls }

            // Without the link there is nobody to delete it for but this device.
            two.wa.paired = false
            question = askToDelete(activity, mine)
            assertTrue(two.hasText(question.window!!.decorView, activity.getString(R.string.chat_delete_message_question_local)))
            assertEquals(activity.getString(R.string.chat_action_delete), question.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertFalse(question.getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
            question.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aServiceIsNotAskedForWhatItCannotDo() {
        two.env.pairedManager()
        two.seedConversation(whatsApp, carol, "Carol")
        two.waRepo.upsertMessage(
            MessageDto(
                id = "w1", conversationId = carol, senderId = carol, senderName = "Carol", timestampMs = 1_000, isOld = true,
                parts = listOf(PartDto(partId = "media", kind = "media", mime = "image/jpeg", mediaId = "wa1.token", size = 10)),
            )
        )
        assertFalse(Capability.FULL_SIZE_REQUEST in two.wa.capabilities)
        val controller = open(carol, whatsApp)
        try {
            val activity = controller.get()
            two.waitUntil("the picture message") { shownIds(activity) == listOf("w1") }
            // The list asks for the original of any picture that looks like a thumbnail; WhatsApp has no such request.
            val row = two.waRepo.message("w1")!!
            activity.onLoadFullSize(row, row.media.single())
            two.settle()
            assertTrue("${two.wa.calls}", two.wa.calls.none { it.startsWith("requestFullSize") })

            val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
            activity.onPrepareOptionsMenu(menu)
            assertFalse(menu.findItem(R.id.action_not_spam).isVisible)
            assertFalse(menu.findItem(R.id.action_restore).isVisible)
            assertTrue("archiving is for every service", menu.findItem(R.id.action_archive).isVisible)

            // "In use elsewhere" has a "Use here" only where the service has one; otherwise the details.
            two.wa.stateFlow.value = RelayState(RelayState.Phase.ACTIVE_ELSEWHERE)
            two.waitUntil("the banner") { activity.findViewById<View>(R.id.bannerAction).isShown }
            assertEquals(activity.getString(R.string.link_fix_status), activity.findViewById<TextView>(R.id.bannerAction).text.toString())
            activity.findViewById<View>(R.id.bannerAction).performClick()
            assertEquals(WaStatusActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            assertFalse("useHere" in two.wa.calls)

            // The link's settings are WhatsApp's.
            activity.onOptionsItemSelected(RoboMenuItem(R.id.action_link_status))
            assertEquals(WaStatusActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun anErrorIsExplainedInTheServicesOwnWords() {
        two.env.pairedManager()
        two.seedConversation(whatsApp, carol, "Carol")
        two.seedIncoming(whatsApp, "w1", carol, "Hello")
        val controller = open(carol, whatsApp)
        try {
            val activity = controller.get()
            two.waitUntil("the message") { shownIds(activity) == listOf("w1") }
            two.wa.failure = RelayException(WaErrors.NOT_ALLOWED, "refused")
            ShadowToast.reset()
            activity.onReactionTap(two.waRepo.message("w1")!!, ReactionDto(emoji = "👍"))
            two.waitUntil("the explanation") { ShadowToast.getTextOfLatestToast() != null }
            assertEquals(activity.getString(R.string.chat_wa_error_not_allowed), ShadowToast.getTextOfLatestToast())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- voice ----------------------------------------------------------------------------------

    @Test
    fun newWhatsAppMessagesAreReadAloudLikeAnyOthers() {
        val reader = FakeReader()
        MessageReader.factory = { _, listener -> reader.also { it.listener = listener } }
        VoicePrefs.setReadNewAloud(two.app, true)
        two.env.pairedManager()
        two.seedConversation(whatsApp, "c1", "Carol")
        two.seedConversation(messages, "c1", "Alice")
        two.seedIncoming(whatsApp, "w1", "c1", "Already here")
        val controller = open("c1", whatsApp)
        try {
            val activity = controller.get()
            two.waitUntil("the thread") { shownIds(activity) == listOf("w1") }
            two.settle()
            assertTrue("nothing already there is read: ${reader.spoken}", reader.spoken.isEmpty())

            val now = System.currentTimeMillis()
            // Google Messages' "c1" gets a message too: not this conversation, not read out.
            two.env.repo.upsertMessage(MessageDto(id = "m9", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = now, text = "Wrong thread"))
            two.waRepo.upsertMessage(MessageDto(id = "w2", conversationId = "c1", senderId = "c1", senderName = "Carol", timestampMs = now, text = "Are you there?"))
            two.waitUntil("the new message is read") { reader.spoken.isNotEmpty() }
            two.settle()
            assertEquals(listOf("Carol said: Are you there?" to false), reader.spoken.toList())

            // And on request, from the long-press sheet.
            reader.spoken.clear()
            activity.onMessageLongPress(two.waRepo.message("w1")!!)
            val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
            sheet.findViewById<View>(R.id.actionReadAloud)!!.performClick()
            assertEquals(listOf("Someone said: Already here" to true), reader.spoken.toList())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- starting a conversation ----------------------------------------------------------------

    @Test
    fun aNewWhatsAppConversationIsWithOnePersonAndOpensInWhatsApp() {
        two.env.pairedManager()
        two.waRepo.upsertContacts(
            listOf(
                ContactDto(id = "k1", name = "Dora", number = "+15550101", formattedNumber = "+1 555-0101"),
                ContactDto(id = "k2", name = "Eve", number = "+15550102", formattedNumber = "+1 555-0102"),
            )
        )
        // Google Messages' contacts are another list.
        two.env.repo.upsertContacts(listOf(ContactDto(id = "g1", name = "Gus", number = "+15550109", formattedNumber = "+1 555-0109")))
        two.wa.startedConversationId = "15550102@s.whatsapp.net"

        val intent = Intent(two.app, NewConversationActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, whatsApp.key)
        ActivityScenario.launch<NewConversationActivity>(intent).use { scenario ->
            two.waitUntil("WhatsApp's contacts") {
                var names = emptyList<String>()
                scenario.onActivity { names = contacts(it).map { c -> c.name } }
                names == listOf("Dora", "Eve")
            }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.contacts)
                list.getChildAt(0).performClick()
                list.getChildAt(1).performClick()
            }
            two.settle()
            scenario.onActivity { activity ->
                // No groups from here: the second pick replaced the first.
                assertEquals(listOf("Eve"), contacts(activity).filter { it.selected }.map { it.name })
                val start = activity.findViewById<TextView>(R.id.start)
                assertEquals(activity.getString(R.string.chat_start), start.text.toString())
                start.performClick()
            }
            two.waitUntil("started through WhatsApp") { "startConversation:+15550102:" in two.wa.calls }
            two.waitUntil("its thread opens") {
                var next: Intent? = null
                scenario.onActivity { next = shadowOf(it).peekNextStartedActivity() }
                next != null
            }
            scenario.onActivity { activity ->
                val thread = shadowOf(activity).nextStartedActivity
                assertEquals(ThreadActivity::class.java.name, thread.component?.className)
                assertEquals("15550102@s.whatsapp.net", thread.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
                assertEquals(whatsApp, RelayIntents.serviceOf(thread))
            }
        }
    }

    // --- the home screen ------------------------------------------------------------------------

    @Test
    fun theHomeScreenHasBothLinks() {
        two.env.manager()
        two.seedConversation(whatsApp, carol, "Carol", unread = true)
        two.seedConversation(whatsApp, "15550103@s.whatsapp.net", "Frank", unread = true)
        ActivityScenario.launch(InboxActivity::class.java).use { scenario ->
            two.waitUntil("both blocks") {
                var ok = false
                scenario.onActivity { activity ->
                    ok = text(activity, R.id.relayBody) == activity.getString(R.string.link_card_not_set_up) &&
                        text(activity, R.id.waBody) == activity.getString(R.string.link_card_connected_unread, 2)
                }
                ok
            }
            scenario.onActivity { activity ->
                // Google Messages is not set up; WhatsApp is connected.
                assertEquals(activity.getString(R.string.link_card_set_up), text(activity, R.id.relayAction))
                assertFalse(activity.findViewById<View>(R.id.relaySettings).isShown)
                assertEquals(activity.getString(R.string.chat_wa_card_title), text(activity, R.id.waTitle))
                assertEquals(activity.getString(R.string.chat_wa_card_open), text(activity, R.id.waAction))
                assertTrue(activity.findViewById<View>(R.id.waSettings).isShown)

                activity.findViewById<View>(R.id.relayAction).performClick()
                assertEquals(RelaySetupActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)

                activity.findViewById<View>(R.id.waAction).performClick()
                val list = shadowOf(activity).nextStartedActivity
                assertEquals(ConversationsActivity::class.java.name, list.component?.className)
                assertEquals("WhatsApp's tab", whatsApp, RelayIntents.serviceOf(list))

                activity.findViewById<View>(R.id.waSettings).performClick()
                assertEquals(WaStatusActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            }
            // WhatsApp unlinked on the phone: its block says so and leads to linking again.
            two.wa.paired = false
            two.wa.stateFlow.value = RelayState(RelayState.Phase.LOGGED_OUT, RelayException.LOGGED_OUT)
            two.waitUntil("the reason") {
                var body = ""
                scenario.onActivity { body = text(it, R.id.waBody) }
                body.contains(two.app.getString(R.string.chat_wa_error_logged_out))
            }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.link_card_fix), text(activity, R.id.waAction))
                activity.findViewById<View>(R.id.waAction).performClick()
                assertEquals(WaSetupActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private class FakeReader : MessageReader {
        var listener: MessageReader.Listener? = null
        val spoken = mutableListOf<Pair<String, Boolean>>()
        private var speaking = false

        override val isAvailable get() = true
        override val isSpeaking get() = speaking

        override fun speak(text: String, interrupt: Boolean) {
            spoken += text to interrupt
            setSpeaking(true)
        }

        override fun stop() = setSpeaking(false)

        override fun shutdown() = setSpeaking(false)

        private fun setSpeaking(on: Boolean) {
            if (speaking == on) return
            speaking = on
            listener?.onSpeakingChanged(on)
        }
    }

    private fun open(conversationId: String, service: ChatService): ActivityController<ThreadActivity> {
        val intent = RelayIntents.thread(two.app, conversationId, service = service).setFlags(0)
        val controller = Robolectric.buildActivity(ThreadActivity::class.java, intent).setup()
        two.settle()
        return controller
    }

    /** The long-press sheet's Delete for [row], and the question it asks. */
    private fun askToDelete(activity: ThreadActivity, row: MessageRow): AlertDialog {
        activity.onMessageLongPress(row)
        (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.actionDelete)!!.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        return dialog
    }

    private fun shownIds(activity: ThreadActivity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList
            .filterIsInstance<ThreadItem.Message>().map { it.row.id }

    private fun subtitle(activity: ThreadActivity): String? = activity.supportActionBar?.subtitle?.toString()

    private fun contacts(activity: NewConversationActivity) =
        (activity.findViewById<RecyclerView>(R.id.contacts).adapter as ContactsAdapter).currentList

    private fun text(activity: Activity, id: Int): String = activity.findViewById<TextView>(id).text.toString()
}
