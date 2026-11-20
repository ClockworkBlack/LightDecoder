package com.lightdecoder.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.materialswitch.MaterialSwitch
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayPrefs
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.MediaLoader
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.ThreadItems
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the review fixes in the messaging screens: the link
 * never starts without its notification, a kept archive can be deleted
 * without a pairing, a thread switched from a notification starts clean, a
 * notification-opened thread has the list behind it, the viewer does not
 * open this app's private files for other apps, pasted cookies leave the
 * clipboard, and a scrolled-away picture stops loading.
 */
@RunWith(AndroidJUnit4::class)
class LinkFixesTest {

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

    // --- the link never runs without its notification -------------------------------

    @Test
    fun stayConnectedDoesNothingWhileNotificationsAreOff() {
        env.pairedManager()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications(enabled = false)
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the notifications-off state") {
                it.findViewById<View>(R.id.turnOnNotifications).isShown &&
                    it.findViewById<TextView>(R.id.stateTitle).text.toString() ==
                    it.getString(R.string.link_phase_notifications_off)
            }
            scenario.onActivity { activity ->
                shadowOf(app).clearStartedServices()
                val live = activity.findViewById<MaterialSwitch>(R.id.switchLive)
                live.performClick()
                assertFalse("switch went back off", live.isChecked)
                assertFalse("stay connected not saved", RelayPrefs.liveEnabled(activity))
                assertNull("the service was started", shadowOf(app).nextStartedService)
                // The user is told why, and offered the settings.
                val dialog = latestDialog()
                assertTrue(hasText(dialog.window!!.decorView, activity.getString(R.string.link_notifications_needed_body)))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle() // dialog buttons answer through a handler
                val settings = shadowOf(activity).nextStartedActivityForResult?.intent
                assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings?.action)
            }
        }
    }

    @Test
    fun aRefusedPermissionDoesNotStartTheLink() {
        env.pairedManager()
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the controls") { it.findViewById<View>(R.id.controls).isShown }
            scenario.onActivity { activity ->
                // Android 13+: no permission yet, so it is asked for first.
                notifications(enabled = false)
                shadowOf(app).clearStartedServices()
                activity.findViewById<MaterialSwitch>(R.id.switchLive).performClick()
                val request = shadowOf(activity).lastRequestedPermission
                assertNotNull("permission asked for", request)
                assertEquals(Manifest.permission.POST_NOTIFICATIONS, request.requestedPermissions.single())
                activity.onRequestPermissionsResult(
                    request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED),
                )
            }
            settle()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<MaterialSwitch>(R.id.switchLive).isChecked)
                assertFalse(RelayPrefs.liveEnabled(activity))
                assertNull(shadowOf(app).nextStartedService)
                assertTrue(latestDialog().isShowing)
            }
        }
    }

    @Test
    fun bannerOffersNotificationSettingsInsteadOfConnect() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        notifications(enabled = false)
        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            scenario.waitFor("the banner") { activity ->
                activity.findViewById<TextView>(R.id.bannerAction).let {
                    it.isShown && it.text.toString() == activity.getString(R.string.link_fix_notifications)
                }
            }
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.link_phase_notifications_off),
                    activity.findViewById<TextView>(R.id.bannerText).text.toString(),
                )
                activity.findViewById<View>(R.id.bannerAction).performClick()
                assertNotNull(shadowOf(activity).nextStartedActivityForResult)
            }
        }
    }

    // --- an archive kept after unpairing ---------------------------------------------

    @Test
    fun statusDeletesAnArchiveKeptAfterUnpairing() {
        env.manager()
        seedConversation("c1", "Alice")
        File(MediaCache.mediaDir(app.filesDir), "kept.bin").apply { parentFile!!.mkdirs(); writeText("x") }
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the delete-archive card") { it.findViewById<View>(R.id.archiveCard).isShown }
            scenario.onActivity { activity ->
                assertFalse("no unpair without a pairing", activity.findViewById<View>(R.id.controls).isShown)
                activity.findViewById<View>(R.id.deleteArchive).performClick()
                latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("the archive gone") {
                env.repo.conversations(null, 10).isEmpty() && !it.findViewById<View>(R.id.archiveCard).isShown
            }
            assertFalse(MediaCache.mediaDir(app.filesDir).exists())
        }
    }

    @Test
    fun withoutAPairingAConversationIsDeletedOnThisDeviceOnly() {
        env.manager()
        seedConversation("c1", "Alice", lastTs = 2_000)
        seedConversation("c2", "Bob", lastTs = 1_000)
        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            scenario.waitFor("both rows") { it.findViewById<RecyclerView>(R.id.list).let { l -> l.childCount == 2 } }
            scenario.onActivity { activity ->
                val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
                assertTrue("delete archive offered", menu.findItem(R.id.action_delete_archive).isVisible)
                assertFalse("nothing to refresh from", menu.findItem(R.id.action_refresh).isVisible)
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performLongClick()
                val actions = latestDialog()
                val delete = activity.getString(R.string.chat_action_delete)
                val index = (0 until actions.listView.adapter.count).first { actions.listView.adapter.getItem(it) == delete }
                actions.listView.performItemClick(null, index, 0)
                val confirm = latestDialog()
                assertTrue(hasText(confirm.window!!.decorView, activity.getString(R.string.chat_delete_conversation_body_local)))
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("Alice deleted") { env.repo.conversation("c1") == null }
            assertNotNull(env.repo.conversation("c2"))
        }
    }

    // --- thread: switching, Up and Back -----------------------------------------------

    @Test
    fun switchingThreadFromANotificationDropsTheDraft() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        seedConversation("c2", "Bob")
        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c1").setFlags(0)).setup()
        try {
            val activity = controller.get()
            val input = activity.findViewById<EditText>(R.id.input)
            input.setText("private reply for Alice")
            controller.newIntent(RelayIntents.thread(app, "c2").setFlags(0))
            assertEquals("", input.text.toString())
            settle()
            assertEquals("Bob", activity.supportActionBar?.title?.toString())
            assertFalse("nothing to send", activity.findViewById<View>(R.id.send).isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun upAndBackFromANotificationOpenedThreadLeadToTheConversations() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        val up = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c1").setFlags(0)).setup()
        try {
            val activity = up.get()
            activity.onOptionsItemSelected(RoboMenuItem(android.R.id.home))
            val next = shadowOf(activity).nextStartedActivity
            assertEquals(ConversationsActivity::class.java.name, next?.component?.className)
            assertTrue(next!!.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue(activity.isFinishing)
        } finally {
            up.pause().stop().destroy()
        }

        // Back from a thread that is the whole task (a cold start from a notification).
        val back = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c1").setFlags(0)).setup()
        try {
            val activity = back.get()
            shadowOf(activity).setIsTaskRoot(true)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(ConversationsActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            assertTrue(activity.isFinishing)
        } finally {
            back.pause().stop().destroy()
        }

        // Back with the app's screens below: just back.
        val below = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c1").setFlags(0)).setup()
        try {
            val activity = below.get()
            shadowOf(activity).setIsTaskRoot(false)
            activity.onBackPressedDispatcher.onBackPressed()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(activity.isFinishing)
        } finally {
            below.pause().stop().destroy()
        }
    }

    // --- viewer: other apps cannot open this app's files through it ------------------

    @Test
    fun viewerRefusesThisAppsFilesFromOtherApps() {
        val file = File(MediaCache.mediaDir(app.filesDir), "private.png").apply {
            parentFile!!.mkdirs()
            writeBytes(png())
        }
        val external = listOf(
            Intent(Intent.ACTION_VIEW, Uri.fromFile(file)),
            Intent(Intent.ACTION_VIEW, Uri.parse("content://${app.packageName}.fileprovider/relay-media/private.png")),
            Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://0@${app.packageName}.fileprovider/relay-media/private.png")),
            // The user id with its '@' percent-encoded: Uri's host keeps it, the content resolver does not.
            Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://0%40${app.packageName}.fileprovider/relay-media/private.png")),
            Intent(Intent.ACTION_VIEW, Uri.parse("content://${app.packageName}%2Efileprovider/relay-media/private.png")),
        )
        for (intent in external) {
            ShadowToast.reset()
            val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent.putExtra("com.lightdecoder.extra.TITLE", "Spoofed")).setup()
            try {
                val activity = controller.get()
                settle()
                assertEquals(intent.toString(), activity.getString(R.string.cannot_open), ShadowToast.getTextOfLatestToast())
                assertTrue(activity.findViewById<View>(R.id.emptyState).isShown)
                assertEquals(activity.getString(R.string.app_name), activity.supportActionBar?.title?.toString())
            } finally {
                controller.pause().stop().destroy()
            }
        }

        // The app's own intent opens it, with its title.
        val own = ViewerActivity.intentForFile(app, file, "image/png", "Alice")
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, own).setup()
        try {
            val activity = controller.get()
            val deadline = System.currentTimeMillis() + 10_000
            while (!activity.findViewById<View>(R.id.stickerView).isShown) {
                if (System.currentTimeMillis() > deadline) fail("the picture did not open")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                Thread.sleep(20)
            }
            assertEquals("Alice", activity.supportActionBar?.title?.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- setup: pasted cookies ----------------------------------------------------------

    @Test
    fun pastedCookiesAreTakenOffTheClipboard() {
        val cookies = "SID=a; HSID=b; SSID=c; APISID=d; SAPISID=e; OSID=f"
        val (left, toast) = pasteCookies(clip = cookies, pasted = cookies)
        assertEquals("", left)
        assertEquals(app.getString(R.string.link_paste_clipboard_cleared), toast)
    }

    @Test
    fun aClipHoldingOneOfTheUsedCookiesIsTakenOffToo() {
        // The paste dialog said OSID was missing, so the user copied it on its own and added it.
        val (left, toast) = pasteCookies(clip = "OSID=f", pasted = "SID=a; HSID=b; SSID=c; APISID=d; SAPISID=e; OSID=f")
        assertEquals("", left)
        assertEquals(app.getString(R.string.link_paste_clipboard_cleared), toast)
    }

    @Test
    fun anUnrelatedClipStaysButTheKeyboardHistoryIsStillMentioned() {
        // Pasted from the keyboard's clipboard history; the clip is something else since.
        val url = "https://example.com/some/page"
        val (left, toast) = pasteCookies(clip = url, pasted = "SID=a; HSID=b; SSID=c; APISID=d; SAPISID=e; OSID=f")
        assertEquals(url, left)
        assertEquals(app.getString(R.string.link_paste_clipboard_history), toast)
    }

    @Test
    fun whatCountsAsAClipThatHoldsTheCookies() {
        val used = mapOf("SID" to "a".repeat(40), "OSID" to "f")
        val pasted = "SID=${"a".repeat(40)}; OSID=f"
        assertTrue(RelaySetupActivity.clipHoldsCookies(" $pasted\n", pasted, used))
        assertTrue("a superset", RelaySetupActivity.clipHoldsCookies("$pasted; HSID=b", "x", used))
        assertTrue("a long value alone", RelaySetupActivity.clipHoldsCookies("a".repeat(40), "x", used))
        assertFalse("a short value alone could be anything", RelaySetupActivity.clipHoldsCookies("f", "x", used))
        assertFalse("another value", RelaySetupActivity.clipHoldsCookies("OSID=g", "x", used))
        assertFalse(RelaySetupActivity.clipHoldsCookies("https://example.com", "x", used))
    }

    /** Pastes [pasted] in the setup screen with [clip] on the clipboard: what is left there, and what the user is told. */
    private fun pasteCookies(clip: String, pasted: String): Pair<String, String?> {
        // The pairing itself is not under test: let it fail at once.
        env.setUpFake = { it.startPairingError = RelayException(RelayException.NO_DEVICES, "") }
        env.manager()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("clip", clip))
        var result: Pair<String, String?>? = null
        ActivityScenario.launch(RelaySetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<CompoundButton>(R.id.introAgree).isChecked = true
                activity.findViewById<View>(R.id.introContinue).performClick()
            }
            scenario.waitFor("the sign-in step") { it.findViewById<View>(R.id.stepSignIn).isShown }
            scenario.onActivity { it.findViewById<View>(R.id.signInPaste).performClick() }
            settle() // the dialog's own buttons are wired when it has been shown
            scenario.onActivity {
                ShadowToast.reset()
                val dialog = latestDialog()
                dialog.findViewById<EditText>(R.id.pasteInput)!!.setText(pasted)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse("the cookies were used", dialog.isShowing)
                val left = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                result = left to ShadowToast.getTextOfLatestToast()
            }
        }
        return result!!
    }

    // --- pictures of rows scrolled away stop loading -----------------------------------

    @Test
    fun aRecycledRowsQueuedPictureIsNeverDownloaded() {
        val (manager, fake) = env.pairedManager()
        seedConversation("c1", "Alice")
        val release = CountDownLatch(1)
        fake.beforeDownloadReturns = { release.await(10, TimeUnit.SECONDS) }
        for (i in 1..4) {
            fake.media["x$i"] = png()
            env.repo.upsertMessage(
                MessageDto(
                    id = "m$i", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = i * 1_000L,
                    isOld = true,
                    parts = listOf(PartDto(partId = "0", kind = "media", mediaId = "x$i", mime = "image/png", size = 100)),
                )
            )
        }
        val rows = env.repo.messages("c1", limit = 10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val loader = MediaLoader(manager, scope)
            val context = ContextThemeWrapper(app, R.style.Theme_LightDecoder)
            val parent = FrameLayout(context)
            val adapter = MessagesAdapter(loader, NoCallbacks)
            val items = ThreadItems.build(rows, false, emptyMap(), null, null, { 0L }, { "" })
                .filterIsInstance<ThreadItem.Message>()
            adapter.submitList(items)
            val holders = items.indices.map { position ->
                adapter.onCreateViewHolder(parent, adapter.getItemViewType(position)).also { adapter.onBindViewHolder(it, position) }
            }
            // The first three loads take the loader's three slots (two of them already
            // waiting inside a download); the fourth queues behind them.
            waitUntil("the first downloads under way") { fake.downloads.size >= 2 }
            // The fourth row is scrolled away before its turn comes.
            adapter.onViewRecycled(holders[3])
            release.countDown()
            waitUntil("the first three pictures") { fake.downloads.containsAll(listOf("x1", "x2", "x3")) }
            repeat(10) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                Thread.sleep(20)
            }
            assertFalse("a scrolled-away picture was downloaded", "x4" in fake.downloads)
        } finally {
            release.countDown()
            scope.cancel()
        }
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

    private fun notifications(enabled: Boolean) {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(enabled)
    }

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
