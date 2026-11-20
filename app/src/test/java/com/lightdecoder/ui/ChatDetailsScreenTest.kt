package com.lightdecoder.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.materialswitch.MaterialSwitch
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.Handles
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ChatDetailsActivity
import com.lightdecoder.ui.chat.FakeChatBackend
import com.lightdecoder.ui.chat.NewConversationActivity
import com.lightdecoder.ui.chat.ScreenBackends
import com.lightdecoder.ui.chat.ThreadActivity
import java.io.File
import java.util.Collections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * A chat's details, as an iPhone's: opened from the name at the top of an iMessage chat; a group
 * renamed, someone not on iMessage refused as a member, the group left (then read only), Hide Alerts
 * (also on the person's iMessage chat inside a Messages conversation) and a chat's own Send Read Receipts.
 */
@RunWith(AndroidJUnit4::class)
class ChatDetailsScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        IMessageManager.installForTesting(two.app, imRepo)
        IMessagePrefs.setEnabled(two.app, true)
        DirectPrefs.setPhoneStyleOn(two.app, true)
        for (file in listOf("imessage_group_versions", "imessage_read_receipts", "imessage_group_names")) {
            two.app.getSharedPreferences(file, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun tearDown() {
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        two.tearDown()
        imDb.close()
    }

    /** The iMessage library set up, answering as Apple would: everyone is on iMessage but [notOnIMessage]. */
    private fun directMode(notOnIMessage: Set<String> = emptySet()) {
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            when (what) {
                DirectWire.REACH -> {
                    val handle = Handles.normalize(request.getString("recipient"))!!
                    if (handle in notOnIMessage) JSONObject() else JSONObject().put(
                        "conversation",
                        ConversationDto(
                            id = "imsg:$handle", type = "IMESSAGE", defaultOutgoingId = ME,
                            participants = listOf(ParticipantDto(id = handle, number = Handles.display(handle)), ParticipantDto(id = ME, isMe = true)),
                        ).toJson(),
                    )
                }
                DirectWire.RENAME, DirectWire.MEMBERS, DirectWire.GROUP_PHOTO -> DirectWire.guid("GG-${asked.size}")
                DirectWire.SEND -> DirectWire.sent("S-${asked.size}", System.currentTimeMillis())
                else -> DirectWire.ok()
            }
        }
    }

    private fun person(number: String, name: String) = ParticipantDto(id = number, name = name, number = number, formattedNumber = number)

    /** "Saturday hike": the user, Alice, Ben and Cleo, with a message from Alice. */
    private fun group() {
        imRepo.upsertConversation(
            ConversationDto(
                id = GROUP, name = "Saturday hike", isGroup = true, type = IMessageManager.TRANSPORT, defaultOutgoingId = ME,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    person("+15551234567", "Alice Martin"), person("+15557654321", "Ben Ortiz"), person("+15553334444", "Cleo Park"),
                ),
            ),
        )
        imRepo.upsertMessage(
            MessageDto(
                id = "imsg:G1", conversationId = GROUP, senderId = "+15551234567", senderName = "Alice Martin",
                timestampMs = System.currentTimeMillis() - 60_000, direction = "in", status = "INCOMING_COMPLETE",
                transport = IMessageManager.TRANSPORT, text = "Who is driving?", parts = listOf(PartDto("0", "text", "Who is driving?")), isOld = true,
            ),
        )
    }

    /** A chat with Alice alone. */
    private fun aliceChat() {
        imRepo.upsertConversation(
            ConversationDto(
                id = ALICE, name = "Alice Martin", type = IMessageManager.TRANSPORT, defaultOutgoingId = ME,
                participants = listOf(ParticipantDto(id = "me", isMe = true), person("+15551234567", "Alice Martin")),
            ),
        )
    }

    private fun details(chat: String, service: ChatService = ChatService.IMESSAGE, companion: String? = null): ActivityScenario<ChatDetailsActivity> =
        ActivityScenario.launch(ChatDetailsActivity.intent(two.app, chat, service, companion))

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun memberNames(activity: Activity): List<String> {
        val list = activity.findViewById<android.view.ViewGroup>(R.id.members)
        return (0 until list.childCount).map { list.getChildAt(it).findViewById<TextView>(R.id.name).text.toString() }
    }

    // --- opened from the chat ---------------------------------------------------------------------

    @Test
    fun tappingTheNameAtTheTopOfAnIMessageGroupOpensItsDetails() {
        directMode()
        group()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, GROUP, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("the iPhone-style top") { it.phoneStyle && it.findViewById<View>(R.id.phoneHeader).isShown }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.phoneHeader).performClick())
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(ChatDetailsActivity::class.java.name, started.component!!.className)
                assertEquals(GROUP, started.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
                assertEquals(ChatService.IMESSAGE, RelayIntents.serviceOf(started))
                // Said as what it does.
                assertTrue(activity.findViewById<View>(R.id.phoneHeader).contentDescription.toString().endsWith("details"))
            }
        }
        // Not iPhone-style: the name in the bar opens them all the same.
        DirectPrefs.setPhoneStyleOn(two.app, false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, GROUP, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("the chat") { !it.phoneStyle && it.supportActionBar?.title?.toString() == "Saturday hike" }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolbar).performClick()
                assertEquals(ChatDetailsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
            }
        }
    }

    @Test
    fun aMessagesConversationWithoutAnIMessageChatKeepsItsMenu() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Dana")
        two.seedIncoming(ChatService.MESSAGES, "m1", "c1", "hello")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1", service = ChatService.MESSAGES).setFlags(0)).use { scenario ->
            scenario.waitFor("the chat") { it.supportActionBar?.title?.toString() == "Dana" }
            scenario.onActivity { activity ->
                assertFalse(activity.openDetails())
                activity.findViewById<View>(R.id.toolbar).performClick()
                assertNull(shadowOf(activity).nextStartedActivity)
            }
        }
    }

    // --- a group --------------------------------------------------------------------------------------

    @Test
    fun aGroupsDetailsListItsMembersAndItIsRenamedFromThem() {
        directMode()
        group()
        details(GROUP).use { scenario ->
            scenario.waitFor("the group") { it.details != null }
            scenario.onActivity { activity ->
                assertEquals("Saturday hike", activity.findViewById<TextView>(R.id.name).text.toString())
                assertEquals("4 people", activity.findViewById<TextView>(R.id.membersHeader).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.subtitle).visibility)
                assertEquals(listOf("Alice Martin", "Ben Ortiz", "Cleo Park"), memberNames(activity))
                for (id in listOf(R.id.changeNamePhoto, R.id.addMember, R.id.leave, R.id.hideAlerts)) assertTrue(activity.findViewById<View>(id).isShown)
                // A group has no read receipts of its own.
                assertEquals(View.GONE, activity.findViewById<View>(R.id.readReceipts).visibility)
                // Four people: anyone can be removed (iPhone's rule).
                val first = activity.findViewById<android.view.ViewGroup>(R.id.members).getChildAt(0)
                assertEquals(View.VISIBLE, first.findViewById<View>(R.id.remove).visibility)
                assertEquals("Remove Alice Martin", first.findViewById<View>(R.id.remove).contentDescription.toString())
                activity.findViewById<View>(R.id.changeNamePhoto).performClick()
            }
            scenario.onActivity { activity ->
                val dialog = activity.dialog as AlertDialog
                val input = dialog.findViewById<EditText>(R.id.nameInput)!!
                // Its own name to start from.
                assertEquals("Saturday hike", input.text.toString())
                input.setText("Ski trip")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("the new name") { it.findViewById<TextView>(R.id.name).text.toString() == "Ski trip" }
        }
        val rename = DirectWire.RenameRequest.fromJson(asked.last { it.first == DirectWire.RENAME }.second)
        assertEquals("Ski trip", rename.name)
        assertEquals("Ski trip", imRepo.conversation(GROUP)!!.name)
        assertTrue(imRepo.messages(GROUP).any { it.text == "You named the conversation “Ski trip”." })
    }

    @Test
    fun someoneNotOnIMessageIsNotAddedAndTheScreenSaysWhy() {
        directMode(notOnIMessage = setOf("tel:+15557778888"))
        group()
        val add: Intent = details(GROUP).use { scenario ->
            scenario.waitFor("the group") { it.details != null }
            var started: Intent? = null
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.addMember).performClick()
                started = shadowOf(activity).nextStartedActivity
            }
            started!!
        }
        assertEquals(NewConversationActivity::class.java.name, add.component!!.className)
        assertEquals(GROUP, add.getStringExtra(NewConversationActivity.EXTRA_ADD_TO))
        ActivityScenario.launch<NewConversationActivity>(add).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.chat_details_add_member), activity.supportActionBar?.title?.toString())
                activity.findViewById<EditText>(R.id.query).setText("+1 555 777 8888")
            }
            two.settle()
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.useNumber).performClick()
                val start = activity.findViewById<TextView>(R.id.start)
                assertEquals(activity.getString(R.string.chat_details_add), start.text.toString())
                start.performClick()
            }
            scenario.waitFor("the refusal") { ShadowToast.getTextOfLatestToast() == "+15557778888 is not on iMessage, so they can't be added." }
            scenario.onActivity { activity ->
                assertFalse("still open, to pick someone else", activity.isFinishing)
                assertTrue(activity.findViewById<View>(R.id.start).isEnabled)
            }
        }
        assertTrue(asked.none { it.first == DirectWire.MEMBERS })
        assertEquals(setOf("+15551234567", "+15557654321", "+15553334444"), imRepo.participants(GROUP).filter { !it.isMe }.map { it.number }.toSet())
    }

    @Test
    fun leavingAfterConfirmingMakesTheChatReadOnly() {
        directMode()
        group()
        details(GROUP).use { scenario ->
            scenario.waitFor("the group") { it.details != null }
            scenario.onActivity { it.findViewById<View>(R.id.leave).performClick() }
            scenario.onActivity { activity ->
                val dialog = activity.dialog as AlertDialog
                assertEquals(activity.getString(R.string.chat_details_leave_confirm), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                // Not before the user said so.
                assertTrue(asked.none { it.first == DirectWire.MEMBERS })
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("the group left") { it.findViewById<View>(R.id.leftNote).isShown }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.leave).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.addMember).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.changeNamePhoto).visibility)
            }
        }
        val leave = DirectWire.MembersRequest.fromJson(asked.last { it.first == DirectWire.MEMBERS }.second)
        assertFalse(ME in leave.participants)
        assertTrue(imRepo.conversation(GROUP)!!.readOnly)
        // The chat says so where the composer was, as an iPhone does.
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, GROUP, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("the read-only chat") { it.findViewById<View>(R.id.readOnlyText).isShown }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.imessage_not_in_group), activity.findViewById<TextView>(R.id.readOnlyText).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.composer).visibility)
            }
        }
    }

    // --- switches ---------------------------------------------------------------------------------------

    @Test
    fun hideAlertsInAMessagesConversationAlsoHidesItsIMessageChatsAlerts() {
        directMode()
        aliceChat()
        val messages = FakeChatBackend(ChatService.MESSAGES, two.env.repo)
        ScreenBackends.forTesting = { service ->
            when (service) {
                ChatService.MESSAGES -> messages
                ChatService.WHATSAPP -> two.wa
                else -> null
            }
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice Martin", number = "+15551234567")
        details("c1", ChatService.MESSAGES, companion = ALICE).use { scenario ->
            scenario.waitFor("the conversation") { it.details != null }
            scenario.onActivity { activity ->
                val hide = activity.findViewById<MaterialSwitch>(R.id.hideAlerts)
                assertFalse(hide.isChecked)
                // Her iMessage chat goes through the library: its read receipts are this conversation's.
                assertTrue(activity.findViewById<View>(R.id.readReceipts).isShown)
                hide.performClick()
            }
            two.waitUntil("both muted") { "setMuted:c1:true" in messages.calls && imRepo.conversation(ALICE)?.muted == true }
        }
    }

    @Test
    fun sendReadReceiptsIsTheChatsOwnChoiceAndStays() {
        directMode()
        aliceChat()
        details(ALICE).use { scenario ->
            scenario.waitFor("the chat") { it.details != null }
            scenario.onActivity { activity ->
                assertEquals("Alice Martin", activity.findViewById<TextView>(R.id.name).text.toString())
                assertEquals("+15551234567", activity.findViewById<TextView>(R.id.subtitle).text.toString())
                // No members to list, nothing to rename.
                assertEquals(View.GONE, activity.findViewById<View>(R.id.membersCard).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.changeNamePhoto).visibility)
                val receipts = activity.findViewById<MaterialSwitch>(R.id.readReceipts)
                // The iMessage screen's setting (off) until the chat has its own.
                assertTrue(receipts.isShown)
                assertFalse(receipts.isChecked)
                receipts.performClick()
            }
            two.waitUntil("kept") { DirectLink.get(two.app).sendsReadReceipts(ALICE) }
        }
        details(ALICE).use { scenario ->
            scenario.waitFor("the chat again") { it.details != null }
            scenario.onActivity { assertTrue(it.findViewById<MaterialSwitch>(R.id.readReceipts).isChecked) }
        }
        assertNotNull(com.lightdecoder.imessage.direct.ReadReceiptChoices(two.app).of(ALICE))
    }

    companion object {
        const val IM_DB = "chat-details-test.db"
        const val ME = "tel:+15550000001"
        const val GROUP = "imsg:group:11111111-2222-3333-4444-555555555555"
        const val ALICE = "imsg:tel:+15551234567"
    }
}
