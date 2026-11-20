package com.lightdecoder.imessage

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.BuildConfig
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.OpenBubblesTestNotifications.ALICE_GUID
import com.lightdecoder.imessage.OpenBubblesTestNotifications.Line
import com.lightdecoder.imessage.OpenBubblesTestNotifications.PACKAGE
import com.lightdecoder.imessage.OpenBubblesTestNotifications.chat
import com.lightdecoder.imessage.OpenBubblesTestNotifications.posted
import com.lightdecoder.imessage.OpenBubblesTestNotifications.sender
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * OpenBubbles is trusted by its signing certificate, never by its package name: an app that only
 * took the name gets nothing in (notifications, saved pictures), nothing out (the user's replies,
 * files), and its buttons are never pressed; nor are buttons another app made.
 */
@RunWith(AndroidJUnit4::class)
class OpenBubblesTrustTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private lateinit var manager: IMessageManager
    private val alice = sender("Alice", "tel:+15551234567")
    private val replies = ArrayList<String>()
    private var markReads = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                OpenBubblesTestNotifications.REPLY_ACTION ->
                    replies += RemoteInput.getResultsFromIntent(intent)?.getCharSequence(OpenBubblesTestNotifications.REPLY_KEY)?.toString() ?: "<none>"
                OpenBubblesTestNotifications.MARK_READ_ACTION -> markReads++
            }
        }
    }

    /** What the certificate check answered (it is asked only when nothing is known for the installed version). */
    private var signedAsPinned = true
    private var checks = 0

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        manager = IMessageManager.installForTesting(context, repo)
        IMessagePrefs.setEnabled(context, true)
        IMessageLog.clear(context)
        OpenBubbles.verifier = { _, pkg, pin ->
            checks++
            assertEquals("only the Play Store build is pinned", OpenBubbles.PLAY_PACKAGE, pkg)
            assertEquals(OpenBubbles.PLAY_CERT_SHA256, pin)
            signedAsPinned
        }
        val filter = IntentFilter().apply {
            addAction(OpenBubblesTestNotifications.REPLY_ACTION)
            addAction(OpenBubblesTestNotifications.MARK_READ_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        IMessagePrefs.setEnabled(context, false)
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        OpenBubbles.resetForTesting()
        IMessageMedia.resetForTesting()
        db.close()
    }

    /** An app under OpenBubbles' Play Store name, last updated at [updated]. */
    private fun install(updated: Long = 1_000L, pkg: String = PACKAGE) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = pkg; lastUpdateTime = updated })
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun log() = IMessageLog.read(context).joinToString("\n")

    @Test
    fun theCertificateIsPinnedAndTheTestBuildsAreForDebugBuildsOnly() {
        assertEquals(OpenBubbles.PLAY_PACKAGE, OpenBubbles.PACKAGES.first())
        assertEquals(32, AppSigning.hexToBytes(OpenBubbles.PLAY_CERT_SHA256)?.size)
        // No certificate is known for the alpha and beta builds: a release build never takes them.
        assertEquals(BuildConfig.DEBUG, OpenBubbles.PACKAGES.containsAll(OpenBubbles.TEST_PACKAGES))
        assertEquals(if (BuildConfig.DEBUG) 3 else 1, OpenBubbles.PACKAGES.size)
        assertFalse(OpenBubbles.isOpenBubbles("com.openbubbles.messaging.fake"))
    }

    @Test
    fun theCheckIsKeptUntilTheAppIsUpdated() {
        assertEquals(OpenBubbles.Trust.NOT_INSTALLED, OpenBubbles.trust(context, PACKAGE))
        install(updated = 1_000L)
        assertEquals(OpenBubbles.Trust.VERIFIED, OpenBubbles.trust(context, PACKAGE))
        assertEquals(OpenBubbles.Trust.VERIFIED, OpenBubbles.trust(context, PACKAGE))
        assertEquals("checked once for that version", 1, checks)
        // Replaced by another app under the same name.
        signedAsPinned = false
        install(updated = 2_000L)
        assertEquals(OpenBubbles.Trust.UNVERIFIED, OpenBubbles.trust(context, PACKAGE))
        assertFalse(OpenBubbles.isTrusted(context, PACKAGE))
        assertNull(OpenBubbles.installedPackage(context))
        assertEquals(PACKAGE, OpenBubbles.impostor(context))
        assertEquals(2, checks)
        // A check that fails is no pass.
        OpenBubbles.verifier = { _, _, _ -> throw SecurityException("no") }
        install(updated = 3_000L)
        assertEquals(OpenBubbles.Trust.UNVERIFIED, OpenBubbles.trust(context, PACKAGE))
        assertFalse(OpenBubbles.isTrusted(context, "com.example.other"))
        assertFalse(OpenBubbles.isTrusted(context, null))
    }

    @Test
    fun aTestBuildIsTakenByNameOnlyInADebugBuild() {
        val beta = "com.bluebubbles.messaging.beta"
        install(pkg = beta)
        assertEquals(BuildConfig.DEBUG, OpenBubbles.isTrusted(context, beta))
        assertEquals("its certificate is not looked at (none is known)", 0, checks)
    }

    @Test
    fun notificationsFromAnImpostorAreNotRead() {
        signedAsPinned = false
        install()
        manager.onNotification(posted(chat(context, listOf(Line("Hi, it's your bank", 1_700_000_000_000L, alice)))), live = true)
        assertNull("nothing stored", repo.conversation(ALICE_GUID))
        assertFalse(manager.canReply(ALICE_GUID))
        assertTrue(log(), log().contains("not signed as OpenBubbles"))
        // The screens say why nothing comes.
        manager.refreshState()
        assertEquals(RelayState.Phase.ERROR, manager.state.value.phase)
        assertEquals(IMessageErrors.NOT_VERIFIED, manager.state.value.code)
        assertTrue(manager.setupSummary(), manager.setupSummary().contains("NOT USED"))
    }

    @Test
    fun aNotificationFromAnotherUserIsNotRead() {
        install()
        val other = android.os.UserHandle.getUserHandleForUid(android.os.Process.myUid() + 100_000 * 10)
        @Suppress("DEPRECATION")
        val sbn = android.service.notification.StatusBarNotification(
            PACKAGE, PACKAGE, 7, OpenBubbles.MESSAGE_TAG, 10200, 0, 0,
            chat(context, listOf(Line("Hi", 1_700_000_000_000L, alice))), other, 5_000L,
        )
        manager.onNotification(sbn, live = true)
        assertNull(repo.conversation(ALICE_GUID))
    }

    @Test
    fun theRealOpenBubblesIsReadAndAnswered() {
        install()
        manager.onNotification(posted(chat(context, listOf(Line("Hi there", 1_700_000_000_000L, alice)))), live = true)
        assertNotNull(repo.conversation(ALICE_GUID))
        manager.sendText(ALICE_GUID, "On my way")
        idle()
        assertEquals(listOf("On my way"), replies)
    }

    @Test
    fun buttonsAnotherAppMadeAreNeverPressed() {
        install()
        // The real OpenBubbles' notification, but its buttons are another app's PendingIntents.
        manager.onNotification(
            posted(chat(context, listOf(Line("Hi there", 1_700_000_000_000L, alice)), buttonsBy = "com.example.collector")),
            live = true,
        )
        assertNotNull("the messages are kept", repo.conversation(ALICE_GUID))
        assertFalse("but there is no Reply button to send through", manager.canReply(ALICE_GUID))
        val failure = runCatching { manager.sendText(ALICE_GUID, "my secret") }.exceptionOrNull()
        assertEquals(IMessageErrors.NO_REPLY_BUTTON, (failure as RelayException).code)
        assertFalse(IMessageReplies.markRead(context, ALICE_GUID))
        assertFalse(IMessageReplies.open(context, ALICE_GUID))
        idle()
        assertTrue(replies.isEmpty())
        assertEquals(0, markReads)
    }

    @Test
    fun aButtonIsCheckedAgainBeforeItIsPressed() {
        install(updated = 1_000L)
        manager.onNotification(posted(chat(context, listOf(Line("Hi there", 1_700_000_000_000L, alice)))), live = true)
        assertTrue(manager.canReply(ALICE_GUID))
        // OpenBubbles was replaced (uninstalled, and another app installed under its name) since.
        signedAsPinned = false
        install(updated = 9_000L)
        assertEquals(IMessageReplies.Result.WITHDRAWN, IMessageReplies.send(context, ALICE_GUID, "my secret"))
        assertFalse("dropped", manager.canReply(ALICE_GUID))
        assertTrue(runCatching { manager.sendText(ALICE_GUID, "my secret") }.isFailure)
        idle()
        assertTrue(replies.isEmpty())
    }

    @Test
    fun nothingIsHandedToAnImpostor() {
        signedAsPinned = false
        install()
        repo.upsertConversation(
            com.lightdecoder.relay.ConversationDto(
                id = ALICE_GUID, name = "Alice", isGroup = false, lastMessageTs = 1L,
                participants = listOf(com.lightdecoder.relay.ParticipantDto(id = "p", name = "Alice", number = "+15551234567", isMe = false)),
            ),
        )
        val file = Uri.parse("content://${context.packageName}.fileprovider/imessage-outbox/x")
        assertFalse(manager.handOff(context, ALICE_GUID, "hello", listOf(file to "image/jpeg")))
        assertNull("no screen of it opened, no file granted", shadowOf(context as android.app.Application).nextStartedActivity)
        assertEquals(OpenBubbles.NewChat.NOT_INSTALLED, OpenBubbles.openNewChat(context, "+15551234567", "hi"))
        assertFalse(OpenBubbles.launch(context))
        assertTrue(log(), log().contains("not signed as OpenBubbles"))
    }

    /** The gallery, answering with whatever is asked of it, and noting which apps' files were asked for. */
    class Gallery : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            asked += selectionArgs.orEmpty().toList()
            return MatrixCursor(projection ?: arrayOf("_id"))
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            val asked = ArrayList<String>()
        }
    }

    @Test
    fun onlyTheRealOpenBubblesSavedPicturesAreTaken() {
        Gallery.asked.clear()
        Robolectric.buildContentProvider(Gallery::class.java).create(ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        IMessageMedia.resetForTesting()
        signedAsPinned = false
        install()
        assertTrue(IMessageMedia.source(context, 0, 10).isEmpty())
        assertTrue("the gallery is not even asked", Gallery.asked.isEmpty())
        signedAsPinned = true
        install(updated = 5_000L)
        IMessageMedia.source(context, 0, 10)
        assertTrue(Gallery.asked.toString(), PACKAGE in Gallery.asked)
        // Only apps that are OpenBubbles here: a test build that is not installed is not asked for.
        assertFalse(Gallery.asked.toString(), "com.bluebubbles.messaging.beta" in Gallery.asked)
    }

    private companion object {
        const val DB = "imessage-trust-test.db"
    }
}
