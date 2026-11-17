package com.lightdecoder.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ThreadActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The call button and avatar in a conversation's top bar. */
@RunWith(AndroidJUnit4::class)
class ThreadCallTest {

    private lateinit var env: RelayTestEnv
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
        env.pairedManager()
    }

    @After
    fun tearDown() {
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    private fun settle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(10)
    }

    private fun seed(id: String, vararg people: Pair<String, String>) {
        env.repo.upsertConversation(
            ConversationDto(
                id = id, name = people.first().first, isGroup = people.size > 1, folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true)) +
                    people.mapIndexed { i, (name, number) -> ParticipantDto(id = "p$i", name = name, number = number, formattedNumber = number) },
            )
        )
    }

    @Test
    fun aOneToOneConversationCanBeCalledFromItsTopBar() {
        seed("c1", "Nina" to "+15550123456")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                assertNotNull("the avatar is next to the name", toolbar.logo)
                assertEquals("Nina", activity.supportActionBar?.title?.toString())
                org.junit.Assert.assertNull("no phone number under the name", activity.supportActionBar?.subtitle)
                val call = toolbar.menu.findItem(R.id.action_call)
                assertTrue(call.isVisible)
                activity.onOptionsItemSelected(call)
                val dial = shadowOf(app).nextStartedActivity
                assertEquals(Intent.ACTION_DIAL, dial.action)
                assertEquals("tel", dial.data!!.scheme)
                assertEquals("+15550123456", dial.data!!.schemeSpecificPart)
            }
        }
    }

    @Test
    fun aGroupHasNoCallButton() {
        seed("g1", "Nina" to "+15550123456", "Omar" to "+15550654321")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "g1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_call).isVisible)
            }
        }
    }
}
