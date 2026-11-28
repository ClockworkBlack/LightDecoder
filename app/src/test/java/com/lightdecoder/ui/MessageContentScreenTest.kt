package com.lightdecoder.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spanned
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.imessage.direct.Mention
import com.lightdecoder.imessage.direct.Mentions
import com.lightdecoder.imessage.direct.Polls
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.MentionHighlightSpan
import com.lightdecoder.ui.chat.MentionSpan
import com.lightdecoder.ui.chat.ThreadActivity
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import uniffi.rust_bridge.MessagePart

/**
 * Message content in an iMessage chat, on screen: "@" offers a group's members and a pick is a
 * mention that goes as one; a link's card with its page's picture; a poll's card, whose choices vote;
 * a sticker as a tapback shows as its picture. [contentScreenshotsForReview] draws them to PNG files,
 * only when asked (-Dlightdecoder.uiShots=<folder>).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h800dp-xhdpi")
class MessageContentScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository

    /** What went to the iMessage library, by kind. */
    private val sent: MutableList<Pair<Int, JSONObject>> = java.util.Collections.synchronizedList(ArrayList())

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
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        var n = 0
        DirectLink.installForTesting(two.app) { what, request, _ ->
            sent += what to request
            when (what) {
                DirectWire.SEND, DirectWire.EXTENSION -> DirectWire.sent("OUT${++n}", System.currentTimeMillis())
                else -> DirectWire.ok()
            }
        }
        group()
    }

    @After
    fun tearDown() {
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        com.lightdecoder.archive.MediaCache.mediaDir(two.app, ChatService.IMESSAGE).deleteRecursively()
        two.tearDown()
        imDb.close()
    }

    // --- set-up ------------------------------------------------------------------------------

    private val alice = "+15551234567" to "Alice Martin"
    private val ben = "+15557654321" to "Ben Ortiz"

    private fun group() {
        imRepo.upsertConversation(
            ConversationDto(
                id = GROUP, name = "Saturday hike", isGroup = true, type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = alice.first, name = alice.second, number = alice.first, formattedNumber = alice.first),
                    ParticipantDto(id = ben.first, name = ben.second, number = ben.first, formattedNumber = ben.first),
                ),
            ),
        )
    }

    private fun message(
        guid: String,
        text: String,
        agoMs: Long,
        mine: Boolean = false,
        sender: Pair<String, String> = alice,
        extra: IMessageExtra = IMessageExtra(),
        reactions: List<ReactionDto> = emptyList(),
    ) {
        imRepo.upsertMessage(
            MessageDto(
                id = "imsg:$guid", conversationId = GROUP, fromMe = mine,
                senderId = if (mine) "" else sender.first, senderName = if (mine) "" else sender.second,
                timestampMs = System.currentTimeMillis() - agoMs, direction = if (mine) "out" else "in",
                status = if (mine) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE",
                transport = IMessageManager.TRANSPORT, text = text, parts = listOf(PartDto("0", "text", text)), isOld = true,
                reactions = reactions, extra = extra.toJson(),
            ),
        )
    }

    /** A picture, as a JPEG (a link's) or a PNG with transparency (a sticker's), at the key's file. */
    private fun picture(key: String, sticker: Boolean = false) {
        val bitmap = Bitmap.createBitmap(if (sticker) 120 else 600, if (sticker) 120 else 315, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (sticker) {
            canvas.drawCircle(60f, 60f, 54f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() })
            canvas.drawCircle(42f, 48f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            canvas.drawCircle(78f, 48f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        } else {
            canvas.drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 600f, 315f, 0xFF3A7BD5.toInt(), 0xFF00D2FF.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            canvas.drawCircle(470f, 90f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF3B0.toInt() })
        }
        val file = DirectLink.pictureFile(two.app, key)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(if (sticker) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private var pollIds = 0

    private fun poll(): Polls.Poll = Polls.create("Dinner on Saturday?", listOf("Pizza", "Tacos", "Sushi"), alice.first) { "OPT-${++pollIds}" }!!

    private fun pollCard(guid: String, agoMs: Long, votes: List<Polls.Ballot> = emptyList()) {
        val p = poll()
        message(guid, "Poll: ${p.title}", agoMs, extra = IMessageExtra(app = Polls.card(p, "0F8FAD5B-D9CB-469F-A165-70867728950E", root = "imsg:$guid"), pollVotes = votes))
    }

    private fun launch(chat: String = GROUP): ActivityScenario<ThreadActivity> =
        ActivityScenario.launch(RelayIntents.thread(two.app, chat, service = ChatService.IMESSAGE).setFlags(0))

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun rowOf(activity: Activity, text: String): View? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.findViewById<TextView>(R.id.body)?.text?.toString() == text }
    }

    // --- mentions ----------------------------------------------------------------------------

    @Test
    fun anAtSignOffersTheGroupsMembersAndAPickIsAMentionThatGoesAsOne() {
        message("M0", "Who's driving?", 10 * 60_000)
        launch().use { scenario ->
            scenario.waitFor("the chat, reachable") { it.mentionsOffered() && rowOf(it, "Who's driving?") != null }
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.input)
                val strip = activity.findViewById<View>(R.id.mentionSuggestions)
                val chips = activity.findViewById<LinearLayout>(R.id.mentionChips)
                input.setText("Me and ")
                input.setSelection(input.length())
                input.text.insert(input.selectionEnd, "@")
                assertEquals(View.VISIBLE, strip.visibility)
                assertEquals(listOf("Alice Martin", "Ben Ortiz"), (0 until chips.childCount).map { (chips.getChildAt(it) as TextView).text.toString() })
                input.text.insert(input.selectionEnd, "Al")
                assertEquals(listOf("Alice Martin"), (0 until chips.childCount).map { (chips.getChildAt(it) as TextView).text.toString() })
                assertEquals(activity.getString(R.string.imessage_mention_suggestion, "Alice Martin"), chips.getChildAt(0).contentDescription.toString())
                chips.getChildAt(0).performClick()
                assertEquals(View.GONE, strip.visibility)
                // Her first name, a mention, then a space and the cursor.
                assertEquals("Me and Alice ", input.text.toString())
                val span = input.text.getSpans(0, input.length(), MentionSpan::class.java).single()
                assertEquals(7, input.text.getSpanStart(span))
                assertEquals(12, input.text.getSpanEnd(span))
                assertEquals(alice.first, span.handle)
                assertEquals(13, input.selectionEnd)
                // A space after it: no suggestions.
                input.text.insert(input.selectionEnd, "x")
                assertEquals(View.GONE, strip.visibility)
                input.text.delete(13, 14)
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("the send") { sent.any { it.first == DirectWire.SEND } }
            val request = DirectWire.SendRequest.fromJson(sent.first { it.first == DirectWire.SEND }.second)
            assertEquals("Me and Alice", request.text)
            assertEquals(listOf(Mention(7, 12, alice.first)), request.mentions)
            // As the ":imessage" process makes the parts of it: the mention a part of its own, part 0 with the text.
            assertEquals(MessagePart.Mention(alice.first, "Alice"), Mentions.toParts(request.text, request.runs, request.mentions)[1])
        }
    }

    @Test
    fun aDeletionThatReachesIntoAMentionTakesAllOfItAndTypingIntoOneMakesItPlainText() {
        launch().use { scenario ->
            scenario.waitFor("the chat, reachable") { it.mentionsOffered() }
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.input)
                input.setText("Hi @")
                input.setSelection(input.length())
                activity.pickMention(Mentions.Query(3, 4, ""), Mentions.Member(ben.first, ben.second))
                assertEquals("Hi Ben ", input.text.toString())
                // Backspace over the space, then into the name: all of it goes.
                input.text.delete(6, 7)
                input.text.delete(5, 6)
                assertEquals("Hi ", input.text.toString())
                assertTrue(input.text.getSpans(0, input.length(), MentionSpan::class.java).isEmpty())
                // Typed into: plain text from now on.
                input.setText("Hi @")
                input.setSelection(input.length())
                activity.pickMention(Mentions.Query(3, 4, ""), Mentions.Member(ben.first, ben.second))
                input.text.insert(4, "x")
                assertEquals("Hi Bxen ", input.text.toString())
                assertTrue(input.text.getSpans(0, input.length(), MentionSpan::class.java).isEmpty())
            }
        }
    }

    @Test
    fun aOneToOneChatOffersNoMentions() {
        imRepo.upsertConversation(
            ConversationDto(
                id = ALICE, name = "Alice Martin", type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = alice.first, name = alice.second, number = alice.first)),
            ),
        )
        launch(ALICE).use { scenario ->
            scenario.waitFor("the chat") { it.findViewById<View>(R.id.input).isShown }
            scenario.onActivity { activity ->
                assertFalse(activity.mentionsOffered())
                val input = activity.findViewById<EditText>(R.id.input)
                input.setText("")
                input.text.insert(0, "@")
                assertEquals(View.GONE, activity.findViewById<View>(R.id.mentionSuggestions).visibility)
            }
        }
    }

    @Test
    fun anIncomingMentionShowsHighlightedAndOneOfTheUserMoreSo() {
        message(
            "MI1", "Ben and Me, bring water", 5 * 60_000,
            extra = IMessageExtra(mentions = mapOf(0 to listOf(Mention(0, 3, ben.first), Mention(8, 10, "+15550000001", mine = true)))),
        )
        launch().use { scenario ->
            scenario.waitFor("the message") { rowOf(it, "Ben and Me, bring water") != null }
            scenario.onActivity { activity ->
                val body = rowOf(activity, "Ben and Me, bring water")!!.findViewById<TextView>(R.id.body).text as Spanned
                val spans = body.getSpans(0, body.length, MentionHighlightSpan::class.java).sortedBy { body.getSpanStart(it) }
                assertEquals(listOf(0 to 3, 8 to 10), spans.map { body.getSpanStart(it) to body.getSpanEnd(it) })
                val paint = android.text.TextPaint()
                spans[0].updateDrawState(paint)
                assertTrue(paint.isFakeBoldText)
                assertEquals(0, paint.bgColor)
                val mine = android.text.TextPaint()
                spans[1].updateDrawState(mine)
                assertTrue("the user's own on a highlight", mine.bgColor != 0)
            }
        }
    }

    // --- link previews -----------------------------------------------------------------------

    @Test
    fun aLinksCardShowsItsPagesPictureAboveItsLines() {
        picture("LK1/link")
        message("LK1", "https://example.com/trail", 5 * 60_000, extra = IMessageExtra(link = IMessageExtra.Link("https://example.com/trail", "The ridge trail", "Six miles of views", "example.com", "LK1/link")))
        message("LK2", "https://example.com/plain", 4 * 60_000, extra = IMessageExtra(link = IMessageExtra.Link("https://example.com/plain", "No picture", "", "example.com")))
        launch().use { scenario ->
            scenario.waitFor("the links") { rowOf(it, "https://example.com/plain") != null }
            scenario.onActivity { activity ->
                val card = rowOf(activity, "https://example.com/trail")!!
                val image = card.findViewById<ImageView>(R.id.linkImage)
                assertEquals(View.VISIBLE, image.visibility)
                assertNotNull(image.drawable)
                assertTrue("above its lines", image.bottom <= card.findViewById<View>(R.id.linkTitle).top)
                assertEquals("The ridge trail", card.findViewById<TextView>(R.id.linkTitle).text.toString())
                // Without a picture (or its file gone): the lines alone.
                assertEquals(View.GONE, rowOf(activity, "https://example.com/plain")!!.findViewById<View>(R.id.linkImage).visibility)
            }
        }
    }

    // --- a group's people ----------------------------------------------------------------------

    @Test
    fun anIMessageGroupsMenuAddsPeopleToItAndOpensItsDetails() {
        message("M0", "Who's coming?", 10 * 60_000)
        launch().use { scenario ->
            scenario.waitFor("the group, as the library can change it") { it.groupManageable && rowOf(it, "Who's coming?") != null }
            scenario.onActivity { activity ->
                val menu = androidx.appcompat.widget.PopupMenu(activity, activity.findViewById(R.id.messages)).menu
                activity.menuInflater.inflate(R.menu.thread_actions, menu)
                activity.onPrepareOptionsMenu(menu)
                val add = menu.findItem(R.id.action_add_people)
                assertTrue("Add People is in the group's menu", add.isVisible)
                assertEquals("Add People", add.title.toString())
                assertTrue(menu.findItem(R.id.action_chat_details).isVisible)
                // It opens the same picker as "Add Member" in the group's details, for this group.
                activity.onOptionsItemSelected(add)
                val started = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
                assertEquals(com.lightdecoder.ui.chat.NewConversationActivity::class.java.name, started.component!!.className)
                assertEquals(GROUP, started.getStringExtra(com.lightdecoder.ui.chat.NewConversationActivity.EXTRA_ADD_TO))
                activity.onOptionsItemSelected(menu.findItem(R.id.action_chat_details))
                assertEquals(
                    com.lightdecoder.ui.chat.ChatDetailsActivity::class.java.name,
                    org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.component!!.className,
                )
            }
        }
    }

    // --- polls -------------------------------------------------------------------------------

    @Test
    fun aPollsCardShowsItsChoicesAndVotesAndATapVotesAsOpenBubblesDoes() {
        pollCard("PL1", 5 * 60_000, votes = listOf(Polls.Ballot(alice.first, listOf("OPT-1"), 1), Polls.Ballot(ben.first, listOf("OPT-1", "OPT-2"), 2)))
        launch().use { scenario ->
            scenario.waitFor("the poll") { rowOf(it, "Poll: Dinner on Saturday?") != null }
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Poll: Dinner on Saturday?")!!
                assertEquals(View.VISIBLE, row.findViewById<View>(R.id.pollCard).visibility)
                assertEquals(View.GONE, row.findViewById<View>(R.id.appCard).visibility)
                assertEquals("Dinner on Saturday?", row.findViewById<TextView>(R.id.pollTitle).text.toString())
                val options = row.findViewById<LinearLayout>(R.id.pollOptions)
                assertEquals(3, options.childCount)
                fun count(i: Int) = options.getChildAt(i).findViewById<TextView>(R.id.pollOptionCount).text.toString()
                assertEquals(listOf("2", "1", ""), (0 until 3).map(::count))
                assertEquals(listOf("Pizza", "Tacos", "Sushi"), (0 until 3).map { options.getChildAt(it).findViewById<TextView>(R.id.pollOptionText).text.toString() })
                assertEquals(2, options.getChildAt(0).findViewById<LinearLayout>(R.id.pollOptionVoters).childCount)
                assertEquals(View.VISIBLE, row.findViewById<View>(R.id.pollAddChoice).visibility)
                options.getChildAt(2).performClick()
            }
            two.waitUntil("the vote") { sent.any { it.first == DirectWire.EXTENSION } }
            val r = DirectWire.ExtensionRequest.fromJson(sent.first { it.first == DirectWire.EXTENSION }.second)
            assertEquals("PL1", r.rootGuid)
            assertTrue(r.isMeta)
            assertEquals(listOf("OPT-3"), Polls.decodeVotes(r.app.url))
            assertEquals(Polls.BUNDLE_ID, r.app.bundleId)
            // The card shows the user's vote: marked, counted.
            scenario.waitFor("the vote shown") { a ->
                rowOf(a, "Poll: Dinner on Saturday?")?.findViewById<LinearLayout>(R.id.pollOptions)?.getChildAt(2)
                    ?.findViewById<TextView>(R.id.pollOptionCount)?.text?.toString() == "1"
            }
            scenario.onActivity { activity ->
                val mark = rowOf(activity, "Poll: Dinner on Saturday?")!!.findViewById<LinearLayout>(R.id.pollOptions).getChildAt(2).findViewById<TextView>(R.id.pollOptionMark)
                assertEquals("✓", mark.text.toString())
            }
        }
    }

    @Test
    fun aPollTheUserMadeCanBeLockedAndHaveAChoiceTakenOff() {
        // Alice's poll offers no "Edit Poll"; the user's own does.
        pollCard("PL1", 6 * 60_000)
        val mine = Polls.create("Which trail?", listOf("Ridge", "Lake", "Forest"), "+15550000001") { "MY-${++pollIds}" }!!
        message("PL2", "Poll: Which trail?", 5 * 60_000, mine = true, extra = IMessageExtra(app = Polls.card(mine, "1F8FAD5B-D9CB-469F-A165-70867728950E", root = "imsg:PL2")))
        fun shown() = Polls.decode(IMessageExtra.parse(imRepo.message("imsg:PL2")!!.extra).app!!.url)!!
        launch().use { scenario ->
            scenario.waitFor("the polls") { rowOf(it, "Poll: Which trail?") != null && rowOf(it, "Poll: Dinner on Saturday?") != null }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, rowOf(activity, "Poll: Dinner on Saturday?")!!.findViewById<View>(R.id.pollEdit).visibility)
                val row = rowOf(activity, "Poll: Which trail?")!!
                assertEquals(View.VISIBLE, row.findViewById<View>(R.id.pollEdit).visibility)
                assertEquals(View.VISIBLE, row.findViewById<View>(R.id.pollAddChoice).visibility)
                assertEquals(View.GONE, row.findViewById<View>(R.id.pollLockedNote).visibility)
                // "Edit Poll" > "Remove a choice" > Lake > Remove.
                row.findViewById<View>(R.id.pollEdit).performClick()
                val edit = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertEquals(
                    listOf(activity.getString(R.string.imessage_poll_lock), activity.getString(R.string.imessage_poll_remove_choice)),
                    (0 until edit.listView.adapter.count).map { edit.listView.adapter.getItem(it).toString() },
                )
                edit.listView.performItemClick(edit.listView.adapter.getView(1, null, edit.listView), 1, 1L)
                val pick = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertEquals(listOf("Ridge", "Lake", "Forest"), (0 until pick.listView.adapter.count).map { pick.listView.adapter.getItem(it).toString() })
                pick.listView.performItemClick(pick.listView.adapter.getView(1, null, pick.listView), 1, 1L)
                (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            two.waitUntil("the poll sent again without it") { sent.any { it.first == DirectWire.EXTENSION } && shown().options.size == 2 }
            val r = DirectWire.ExtensionRequest.fromJson(sent.first { it.first == DirectWire.EXTENSION }.second)
            assertEquals("PL2", r.rootGuid)
            assertFalse(r.isMeta)
            assertEquals(listOf("Ridge", "Forest"), Polls.decode(r.app.url)!!.options.map { it.text })
            scenario.waitFor("two choices shown") { a -> rowOf(a, "Poll: Which trail?")?.findViewById<LinearLayout>(R.id.pollOptions)?.childCount == 2 }
            // Locked: said once what it does, then "Add Choice" makes way for "Choices locked", here only.
            val before = sent.size
            scenario.onActivity { activity ->
                rowOf(activity, "Poll: Which trail?")!!.findViewById<View>(R.id.pollEdit).performClick()
                val edit = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                edit.listView.performItemClick(edit.listView.adapter.getView(0, null, edit.listView), 0, 0L)
                val confirm = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                confirm.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            two.waitUntil("locked") { IMessageExtra.parse(imRepo.message("imsg:PL2")!!.extra).pollLocked }
            scenario.waitFor("the lock shown") { a ->
                val row = rowOf(a, "Poll: Which trail?") ?: return@waitFor false
                row.findViewById<View>(R.id.pollLockedNote).visibility == View.VISIBLE && row.findViewById<View>(R.id.pollAddChoice).visibility == View.GONE
            }
            assertEquals("locking sends nothing", before, sent.size)
            // And unlocked again from the same place, with no question.
            scenario.onActivity { activity ->
                rowOf(activity, "Poll: Which trail?")!!.findViewById<View>(R.id.pollEdit).performClick()
                val edit = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertEquals(activity.getString(R.string.imessage_poll_unlock), edit.listView.adapter.getItem(0).toString())
                edit.listView.performItemClick(edit.listView.adapter.getView(0, null, edit.listView), 0, 0L)
            }
            two.waitUntil("unlocked") { !IMessageExtra.parse(imRepo.message("imsg:PL2")!!.extra).pollLocked }
        }
    }

    @Test
    fun thePlusOffersAPollWhichGoesAsANewCard() {
        launch().use { scenario ->
            scenario.waitFor("the chat, reachable") { it.mentionsOffered() }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.attach).performClick()
                val choices = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                val list = choices.listView
                val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
                assertTrue(labels.toString(), activity.getString(R.string.imessage_attach_poll) in labels)
                choices.dismiss()
                activity.askPoll(GROUP)
                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                val fields = two.findAll(dialog.window!!.decorView) { it is EditText }.map { it as EditText }
                // The question, then two choices (a third once the second has words).
                assertEquals(3, fields.size)
                fields[0].setText("Where to?")
                fields[1].setText("Beach")
                fields[2].setText("Hills")
                assertEquals(4, two.findAll(dialog.window!!.decorView) { it is EditText }.size)
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            two.waitUntil("the poll") { sent.any { it.first == DirectWire.SEND } }
            val request = DirectWire.SendRequest.fromJson(sent.first { it.first == DirectWire.SEND }.second)
            val poll = Polls.decode(request.app!!.url)!!
            assertEquals("Where to?", poll.title)
            assertEquals(listOf("Beach", "Hills"), poll.options.map { it.text })
            two.waitUntil("its card") { imRepo.messages(GROUP).any { it.text == "Poll: Where to?" && !it.isLocal } }
        }
    }

    // --- stickers ----------------------------------------------------------------------------

    @Test
    fun aStickerAsATapbackShowsAsItsPictureAndATapSaysWhoNotATapback() {
        picture(IMessageExtra.tapbackStickerKey("ST1", alice.first), sticker = true)
        picture(IMessageExtra.stickerKey("ST1", "PS1"), sticker = true)
        message(
            "ST1", "Made it to the top!", 6 * 60_000, mine = true,
            reactions = listOf(ReactionDto(DirectArchive.STICKER_TAPBACK, listOf(alice.first))),
            extra = IMessageExtra(stickers = listOf(IMessageExtra.PlacedSticker("PS1", ben.first, 1))),
        )
        launch().use { scenario ->
            scenario.waitFor("the message") { rowOf(it, "Made it to the top!") != null }
            scenario.onActivity { activity ->
                val box = rowOf(activity, "Made it to the top!")!!.findViewById<android.view.ViewGroup>(R.id.reactions)
                assertEquals(View.VISIBLE, box.visibility)
                val pill = box.getChildAt(0) as TextView
                assertTrue("its picture, not 🖼️", pill.compoundDrawablesRelative[0] is android.graphics.drawable.BitmapDrawable)
                assertEquals("", pill.text.toString())
                assertEquals(activity.resources.getQuantityString(R.plurals.chat_reaction, 1, activity.getString(R.string.imessage_sticker_tapback), 1), pill.contentDescription.toString())
                // The one dragged onto it, by its picture.
                val placed = box.getChildAt(1) as ImageView
                assertNotNull(placed.drawable)
                assertEquals(activity.getString(R.string.imessage_sticker_by, ben.second), placed.contentDescription.toString())
                pill.performClick()
            }
            two.settle()
            assertTrue("no tapback sent", sent.none { it.first == DirectWire.REACT })
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        }
    }

    // --- screenshots for review ----------------------------------------------------------------

    /** Draws a group with a link's card with its picture, a poll, mentions and a sticker, in light and dark, when asked. */
    @Test
    @Config(qualifiers = "w390dp-h1180dp-xhdpi")
    fun contentScreenshotsForReview() {
        val folder = System.getProperty(SHOTS_PROPERTY).orEmpty()
        assumeTrue("screenshots only when asked: -D$SHOTS_PROPERTY=<folder>", folder.isNotEmpty())
        val out = File(folder).apply { mkdirs() }
        picture("C2/link")
        message("C2", "Route for Saturday https://example.com/ridge-trail", 25 * 60_000, sender = ben,
            extra = IMessageExtra(link = IMessageExtra.Link("https://example.com/ridge-trail", "The Ridge Trail: six miles of views", "A loop above the fog line, best before noon.", "example.com", "C2/link")))
        pollCard("C4", 20 * 60_000, votes = listOf(Polls.Ballot(alice.first, listOf("OPT-1"), 1), Polls.Ballot(ben.first, listOf("OPT-1", "OPT-2"), 2), Polls.Ballot(Polls.ME, listOf("OPT-1"), 3)))
        message("C1", "Ben and Me, who's bringing the map?", 15 * 60_000, sender = alice,
            extra = IMessageExtra(mentions = mapOf(0 to listOf(Mention(0, 3, ben.first), Mention(8, 10, "+15550000001", mine = true)))))
        picture(IMessageExtra.tapbackStickerKey("C3", alice.first), sticker = true)
        message("C3", "I'll bring it, Alice", 10 * 60_000, mine = true,
            reactions = listOf(ReactionDto(DirectArchive.STICKER_TAPBACK, listOf(alice.first))),
            extra = IMessageExtra(mentions = mapOf(0 to listOf(Mention(15, 20, alice.first)))))
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            launch().use { scenario ->
                scenario.waitFor("the messages") { rowOf(it, "Poll: Dinner on Saturday?") != null && rowOf(it, "I'll bring it, Alice") != null }
                scenario.onActivity { it.findViewById<EditText>(R.id.input).apply { setText(""); text.insert(0, "@") } }
                two.settle()
                scenario.onActivity { shoot(it, File(out, "content-${if (night) "dark" else "light"}.png")) }
            }
        }
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    private fun shoot(activity: AppCompatActivity, file: File) {
        ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val IM_DB = "message-content-test.db"
        const val GROUP = "imsg:group:11111111-2222-3333-4444-555555555555"
        const val ALICE = "imsg:tel:+15551234567"
        const val SHOTS_PROPERTY = "lightdecoder.uiShots"
    }
}
