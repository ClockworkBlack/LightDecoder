package com.lightdecoder.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.ChatBackgrounds
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.BubbleLayout
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.PhoneBubbleDrawable
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.TimeFormat
import com.lightdecoder.ui.chat.TypingDotsView
import com.lightdecoder.ui.imessage.DirectSetupActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * "iPhone-style chats": a conversation that goes through the iMessage library is drawn as on an
 * iPhone (blue, green and grey bubbles with tails, times between the messages, the name in the
 * middle of the bar, a "+" and a pill); every other conversation, and all of them with the
 * setting off, as before.
 *
 * [screenshotsForReview] draws the screen to PNG files, only when asked (-Dlightdecoder.uiShots=<folder>).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneStyleScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private lateinit var iMessage: IMessageManager

    /** What was sent to Apple, through the iMessage library ([directMode]). */
    private val sentRequests: MutableList<org.json.JSONObject> = java.util.Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        iMessage = IMessageManager.installForTesting(two.app, imRepo)
        IMessagePrefs.setEnabled(two.app, true)
        DirectPrefs.setPhoneStyleOn(two.app, true)
        DirectPrefs.setReplyThreadsInChat(two.app, false)
        ChatBackgrounds.dir(two.app).deleteRecursively()
    }

    @After
    fun tearDown() {
        DirectPrefs.setPhoneStyleOn(two.app, true)
        DirectPrefs.setReplyThreadsInChat(two.app, false)
        ChatBackgrounds.dir(two.app).deleteRecursively()
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        two.tearDown()
        imDb.close()
    }

    // --- set-up ------------------------------------------------------------------------------

    /** The iMessage library set up, answering as Apple would; Alice's iMessage chat as it stores it. */
    private fun directMode() {
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        var sends = 0
        DirectLink.installForTesting(two.app) { what, request, _ ->
            if (what == DirectWire.SEND) {
                sentRequests += request
                DirectWire.sent("S${++sends}", System.currentTimeMillis())
            } else {
                DirectWire.ok()
            }
        }
        imRepo.upsertConversation(
            ConversationDto(
                id = ALICE_DIRECT, name = "Alice Martin", type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice Martin", number = "+15551234567", formattedNumber = "+15551234567"),
                ),
            ),
        )
    }

    private fun message(
        guid: String,
        text: String,
        ts: Long,
        mine: Boolean,
        chat: String = ALICE_DIRECT,
        sender: Pair<String, String> = "+15551234567" to "Alice Martin",
        reactions: List<ReactionDto> = emptyList(),
        system: Boolean = false,
        editedAtMs: Long = 0,
        /** The GUID of the message it answers inline, if any. */
        replyTo: String = "",
    ) {
        imRepo.upsertMessage(
            MessageDto(
                id = "imsg:$guid", conversationId = chat, fromMe = mine,
                senderId = if (mine) "" else sender.first, senderName = if (mine) "" else sender.second,
                timestampMs = ts,
                direction = when {
                    system -> "system"
                    mine -> "out"
                    else -> "in"
                },
                status = if (mine) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE",
                transport = IMessageManager.TRANSPORT, text = text, parts = listOf(PartDto("0", "text", text)), isOld = true,
                reactions = reactions, editedAtMs = editedAtMs,
                replyToId = if (replyTo.isEmpty()) "" else "imsg:$replyTo",
            ),
        )
    }

    /** A conversation with Alice: two days, runs of several messages, a tapback, an edit, a notice, "Delivered". */
    private fun aliceChat(now: Long = System.currentTimeMillis()) {
        val day = 24 * 3_600_000L
        message("A1", "Are we still on for Saturday?", now - day - 3_600_000, mine = false)
        message("A2", "Yes! 10 at the trailhead", now - day - 3_590_000, mine = true)
        message("A3", "Bring the big thermos ☕", now - day - 3_580_000, mine = true, reactions = listOf(ReactionDto("❤️", listOf("+15551234567"))))
        message("B1", "Good morning", now - 50 * 60_000, mine = false)
        message("B2", "I found the photos from last year, the ones from the ridge where the fog rolled in under us", now - 50 * 60_000 + 10_000, mine = false)
        message("B3", "Sending them tonight", now - 50 * 60_000 + 20_000, mine = false, reactions = listOf(ReactionDto("👍", listOf("me"), fromMe = true)))
        message("B4", "Alice named the conversation Saturday hike.", now - 40 * 60_000, mine = false, system = true)
        message("C1", "Perfect", now - 5 * 60_000, mine = true)
        message("C2", "I'll bring the map and the good boots this time", now - 5 * 60_000 + 15_000, mine = true, editedAtMs = now - 4 * 60_000)
        message("C3", "See you there", now - 5 * 60_000 + 30_000, mine = true)
        imRepo.advanceMessageStatus(listOf("imsg:C3"), DirectArchive.STATUS_DELIVERED, 2, DirectArchive::statusRank)
    }

    /**
     * Inline replies, as an iPhone sends them: one right under its message, one a message further
     * down (with a faded copy), a reply to that reply (in the same thread), and one far below
     * the message it answers (the copy only).
     */
    private fun replyChat(now: Long = System.currentTimeMillis(), chat: String = ALICE_DIRECT) {
        if (chat != ALICE_DIRECT) {
            imRepo.upsertConversation(
                ConversationDto(
                    id = chat, name = "Alice Martin", type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                    participants = listOf(
                        ParticipantDto(id = "me", isMe = true),
                        ParticipantDto(id = "+15551234567", name = "Alice Martin", number = "+15551234567", formattedNumber = "+15551234567"),
                    ),
                ),
            )
        }
        val start = now - 20 * 60_000
        var at = start
        fun next() = 20_000L.also { at += it }.let { at }
        message("R1", "Lunch at the summit?", next(), mine = false, chat = chat)
        message("R2", "Yes! I'll pack sandwiches", next(), mine = true, chat = chat, replyTo = "R1")
        message("R3", "Who's bringing the tent?", next(), mine = false, chat = chat)
        message("R4", "And the stove, it gets cold up there", next(), mine = false, chat = chat)
        message("R5", "Tent is in my car", next(), mine = true, chat = chat, replyTo = "R3")
        message("R6", "Perfect, I'll bring the poles", next(), mine = false, chat = chat, replyTo = "R5")
        message("R7", "What time do we leave?", next(), mine = true, chat = chat)
        message("R8", "8?", next(), mine = false, chat = chat)
        message("R9", "8 works", next(), mine = true, chat = chat)
        message("R10", "Bring a headlamp too", next(), mine = false, chat = chat)
        message("R11", "Already packed", next(), mine = true, chat = chat)
        message("R12", "Turkey or ham, by the way?", next(), mine = false, chat = chat, replyTo = "R1")
    }

    /** An iMessage group with Alice and Ben. */
    private fun groupChat(now: Long = System.currentTimeMillis()) {
        imRepo.upsertConversation(
            ConversationDto(
                id = GROUP, name = "Saturday hike", isGroup = true, type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice Martin", number = "+15551234567", formattedNumber = "+15551234567"),
                    ParticipantDto(id = "+15557654321", name = "Ben Ortiz", number = "+15557654321", formattedNumber = "+15557654321"),
                ),
            ),
        )
        val alice = "+15551234567" to "Alice Martin"
        val ben = "+15557654321" to "Ben Ortiz"
        message("G1", "Who is driving?", now - 30 * 60_000, mine = false, chat = GROUP, sender = alice)
        message("G2", "I can take three", now - 30 * 60_000 + 10_000, mine = false, chat = GROUP, sender = alice)
        message("G3", "I'll ride with Alice then", now - 29 * 60_000, mine = false, chat = GROUP, sender = ben, reactions = listOf(ReactionDto("👍", listOf("+15551234567"))))
        message("G4", "Same, pick me up at 9:30", now - 28 * 60_000, mine = true, chat = GROUP)
        message("G5", "Deal", now - 3 * 60_000, mine = false, chat = GROUP, sender = alice)
        message("G6", "Don't forget the snacks", now - 3 * 60_000 + 20_000, mine = false, chat = GROUP, sender = ben)
        message("G7", "And water. Lots of water.", now - 3 * 60_000 + 30_000, mine = false, chat = GROUP, sender = ben)
        message("G8", "On it", now - 60_000, mine = true, chat = GROUP)
    }

    /** Alice changed the chat's background to a gradient, as of [version]. */
    private fun aliceSetsBackground(guid: String, version: Long) = DirectLink.get(two.app).apply(
        DirectWire.decode(
            org.json.JSONObject(
                DirectWire.encode(
                    DirectArchive.Update.Background(
                        ALICE_DIRECT, null, "tel:+15551234567", version, null,
                        DirectArchive.Update.Upsert(
                            ConversationDto(
                                id = ALICE_DIRECT, name = "Alice Martin", type = DirectArchive.TRANSPORT,
                                participants = listOf(
                                    ParticipantDto(id = "tel:+15551234567", name = "Alice Martin", number = "+15551234567"),
                                    ParticipantDto(id = "tel:+15550000001", isMe = true),
                                ),
                                otherParticipantIds = listOf("tel:+15551234567"),
                            ),
                            MessageDto(
                                id = "imsg:$guid", conversationId = ALICE_DIRECT, senderId = "tel:+15551234567", senderName = "Alice",
                                timestampMs = System.currentTimeMillis() - 45 * 60_000, direction = "system", transport = DirectArchive.TRANSPORT,
                                text = "Alice changed the chat background.", parts = listOf(PartDto("0", "text", "Alice changed the chat background.")),
                            ),
                        ),
                        com.lightdecoder.imessage.direct.BackgroundPalettes.ALL.first { it.id == "sunset" }.style,
                    ),
                ).toString(),
            ),
        ),
    )

    private fun launch(chat: String, service: ChatService = ChatService.IMESSAGE): ActivityScenario<ThreadActivity> =
        ActivityScenario.launch(RelayIntents.thread(two.app, chat, service = service).setFlags(0))

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun items(activity: Activity): List<ThreadItem> =
        (activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList

    private fun shownTexts(activity: Activity): List<String> = items(activity).filterIsInstance<ThreadItem.Message>().map { it.row.text }

    /** The list row on screen whose text is [text]. */
    private fun rowOf(activity: Activity, text: String): View? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.findViewById<TextView>(R.id.body)?.text?.toString() == text }
    }

    private fun bubbleColor(activity: Activity, text: String): Int =
        rowOf(activity, text)!!.findViewById<View>(R.id.bubble).backgroundTintList!!.defaultColor

    // --- where it applies --------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun anIMessageChatThroughTheLibraryLooksAsOnAnIphone() {
        directMode()
        aliceChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the messages") { shownTexts(it).contains("See you there") && rowOf(it, "See you there") != null }
            scenario.onActivity { activity ->
                assertTrue(activity.phoneStyle)
                // Blue and grey bubbles drawn here, with a tail on the last of a run only.
                assertEquals(activity.getColor(R.color.phone_blue), bubbleColor(activity, "See you there"))
                assertEquals(activity.getColor(R.color.phone_incoming), bubbleColor(activity, "Sending them tonight"))
                val last = rowOf(activity, "See you there")!!.findViewById<View>(R.id.bubble).background as PhoneBubbleDrawable
                assertTrue(last.tail)
                assertTrue("the user's own: on the right", last.tailRight)
                val middle = rowOf(activity, "I'll bring the map and the good boots this time")!!.findViewById<View>(R.id.bubble).background as PhoneBubbleDrawable
                assertFalse(middle.tail)
                val hers = rowOf(activity, "Sending them tonight")!!.findViewById<View>(R.id.bubble).background as PhoneBubbleDrawable
                assertTrue(hers.tail)
                assertFalse(hers.tailRight)
                // At most three quarters of the screen wide.
                assertEquals(0.75f, rowOf(activity, "See you there")!!.findViewById<BubbleLayout>(R.id.column).maxWidthFraction, 0f)

                // No time and no ticks in a bubble; "Delivered" stays under the latest, "Edited" under its bubble.
                val row = rowOf(activity, "See you there")!!
                assertEquals(View.GONE, row.findViewById<View>(R.id.time).visibility)
                assertEquals(View.GONE, row.findViewById<View>(R.id.statusIcon).visibility)
                assertFalse(row.findViewById<View>(R.id.meta).isShown)
                val receipt = row.findViewById<TextView>(R.id.receipt)
                assertEquals(View.VISIBLE, receipt.visibility)
                assertEquals(activity.getString(R.string.imessage_receipt_delivered), receipt.text.toString())
                val editedRow = rowOf(activity, "I'll bring the map and the good boots this time")!!
                val edited = editedRow.findViewById<View>(R.id.edited)
                assertTrue(edited.isShown)
                assertTrue("under its bubble", edited.top >= editedRow.findViewById<View>(R.id.bubble).bottom)
                // What is no longer drawn is still read out: the time, and how far it got.
                val spoken = row.findViewById<View>(R.id.bubble).contentDescription.toString()
                val sentAt = items(activity).filterIsInstance<ThreadItem.Message>().first { it.row.text == "See you there" }.row.timestampMs
                assertTrue(spoken, spoken.contains(TimeFormat.clock(activity, sentAt)))
                assertTrue(spoken, spoken.contains(activity.getString(R.string.imessage_receipt_delivered)))

                // The time is between the messages: one above each stretch, none within fifteen minutes.
                val times = items(activity).filterIsInstance<ThreadItem.Day>()
                assertEquals(3, times.size)
                assertEquals(activity.getString(R.string.chat_day_yesterday), times[0].label)
                assertEquals(activity.getString(R.string.chat_day_today), times[1].label)
                assertTrue(times.all { it.time.isNotEmpty() })

                // A notice: small, upright, in the middle.
                val notice = two.findAll(activity.findViewById(R.id.messages)) { it.id == R.id.systemText }.single() as TextView
                assertEquals("Alice named the conversation Saturday hike.", notice.text.toString())
                assertFalse(notice.typeface?.isItalic == true)

                // A tapback sits on the bubble's top corner: above it in the column, drawn over it.
                val tapped = rowOf(activity, "Sending them tonight")!!
                val column = tapped.findViewById<BubbleLayout>(R.id.column)
                val reactions = tapped.findViewById<View>(R.id.reactions)
                val bubble = tapped.findViewById<View>(R.id.bubble)
                assertEquals(View.VISIBLE, reactions.visibility)
                assertTrue(column.indexOfChild(reactions) < column.indexOfChild(bubble))
                assertTrue("it overlaps the bubble's top", reactions.bottom > bubble.top && reactions.top < bubble.top)
                assertTrue("drawn over the bubble", reactions.translationZ > 0f)
                assertEquals("on the far corner from the tail", bubble.right, reactions.right)
                assertTrue("inside its row: nothing is cut off", reactions.top + column.top >= -reactions.height / 4 && column.top >= 0)

                // The bar: her picture and name in the middle, no title beside the arrow; a tap opens the menu.
                val header = activity.findViewById<View>(R.id.phoneHeader)
                assertTrue(header.isShown)
                assertEquals("Alice Martin", activity.findViewById<TextView>(R.id.phoneName).text.toString())
                assertEquals("AM", activity.findViewById<TextView>(R.id.phoneAvatar).text.toString())
                assertTrue(activity.supportActionBar?.title.isNullOrEmpty())
                assertNull(activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).logo)
                assertTrue(header.contentDescription.toString().contains("Alice Martin"))
                assertTrue(header.hasOnClickListeners())
                val centre = header.left + header.width / 2
                assertEquals((header.parent as View).width / 2f, centre.toFloat(), 2f * activity.resources.displayMetrics.density)

                // The composer: a "+", "iMessage" in the pill, and the blue arrow only with something to send.
                val input = activity.findViewById<EditText>(R.id.input)
                assertEquals("iMessage", input.hint.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.stickers).visibility)
                assertEquals(activity.getString(R.string.imessage_phone_add), activity.findViewById<View>(R.id.attach).contentDescription.toString())
                val send = activity.findViewById<ImageView>(R.id.send)
                input.setText("On my way")
                assertEquals(View.VISIBLE, send.visibility)
                assertTrue(send.isEnabled)
                assertEquals(activity.getColor(R.color.phone_blue), send.backgroundTintList!!.defaultColor)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.voiceMessage).visibility)
                input.setText("")
                assertFalse(send.isEnabled)
            }
        }
    }

    @Test
    fun thePlusOffersPicturesAndStickers() {
        directMode()
        DirectPrefs.setGamesOn(two.app, false)
        try {
            aliceChat()
            launch(ALICE_DIRECT).use { scenario ->
                scenario.waitFor("the messages") { rowOf(it, "See you there") != null }
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.attach).performClick()
                    ShadowLooper.idleMainLooper()
                    val list = (ShadowDialog.getLatestDialog() as AlertDialog).listView
                    assertEquals(
                        listOf(activity.getString(R.string.imessage_attach_photos), activity.getString(R.string.imessage_attach_stickers)),
                        (0 until list.adapter.count).map { list.adapter.getItem(it).toString() },
                    )
                }
            }
        } finally {
            DirectPrefs.setGamesOn(two.app, true)
        }
    }

    @Test
    fun whoIsTypingIsABubbleWithThreeDots() {
        directMode()
        aliceChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "See you there") != null }
            iMessage.noteTyping(ALICE_DIRECT, "+15551234567", "Alice", typing = true)
            scenario.waitFor("her typing") { it.findViewById<View>(R.id.typingDots).visibility == View.VISIBLE }
            scenario.onActivity { activity ->
                val dots = activity.findViewById<TypingDotsView>(R.id.typingDots)
                assertTrue(dots.isShown)
                assertEquals("no words beside it", View.GONE, activity.findViewById<View>(R.id.typingText).visibility)
                assertEquals(activity.getString(R.string.chat_typing_one, "Alice"), dots.contentDescription.toString())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, dots.accessibilityLiveRegion)
            }
            iMessage.noteTyping(ALICE_DIRECT, "+15551234567", "Alice", typing = false)
            scenario.waitFor("her typing gone") { it.findViewById<View>(R.id.typingDots).visibility == View.GONE }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun inAGroupTheSendersNameIsAboveTheirRunAndTheirPictureBesideItsLastBubble() {
        directMode()
        groupChat()
        launch(GROUP).use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "On it") != null && rowOf(it, "Who is driving?") != null }
            scenario.onActivity { activity ->
                assertTrue(activity.phoneStyle)
                fun sender(text: String) = rowOf(activity, text)!!.findViewById<TextView>(R.id.sender)
                fun avatar(text: String) = rowOf(activity, text)!!.findViewById<TextView>(R.id.avatar)
                assertEquals(View.VISIBLE, sender("Who is driving?").visibility)
                assertEquals("Alice Martin", sender("Who is driving?").text.toString())
                assertEquals(View.GONE, avatar("Who is driving?").visibility)
                assertEquals(View.GONE, sender("I can take three").visibility)
                assertEquals(View.VISIBLE, avatar("I can take three").visibility)
                assertEquals("AM", avatar("I can take three").text.toString())
                assertEquals("Ben Ortiz", sender("I'll ride with Alice then").text.toString())
                assertEquals("BO", avatar("I'll ride with Alice then").text.toString())
                // The user's own: neither.
                assertEquals(View.GONE, sender("On it").visibility)
                assertEquals(View.GONE, avatar("On it").visibility)
                // Others' bubbles all leave the picture's room, so a run lines up.
                val first = rowOf(activity, "Who is driving?")!!.findViewById<View>(R.id.column)
                val second = rowOf(activity, "I can take three")!!.findViewById<View>(R.id.column)
                assertEquals(first.left, second.left)
                assertTrue(avatar("I can take three").right <= second.left)
                assertEquals("Saturday hike", activity.findViewById<TextView>(R.id.phoneName).text.toString())
            }
        }
    }

    @Test
    fun inAMessagesConversationHerIMessagesAreBlueAndTextsGreen() {
        two.env.pairedManager()
        directMode()
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        two.seedOutgoing(ChatService.MESSAGES, "sms1", "c1", "A plain text", ts = now - 120_000)
        message("M1", "An iMessage", now - 60_000, mine = true)
        launch("c1", ChatService.MESSAGES).use { scenario ->
            scenario.waitFor("both") { it.phoneStyle && rowOf(it, "A plain text") != null && rowOf(it, "An iMessage") != null }
            scenario.onActivity { activity ->
                assertEquals(activity.getColor(R.color.phone_blue), bubbleColor(activity, "An iMessage"))
                assertEquals(activity.getColor(R.color.phone_green), bubbleColor(activity, "A plain text"))
                assertEquals("iMessage", activity.findViewById<EditText>(R.id.input).hint.toString())
                // A text's ticks are not drawn either; TalkBack still hears them.
                val text = rowOf(activity, "A plain text")!!
                assertEquals(View.GONE, text.findViewById<View>(R.id.statusIcon).visibility)
                assertTrue(text.findViewById<View>(R.id.bubble).contentDescription.toString().contains(activity.getString(R.string.chat_status_delivered)))
            }
        }
    }

    @Test
    fun overAChatBackgroundTimesAndNoticesKeepAPillAndWhatIsUnderABubbleIsWhite() {
        directMode()
        aliceChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "See you there") != null }
            fun notices(activity: Activity) = two.findAll(activity.findViewById(R.id.messages)) { it.id == R.id.systemText || it.id == R.id.dayLabel }
            scenario.onActivity { activity ->
                assertTrue(notices(activity).isNotEmpty())
                assertTrue("plain on the screen", notices(activity).all { it.background == null })
                assertEquals(activity.getColor(R.color.phone_meta), rowOf(activity, "See you there")!!.findViewById<TextView>(R.id.receipt).currentTextColor)
            }
            aliceSetsBackground("BG1", 1_000)
            scenario.waitFor("the background, and pills on it") { activity ->
                activity.findViewById<View>(R.id.chatBackground).visibility == View.VISIBLE &&
                    notices(activity).isNotEmpty() && notices(activity).all { it.background != null } &&
                    rowOf(activity, "See you there")?.findViewById<TextView>(R.id.receipt)?.currentTextColor == android.graphics.Color.WHITE
            }
            // The bubbles themselves stay solid.
            scenario.onActivity { activity -> assertEquals(255, android.graphics.Color.alpha(bubbleColor(activity, "See you there"))) }
        }
    }

    @Test
    fun aPlainTextConversationLooksAsBefore() {
        two.env.pairedManager()
        directMode()
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 1_000, number = "+1 555-987-0000")
        two.seedIncoming(ChatService.MESSAGES, "b1", "c2", "Hi from Bob", ts = System.currentTimeMillis() - 60_000)
        two.seedOutgoing(ChatService.MESSAGES, "b2", "c2", "Hi Bob", ts = System.currentTimeMillis() - 30_000)
        launch("c2", ChatService.MESSAGES).use { scenario ->
            scenario.waitFor("his text") { rowOf(it, "Hi Bob") != null }
            scenario.onActivity { activity -> assertLooksAsBefore(activity, "Hi Bob", "Bob") }
        }
    }

    @Test
    fun withTheSettingOffAnIMessageChatLooksAsBeforeAndTurnedOnAgainItChanges() {
        directMode()
        aliceChat()
        DirectPrefs.setPhoneStyleOn(two.app, false)
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "See you there") != null }
            scenario.onActivity { activity ->
                assertLooksAsBefore(activity, "See you there", "Alice Martin")
                // Still an iMessage chat's blue, and its days' names.
                assertEquals(activity.getColor(R.color.imessage_blue), bubbleColor(activity, "See you there"))
                assertTrue(items(activity).filterIsInstance<ThreadItem.Day>().all { it.time.isEmpty() })
                assertEquals(2, items(activity).filterIsInstance<ThreadItem.Day>().size)
            }
            // Turned on (on the iMessage screen) and back here: it changes without reopening.
            DirectPrefs.setPhoneStyleOn(two.app, true)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.waitFor("the iPhone look") {
                it.phoneStyle && rowOf(it, "See you there")?.findViewById<View>(R.id.bubble)?.background is PhoneBubbleDrawable
            }
            scenario.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.phoneHeader).isShown) }
            // And off again: everything is put back.
            DirectPrefs.setPhoneStyleOn(two.app, false)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.waitFor("the usual look") {
                !it.phoneStyle && rowOf(it, "See you there")?.findViewById<View>(R.id.bubble)?.background !is PhoneBubbleDrawable &&
                    items(it).filterIsInstance<ThreadItem.Day>().size == 2
            }
            scenario.onActivity { activity -> assertLooksAsBefore(activity, "See you there", "Alice Martin") }
        }
    }

    /** The screen as every conversation was drawn before iPhone-style chats: [mine] is one of the user's messages on it. */
    private fun assertLooksAsBefore(activity: ThreadActivity, mine: String, title: String) {
        assertFalse(activity.phoneStyle)
        val row = rowOf(activity, mine)!!
        val bubble = row.findViewById<View>(R.id.bubble)
        assertFalse(bubble.background is PhoneBubbleDrawable)
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.time).visibility)
        assertTrue("its time is in the bubble", row.findViewById<View>(R.id.meta).isShown)
        val column = row.findViewById<BubbleLayout>(R.id.column)
        assertEquals(0.8f, column.maxWidthFraction, 0f)
        assertTrue("reactions go under the bubble", column.indexOfChild(row.findViewById(R.id.reactions)) > column.indexOfChild(bubble))
        assertEquals(View.GONE, row.findViewById<View>(R.id.avatar).visibility)
        assertTrue("\"Edited\" belongs to the bubble's time line", row.findViewById<View>(R.id.edited).parent === row.findViewById<View>(R.id.meta))
        val d = activity.resources.displayMetrics.density
        assertEquals((2 * d).toInt(), row.paddingTop)
        assertEquals((12 * d).toInt(), row.paddingStart)
        assertEquals((12 * d).toInt(), bubble.paddingStart)
        assertEquals((12 * d).toInt(), bubble.paddingEnd)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.phoneHeader).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.phoneHeaderLine).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.typingDots).visibility)
        assertEquals(title, activity.supportActionBar?.title?.toString())
        assertNotNull(activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).logo)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.stickers).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.send).visibility)
        assertEquals(activity.getString(R.string.chat_attach), activity.findViewById<View>(R.id.attach).contentDescription.toString())
        assertNull(activity.findViewById<View>(R.id.send).backgroundTintList)
        val actionBar = android.util.TypedValue().also { activity.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, it, true) }
        assertEquals(
            android.util.TypedValue.complexToDimensionPixelSize(actionBar.data, activity.resources.displayMetrics),
            activity.findViewById<View>(R.id.toolbar).layoutParams.height,
        )
    }

    @Test
    fun theSettingIsOnTheIMessageScreenAndIsKept() {
        assertTrue("on by default", DirectPrefs.phoneStyleOn(two.app))
        ActivityScenario.launch(DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPhoneStyle)
                assertEquals("iPhone-style chats", switch.text.toString())
                assertTrue(switch.isChecked)
                switch.isChecked = false
                assertFalse(DirectPrefs.phoneStyleOn(activity))
            }
        }
        // Kept: the screen shows it off when opened again.
        ActivityScenario.launch(DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPhoneStyle)
                assertFalse(switch.isChecked)
                switch.isChecked = true
                assertTrue(DirectPrefs.phoneStyleOn(activity))
            }
        }
    }

    // --- inline replies ----------------------------------------------------------------------

    private fun rowItem(activity: Activity, text: String): ThreadItem.Message =
        items(activity).filterIsInstance<ThreadItem.Message>().first { it.row.text == text }

    private fun replyLines(list: RecyclerView): com.lightdecoder.ui.chat.ReplyLines =
        (0 until list.itemDecorationCount).map { list.getItemDecorationAt(it) }.filterIsInstance<com.lightdecoder.ui.chat.ReplyLines>().single()

    /** Draws [list] once (the lines are drawn with it) and says how many lines it drew. */
    private fun linesDrawn(list: RecyclerView): Int {
        val bitmap = Bitmap.createBitmap(maxOf(1, list.width), maxOf(1, list.height), Bitmap.Config.ARGB_8888)
        list.draw(Canvas(bitmap))
        return replyLines(list).drawn
    }

    private fun panelTexts(panel: com.lightdecoder.ui.chat.ReplyThreadPanel): List<String> =
        panel.adapter.currentList.filterIsInstance<ThreadItem.Message>().map { it.row.text }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun withReplyThreadsShownInTheChatAReplyShowsAFadedCopyOfWhatItAnswersAndThatMessageHowManyReplies() {
        directMode()
        replyChat()
        DirectPrefs.setReplyThreadsInChat(two.app, true)
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Turkey or ham, by the way?") != null && rowOf(it, "Lunch at the summit?") != null }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                // Close under the message it answers: a faded copy of it, on that message's side (Alice's: the left).
                val near = rowOf(activity, "Tent is in my car")!!
                val copy = near.findViewById<TextView>(R.id.threadQuote)
                assertEquals(View.VISIBLE, copy.visibility)
                assertEquals("Who's bringing the tent?", copy.text.toString())
                assertTrue("on her side", copy.right < list.width / 2 + copy.width / 2 && copy.left < list.width / 4)
                assertTrue("above the bubble", copy.bottom <= near.findViewById<View>(R.id.column).top)
                assertEquals("the old quote block is not drawn", View.GONE, near.findViewById<View>(R.id.quote).visibility)
                assertEquals("Reply to Alice Martin: Who's bringing the tent?", copy.contentDescription.toString())
                assertEquals(com.lightdecoder.ui.chat.ReplyThreads.Mark(root = "imsg:R3", fadedQuote = true, connectTo = "imsg:R3"), rowItem(activity, "Tent is in my car").thread)
                // Right under it: the line only.
                assertEquals(View.GONE, rowOf(activity, "Yes! I'll pack sandwiches")!!.findViewById<View>(R.id.threadQuote).visibility)
                assertEquals(View.GONE, rowOf(activity, "Perfect, I'll bring the poles")!!.findViewById<View>(R.id.threadQuote).visibility)
                assertEquals("a reply to a reply is in its thread", "imsg:R3", rowItem(activity, "Perfect, I'll bring the poles").thread?.root)
                // Far below: the copy only.
                val far = rowItem(activity, "Turkey or ham, by the way?").thread!!
                assertTrue(far.fadedQuote)
                assertNull(far.connectTo)
                assertEquals(View.VISIBLE, rowOf(activity, "Turkey or ham, by the way?")!!.findViewById<View>(R.id.threadQuote).visibility)

                // "2 Replies" in blue under each message answered; none under the others.
                fun count(text: String) = rowOf(activity, text)!!.findViewById<TextView>(R.id.replyCount)
                assertEquals(View.VISIBLE, count("Who's bringing the tent?").visibility)
                assertEquals("2 Replies", count("Who's bringing the tent?").text.toString())
                assertEquals(activity.getColor(R.color.phone_blue), count("Who's bringing the tent?").currentTextColor)
                assertEquals("2 Replies", count("Lunch at the summit?").text.toString())
                assertEquals(View.GONE, count("Tent is in my car").visibility)
                assertEquals(View.GONE, count("8 works").visibility)

                // The lines, drawn with the list: right under (R1-R2, R5-R6), close (R3 to the copy of it, the
                // copy to R5), and the far one's copy to it.
                assertEquals(5, linesDrawn(list))
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi", sdk = [33])
    fun onAndroid12AndLaterTheThreadViewBlursTheChatItselfAndClosingClearsIt() {
        directMode()
        replyChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Tent is in my car") != null }
            scenario.onActivity { activity ->
                fadedCopy(activity, "Tent is in my car").performClick()
                ShadowLooper.idleMainLooper()
                val panel = activity.threadPanel!!
                assertEquals(com.lightdecoder.ui.chat.ReplyThreadPanel.Backdrop.RENDER_EFFECT, panel.backdrop)
                assertNotNull("the chat's own views are blurred", panel.blurEffect)
                assertNull("no snapshot needed", panel.binding.threadPanel.background)
                assertEquals("a light veil over the blur", activity.getColor(R.color.phone_thread_scrim), (panel.binding.threadScrim.background as android.graphics.drawable.ColorDrawable).color)
                assertTrue("the veil lets the chat show through", android.graphics.Color.alpha(activity.getColor(R.color.phone_thread_scrim)) < 0xA0)
                // The window lets the chat show: transparent, no dim.
                val window = panel.dialog.window!!
                assertEquals(0, window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
                assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
                // A tap on the veil closes it, and the blur goes.
                panel.binding.threadScrim.performClick()
                ShadowLooper.idleMainLooper()
                assertNull(activity.threadPanel)
                assertEquals(com.lightdecoder.ui.chat.ReplyThreadPanel.Backdrop.NONE, panel.backdrop)
                assertNull(panel.blurEffect)
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi", sdk = [28])
    fun beforeAndroid12TheThreadViewShowsABlurredSnapshotOfTheChat() {
        directMode()
        replyChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Tent is in my car") != null }
            scenario.onActivity { activity ->
                fadedCopy(activity, "Tent is in my car").performClick()
                ShadowLooper.idleMainLooper()
                val panel = activity.threadPanel!!
                assertEquals(com.lightdecoder.ui.chat.ReplyThreadPanel.Backdrop.SNAPSHOT, panel.backdrop)
                assertNull(panel.blurEffect)
                val snapshot = (panel.binding.threadPanel.background as android.graphics.drawable.BitmapDrawable).bitmap
                assertNotNull(snapshot)
                val decor = activity.window.decorView
                assertEquals("an eighth of the screen", decor.width / 8, snapshot.width)
                assertEquals(decor.height / 8, snapshot.height)
                assertEquals(activity.getColor(R.color.phone_thread_scrim), (panel.binding.threadScrim.background as android.graphics.drawable.ColorDrawable).color)
                // The thread's bubbles stay on top of it, sharp.
                assertEquals(listOf("Who's bringing the tent?", "Tent is in my car", "Perfect, I'll bring the poles"), panelTexts(panel))
                panel.dialog.onBackPressed()
                ShadowLooper.idleMainLooper()
                assertNull(activity.threadPanel)
                assertEquals(com.lightdecoder.ui.chat.ReplyThreadPanel.Backdrop.NONE, panel.backdrop)
                assertNull("let go", panel.binding.threadPanel.background)
                assertTrue(snapshot.isRecycled)
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun withReplyThreadsShownInTheChatRepliesOpenTheThreadWithOnlyItsMessagesAndBackClosesIt() {
        directMode()
        replyChat()
        DirectPrefs.setReplyThreadsInChat(two.app, true)
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Who's bringing the tent?") != null }
            scenario.onActivity { activity ->
                rowOf(activity, "Who's bringing the tent?")!!.findViewById<View>(R.id.replyCount).performClick()
                ShadowLooper.idleMainLooper()
                val panel = activity.threadPanel!!
                assertTrue(panel.isShowing)
                assertEquals(listOf("Who's bringing the tent?", "Tent is in my car", "Perfect, I'll bring the poles"), panelTexts(panel))
                // Drawn as in the chat: the same bubbles, no copies and no counts, one line between each.
                assertTrue(panel.adapter.phoneStyle)
                assertTrue(panel.adapter.currentList.filterIsInstance<ThreadItem.Message>().all { it.thread?.fadedQuote == false && it.thread?.replyCount == 0 })
                assertEquals(activity.getString(R.string.imessage_thread_reply_hint), panel.binding.threadInput.hint.toString())
                assertFalse("nothing to send yet", panel.binding.threadSend.isEnabled)
                // Back closes it.
                panel.dialog.onBackPressed()
                ShadowLooper.idleMainLooper()
                assertFalse(panel.isShowing)
                assertNull(activity.threadPanel)

                // A reply's faded copy opens its thread too; a tap beside the messages closes it.
                rowOf(activity, "Tent is in my car")!!.findViewById<View>(R.id.threadQuote).performClick()
                ShadowLooper.idleMainLooper()
                val again = activity.threadPanel!!
                assertEquals(listOf("Who's bringing the tent?", "Tent is in my car", "Perfect, I'll bring the poles"), panelTexts(again))
                again.binding.threadScrim.performClick()
                ShadowLooper.idleMainLooper()
                assertNull(activity.threadPanel)

                // The far one's thread: the first message and both its replies.
                rowOf(activity, "Lunch at the summit?")!!.findViewById<View>(R.id.replyCount).performClick()
                ShadowLooper.idleMainLooper()
                assertEquals(listOf("Lunch at the summit?", "Yes! I'll pack sandwiches", "Turkey or ham, by the way?"), panelTexts(activity.threadPanel!!))
                activity.threadPanel!!.dismiss()
                ShadowLooper.idleMainLooper()

                // And, new with the bubbles' own tap: a reply's bubble opens its thread here too.
                assertTrue(rowOf(activity, "Perfect, I'll bring the poles")!!.findViewById<View>(R.id.bubble).performClick())
                ShadowLooper.idleMainLooper()
                assertEquals("imsg:R3", activity.threadPanel!!.rootId)
                activity.threadPanel!!.dismiss()
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun aReplyFromTheThreadGoesToItsFirstMessageAndShowsInIt() {
        directMode()
        replyChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Tent is in my car") != null }
            var panel: com.lightdecoder.ui.chat.ReplyThreadPanel? = null
            scenario.onActivity { activity ->
                fadedCopy(activity, "Tent is in my car").performClick()
                ShadowLooper.idleMainLooper()
                panel = activity.threadPanel!!
                panel!!.binding.threadInput.setText("And a tarp")
                assertTrue(panel!!.binding.threadSend.isEnabled)
                panel!!.binding.threadSend.performClick()
                assertEquals("the field is emptied", "", panel!!.binding.threadInput.text.toString())
            }
            two.waitUntil("sent to Apple") { sentRequests.any { it.optString("text") == "And a tarp" } }
            val sent = sentRequests.first { it.optString("text") == "And a tarp" }
            assertEquals("to the thread's first message", "R3", sent.getString("replyGuid"))
            assertEquals("0:0:" + "Who's bringing the tent?".length, sent.getString("replyPart"))
            // It shows in the open thread, and so does a reply that arrives meanwhile.
            scenario.waitFor("in the thread") { panelTexts(panel!!).lastOrNull() == "And a tarp" }
            message("R13", "Good idea", System.currentTimeMillis() + 1_000, mine = false, replyTo = "R3")
            scenario.waitFor("hers too") { panelTexts(panel!!).lastOrNull() == "Good idea" }
            scenario.onActivity { activity ->
                assertEquals(5, panelTexts(panel!!).size)
                assertEquals("4 Replies", rowOf(activity, "Who's bringing the tent?")?.findViewById<TextView>(R.id.replyCount)?.text?.toString())
                // A long press in it opens the message's actions, as in the chat.
                val list = panel!!.binding.threadMessages
                val bubble = (0 until list.childCount).map { list.getChildAt(it) }
                    .first { it.findViewById<TextView>(R.id.body)?.text?.toString() == "Tent is in my car" }.findViewById<View>(R.id.bubble)
                assertTrue(bubble.performLongClick())
                ShadowLooper.idleMainLooper()
                assertTrue(ShadowDialog.getLatestDialog() is com.google.android.material.bottomsheet.BottomSheetDialog)
                ShadowDialog.getLatestDialog().dismiss()
                panel!!.dismiss()
            }
        }
    }

    @Test
    fun swipingToReplyToAReplyKeepsTheNewMessageInTheSameThread() {
        directMode()
        replyChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { shownTexts(it).contains("Perfect, I'll bring the poles") && it.phoneStyle }
            scenario.onActivity { activity ->
                activity.onReply(rowItem(activity, "Perfect, I'll bring the poles").row)
                activity.findViewById<EditText>(R.id.input).setText("Poles are in the shed")
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("sent to Apple") { sentRequests.any { it.optString("text") == "Poles are in the shed" } }
            assertEquals("R3", sentRequests.first { it.optString("text") == "Poles are in the shed" }.getString("replyGuid"))
            // A reply to a message in no thread answers that message.
            scenario.onActivity { activity ->
                activity.onReply(rowItem(activity, "8 works").row)
                activity.findViewById<EditText>(R.id.input).setText("Or 7:30")
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("sent to Apple") { sentRequests.any { it.optString("text") == "Or 7:30" } }
            assertEquals("R9", sentRequests.first { it.optString("text") == "Or 7:30" }.getString("replyGuid"))
        }
    }

    private fun fadedCopy(activity: Activity, text: String): TextView = rowOf(activity, text)!!.findViewById(R.id.threadQuote)

    private fun bubble(activity: Activity, text: String): View = rowOf(activity, text)!!.findViewById(R.id.bubble)

    /** A real touch (down and up) at ([x], [y]) of [view]. */
    private fun touch(view: View, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(at, at + 20, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun byDefaultEveryReplyShowsAFadedCopyOfWhatItAnswersAndNoLineIsDrawn() {
        directMode()
        replyChat()
        assertFalse("off by default", DirectPrefs.replyThreadsInChat(two.app))
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Turkey or ham, by the way?") != null && rowOf(it, "Lunch at the summit?") != null }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                assertFalse(activity.threadsInChat)
                // No old quote block and no line.
                assertTrue(two.findAll(list) { it.id == R.id.quote }.none { it.visibility == View.VISIBLE })
                assertEquals(0, linesDrawn(list))
                assertTrue(items(activity).filterIsInstance<ThreadItem.Message>().none { it.thread?.connectTo != null })
                // Every reply (right under its message, close to it, far from it) has a faded copy of it above its bubble.
                val answered = mapOf(
                    "Yes! I'll pack sandwiches" to "Lunch at the summit?",
                    "Tent is in my car" to "Who's bringing the tent?",
                    "Perfect, I'll bring the poles" to "Tent is in my car",
                    "Turkey or ham, by the way?" to "Lunch at the summit?",
                )
                for ((reply, original) in answered) {
                    val copy = fadedCopy(activity, reply)
                    assertEquals(reply, View.VISIBLE, copy.visibility)
                    assertEquals(reply, original, copy.text.toString())
                    assertEquals(activity.getColor(R.color.phone_meta), copy.currentTextColor)
                    assertTrue("above the bubble", copy.bottom <= rowOf(activity, reply)!!.findViewById<View>(R.id.column).top)
                    assertTrue(copy.isClickable)
                    assertTrue(items(activity).filterIsInstance<ThreadItem.Message>().first { it.row.text == reply }.thread!!.fadedQuote)
                }
                // On the side of whoever sent what it answers: hers.
                assertTrue(fadedCopy(activity, "Tent is in my car").left < list.width / 4)
                // TalkBack reads it and says a tap opens the thread.
                val copy = fadedCopy(activity, "Tent is in my car")
                assertEquals("Reply to Alice Martin: Who's bringing the tent?", copy.contentDescription.toString())
                val info = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(copy.createAccessibilityNodeInfo())
                assertEquals("open the thread", info.actionList.first { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }.label.toString())
                // The others have none; the messages answered keep "2 Replies" in blue.
                for (other in listOf("Lunch at the summit?", "Who's bringing the tent?", "8 works", "Already packed")) {
                    assertEquals(other, View.GONE, fadedCopy(activity, other).visibility)
                    assertFalse(other, fadedCopy(activity, other).isClickable)
                }
                fun count(text: String) = rowOf(activity, text)!!.findViewById<TextView>(R.id.replyCount)
                assertEquals("2 Replies", count("Who's bringing the tent?").text.toString())
                assertEquals(View.VISIBLE, count("Lunch at the summit?").visibility)
                assertEquals(activity.getColor(R.color.phone_blue), count("Lunch at the summit?").currentTextColor)
                assertEquals(View.GONE, count("Tent is in my car").visibility)
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun aTapOnAReplyOrOnAMessageWithRepliesOpensItsThread() {
        directMode()
        replyChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Turkey or ham, by the way?") != null && rowOf(it, "Lunch at the summit?") != null }
            scenario.onActivity { activity ->
                fun opened(): List<String>? = activity.threadPanel?.also { assertTrue(it.isShowing) }?.let(::panelTexts)
                fun close() {
                    activity.threadPanel?.dismiss()
                    ShadowLooper.idleMainLooper()
                    assertNull(activity.threadPanel)
                }
                val tent = listOf("Who's bringing the tent?", "Tent is in my car", "Perfect, I'll bring the poles")
                val lunch = listOf("Lunch at the summit?", "Yes! I'll pack sandwiches", "Turkey or ham, by the way?")
                // A reply's bubble: a real touch on its text.
                val body = rowOf(activity, "Tent is in my car")!!.findViewById<View>(R.id.body)
                touch(bubble(activity, "Tent is in my car"), body.left + body.width / 2f, body.top + body.height / 2f)
                assertEquals(tent, opened())
                assertEquals("imsg:R3", activity.threadPanel!!.rootId)
                // In the thread view a bubble's tap opens nothing more.
                val inPanel = activity.threadPanel!!.binding.threadMessages
                assertTrue(two.findAll(inPanel) { it.id == R.id.bubble }.none { it.isClickable })
                assertTrue("no copies in it", two.findAll(inPanel) { it.id == R.id.threadQuote }.none { it.visibility == View.VISIBLE })
                close()
                // A reply to a reply: the thread of the first message.
                assertTrue(bubble(activity, "Perfect, I'll bring the poles").performClick())
                assertEquals(tent, opened())
                close()
                // Its faded copy.
                assertTrue(fadedCopy(activity, "Turkey or ham, by the way?").performClick())
                assertEquals(lunch, opened())
                close()
                // The message others answered: its bubble, and "2 Replies".
                assertTrue(bubble(activity, "Lunch at the summit?").performClick())
                assertEquals(lunch, opened())
                close()
                assertTrue(rowOf(activity, "Who's bringing the tent?")!!.findViewById<View>(R.id.replyCount).performClick())
                assertEquals(tent, opened())
                close()
                // A message in no thread: a tap does nothing.
                assertFalse(bubble(activity, "8 works").isClickable)
                assertFalse(bubble(activity, "8 works").performClick())
                assertNull(activity.threadPanel)
                // A long press is still the message's actions, not the thread.
                assertTrue(bubble(activity, "Tent is in my car").performLongClick())
                ShadowLooper.idleMainLooper()
                assertNull(activity.threadPanel)
                assertTrue(ShadowDialog.getLatestDialog() is com.google.android.material.bottomsheet.BottomSheetDialog)
                ShadowDialog.getLatestDialog().dismiss()
                // TalkBack is told what the tap does.
                val info = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(bubble(activity, "Tent is in my car").createAccessibilityNodeInfo())
                assertEquals("open the thread", info.actionList.first { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }.label.toString())
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun aLinkInvisibleInkAPictureAndAFailedMessageKeepTheirOwnTap() {
        directMode()
        val now = System.currentTimeMillis()
        message("T1", "Where do we meet?", now - 10 * 60_000, mine = false)
        fun reply(guid: String, text: String, ago: Long, mine: Boolean = true, status: String = "", extra: String = "", parts: List<PartDto> = listOf(PartDto("0", "text", text))) =
            imRepo.upsertMessage(
                MessageDto(
                    id = "imsg:$guid", conversationId = ALICE_DIRECT, fromMe = mine,
                    senderId = if (mine) "" else "+15551234567", senderName = if (mine) "" else "Alice Martin",
                    timestampMs = now - ago, direction = if (mine) "out" else "in",
                    status = status.ifEmpty { if (mine) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE" },
                    transport = IMessageManager.TRANSPORT, text = text, parts = parts, isOld = true, replyToId = "imsg:T1", extra = extra,
                ),
            )
        reply("T2", "Here: https://example.com/trailhead", 9 * 60_000, mine = false)
        reply("T3", "The secret spot", 8 * 60_000, mine = false, extra = com.lightdecoder.imessage.direct.IMessageExtra(effect = com.lightdecoder.imessage.direct.IMessageExtra.INVISIBLE_INK).toJson())
        val png = java.io.ByteArrayOutputStream().also { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        reply("T4", "", 7 * 60_000, mine = false, parts = listOf(PartDto("p0", "media", mime = "image/png", fileName = "map.png", width = 40, height = 30, inlineData = png)))
        reply("T5", "Never left", 6 * 60_000, status = "OUTGOING_FAILED_GENERIC")
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { a -> rowOf(a, "Never left") != null && rowOf(a, "Where do we meet?") != null && items(a).count { (it as? ThreadItem.Message)?.thread?.fadedQuote == true } == 4 }
            two.settle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                fun rowOfId(id: String): View = (0 until list.childCount).map { list.getChildAt(it) }
                    .first { (list.getChildViewHolder(it) as? MessagesAdapter.MessageHolder)?.let { h -> (items(activity)[h.bindingAdapterPosition] as ThreadItem.Message).row.id == id } == true }
                fun close() {
                    activity.threadPanel?.dismiss()
                    ShadowLooper.idleMainLooper()
                }

                // A link in the text: a touch on it opens the link, not the thread; a touch on the words before it, the thread.
                val linkRow = rowOf(activity, "Here: https://example.com/trailhead")!!
                val body = linkRow.findViewById<TextView>(R.id.body)
                val layout = body.layout
                val text = body.text.toString()
                val onLink = layout.getPrimaryHorizontal(text.indexOf("example")) + body.totalPaddingLeft
                val y = body.totalPaddingTop + layout.getLineBottom(0) / 2f
                touch(body, onLink, y)
                assertNull("the link's tap is the link's", activity.threadPanel)
                assertEquals("https://example.com/trailhead", org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.dataString)
                touch(body, body.totalPaddingLeft + layout.getPrimaryHorizontal(2), y)
                assertEquals("imsg:T1", activity.threadPanel?.rootId)
                assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
                close()

                // Invisible ink: the first tap shows the text.
                val inked = rowOfId("imsg:T3")
                assertTrue(inked.findViewById<View>(R.id.bubble).performClick())
                ShadowLooper.idleMainLooper()
                assertNull("the first tap shows it", activity.threadPanel)
            }
            scenario.waitFor("the ink gone") { rowOf(it, "The secret spot") != null }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                // The next tap is as on any reply.
                assertTrue(bubble(activity, "The secret spot").performClick())
                ShadowLooper.idleMainLooper()
                assertEquals("imsg:T1", activity.threadPanel?.rootId)
                activity.threadPanel?.dismiss()
                ShadowLooper.idleMainLooper()

                // A picture: its tap opens the picture; the bubble around it opens nothing; its faded copy, the thread.
                val picture = (0 until list.childCount).map { list.getChildAt(it) }.first { it.findViewById<View>(R.id.mediaFrame) != null }
                assertTrue(picture.findViewById<View>(R.id.mediaFrame).performClick())
                ShadowLooper.idleMainLooper()
                assertNull("a picture's tap is the picture's", activity.threadPanel)
                assertFalse(picture.findViewById<View>(R.id.bubble).isClickable)
                assertEquals(View.VISIBLE, picture.findViewById<View>(R.id.threadQuote).visibility)
                assertEquals("Where do we meet?", picture.findViewById<TextView>(R.id.threadQuote).text.toString())
                assertTrue(picture.findViewById<View>(R.id.threadQuote).performClick())
                ShadowLooper.idleMainLooper()
                assertEquals("imsg:T1", activity.threadPanel?.rootId)
                activity.threadPanel?.dismiss()
                ShadowLooper.idleMainLooper()

                // A failed one: its tap is the failure's (on the bubble and on the text), never the thread.
                val failed = rowOf(activity, "Never left")!!
                assertEquals(View.VISIBLE, failed.findViewById<View>(R.id.failedText).visibility)
                assertTrue(failed.findViewById<View>(R.id.bubble).performClick())
                failed.findViewById<View>(R.id.body).performClick()
                ShadowLooper.idleMainLooper()
                assertNull("a failed message's tap is the retry's", activity.threadPanel)
                // Its faded copy still opens the thread.
                assertTrue(failed.findViewById<View>(R.id.threadQuote).performClick())
                ShadowLooper.idleMainLooper()
                assertEquals("imsg:T1", activity.threadPanel?.rootId)
                activity.threadPanel?.dismiss()
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun turnedOnTheSettingBringsTheCopiesAndLinesBackWhenTheChatIsReturnedTo() {
        directMode()
        replyChat()
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Tent is in my car") != null && fadedCopy(it, "Tent is in my car").visibility == View.VISIBLE }
            scenario.onActivity { activity ->
                assertEquals(0, linesDrawn(activity.findViewById(R.id.messages)))
                assertEquals("by default the reply right under its message has its copy", View.VISIBLE, fadedCopy(activity, "Yes! I'll pack sandwiches").visibility)
            }
            // Turned on on the iMessage screen, and back to the chat.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            DirectPrefs.setReplyThreadsInChat(two.app, true)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.waitFor("the copies") { rowOf(it, "Tent is in my car")?.findViewById<View>(R.id.threadQuote)?.visibility == View.VISIBLE }
            two.settle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                assertTrue(activity.threadsInChat)
                assertEquals("Who's bringing the tent?", rowOf(activity, "Tent is in my car")!!.findViewById<TextView>(R.id.threadQuote).text.toString())
                assertEquals(View.VISIBLE, rowOf(activity, "Turkey or ham, by the way?")!!.findViewById<View>(R.id.threadQuote).visibility)
                assertEquals("right under its message: the line instead", View.GONE, fadedCopy(activity, "Yes! I'll pack sandwiches").visibility)
                assertEquals(5, linesDrawn(list))
                assertEquals("2 Replies", rowOf(activity, "Who's bringing the tent?")!!.findViewById<TextView>(R.id.replyCount).text.toString())
            }
            // And off again.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            DirectPrefs.setReplyThreadsInChat(two.app, false)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.waitFor("the default copies") { rowOf(it, "Yes! I'll pack sandwiches")?.findViewById<View>(R.id.threadQuote)?.visibility == View.VISIBLE }
            two.settle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                assertEquals(View.VISIBLE, fadedCopy(activity, "Tent is in my car").visibility)
                assertEquals(0, linesDrawn(list))
            }
        }
    }

    @Test
    fun showingReplyThreadsInTheChatIsOnTheIMessageScreenOffByDefaultAndIsKept() {
        assertFalse("off by default", DirectPrefs.replyThreadsInChat(two.app))
        ActivityScenario.launch(DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchReplyThreads)
                assertEquals("Show reply threads in the chat", switch.text.toString())
                assertFalse(switch.isChecked)
                switch.isChecked = true
                assertTrue(DirectPrefs.replyThreadsInChat(activity))
                assertTrue("the other setting is its own", DirectPrefs.phoneStyleOn(activity))
            }
        }
        // Kept: the screen shows it on when opened again.
        ActivityScenario.launch(DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchReplyThreads)
                assertTrue(switch.isChecked)
                switch.isChecked = false
                assertFalse(DirectPrefs.replyThreadsInChat(activity))
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun withTheSettingOffRepliesKeepTheirQuoteAsBefore() {
        directMode()
        replyChat()
        DirectPrefs.setPhoneStyleOn(two.app, false)
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the replies") { rowOf(it, "Tent is in my car") != null }
            scenario.onActivity { activity ->
                assertFalse(activity.phoneStyle)
                val row = rowOf(activity, "Tent is in my car")!!
                assertEquals(View.VISIBLE, row.findViewById<View>(R.id.quote).visibility)
                assertEquals("Who's bringing the tent?", row.findViewById<TextView>(R.id.quoteText).text.toString())
                assertEquals(View.GONE, row.findViewById<View>(R.id.threadQuote).visibility)
                assertFalse("a tap on it opens nothing", row.findViewById<View>(R.id.bubble).performClick())
                assertEquals(View.GONE, rowOf(activity, "Who's bringing the tent?")!!.findViewById<View>(R.id.replyCount).visibility)
                assertTrue(items(activity).filterIsInstance<ThreadItem.Message>().all { it.thread == null })
                assertEquals(0, linesDrawn(activity.findViewById(R.id.messages)))
                activity.openThread("imsg:R3")
                assertNull("no thread view", activity.threadPanel)
            }
        }
    }

    // --- screenshots for review --------------------------------------------------------------

    /**
     * Draws the screen (a one-to-one chat with someone typing, and a group) in light and dark
     * to PNG files in the folder -Dlightdecoder.uiShots names. Skipped without it: the suite writes nothing.
     */
    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun screenshotsForReview() {
        val folder = System.getProperty(SHOTS_PROPERTY).orEmpty()
        assumeTrue("screenshots only when asked: -D$SHOTS_PROPERTY=<folder>", folder.isNotEmpty())
        val out = File(folder).apply { mkdirs() }
        // A phone has a microphone: the voice message button is where the arrow will be.
        org.robolectric.Shadows.shadowOf(two.app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE, true)
        directMode()
        aliceChat()
        groupChat()
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val suffix = if (night) "dark" else "light"
            launch(ALICE_DIRECT).use { scenario ->
                scenario.waitFor("the messages") { rowOf(it, "See you there") != null }
                iMessage.noteTyping(ALICE_DIRECT, "+15551234567", "Alice", typing = true)
                scenario.waitFor("her typing") { it.findViewById<View>(R.id.typingDots).visibility == View.VISIBLE }
                scenario.onActivity { shoot(it, File(out, "chat-$suffix.png")) }
                scenario.onActivity { it.findViewById<EditText>(R.id.input).setText("Leaving in five") }
                two.settle()
                scenario.onActivity { shoot(it, File(out, "chat-typing-$suffix.png")) }
                iMessage.noteTyping(ALICE_DIRECT, "+15551234567", "Alice", typing = false)
            }
            launch(GROUP).use { scenario ->
                scenario.waitFor("the messages") { rowOf(it, "On it") != null }
                scenario.onActivity { shoot(it, File(out, "group-$suffix.png")) }
            }
        }
        // Right to left.
        RuntimeEnvironment.setQualifiers("+ldrtl-notnight")
        launch(GROUP).use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "On it") != null }
            scenario.onActivity { shoot(it, File(out, "group-rtl-light.png")) }
        }
        RuntimeEnvironment.setQualifiers("+ldltr")
        // Over a chat background: Alice sets one (a gradient), as Apple would tell it. A reply in it, to see its faded copy over the picture.
        message("C4", "Can't wait to see them!", System.currentTimeMillis() - 4 * 60_000, mine = true, replyTo = "B2")
        aliceSetsBackground("BG1", 1_000)
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            launch(ALICE_DIRECT).use { scenario ->
                scenario.waitFor("the background") {
                    rowOf(it, "Can't wait to see them!") != null && it.findViewById<View>(R.id.chatBackground).visibility == View.VISIBLE
                }
                two.settle()
                scenario.onActivity { shoot(it, File(out, "chat-background-${if (night) "dark" else "light"}.png")) }
            }
        }
        // Inline replies: in the chat, and a thread opened over it.
        replyChat(chat = REPLY_CHAT)
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val suffix = if (night) "dark" else "light"
            launch(REPLY_CHAT).use { scenario ->
                scenario.waitFor("the replies") { rowOf(it, "Turkey or ham, by the way?") != null }
                two.settle()
                // By default: a faded copy above each reply, no lines, and "N Replies" under what they answer.
                scenario.onActivity { shoot(it, File(out, "reply-chain-$suffix.png")) }
                scenario.onActivity { it.openThread("imsg:R3") }
                two.settle()
                scenario.onActivity { activity ->
                    val panel = activity.threadPanel!!
                    shoot(activity, File(out, "reply-thread-panel-$suffix.png"), panel.dialog.window!!.decorView)
                    panel.dismiss()
                }
            }
        }
        // With "Show reply threads in the chat" on: the faded copies and the lines.
        RuntimeEnvironment.setQualifiers("+notnight")
        DirectPrefs.setReplyThreadsInChat(two.app, true)
        launch(REPLY_CHAT).use { scenario ->
            scenario.waitFor("the copies") { rowOf(it, "Turkey or ham, by the way?")?.findViewById<View>(R.id.threadQuote)?.visibility == View.VISIBLE }
            two.settle()
            scenario.onActivity { shoot(it, File(out, "reply-chain-lines-light.png")) }
        }
        DirectPrefs.setReplyThreadsInChat(two.app, false)
        // The same chat with the setting off, to compare.
        RuntimeEnvironment.setQualifiers("+notnight")
        ChatBackgrounds.dir(two.app).deleteRecursively()
        DirectPrefs.setPhoneStyleOn(two.app, false)
        launch(ALICE_DIRECT).use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "See you there") != null }
            scenario.onActivity { shoot(it, File(out, "chat-setting-off-light.png")) }
        }
    }

    /** The screen to [file]; with [over] (a dialog's window, full screen) drawn on top of it. */
    private fun shoot(activity: AppCompatActivity, file: File, over: View? = null) {
        ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        if (over != null) {
            // The chat is blurred behind it on a phone (a RenderEffect, which drawing to a bitmap leaves out): about so.
            val small = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width / 16), maxOf(1, bitmap.height / 16), true)
            val blurred = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, small.width * 2, small.height * 2, true), bitmap.width, bitmap.height, true)
            canvas.drawBitmap(blurred, 0f, 0f, null)
            over.draw(canvas)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val IM_DB = "phone-style-test.db"
        const val ALICE_DIRECT = "imsg:tel:+15551234567"
        const val GROUP = "imsg:group:11111111-2222-3333-4444-555555555555"
        const val REPLY_CHAT = "imsg:tel:+15551230000"
        const val SHOTS_PROPERTY = "lightdecoder.uiShots"
    }
}
