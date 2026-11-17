package com.lightdecoder.mail

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.mail.MailComposeActivity
import com.lightdecoder.ui.mail.MailSetupActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The Gmail screens against the real Gmail runtime over a fake mail server. */
@RunWith(AndroidJUnit4::class)
class MailScreensTest {

    private lateinit var env: MailTestEnv

    @Before
    fun setUp() {
        env = MailTestEnv()
        env.manager()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    @Test
    fun addingTheAccountChecksItWithGmailAndShowsItsSettings() {
        env.fake.receive("ann@example.com", "Lunch", "Hi")
        ActivityScenario.launch(MailSetupActivity::class.java).use { scenario ->
            waitFor(scenario, "the add form") { it.findViewById<View>(R.id.addSection).isShown }
            scenario.onActivity { a ->
                a.findViewById<TextView>(R.id.email).text = "me@gmail.com"
                a.findViewById<TextView>(R.id.password).text = "wrong password"
                a.findViewById<View>(R.id.add).performClick()
            }
            waitFor(scenario, "the error") { it.findViewById<View>(R.id.error).isShown }
            scenario.onActivity { a ->
                assertEquals(a.getString(R.string.mail_error_auth_failed), a.findViewById<TextView>(R.id.error).text.toString())
                a.findViewById<TextView>(R.id.password).text = FakeMailBridge.PASSWORD
                a.findViewById<View>(R.id.add).performClick()
            }
            waitFor(scenario, "the settings") { it.findViewById<View>(R.id.statusSection).isShown }
            scenario.onActivity { a ->
                assertEquals(a.getString(R.string.mail_status_account, "me@gmail.com"), a.findViewById<TextView>(R.id.account).text.toString())
            }
        }
        assertEquals(1, env.repo.conversations(Folder.INBOX).size)
    }

    @Test
    fun theGmailTabListsThreadsAndItsButtonWritesAnEmail() {
        env.fake.receive("ann@example.com", "Lunch", "Hi")
        runBlocking { com.lightdecoder.mail.MailManager.get(env.context).addAccount("me@gmail.com", FakeMailBridge.PASSWORD) }
        val intent = Intent(env.context, ConversationsActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, ChatService.GMAIL.key)
        ActivityScenario.launch<ConversationsActivity>(intent).use { scenario ->
            waitFor(scenario, "the thread") { a -> hasText(a.findViewById(android.R.id.content), "Lunch") }
            scenario.onActivity { a ->
                val tabs = a.findViewById<TabLayout>(R.id.services)
                // Paused by the user: the list still works when opened for Gmail, but has no Gmail tab.
                // (Discord's tab is counted apart: it is not a service.)
                val serviceTabs = (0 until tabs.tabCount).count { tabs.getTabAt(it)?.tag is ChatService }
                assertEquals(com.lightdecoder.chat.Features.shownServices().size, serviceTabs)
                a.findViewById<View>(R.id.fab).performClick()
                val next = shadowOf(env.context as Application).nextStartedActivity
                assertEquals(MailComposeActivity::class.java.name, next.component?.className)
            }
        }
    }

    @Test
    fun composingSendsAndOpensTheNewThread() {
        runBlocking { com.lightdecoder.mail.MailManager.get(env.context).addAccount("me@gmail.com", FakeMailBridge.PASSWORD) }
        ActivityScenario.launch(MailComposeActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { a ->
                a.findViewById<TextView>(R.id.to).text = "dan@example.com"
                a.findViewById<TextView>(R.id.subject).text = "Weekend"
                a.findViewById<TextView>(R.id.body).text = "Free on Saturday?"
                a.findViewById<View>(R.id.send).performClick()
            }
            env.eventually("the email to go out") { env.fake.sent.isNotEmpty() }
            settle()
            val next = shadowOf(env.context as Application).nextStartedActivity
            assertEquals(ThreadActivity::class.java.name, next.component?.className)
            assertEquals(ChatService.GMAIL, RelayIntents.serviceOf(next))
        }
        val email = env.fake.sent.single()
        assertEquals("Weekend", email.subject)
        assertEquals("Free on Saturday?", email.text)
        assertTrue(email.to.single().email == "dan@example.com")
    }

    private fun settle() = repeat(5) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(10)
    }

    private fun <A : android.app.Activity> waitFor(scenario: ActivityScenario<A>, what: String, condition: (A) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            var ok = false
            scenario.onActivity { ok = condition(it) }
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun hasText(root: View, text: String): Boolean {
        if (root is TextView && root.isShown && root.text.toString() == text) return true
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) if (hasText(root.getChildAt(i), text)) return true
        return false
    }
}
