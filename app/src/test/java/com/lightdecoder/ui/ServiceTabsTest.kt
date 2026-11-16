package com.lightdecoder.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.NewConversationActivity
import com.lightdecoder.ui.chat.SearchResultsAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
import com.lightdecoder.ui.wa.WaSetupActivity
import com.lightdecoder.ui.wa.WaStatusActivity
import com.lightdecoder.wa.WaErrors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

/**
 * The conversation list with two services: a tab each, and everything under
 * the tabs (folders, conversations, search, the banner, what a tap opens)
 * belonging to the service whose tab is shown.
 */
@RunWith(AndroidJUnit4::class)
class ServiceTabsTest {

    private lateinit var two: TwoServicesEnv
    private val messages = ChatService.MESSAGES
    private val whatsApp = ChatService.WHATSAPP

    @Before
    fun setUp() {
        two = TwoServicesEnv()
    }

    @After
    fun tearDown() {
        two.tearDown()
    }

    // --- the tabs, and what follows them ----------------------------------------------------

    @Test
    fun eachServiceHasATabWithItsOwnConversationsAndFolders() {
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice", lastTs = 2_000)
        two.seedConversation(messages, "c2", "Bob", lastTs = 1_000)
        two.seedConversation(messages, "c3", "Archived Erin", folder = "archive")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")
        two.seedConversation(whatsApp, "222@s.whatsapp.net", "Archived Dave", folder = "archive")

        launch().use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(listOf("Messages", "WhatsApp"), tabTexts(activity.findViewById(R.id.services)))
                assertEquals("Google Messages first", 0, activity.findViewById<TabLayout>(R.id.services).selectedTabPosition)
            }
            scenario.waitFor("the Messages inbox") { titles(it) == listOf("Alice", "Bob") }
            scenario.onActivity { activity ->
                assertEquals(listOf("Inbox", "Archived", "Spam", "Deleted"), tabTexts(activity.findViewById(R.id.folders)))
                selectService(activity, whatsApp)
            }
            scenario.waitFor("the WhatsApp inbox") { titles(it) == listOf("Carol") }
            scenario.onActivity { activity ->
                assertEquals("WhatsApp has no spam or deleted folder", listOf("Inbox", "Archived"), tabTexts(activity.findViewById(R.id.folders)))
                activity.findViewById<TabLayout>(R.id.folders).getTabAt(1)!!.select()
            }
            scenario.waitFor("WhatsApp's archive") { titles(it) == listOf("Archived Dave") }
            // A folder that is not synced by itself is fetched when opened, from the service shown.
            scenario.waitFor("the archive asked of WhatsApp") { "refreshConversations:archive" in two.wa.calls }

