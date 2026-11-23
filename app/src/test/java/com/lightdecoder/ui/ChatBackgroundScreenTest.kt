package com.lightdecoder.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.AuroraDrawable
import com.lightdecoder.imessage.direct.BackgroundPalettes
import com.lightdecoder.imessage.direct.BackgroundStyle
import com.lightdecoder.imessage.direct.ChatBackgrounds
import com.lightdecoder.imessage.direct.GradientBackgroundDrawable
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.BackgroundPicker
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * An iMessage chat's screen through the iMessage library: its background (iOS 26) behind the
 * messages, changing as Apple says, and set from the menu; Send Later (iOS 18) from the effect sheet.
 */
@RunWith(AndroidJUnit4::class)
class ChatBackgroundScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    /** What was asked so far, copied under the list's lock (the stand-in answers on another thread meanwhile). */
    private val askedNow: List<Pair<Int, JSONObject>> get() = synchronized(asked) { asked.toList() }

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        IMessageManager.installForTesting(two.app, imRepo)
        ChatBackgrounds.dir(two.app).deleteRecursively()
        IMessagePrefs.setEnabled(two.app, true)
    }

    @After
    fun tearDown() {
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        two.tearDown()
        imDb.close()
        ChatBackgrounds.dir(two.app).deleteRecursively()
    }

    /** The iMessage library set up, answering as Apple would; Alice's iMessage chat with one message from her. */
    private fun directMode() {
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        var sends = 0
        DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.SEND) DirectWire.sent("S${++sends}", System.currentTimeMillis()) else DirectWire.ok()
        }
        imRepo.upsertConversation(
            ConversationDto(
                id = ALICE_DIRECT, type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567", formattedNumber = "+15551234567"),
                ),
            ),
        )
        imRepo.upsertMessage(
            MessageDto(
                id = "imsg:H1", conversationId = ALICE_DIRECT, senderId = "+15551234567", senderName = "Alice",
                timestampMs = System.currentTimeMillis() - 60_000, direction = "in", status = "INCOMING_COMPLETE",
                transport = IMessageManager.TRANSPORT, text = "Hello", parts = listOf(PartDto("0", "text", "Hello")), isOld = true,
            ),
        )
    }

    private fun picture(color: Int): String {
        val b = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val f = ChatBackgrounds.incomingFile(two.app)
        f.writeBytes(ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())
        return f.path
    }

    /** Alice changed the background (to a picture at [path], or to [style]; or took it off: neither), as of [version]. */
    private fun aliceChanged(guid: String, version: Long, path: String?, style: BackgroundStyle? = null) = DirectLink.get(two.app).apply(
        DirectWire.decode(
            JSONObject(
                DirectWire.encode(
                    DirectArchive.Update.Background(
                        ALICE_DIRECT, null, "tel:+15551234567", version, path,
                        DirectArchive.Update.Upsert(
                            ConversationDto(
                                id = ALICE_DIRECT, type = DirectArchive.TRANSPORT,
                                participants = listOf(
                                    ParticipantDto(id = "tel:+15551234567", name = "Alice", number = "+15551234567"),
                                    ParticipantDto(id = "tel:+15550000001", isMe = true),
                                ),
                                otherParticipantIds = listOf("tel:+15551234567"),
                            ),
                            MessageDto(
                                id = "imsg:$guid", conversationId = ALICE_DIRECT, senderId = "tel:+15551234567", senderName = "Alice",
                                timestampMs = System.currentTimeMillis(), direction = "system", transport = DirectArchive.TRANSPORT,
                                text = "Alice changed the chat background.", parts = listOf(PartDto("0", "text", "Alice changed the chat background.")),
                            ),
                        ),
                        style,
                    ),
                ).toString(),
            ),
        ),
    )

    private fun background(activity: Activity) = activity.findViewById<View>(R.id.chatBackground)

    @Test
    fun anIMessageChatsBackgroundShowsBehindItsMessagesAndChangesLive() {
        directMode()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her message") { shownTexts(it).contains("Hello") }
            scenario.onActivity { assertEquals(View.GONE, background(it).visibility) }

            // Alice sets one: it shows at once, behind the messages, with the scrim over it.
            aliceChanged("BG1", 1_000, picture(Color.RED))
            val first = ChatBackgrounds(two.app).pictureOf(ALICE_DIRECT)!!
            scenario.waitFor("her background") { background(it).visibility == View.VISIBLE && background(it).tag == first.path }
            scenario.onActivity { activity ->
                val parent = background(activity).parent as android.view.ViewGroup
                val list = activity.findViewById<View>(R.id.messages)
                val scrim = activity.findViewById<View>(R.id.chatBackgroundScrim)
                assertEquals(View.VISIBLE, scrim.visibility)
                assertTrue("behind the messages", parent.indexOfChild(background(activity)) < parent.indexOfChild(scrim))
                assertTrue(parent.indexOfChild(scrim) < parent.indexOfChild(list))
                assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP, (background(activity) as android.widget.ImageView).scaleType)
                assertTrue(shownTexts(activity).contains("Alice changed the chat background."))
            }

            // A new one replaces it.
            aliceChanged("BG2", 2_000, picture(Color.BLUE))
            val second = ChatBackgrounds(two.app).pictureOf(ALICE_DIRECT)!!
            scenario.waitFor("her new background") { background(it).tag == second.path }

            // Taken off: gone.
            aliceChanged("BG3", 3_000, null)
            scenario.waitFor("no background") { background(it).visibility == View.GONE }
        }
    }

    @Test
    fun aMessagesConversationShowsItsIMessageChatsBackground() {
        two.env.pairedManager()
        directMode()
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        aliceChanged("BG1", 1_000, picture(Color.GREEN))
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hello") }
            scenario.waitFor("her chat's background") { background(it).visibility == View.VISIBLE }
        }
        // A conversation with no iMessage chat has none.
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 2_000, number = "+1 555-987-0000")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c2").setFlags(0)).use { scenario ->
            ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { assertEquals(View.GONE, background(it).visibility) }
        }
    }

    private fun menu(activity: Activity): android.view.Menu {
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
        activity.onPrepareOptionsMenu(menu)
        return menu
    }

    @Test
    fun backgroundIsInTheMenuOfIMessageChatsOnlyAndRemoveGoesToApple() {
        two.env.pairedManager()
        directMode()
        aliceChanged("BG1", 1_000, picture(Color.RED))
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her background") { background(it).visibility == View.VISIBLE }
            scenario.waitFor("Background in the menu") { menu(it).findItem(R.id.action_chat_background).isVisible }
            val sheet = openPicker(scenario)
            val items = pickerItems(sheet)
            assertEquals(
                listOf("Colors", "Dynamic", "Themes", "Your photos"),
                items.filterIsInstance<BackgroundPicker.Item.Header>().map { two.app.getString(it.title) },
            )
            val tiles = items.filterIsInstance<BackgroundPicker.Item.Tile>()
            assertEquals(BackgroundPalettes.ALL.size + 1 + 10 + 2, tiles.size)
            assertTrue(tiles.map { it.name }.containsAll(listOf("Sunset", "Ocean", "Slate", "Aurora", "Galaxy", "Photo", "None")))
            // Each section's tiles come under it.
            assertTrue(items.indexOfFirst { it is BackgroundPicker.Item.Tile && it.name == "Aurora" } > items.indexOfFirst { it == BackgroundPicker.Item.Header(R.string.imessage_background_section_dynamic) })
            tap(sheet) { it is BackgroundPicker.Item.Tile && it.pick == BackgroundPicker.Pick.Remove }
            assertFalse(sheet.isShowing)
            two.waitUntil("taken off through Apple") { askedNow.any { it.first == DirectWire.BACKGROUND } }
            val r = DirectWire.BackgroundRequest.fromJson(askedNow.single { it.first == DirectWire.BACKGROUND }.second)
            assertEquals(null, r.path)
            assertEquals(null, r.style)
            assertTrue(r.version > 1_000)
            scenario.waitFor("gone here too") { background(it).visibility == View.GONE }
            scenario.waitFor("said so") { shownTexts(it).contains("You removed the chat background.") }
        }
        // A text conversation: no Background.
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 2_000, number = "+1 555-987-0000")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c2").setFlags(0)).use { scenario ->
            ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { assertFalse(menu(it).findItem(R.id.action_chat_background).isVisible) }
        }
    }

    private fun openPicker(scenario: ActivityScenario<ThreadActivity>): BottomSheetDialog {
        scenario.onActivity { activity -> activity.onOptionsItemSelected(menu(activity).findItem(R.id.action_chat_background)) }
        ShadowLooper.idleMainLooper()
        return ShadowDialog.getLatestDialog() as BottomSheetDialog
    }

    private fun grid(sheet: BottomSheetDialog) = sheet.findViewById<RecyclerView>(R.id.backgroundGrid)!!

    private fun pickerItems(sheet: BottomSheetDialog) = (grid(sheet).adapter as BackgroundPicker.Adapter).items

    /** Taps the picker's tile that [which] picks out. */
    private fun tap(sheet: BottomSheetDialog, which: (BackgroundPicker.Item) -> Boolean) {
        val grid = grid(sheet)
        val adapter = grid.adapter as BackgroundPicker.Adapter
        val position = adapter.items.indexOfFirst(which)
        assertTrue("in the picker", position >= 0)
        grid.scrollToPosition(position)
        ShadowLooper.idleMainLooper()
        val holder = grid.findViewHolderForAdapterPosition(position)
            ?: adapter.createViewHolder(grid, adapter.getItemViewType(position)).also { adapter.bindViewHolder(it, position) }
        assertTrue(holder.itemView.performClick())
        ShadowLooper.idleMainLooper()
    }

    private fun tile(name: String): (BackgroundPicker.Item) -> Boolean = { it is BackgroundPicker.Item.Tile && it.name == name }

    private fun previewBar(activity: Activity) = activity.findViewById<View>(R.id.backgroundPreviewBar)

    private fun drawableOf(activity: Activity) = (background(activity) as android.widget.ImageView).drawable

    @Test
    fun anIPhonesGradientOrAuroraShowsDrawnNotTakenOff() {
        directMode()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her message") { shownTexts(it).contains("Hello") }
            // A light gradient (Peach): drawn behind the messages, with the scrim.
            val peach = BackgroundPalettes.ALL.first { it.id == "peach" }.style
            aliceChanged("BG1", 1_000, null, peach)
            // The background is drawn as soon as the chat is read again, its row once the list built
            // from that read is handed over (on a thread of its own): both are waited for.
            scenario.waitFor("her gradient, and the row saying so") {
                background(it).visibility == View.VISIBLE && drawableOf(it) is GradientBackgroundDrawable &&
                    shownTexts(it).contains("Alice changed the chat background.")
            }
            scenario.onActivity { activity ->
                assertEquals("gradient:${peach.data}", background(activity).tag)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.chatBackgroundScrim).visibility)
                assertTrue(shownTexts(activity).contains("Alice changed the chat background."))
                // Her bubble is opaque over it, so its text reads as on no background.
                val bubble = bubbleOf(activity, "Hello")!!.findViewById<View>(R.id.bubble)
                assertEquals(255, Color.alpha(bubble.backgroundTintList!!.defaultColor))
            }
            // Aurora: drawn moving while the screen is in front, still when it is not.
            aliceChanged("BG2", 2_000, null, BackgroundStyle.Dynamic("aurora_1"))
            scenario.waitFor("her aurora") { drawableOf(it) is AuroraDrawable }
            scenario.onActivity { assertTrue((drawableOf(it) as AuroraDrawable).isRunning) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.onActivity { assertFalse((drawableOf(it) as AuroraDrawable).isRunning) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { assertTrue((drawableOf(it) as AuroraDrawable).isRunning) }
            // One not known here: a plain gradient all the same.
            aliceChanged("BG3", 3_000, null, BackgroundStyle.Dynamic("comet_7"))
            scenario.waitFor("drawn as a gradient") { drawableOf(it) is GradientBackgroundDrawable && background(it).tag == "dynamic:comet_7" }
        }
    }

    @Test
    fun withAnimationsOffTheAuroraStandsStill() {
        directMode()
        android.provider.Settings.Global.putFloat(two.app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            aliceChanged("BG1", 1_000, null, BackgroundStyle.Dynamic("aurora_1"))
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
                scenario.waitFor("her aurora") { drawableOf(it) is AuroraDrawable }
                scenario.onActivity { assertFalse((drawableOf(it) as AuroraDrawable).isRunning) }
            }
        } finally {
            android.provider.Settings.Global.putFloat(two.app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun aPickedBackgroundIsPreviewedAndOnlySetSendsIt() {
        directMode()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her message") { shownTexts(it).contains("Hello") }
            scenario.waitFor("Background in the menu") { menu(it).findItem(R.id.action_chat_background).isVisible }
            // No background yet: no None.
            assertTrue(pickerItems(openPicker(scenario)).none { it is BackgroundPicker.Item.Tile && it.pick == BackgroundPicker.Pick.Remove })
            ShadowDialog.getLatestDialog().dismiss()

            // Sunset: shown behind the thread, with Set and Cancel; nothing sent.
            tap(openPicker(scenario), tile("Sunset"))
            val sunset = BackgroundPalettes.ALL.first { it.id == "sunset" }.style
            scenario.waitFor("previewed") { background(it).visibility == View.VISIBLE && background(it).tag == "gradient:${sunset.data}" }
            scenario.onActivity { assertEquals(View.VISIBLE, previewBar(it).visibility) }
            assertTrue(askedNow.none { it.first == DirectWire.BACKGROUND })
            // Cancel: back to none, nothing sent.
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.backgroundPreviewCancel).performClick()) }
            scenario.waitFor("back to none") { background(it).visibility == View.GONE && previewBar(it).visibility == View.GONE }
            ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertTrue(askedNow.none { it.first == DirectWire.BACKGROUND })

            // A design: drawn for the preview, then Set sends it as a photo.
            tap(openPicker(scenario), tile("Galaxy"))
            scenario.waitFor("the design previewed") { background(it).visibility == View.VISIBLE && background(it).tag == "theme:galaxy" }
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.backgroundPreviewSet).performClick()) }
            two.waitUntil("sent") { askedNow.any { it.first == DirectWire.BACKGROUND } }
            val theme = DirectWire.BackgroundRequest.fromJson(askedNow.single { it.first == DirectWire.BACKGROUND }.second)
            assertTrue(theme.path != null)
            assertEquals(null, theme.style)
            scenario.waitFor("kept and shown") { a ->
                val f = ChatBackgrounds(two.app).pictureOf(ALICE_DIRECT)
                f != null && background(a).tag == f.path && previewBar(a).visibility == View.GONE
            }
            scenario.waitFor("said so") { shownTexts(it).contains("You changed the chat background.") }

            // Aurora: sent as Apple's dynamic background.
            tap(openPicker(scenario), tile("Aurora"))
            scenario.waitFor("aurora previewed") { drawableOf(it) is AuroraDrawable }
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.backgroundPreviewSet).performClick()) }
            two.waitUntil("aurora sent") { askedNow.count { it.first == DirectWire.BACKGROUND } == 2 }
            val aurora = DirectWire.BackgroundRequest.fromJson(askedNow.last { it.first == DirectWire.BACKGROUND }.second)
            assertEquals(BackgroundStyle.Dynamic("aurora_1"), aurora.style)
            assertEquals(null, aurora.path)
            two.waitUntil("stored") { ChatBackgrounds(two.app).of(ALICE_DIRECT).style == BackgroundStyle.Dynamic("aurora_1") }
        }
    }

    @Test
    fun withoutTheLibraryThereIsNoBackgroundToSet() {
        // Alice's chat from OpenBubbles' notifications: no library.
        imRepo.upsertConversation(
            ConversationDto(
                id = ALICE_DIRECT, type = IMessageManager.TRANSPORT,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567")),
            ),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { assertFalse(menu(it).findItem(R.id.action_chat_background).isVisible) }
        }
    }

    // --- Send Later -------------------------------------------------------------------------------------

    @Test
    fun sendLaterFromTheEffectSheetGoesWithItsTimeAndCanBeCancelled() {
        directMode()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her message") { shownTexts(it).contains("Hello") }
            scenario.waitFor("reachable") { menu(it).findItem(R.id.action_chat_background).isVisible }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("Happy birthday")
                assertTrue(activity.findViewById<View>(R.id.send).performLongClick())
            }
            ShadowLooper.idleMainLooper()
            val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
            val later = sheet.findViewById<View>(R.id.effectSendLater)!!
            assertEquals(View.VISIBLE, later.visibility)
            later.performClick()
            ShadowLooper.idleMainLooper()
            assertFalse(sheet.isShowing)
            assertTrue("a day is asked for", ShadowDialog.getLatestDialog() is android.app.DatePickerDialog)
            ShadowDialog.getLatestDialog().dismiss()

            // Too soon: nothing goes, the text stays.
            scenario.onActivity { (it as ThreadActivity).sendLaterAt(System.currentTimeMillis() + 10_000) }
            assertEquals(two.app.getString(R.string.imessage_send_later_too_soon), ShadowToast.getTextOfLatestToast())
            assertTrue(askedNow.none { it.first == DirectWire.SEND })

            val at = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
            scenario.onActivity { (it as ThreadActivity).sendLaterAt(at) }
            two.waitUntil("sent with its time") { askedNow.any { it.first == DirectWire.SEND } }
            val sent = DirectWire.SendRequest.fromJson(askedNow.single { it.first == DirectWire.SEND }.second)
            assertEquals("Happy birthday", sent.text)
            assertEquals(at, sent.scheduledMs)
            scenario.waitFor("shown as Send Later") { a ->
                bubbleOf(a, "Happy birthday")?.findViewById<TextView>(R.id.receipt)?.text?.toString()?.startsWith("Send Later · ") == true
            }
            scenario.onActivity { activity -> assertEquals("", activity.findViewById<EditText>(R.id.input).text.toString()) }

            // Long press: Cancel Send Later (and Send Now for a text).
            two.waitUntil("confirmed") { imRepo.message("imsg:S1") != null }
            scenario.waitFor("the confirmed copy") { a -> shownRows(a).any { it.id == "imsg:S1" } }
            scenario.onActivity { activity -> (activity as ThreadActivity).onMessageLongPress(imRepo.message("imsg:S1")!!) }
            ShadowLooper.idleMainLooper()
            val actions = ShadowDialog.getLatestDialog() as BottomSheetDialog
            assertEquals(View.VISIBLE, actions.findViewById<View>(R.id.actionSendNow)!!.visibility)
            assertEquals(View.GONE, actions.findViewById<View>(R.id.actionUnsend)!!.visibility)
            assertEquals(View.GONE, actions.findViewById<View>(R.id.actionEdit)!!.visibility)
            val cancel = actions.findViewById<View>(R.id.actionCancelSendLater)!!
            assertEquals(View.VISIBLE, cancel.visibility)
            cancel.performClick()
            two.waitUntil("taken back through Apple") { askedNow.any { it.first == DirectWire.UNSCHEDULE } }
            assertEquals("S1", askedNow.single { it.first == DirectWire.UNSCHEDULE }.second.getString("guid"))
            scenario.waitFor("gone") { a -> shownTexts(a).none { it == "Happy birthday" } }
        }
    }

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun shownRows(activity: Activity) =
        ((activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList)
            .filterIsInstance<ThreadItem.Message>().map { it.row }

    private fun shownTexts(activity: Activity): List<String> = shownRows(activity).map { it.text }

    private fun bubbleOf(activity: Activity, text: String): View? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.findViewById<TextView>(R.id.body)?.text?.toString() == text }
    }

    private companion object {
        const val IM_DB = "chat-background-screen-test.db"
        const val ALICE_DIRECT = "imsg:tel:+15551234567"
    }
}
