package com.lightdecoder.imessage.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.IMessageManager
import java.io.File
import java.util.Collections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The main process's view of the optional Apple Account: asked once when first read, then as the ":imessage" process tells it. */
@RunWith(AndroidJUnit4::class)
class DirectLinkAccountTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asked = Collections.synchronizedList(ArrayList<Int>())
    private lateinit var db: ArchiveDb

    @Before
    fun setUp() {
        context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        val repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
    }

    @After
    fun tearDown() {
        // Nothing of an account told here is left for the screens of other tests.
        context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    /** Every ACCOUNT request's body, as sent. */
    private val accountRequests = Collections.synchronizedList(ArrayList<String>())

    private fun link(answer: AccountState) = DirectLink.installForTesting(context) { what, request, _ ->
        asked += what
        if (what == DirectWire.ACCOUNT) accountRequests += request.toString()
        if (what == DirectWire.ACCOUNT) answer.toJson() else DirectWire.ok()
    }

    private fun setUpImessage() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
    }

    private fun told(state: AccountState, restarting: Boolean = false) =
        DirectWire.decode(JSONObject(DirectWire.encodeUpdate(DirectArchive.Update.Account(state, restarting)).toString()))

    @Test
    fun withoutIMessageNothingIsAsked() {
        val link = link(AccountState(signedIn = true))
        assertEquals(AccountState.SIGNED_OUT, link.account.value)
        Thread.sleep(100)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun itIsAskedOnceWhenFirstReadThenTakenAsTold() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        val link = link(AccountState(signedIn = true, icloud = true))
        assertTrue("nothing asked before it is read", asked.isEmpty())
        link.account
        waitFor("the answer") { link.account.value.icloud }
        link.account
        assertEquals(listOf(DirectWire.ACCOUNT), asked.toList())
        // Told by the other process (it restarts for it): as told.
        link.apply(DirectWire.decode(JSONObject(DirectWire.encodeUpdate(DirectArchive.Update.Account(AccountState.SIGNED_OUT)).toString())))
        assertEquals(AccountState.SIGNED_OUT, link.account.value)
    }

    @Test
    fun afterAnAccountUpdateIMessagesServiceIsStartedOnceWhateverTheAnswer() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        // The process answers at once (a bind alone brings it back): its service is started all the same (§4.4, D15).
        val link = link(AccountState(signedIn = true, icloud = true))
        val starts = java.util.concurrent.atomic.AtomicInteger()
        link.restartCheckMs = 50
        link.startService = {
            starts.incrementAndGet()
            true
        }
        link.apply(DirectWire.decode(JSONObject(DirectWire.encodeUpdate(DirectArchive.Update.Account(AccountState(signedIn = true))).toString())))
        assertEquals(0, starts.get())
        waitFor("the service started") { starts.get() == 1 }
        waitFor("asked again") { link.account.value.icloud }
        Thread.sleep(150)
        assertEquals("once", 1, starts.get())
    }

    @Test
    fun theCheckAfterAChangeIsMadeOncePerChangeAndNeverSaysVisible() {
        setUpImessage()
        val link = link(AccountState(signedIn = true))
        val starts = java.util.concurrent.atomic.AtomicInteger()
        link.restartCheckMs = 50
        link.startService = {
            starts.incrementAndGet()
            true
        }
        val logged = IMessageLog.read(context).count { it.contains("Apple Account: signed in") }
        // Told the same again and again (each try of a restart tells it): one check, one line.
        repeat(5) { link.apply(told(AccountState(signedIn = true))) }
        waitFor("the check") { starts.get() == 1 }
        Thread.sleep(200)
        repeat(3) { link.apply(told(AccountState(signedIn = true))) }
        Thread.sleep(200)
        assertEquals("one per change", 1, starts.get())
        assertEquals(logged + 1, IMessageLog.read(context).count { it.contains("Apple Account: signed in") })
        // A change: one more.
        link.apply(told(AccountState.SIGNED_OUT))
        waitFor("the next check") { starts.get() == 2 }
        Thread.sleep(150)
        assertEquals(2, starts.get())
        assertTrue("asked again after each", accountRequests.size >= 2)
        assertTrue("never visible: $accountRequests", accountRequests.none { it.contains("visible") })
    }

    @Test
    fun anUpdateSayingWhatWasKnownStartsNoCheck() {
        setUpImessage()
        val link = link(AccountState.SIGNED_OUT)
        val starts = java.util.concurrent.atomic.AtomicInteger()
        link.restartCheckMs = 20
        link.startService = {
            starts.incrementAndGet()
            true
        }
        link.apply(told(AccountState.SIGNED_OUT))
        Thread.sleep(200)
        assertEquals(0, starts.get())
        assertTrue(accountRequests.isEmpty())
    }

    @Test
    fun theScreensSayTheyAreInSightOnlyOnceAnAccountWasToldOf() {
        // Without iMessage: nothing.
        val link = link(AccountState.SIGNED_OUT)
        DirectLink.screenShown(context)
        link.screenShown()
        Thread.sleep(100)
        assertTrue(asked.isEmpty())
        // With iMessage, for a user who never signed in: nothing either (S1).
        setUpImessage()
        repeat(3) { DirectLink.screenShown(context) }
        Thread.sleep(100)
        assertTrue("nothing of the account sent: $asked", asked.isEmpty())
        // Told of one (a sign-out here, its restart pending): each screen that comes up says so, once.
        link.restartCheckMs = 60 * 60_000L
        link.apply(told(AccountState(signedIn = true)))
        link.apply(told(AccountState.SIGNED_OUT))
        DirectLink.screenShown(context)
        waitFor("the visible request") { accountRequests.isNotEmpty() }
        Thread.sleep(100)
        assertEquals(listOf("""{"visible":true}"""), accountRequests.toList())
        assertEquals(listOf(DirectWire.ACCOUNT), asked.toList())
    }

    @Test
    fun anAccountSaidToBeThereWhenAskedLetsTheScreensSaySo() {
        setUpImessage()
        val link = link(AccountState(signedIn = true))
        link.account
        waitFor("the answer") { link.account.value.signedIn }
        link.screenShown()
        waitFor("the visible request") { accountRequests.size == 2 }
        assertEquals(listOf("{}", """{"visible":true}"""), accountRequests.toList())
    }

    @Test
    fun theRestartsOwnTellingStartsTheCheckThoughTheStateIsTheSame() {
        setUpImessage()
        val link = link(AccountState.SIGNED_OUT)
        val starts = java.util.concurrent.atomic.AtomicInteger()
        link.restartCheckMs = 50
        link.startService = {
            starts.incrementAndGet()
            true
        }
        // Signed out here a while ago (told then, the check made then); the restart comes later and
        // tells the same as it ends: the check is made for it all the same.
        link.apply(told(AccountState.SIGNED_OUT))
        Thread.sleep(150)
        assertEquals("the same as known: no check", 0, starts.get())
        link.apply(told(AccountState.SIGNED_OUT, restarting = true))
        waitFor("the check of the restart itself") { starts.get() == 1 }
        Thread.sleep(150)
        link.apply(told(AccountState.SIGNED_OUT))
        Thread.sleep(150)
        assertEquals(1, starts.get())
        // A later restart (one per restart): its own.
        link.apply(told(AccountState.SIGNED_OUT, restarting = true))
        waitFor("the next restart's check") { starts.get() == 2 }
        Thread.sleep(150)
        // Told the same again without it: nothing.
        link.apply(told(AccountState.SIGNED_OUT))
        Thread.sleep(150)
        assertEquals(2, starts.get())
        assertTrue("never visible: $accountRequests", accountRequests.none { it.contains("visible") })
    }

    @Test
    fun aRestartPendingIsKeptInMindByAMainProcessMadeAgainUntilAnAnswerSaysNothingIsLeft() {
        setUpImessage()
        // Signed out (told), its restart pending, and this process ends.
        val first = link(AccountState.SIGNED_OUT)
        first.restartCheckMs = 60 * 60_000L
        first.apply(told(AccountState.SIGNED_OUT))
        DirectLink.resetForTesting()
        // Made again: a chat comes up, and says so.
        var pending = true
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what
            if (what == DirectWire.ACCOUNT) {
                accountRequests += request.toString()
                DirectWire.accountReply(AccountState.SIGNED_OUT, pending)
            } else {
                DirectWire.ok()
            }
        }
        link.screenShown()
        waitFor("the visible request") { accountRequests.size == 1 }
        Thread.sleep(100)
        // Still pending (that try was kept back): the next chat says so too.
        pending = false
        link.screenShown()
        waitFor("the next visible request") { accountRequests.size == 2 }
        Thread.sleep(100)
        // Made since: neither an account nor a restart left, so nothing more is sent.
        link.screenShown()
        DirectLink.screenShown(context)
        Thread.sleep(200)
        assertEquals(listOf("""{"visible":true}""", """{"visible":true}"""), accountRequests.toList())
    }

    @Test
    fun anAnswerAskedForBeforeATellingNeverStopsTheScreensSayingSo() {
        setUpImessage()
        lateinit var link: DirectLink
        var tellWhileAsked = true
        link = DirectLink.installForTesting(context) { what, request, _ ->
            if (what == DirectWire.ACCOUNT) {
                accountRequests += request.toString()
                // A sign-out told while this was being answered: the answer was made before it.
                if (tellWhileAsked) {
                    tellWhileAsked = false
                    link.apply(told(AccountState.SIGNED_OUT))
                }
                AccountState.SIGNED_OUT.toJson()
            } else {
                DirectWire.ok()
            }
        }
        link.restartCheckMs = 60 * 60_000L
        link.apply(told(AccountState(signedIn = true)))
        link.screenShown()
        waitFor("the visible request") { accountRequests.size == 1 }
        Thread.sleep(100)
        link.screenShown()
        waitFor("still said") { accountRequests.size == 2 }
        Thread.sleep(100)
        // Nothing told while that one was answered: no more.
        link.screenShown()
        Thread.sleep(200)
        assertEquals(2, accountRequests.size)
    }

    private companion object {
        const val DB = "direct-link-account-test.db"

        /** DirectLink's own note that an Apple Account was told of. */
        const val SEEN_PREFS = "imessage_account_link"
    }
}