            // Back to Google Messages: its own folders, at the one it was left in ...
            scenario.onActivity { selectService(it, messages) }
            scenario.waitFor("the Messages inbox again") { titles(it) == listOf("Alice", "Bob") }
            scenario.onActivity { activity ->
                assertEquals(4, activity.findViewById<TabLayout>(R.id.folders).tabCount)
                assertEquals(0, activity.findViewById<TabLayout>(R.id.folders).selectedTabPosition)
                selectService(activity, whatsApp)
            }
            // ... and WhatsApp comes back where it was left too.
            scenario.waitFor("WhatsApp's archive again") { titles(it) == listOf("Archived Dave") }
            scenario.onActivity { assertEquals(1, it.findViewById<TabLayout>(R.id.folders).selectedTabPosition) }
        }
    }

    @Test
    fun theLastTabIsRememberedAndAnIntentCanAskForOne() {
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")

        launch().use { scenario ->
            scenario.waitFor("Messages by default") { titles(it) == listOf("Alice") }
            scenario.onActivity { selectService(it, whatsApp) }
            scenario.waitFor("WhatsApp") { titles(it) == listOf("Carol") }
        }
        launch().use { scenario ->
            scenario.onActivity { assertEquals("opens where it was", 1, it.findViewById<TabLayout>(R.id.services).selectedTabPosition) }
            scenario.waitFor("WhatsApp again") { titles(it) == listOf("Carol") }
        }
        // An intent that names a service gets that tab, and it is then the one to come back to.
        launch(RelayIntents.conversations(two.app, messages).setFlags(0)).use { scenario ->
            scenario.onActivity { assertEquals(0, it.findViewById<TabLayout>(R.id.services).selectedTabPosition) }
            scenario.waitFor("Messages, as asked") { titles(it) == listOf("Alice") }
        }
        launch().use { scenario ->
            scenario.onActivity { assertEquals(0, it.findViewById<TabLayout>(R.id.services).selectedTabPosition) }
        }
    }

    @Test
    fun withOnlyWhatsAppLinkedTheListOpensOnWhatsApp() {
        two.env.manager()
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")
        launch().use { scenario ->
            scenario.waitFor("WhatsApp's conversations") { titles(it) == listOf("Carol") }
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<TabLayout>(R.id.services).selectedTabPosition)
                assertFalse("something is linked: no setup takes over", activity.isFinishing)
                assertNull(shadowOf(activity).nextStartedActivity)
            }
        }
    }

    @Test
    fun anIntentForAnotherServiceSwitchesTheOpenList() {
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")
        launch().use { scenario ->
            scenario.waitFor("Messages") { titles(it) == listOf("Alice") }
            // Up from a WhatsApp thread (opened from its notification) lands on this list.
            scenario.onActivity { activity ->
                val up = Intent(activity, ConversationsActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, whatsApp.key)
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, up)
                assertEquals(1, activity.findViewById<TabLayout>(R.id.services).selectedTabPosition)
            }
            scenario.waitFor("WhatsApp") { titles(it) == listOf("Carol") }
        }
    }

    // --- unread badges ------------------------------------------------------------------------

    @Test
    fun eachTabCountsItsOwnUnreadConversations() {
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice", unread = true)
        two.seedConversation(messages, "c2", "Bob", unread = true)
        two.seedConversation(messages, "c3", "Read Rita")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol", unread = true)

        launch().use { scenario ->
            scenario.waitFor("both badges") { badge(it, 0) == 2 && badge(it, 1) == 1 }
            scenario.waitFor("the Messages inbox") { titles(it).size == 3 }

            // WhatsApp's conversation is read (on the phone, say) while Messages is shown.
            two.waRepo.markConversationRead("111@s.whatsapp.net")
            scenario.waitFor("WhatsApp's badge gone") { badge(it, 1) == null }
            scenario.onActivity { assertEquals("Messages' count is its own", 2, badge(it, 0)) }

            // And a new WhatsApp conversation only shows as a count: the list is still Messages'.
            two.seedConversation(whatsApp, "333@s.whatsapp.net", "Frank", unread = true, lastTs = 9_000)
            scenario.waitFor("WhatsApp's badge back") { badge(it, 1) == 1 }
            two.settle()
            scenario.onActivity { activity ->
                assertEquals(setOf("Alice", "Bob", "Read Rita"), titles(activity).toSet())
                val description = activity.findViewById<TabLayout>(R.id.services).getTabAt(1)!!.badge!!.contentDescription
                assertEquals("1 unread conversation", description.toString())
            }
        }
    }

    // --- a service that is not linked ---------------------------------------------------------

    @Test
    fun whatsAppNotLinkedShowsACardThatLeadsToItsSetup() {
        // The app's own WhatsApp manager, which nobody linked.
        two.useRealWhatsApp()
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")

        launch(RelayIntents.conversations(two.app, whatsApp).setFlags(0)).use { scenario ->
            scenario.waitFor("the card") { it.findViewById<View>(R.id.linkCard).isShown }
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<TabLayout>(R.id.services).selectedTabPosition)
                assertEquals(activity.getString(R.string.chat_wa_link_title), text(activity, R.id.linkCardTitle))
                assertEquals(activity.getString(R.string.chat_wa_link_body), text(activity, R.id.linkCardBody))
                assertEquals("no folders of nothing", View.GONE, activity.findViewById<View>(R.id.folders).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.swipe).visibility)
                assertFalse("nobody to write to", activity.findViewById<View>(R.id.fab).isShown)
                assertFalse("the card says it; no banner on top", activity.findViewById<View>(R.id.bannerText).isShown)
                assertFalse("the tab was asked for: it stays", activity.isFinishing)

                activity.findViewById<View>(R.id.linkCardAction).performClick()
                assertEquals(WaSetupActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)

                // The other tab is as it always was.
                selectService(activity, messages)
            }
            scenario.waitFor("the Messages inbox") { titles(it) == listOf("Alice") }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.linkCard).isShown)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.folders).visibility)
                assertTrue(activity.findViewById<View>(R.id.fab).isShown)
            }
        }
    }

    @Test
    fun messagesNotLinkedShowsItsCardNextToALinkedWhatsApp() {
        two.env.manager()
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")

        launch(RelayIntents.conversations(two.app, messages).setFlags(0)).use { scenario ->
            scenario.waitFor("the card") { it.findViewById<View>(R.id.linkCard).isShown }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.link_setup_title), text(activity, R.id.linkCardTitle))
                assertEquals(activity.getString(R.string.link_card_not_set_up), text(activity, R.id.linkCardBody))
                assertFalse(activity.isFinishing)
                activity.findViewById<View>(R.id.linkCardAction).performClick()
                assertEquals(RelaySetupActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            }
        }
    }

    @Test
    fun anArchiveKeptAfterUnlinkingIsListedInsteadOfTheCard() {
        two.env.pairedManager()
        two.wa.paired = false
        two.wa.stateFlow.value = RelayState(RelayState.Phase.UNPAIRED)
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")

        launch(RelayIntents.conversations(two.app, whatsApp).setFlags(0)).use { scenario ->
            scenario.waitFor("the kept conversations") { titles(it) == listOf("Carol") }
            scenario.waitFor("the banner") { it.findViewById<View>(R.id.bannerText).isShown }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.linkCard).isShown)
                assertEquals(activity.getString(R.string.link_phase_unpaired), text(activity, R.id.bannerText))
                assertEquals(activity.getString(R.string.chat_wa_body_unpaired), text(activity, R.id.bannerDetail))
                val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
                assertTrue("the kept archive can be deleted", menu.findItem(R.id.action_delete_archive).isVisible)
                // Deleting it is WhatsApp's archive, not Google Messages'.
                activity.onOptionsItemSelected(RoboMenuItem(R.id.action_delete_archive))
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("WhatsApp's archive deleted") { "deleteLocalArchive" in two.wa.calls }
            // Nothing is left: now the card.
            scenario.waitFor("the card") { it.findViewById<View>(R.id.linkCard).isShown }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_delete_archive).isVisible)
            }
        }
    }

    // --- the banner, actions and what a tap opens ----------------------------------------------

    @Test
    fun theBannerIsAboutTheLinkOfTheTabShown() {
        val (manager, fake) = two.env.pairedManager()
        fake.goOnline()
        two.env.eventually(manager, what = "Messages online") { manager.state.value.isOnline }
        two.seedConversation(messages, "c1", "Alice")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")
        two.wa.stateFlow.value = RelayState(RelayState.Phase.ERROR, WaErrors.TEMP_BANNED)

        launch().use { scenario ->
            scenario.waitFor("Messages") { titles(it) == listOf("Alice") }
            scenario.onActivity { activity ->
                assertFalse("Google Messages is connected", activity.findViewById<View>(R.id.bannerText).isShown)
                selectService(activity, whatsApp)
            }
            scenario.waitFor("WhatsApp's banner") { it.findViewById<View>(R.id.bannerText).isShown }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.link_phase_error), text(activity, R.id.bannerText))
                assertEquals(activity.getString(R.string.chat_wa_error_temp_banned), text(activity, R.id.bannerDetail))
                activity.findViewById<View>(R.id.bannerAction).performClick()
                assertEquals(WaStatusActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            }
            // WhatsApp reconnects by itself: the banner goes.
            two.wa.stateFlow.value = RelayState(RelayState.Phase.CONNECTED)
            scenario.waitFor("no banner") { !it.findViewById<View>(R.id.bannerText).isShown }

            // A state of the link that is not shown changes nothing on screen.
            scenario.onActivity { selectService(it, messages) }
            two.settle()
            two.wa.stateFlow.value = RelayState(RelayState.Phase.ERROR, WaErrors.TEMP_BANNED)
            two.settle()
            scenario.onActivity { assertFalse(it.findViewById<View>(R.id.bannerText).isShown) }
        }
    }

    @Test
    fun whatATabOpensCarriesItsService() {
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")

        launch().use { scenario ->
            scenario.waitFor("Messages on screen") { it.findViewById<RecyclerView>(R.id.list).childCount == 1 }
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performClick()
                val thread = shadowOf(activity).nextStartedActivity
                assertEquals(ThreadActivity::class.java.name, thread.component?.className)
                assertEquals("c1", thread.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
                assertEquals(messages, RelayIntents.serviceOf(thread))

                activity.onOptionsItemSelected(RoboMenuItem(R.id.action_link_status))
                assertEquals(RelayStatusActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
                selectService(activity, whatsApp)
            }
            scenario.waitFor("WhatsApp on screen") {
                titles(it) == listOf("Carol") && it.findViewById<RecyclerView>(R.id.list).childCount == 1
            }
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performClick()
                val thread = shadowOf(activity).nextStartedActivity
                assertEquals(ThreadActivity::class.java.name, thread.component?.className)
                assertEquals("111@s.whatsapp.net", thread.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
                assertEquals(whatsApp, RelayIntents.serviceOf(thread))

                activity.findViewById<View>(R.id.fab).performClick()
                val start = shadowOf(activity).nextStartedActivity
                assertEquals(NewConversationActivity::class.java.name, start.component?.className)
                assertEquals(whatsApp, RelayIntents.serviceOf(start))

                activity.onOptionsItemSelected(RoboMenuItem(R.id.action_link_status))
                assertEquals(WaStatusActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
            }
        }
    }

    @Test
    fun aRowsActionsGoToItsOwnService() {
        val (_, fake) = two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")

        launch(RelayIntents.conversations(two.app, whatsApp).setFlags(0)).use { scenario ->
            scenario.waitFor("WhatsApp on screen") { it.findViewById<RecyclerView>(R.id.list).childCount == 1 }
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performLongClick()
                clickItem(ShadowDialog.getLatestDialog() as AlertDialog, activity.getString(R.string.chat_action_archive))
            }
            scenario.waitFor("WhatsApp asked to archive it") { "setArchived:111@s.whatsapp.net:true" in two.wa.calls }
            assertTrue("Google Messages was not asked: ${fake.calls}", fake.calls.none { it.contains("rchive") })

            // Deleting says what WhatsApp's delete does, which is not what Google Messages' does.
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performLongClick()
                clickItem(ShadowDialog.getLatestDialog() as AlertDialog, activity.getString(R.string.chat_action_delete))
                val confirm = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(two.hasText(confirm.window!!.decorView, activity.getString(R.string.chat_wa_delete_conversation_body)))
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("WhatsApp asked to delete it") { "deleteConversation:111@s.whatsapp.net" in two.wa.calls }
            assertFalse("deleteConversation" in fake.calls)
        }
    }

    // --- search -------------------------------------------------------------------------------

    @Test
    fun searchCoversTheServiceShown() {
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")
        two.seedIncoming(messages, "m1", "c1", "pineapple on pizza")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")
        two.seedIncoming(whatsApp, "w1", "111@s.whatsapp.net", "pineapple juice")

        launch(RelayIntents.conversations(two.app, whatsApp).setFlags(0)).use { scenario ->
            scenario.waitFor("WhatsApp") { titles(it) == listOf("Carol") }
            scenario.onActivity { activity ->
                val item = activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
                assertTrue(item.expandActionView())
                assertEquals("a search is over one service", View.GONE, activity.findViewById<View>(R.id.services).visibility)
                (item.actionView as SearchView).setQuery("pineapple", true)
            }
            scenario.waitFor("WhatsApp's hit") { hits(it) == listOf("w1") }
            scenario.onActivity { activity ->
                val results = activity.findViewById<RecyclerView>(R.id.searchResults)
                results.getChildAt(0).performClick()
                val thread = shadowOf(activity).nextStartedActivity
                assertEquals("w1", thread.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID))
                assertEquals(whatsApp, RelayIntents.serviceOf(thread))
                activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search).collapseActionView()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.services).visibility)
                selectService(activity, messages)
                val item = activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
                item.expandActionView()
                (item.actionView as SearchView).setQuery("pineapple", true)
            }
            scenario.waitFor("Messages' hit") { hits(it) == listOf("m1") }
        }
    }

    // --- being in front -----------------------------------------------------------------------

    @Test
    fun aServiceIsToldWhileItsTabIsInFront() {
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")

        launch().use { scenario ->
            scenario.waitFor("Messages") { titles(it) == listOf("Alice") }
            assertTrue("WhatsApp's tab is not shown: ${two.wa.visibility}", two.wa.visibility.isEmpty())
            scenario.onActivity { selectService(it, whatsApp) }
            assertEquals(listOf(true), two.wa.visibility.toList())
            // Out of sight (another app, the screen off): no longer in front.
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(listOf(true, false), two.wa.visibility.toList())
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(listOf(true, false, true), two.wa.visibility.toList())
            scenario.onActivity { selectService(it, messages) }
            assertEquals(listOf(true, false, true, false), two.wa.visibility.toList())
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun launch(intent: Intent? = null): ActivityScenario<ConversationsActivity> =
        if (intent == null) ActivityScenario.launch(ConversationsActivity::class.java) else ActivityScenario.launch(intent)

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun selectService(activity: Activity, service: ChatService) {
        val tabs = activity.findViewById<TabLayout>(R.id.services)
        (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == service }.select()
    }

    /** Taps the entry [label] of a dialog that is a list of actions. */
    private fun clickItem(dialog: AlertDialog, label: String) {
        val list = dialog.listView
        val index = (0 until list.adapter.count).first { list.adapter.getItem(it) == label }
        list.performItemClick(null, index, 0)
    }

    private fun tabTexts(tabs: TabLayout): List<String> = (0 until tabs.tabCount).map { tabs.getTabAt(it)!!.text.toString() }

    /** The number on a service tab's badge; null without a badge. */
    private fun badge(activity: Activity, tab: Int): Int? =
        activity.findViewById<TabLayout>(R.id.services).getTabAt(tab)!!.badge?.number

    private fun titles(activity: Activity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList.map { it.title }

    private fun hits(activity: Activity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.searchResults).adapter as SearchResultsAdapter).currentList.map { it.messageId }

    private fun text(activity: Activity, id: Int): String = activity.findViewById<TextView>(id).text.toString()
}
