package com.lightdecoder.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/**
 * In a conversation, a sideways swipe that starts over the composer goes to the next app's
 * conversation list, or the one before, as a swipe on the list does; the message list keeps its
 * swipe to reply, and taps and long presses in the composer work as before.
 */
@RunWith(AndroidJUnit4::class)
class ComposerAppSwipeTest {

    private lateinit var two: TwoServicesEnv
    private val messages = ChatService.MESSAGES
    private val whatsApp = ChatService.WHATSAPP

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.env.pairedManager()
        two.seedConversation(messages, "c1", "Alice")
        two.seedIncoming(messages, "m1", "c1", "reply to me")
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")
    }

    @After
    fun tearDown() {
        two.tearDown()
    }

    // --- the thread ---------------------------------------------------------------------------

    @Test
    fun aSwipeToTheLeftAcrossTheFieldGoesToTheNextAppsList() {
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                val (_, y) = centre(input(activity))
                val w = width(activity)
                input(activity).clearFocus()
                drag(activity, w * 0.75f, y, w * 0.25f, y + 4, durationMs = 500)
                val list = startedList(activity)
                assertEquals(1, list.getIntExtra(ConversationsActivity.EXTRA_APP_STEP, 0))
                assertEquals(messages, RelayIntents.serviceOf(list))
                assertTrue("the list below is reused", list.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
                assertTrue(list.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
                assertTrue("the thread is left", activity.isFinishing)
                assertFalse("the field was not tapped into", input(activity).hasFocus())
            }
        }
    }

    @Test
    fun aSwipeFromSendGoesOnAndASwipeToTheRightGoesBack() {
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                input(activity).setText("half typed")
                val send = activity.findViewById<View>(R.id.send)
                val (x, y) = centre(send)
                // From Send towards the middle, a third of the way across.
                drag(activity, x - 10, y, x - 10 - width(activity) * 0.3f, y - 3, durationMs = 300)
                assertEquals(1, startedList(activity).getIntExtra(ConversationsActivity.EXTRA_APP_STEP, 0))
                assertTrue(activity.isFinishing)
                assertEquals("Send was not tapped", "half typed", input(activity).text.toString())
            }
        }
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                val (_, y) = centre(input(activity))
                val w = width(activity)
                drag(activity, w * 0.3f, y, w * 0.8f, y + 5, durationMs = 400)
                assertEquals(-1, startedList(activity).getIntExtra(ConversationsActivity.EXTRA_APP_STEP, 0))
                assertTrue(activity.isFinishing)
            }
        }
    }

    @Test
    fun aSwipeOverTheReplyBarSwitchesToo() {
        launchThread().use { scenario ->
            scenario.waitFor("the message") { firstBubble(it) != null }
            scenario.onActivity { activity ->
                (activity as ThreadActivity).onReply(two.env.repo.message("m1")!!)
            }
            scenario.waitFor("the reply bar") { it.findViewById<View>(R.id.replyBar).isShown }
            scenario.onActivity { activity ->
                val (_, y) = centre(activity.findViewById(R.id.replyBar))
                val w = width(activity)
                drag(activity, w * 0.8f, y, w * 0.3f, y, durationMs = 400)
                assertEquals(1, startedList(activity).getIntExtra(ConversationsActivity.EXTRA_APP_STEP, 0))
            }
        }
    }

    @Test
    fun aSwipeInTheMessagesStillRepliesAndDoesNotSwitch() {
        launchThread().use { scenario ->
            scenario.waitFor("the message") { firstBubble(it) != null }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                val (x0, y) = centre(firstBubble(activity)!!)
                val x = list.width / 3f
                val step = 15f * activity.resources.displayMetrics.density
                // A phone draws a frame per move; the drag is shown (and armed) while drawing.
                val frame = android.graphics.Canvas(Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888))
                val start = SystemClock.uptimeMillis()
                fun send(action: Int, at: Float, t: Long) {
                    val e = MotionEvent.obtain(start, start + t, action, at, y, 0)
                    activity.dispatchTouchEvent(e)
                    e.recycle()
                }
                send(MotionEvent.ACTION_DOWN, x, 0)
                for (i in 1..20) {
                    send(MotionEvent.ACTION_MOVE, x + step * i, 16L * i)
                    list.draw(frame)
                }
                send(MotionEvent.ACTION_UP, x + step * 20, 400)
                assertTrue(x0 > 0)
            }
            two.settle(20)
            scenario.waitFor("the reply bar") { it.findViewById<View>(R.id.replyBar).isShown }
            scenario.onActivity { activity ->
                assertNull("no switch", shadowOf(activity).nextStartedActivity)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun aTapOnTheFieldStillFocusesIt() {
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                val field = input(activity)
                field.clearFocus()
                assertFalse(field.hasFocus())
                val (x, y) = centre(field)
                drag(activity, x, y, x, y, durationMs = 50, steps = 1)
                assertTrue(field.hasFocus())
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun aTapOnPlusStillOpensItsChooser() {
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                val (x, y) = centre(activity.findViewById(R.id.attach))
                drag(activity, x, y, x, y, durationMs = 50, steps = 1)
                // Outside an iPhone-style chat, "+" goes straight to the photo picker.
                val opened = shadowOf(activity).nextStartedActivity
                assertNotNull("the picker opened", opened)
                assertNotEquals(ConversationsActivity::class.java.name, opened.component?.className)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun aLongPressOnSendStillOffersTheEffects() {
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                input(activity).setText("hello")
                val (x, y) = centre(activity.findViewById(R.id.send))
                val start = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0)
                activity.dispatchTouchEvent(down)
                down.recycle()
                val hold = ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout() + 200L
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(hold))
                val up = MotionEvent.obtain(start, start + hold, MotionEvent.ACTION_UP, x, y, 0)
                activity.dispatchTouchEvent(up)
                up.recycle()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                // A Google Messages chat sends no effects: the long press says they are for iMessage.
                assertEquals(activity.getString(R.string.imessage_effect_not_imessage), ShadowToast.getTextOfLatestToast())
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun aSwipeFromTheEdgeIsLeftToAndroidsBackGesture() {
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                val (_, y) = centre(input(activity))
                val w = width(activity)
                val density = activity.resources.displayMetrics.density
                drag(activity, 4 * density, y, w * 0.8f, y, durationMs = 300)
                drag(activity, w - 4 * density, y, w * 0.2f, y, durationMs = 300)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun anUpOrDownDragOrASelectionDoesNotSwitch() {
        launchThread().use { scenario ->
            scenario.onActivity { activity ->
                val (x, y) = centre(input(activity))
                val w = width(activity)
                // Mostly upwards, a little sideways: not a swipe across.
                drag(activity, x, y, x - 50, y - 200, durationMs = 300)
                // Sideways, but not 2.5 times as far as up.
                drag(activity, w * 0.8f, y, w * 0.3f, y - 220, durationMs = 300)
                assertNull(shadowOf(activity).nextStartedActivity)

                // Words selected in the field: a drag over it is for the selection.
                val field = input(activity)
                field.setText("hello world")
                field.requestFocus()
                field.setSelection(0, 5)
                drag(activity, w * 0.8f, y, w * 0.2f, y, durationMs = 300)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(activity.isFinishing)
            }
        }
    }

    // --- the list takes the step ---------------------------------------------------------------

    @Test
    fun theListGoesOnToTheAppNextToTheConversations() {
        // From WhatsApp (the last app) the next one is Messages, round the end.
        val fromWhatsApp = Intent(two.app, ConversationsActivity::class.java)
            .putExtra(RelayIntents.EXTRA_SERVICE, whatsApp.key)
            .putExtra(ConversationsActivity.EXTRA_APP_STEP, 1)
        ActivityScenario.launch<ConversationsActivity>(fromWhatsApp).use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it) == listOf("Alice") }
            scenario.onActivity { activity ->
                assertEquals(0, selectedTab(activity))
                // The list already open (Up from a thread): the one before Messages, round to WhatsApp.
                val back = Intent(activity, ConversationsActivity::class.java)
                    .putExtra(RelayIntents.EXTRA_SERVICE, messages.key)
                    .putExtra(ConversationsActivity.EXTRA_APP_STEP, -1)
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, back)
                assertFalse("taken once", activity.intent.hasExtra(ConversationsActivity.EXTRA_APP_STEP))
            }
            scenario.waitFor("the WhatsApp inbox") { titles(it) == listOf("Carol") }
            scenario.onActivity { activity ->
                assertEquals(activity.findViewById<TabLayout>(R.id.services).tabCount - 1, selectedTab(activity))
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun launchThread(): ActivityScenario<ThreadActivity> {
        val scenario = ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0))
        scenario.waitFor("the composer") { input(it).isShown && input(it).isEnabled }
        return scenario
    }

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    /** A finger from (x0, y0) to (x1, y1) over [durationMs], through the screen's own dispatch. */
    private fun drag(activity: Activity, x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long, steps: Int = 10) {
        // Each touch after the last one ends: a velocity is never read off two touches' events.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, t: Long, x: Float, y: Float) {
            val e = MotionEvent.obtain(start, start + t, action, x, y, 0)
            activity.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0, x0, y0)
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            send(MotionEvent.ACTION_MOVE, durationMs * i / steps, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
        }
        send(MotionEvent.ACTION_UP, durationMs, x1, y1)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun startedList(activity: Activity): Intent {
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ConversationsActivity::class.java.name, started?.component?.className)
        return started
    }

    private fun input(activity: Activity): EditText = activity.findViewById(R.id.input)

    private fun centre(view: View): Pair<Float, Float> {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return (at[0] + view.width / 2f) to (at[1] + view.height / 2f)
    }

    private fun width(activity: Activity): Float = activity.window.decorView.width.toFloat()

    private fun firstBubble(activity: Activity): View? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { list.getChildViewHolder(it) is com.lightdecoder.ui.chat.MessagesAdapter.MessageHolder }
    }

    private fun selectedTab(activity: Activity): Int = activity.findViewById<TabLayout>(R.id.services).selectedTabPosition

    private fun titles(activity: Activity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList.map { it.title }
}
