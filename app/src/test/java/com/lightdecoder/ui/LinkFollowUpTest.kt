package com.lightdecoder.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.MediaLoader
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.ThreadItems
import com.lightdecoder.ui.relay.LocalArchive
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream
import java.time.Duration

/**
 * The second round of fixes in the screens: an unpair that could not reach
 * Google says how to remove the device on the phone, the kept archive is
 * deleted through the manager, pictures that need the link say "Connect to
 * load" (and load once it is connected), About > Licences shows the licence
 * texts that ship in the app, debug builds warn before pairing, and the
 * viewer no longer offers to open file paths.
 */
@RunWith(AndroidJUnit4::class)
class LinkFollowUpTest {

    private lateinit var env: RelayTestEnv
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
    }

    @After
    fun tearDown() {
        MessageNotifier.visibleConversationId = null
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    // --- an unpair Google did not hear about -------------------------------------------

    @Test
    fun anUnpairThatCouldNotReachGoogleSaysHowToRemoveTheDevice() {
        assertEquals(R.string.link_error_unpair_remote_failed, RelayErrors.messageRes(RelayState.UNPAIR_REMOTE_FAILED))
        assertTrue(RelayErrors.isKnown(RelayState.UNPAIR_REMOTE_FAILED))
        val (manager, fake) = env.pairedManager()
        fake.unpairCode = RelayState.UNPAIR_REMOTE_FAILED
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("unpair offered") { it.findViewById<View>(R.id.unpair).isEnabled }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.unpair).performClick()
                latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("the explanation") {
                val dialog = ShadowDialog.getLatestDialog()
                dialog != null && dialog.isShowing &&
                    hasText(dialog.window!!.decorView, it.getString(R.string.link_unpair_remote_failed_title))
            }
            assertEquals(RelayState.UNPAIR_REMOTE_FAILED, manager.state.value.code)
            // Behind the dialog the screen says it too.
            scenario.waitFor("the state's reason") {
                it.findViewById<TextView>(R.id.stateReason).text.toString() ==
                    it.getString(R.string.link_error_unpair_remote_failed)
            }
            var status: Activity? = null
            scenario.onActivity { activity ->
                status = activity
                val dialog = latestDialog()
                assertTrue(hasText(dialog.window!!.decorView, activity.getString(R.string.link_unpair_remote_failed_body)))
                assertTrue(activity.getString(R.string.link_unpair_remote_failed_body).contains("Device pairing"))
                assertFalse("still here until the user read it", activity.isFinishing)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            settle()
            val activity = status!!
            assertTrue(activity.isFinishing)
            assertEquals(InboxActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        }
        // The home screen's link card says the same while the state lasts.
        ActivityScenario.launch(InboxActivity::class.java).use { scenario ->
            scenario.waitFor("the card's explanation") {
                it.findViewById<TextView>(R.id.relayBody).text.toString() ==
                    it.getString(R.string.link_error_unpair_remote_failed)
            }
        }
    }

    @Test
    fun lastErrorOfARemoteUnpairFailureIsExplained() {
        env.manager()
        env.repo.updateHealth(status = "unpaired", lastError = RelayState.UNPAIR_REMOTE_FAILED)
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the explained last error") {
                hasText(
                    it.window.decorView,
                    it.getString(R.string.link_status_last_error, it.getString(R.string.link_error_unpair_remote_failed)),
                )
            }
        }
    }

    // --- the kept archive goes through the manager -------------------------------------

    @Test
    fun theKeptArchiveIsDeletedThroughTheManagerAndNeverWhilePaired() {
        val (manager, _) = env.pairedManager()
        seedConversation("c1", "Alice")
        assertFalse("refused while paired", runBlocking { LocalArchive.deleteUnpaired(manager) })
        assertNotNull(env.repo.conversation("c1"))

        runBlocking { manager.unpair(deleteArchive = false) }
        assertNotNull("the unpair kept it", env.repo.conversation("c1"))
        assertTrue(runBlocking { LocalArchive.deleteUnpaired(manager) })
        assertTrue(env.repo.conversations(null, 10).isEmpty())
    }

    // --- pictures that need the link ----------------------------------------------------

    @Test
    fun aPictureThatNeedsTheLinkSaysConnectToLoadAndLoadsOnceConnected() {
        val (manager, fake) = env.pairedManager()
        seedConversation("c1", "Alice")
        fake.media["x1"] = png()
        fake.downloadError = RelayException(RelayException.NOT_CONNECTED, "connect to load this media")
        env.repo.upsertMessage(pictureMessage("m1", "x1"))
        val row = env.repo.messages("c1", limit = 10).single()
        assertFalse(manager.state.value.isOnline)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val loader = MediaLoader(manager, scope)
            val context = ContextThemeWrapper(app, R.style.Theme_LightDecoder)
            val parent = FrameLayout(context)
            val adapter = MessagesAdapter(loader, NoCallbacks)
            val items = ThreadItems.build(listOf(row), false, emptyMap(), null, null, { 0L }, { "" })
                .filterIsInstance<ThreadItem.Message>()
            adapter.submitList(items)
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)

            waitUntil("Connect to load") { status(holder)?.visibility == View.VISIBLE }
            assertEquals(app.getString(R.string.chat_media_connect_to_load), status(holder)!!.text.toString())
            assertTrue("retried once connected", loader.hadFailures)
            assertFalse(image(holder)!!.drawable is BitmapDrawable)
            val tries = fake.downloads.size

            // A rebind while still offline says so again without another download that cannot work.
            adapter.onBindViewHolder(holder, 0)
            settle()
            assertEquals(View.VISIBLE, status(holder)?.visibility)
            assertEquals(tries, fake.downloads.size)

            // Connected (the screen clears the failures and rebinds): it loads.
            fake.downloadError = null
            loader.clearFailures()
            adapter.onBindViewHolder(holder, 0)
            assertEquals(View.GONE, status(holder)?.visibility)
            waitUntil("the picture") { image(holder)?.drawable is BitmapDrawable }
            assertEquals(View.GONE, status(holder)?.visibility)
            assertFalse(loader.hadFailures)
            assertTrue(fake.downloads.size > tries)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aPictureThatIsGoneIsNotOfferedAConnection() {
        val (manager, fake) = env.pairedManager()
        seedConversation("c1", "Alice")
        env.repo.upsertMessage(pictureMessage("m1", "missing"))
        val row = env.repo.messages("c1", limit = 10).single()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val loader = MediaLoader(manager, scope)
            val adapter = MessagesAdapter(loader, NoCallbacks)
            adapter.submitList(
                ThreadItems.build(listOf(row), false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>()
            )
            val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_LightDecoder))
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)
            waitUntil("the failed download") { loader.hadFailures }
            settle()
            assertTrue("missing" in fake.downloads)
            assertEquals(View.GONE, status(holder)?.visibility)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun tappingAPictureThatNeedsTheLinkSaysToConnect() {
        val (_, fake) = env.pairedManager()
        seedConversation("c1", "Alice")
        fake.downloadError = RelayException(RelayException.NOT_CONNECTED, "connect to load this media")
        env.repo.upsertMessage(pictureMessage("m1", "x1"))
        val row = env.repo.messages("c1", limit = 10).single()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            ShadowToast.reset()
            scenario.onActivity { it.onMediaTap(row, row.media.single()) }
            scenario.waitFor("the toast") {
                ShadowToast.getTextOfLatestToast() == it.getString(R.string.chat_media_needs_connection)
            }
        }
    }

    // --- licences -----------------------------------------------------------------------

    @Test
    fun theLicenceTextsShipInTheApp() {
        val assets = app.assets
        val files = assets.list(Licenses.DIR).orEmpty().toList()
        assertTrue("libgm's licence is in the app: $files", "20-go-go.mau.fi_mautrix-gmessages.txt" in files)
        assertTrue("$files", "00-lightdecoder.txt" in files)
        assertEquals(files.filter { it.endsWith(".txt") }.sorted(), Licenses.files(assets))
        val notice = assets.open(Licenses.NOTICE).bufferedReader().use { it.readText() }
        assertTrue(notice.contains("mautrix-gmessages"))
        assertTrue(notice.contains("About > Licences"))

        val texts = Licenses.read(assets)
        assertEquals(Licenses.NOTICE, texts.first().first)
        assertEquals(1 + Licenses.files(assets).size, texts.size)
        val gm = texts.single { it.first.startsWith("go.mau.fi/mautrix-gmessages ") }
        assertTrue(gm.second.contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
    }

    @Test
    fun licenceTextsAreCutIntoShortPiecesWithoutLosingAnything() {
        val long = (1..400).joinToString("\n") { "line $it of a paragraph with no blank lines in it" }
        val endless = "x".repeat(5_000)
        val texts = listOf("NOTICE" to "First paragraph.\n\nSecond one.\r\n\r\nThird.", "Big" to "$long\n\n$endless")
        val pieces = Licenses.pieces(texts)
        assertEquals(listOf("NOTICE", "Big"), pieces.filter { it.heading }.map { it.text })
        assertTrue(pieces.all { it.text.length <= 2_000 })
        val body = pieces.filter { !it.heading }.joinToString("") { it.text }.filter { !it.isWhitespace() }
        val expected = texts.joinToString("") { it.second }.filter { !it.isWhitespace() }
        assertEquals(expected, body)
    }

    @Test
    fun aboutOffersTheLicencesAndTheyOpen() {
        env.manager()
        ActivityScenario.launch(InboxActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                activity.onOptionsItemSelected(RoboMenuItem(R.id.action_about))
                val about = latestDialog()
                assertTrue(hasText(about.window!!.decorView, activity.getString(R.string.about_body)))
                val licences = about.getButton(AlertDialog.BUTTON_NEUTRAL)
                assertEquals(activity.getString(R.string.action_licenses), licences.text.toString())
                licences.performClick()
                // The dialog hands the click on through a Handler.
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(LicensesActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            }
        }
        val about = app.getString(R.string.about_body)
        assertTrue(about.contains("mautrix-gmessages") && about.contains("AGPL-3.0") && about.contains("github.com/mautrix/gmessages"))
        assertTrue(about.contains("MIT") && about.contains("BSD-3-Clause") && about.contains("MPL-2.0"))

        ActivityScenario.launch(LicensesActivity::class.java).use { scenario ->
            scenario.waitFor("the texts") { it.findViewById<RecyclerView>(R.id.list).childCount > 0 }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.progress).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.failed).visibility)
                assertEquals(Licenses.NOTICE, (list.getChildAt(0) as TextView).text.toString())
                assertTrue(list.adapter!!.itemCount > Licenses.files(activity.assets).size * 2)
            }
        }
    }

    // --- debug builds warn before pairing --------------------------------------------------

    @Test
    fun theDisclosureWarnsOnDebugBuildsOnly() {
        env.manager()
        val info = app.applicationInfo
        val original = info.flags
        try {
            for (debuggable in listOf(true, false)) {
                info.flags = if (debuggable) original or ApplicationInfo.FLAG_DEBUGGABLE
                else original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                ActivityScenario.launch(RelaySetupActivity::class.java).use { scenario ->
                    settle()
                    scenario.onActivity { activity ->
                        assertEquals(debuggable, RelaySetupActivity.isDebuggableBuild(activity))
                        val warning = activity.findViewById<View>(R.id.introDebugWarning)
                        assertEquals("debuggable=$debuggable", debuggable, warning.isShown)
                        if (debuggable) {
                            assertTrue(activity.findViewById<View>(R.id.stepIntro).isShown)
                            assertTrue(activity.getString(R.string.link_intro_debug).contains("adb"))
                        }
                    }
                }
            }
        } finally {
            info.flags = original
        }
    }

    // --- manifest -----------------------------------------------------------------------

    @Test
    fun theViewerOffersToOpenContentButNotFilePaths() {
        val pm = app.packageManager
        fun viewers(uri: Uri, type: String) = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addCategory(Intent.CATEGORY_DEFAULT), 0,
        ).filter { it.activityInfo.packageName == app.packageName }.map { it.activityInfo.name }
        val viewer = ViewerActivity::class.java.name
        assertEquals(listOf(viewer), viewers(Uri.parse("content://com.example.files/sticker.heic"), "image/heic"))
        assertTrue(viewers(Uri.parse("file:///sdcard/Download/sticker.heic"), "image/heic").isEmpty())
        assertTrue(viewers(Uri.parse("file:///sdcard/Download/sticker.heic"), "application/octet-stream").isEmpty())

        val thread = pm.getActivityInfo(ComponentName(app, ThreadActivity::class.java), 0)
        assertEquals(ConversationsActivity::class.java.name, thread.parentActivityName)
    }

    // --- helpers ----------------------------------------------------------------------

    private object NoCallbacks : MessagesAdapter.Callbacks {
        override fun onMessageLongPress(row: MessageRow) = Unit
        override fun onFailedTap(row: MessageRow) = Unit
        override fun onMediaTap(row: MessageRow, part: MediaRow) = Unit
        override fun onLoadFullSize(row: MessageRow, part: MediaRow) = Unit
        override fun onQuoteTap(messageId: String) = Unit
        override fun onReactionTap(row: MessageRow, reaction: ReactionDto) = Unit
        override fun onReply(row: MessageRow) = Unit
    }

    private fun pictureMessage(id: String, mediaId: String) = MessageDto(
        id = id, conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 1_000L, isOld = true,
        parts = listOf(PartDto(partId = "0", kind = "media", mediaId = mediaId, mime = "image/png", size = 100)),
    )

    private fun status(holder: RecyclerView.ViewHolder): TextView? =
        (holder as MessagesAdapter.MessageHolder).binding.mediaContainer.getChildAt(0)?.findViewById(R.id.mediaStatus)

    private fun image(holder: RecyclerView.ViewHolder): ImageView? =
        (holder as MessagesAdapter.MessageHolder).binding.mediaContainer.getChildAt(0)?.findViewById(R.id.mediaImage)

    private fun latestDialog(): AlertDialog {
        val dialog: Dialog? = ShadowDialog.getLatestDialog()
        assertNotNull("no dialog", dialog)
        assertTrue("dialog not showing", dialog!!.isShowing)
        return dialog as AlertDialog
    }

    private fun seedConversation(id: String, name: String, lastTs: Long = 1_000) {
        env.repo.upsertConversation(
            ConversationDto(
                id = id,
                name = name,
                folder = "inbox",
                lastMessageTs = lastTs,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = name)),
            )
        )
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun settle(rounds: Int = 5) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, timeoutMs: Long = 10_000, condition: (A) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            var ok = false
            onActivity { ok = condition(it) }
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun hasText(root: View, text: String): Boolean {
        if (root is TextView && root.text.toString() == text) return true
        if (root is ViewGroup) for (i in 0 until root.childCount) if (hasText(root.getChildAt(i), text)) return true
        return false
    }
}
