package com.lightdecoder.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.AppSwipe
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A sideways swipe anywhere on the conversation list goes to the next app or the one before, as
 * the swipe along the top bar does; scrolling, pulling to refresh, tapping a row and Android's
 * back gesture at the edges work as before.
 */
@RunWith(AndroidJUnit4::class)
class AppSwipeTest {

    private lateinit var two: TwoServicesEnv
    private val messages = ChatService.MESSAGES
    private val whatsApp = ChatService.WHATSAPP

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.env.pairedManager()
        for (i in 1..3) two.seedConversation(messages, "c$i", "Person $i", lastTs = 1_000L * i)
        two.seedConversation(whatsApp, "111@s.whatsapp.net", "Carol")
    }

    @After
    fun tearDown() {
        two.tearDown()
    }

    @Test
    fun aSidewaysSwipeOverTheListGoesToTheAppBeforeOrTheNext() {
        launch().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it).size == 3 }
            scenario.onActivity { activity ->
                val (x, y) = rowCentre(activity, 0)
                val w = width(activity)
                // To the right, slowly but across most of the screen: the app before (round to WhatsApp).
                drag(activity, w * 0.3f, y, w * 0.85f, y + 6, durationMs = 600)
                assertEquals(3, selectedTab(activity))
                assertNull("the row under the finger did not open", shadowOf(activity).nextStartedActivity)
                assertTrue(x > 0)
            }
            scenario.waitFor("the WhatsApp inbox") { titles(it) == listOf("Carol") }
            scenario.onActivity { activity ->
                val (_, y) = rowCentre(activity, 0)
                val w = width(activity)
                // To the left, short but quick (a fling): the next app, round to Messages.
                drag(activity, w * 0.6f, y, w * 0.6f - 70, y - 4, durationMs = 40, steps = 4)
                assertEquals(0, selectedTab(activity))
            }
            scenario.waitFor("the Messages inbox again") { titles(it).size == 3 }
        }
    }

    @Test
    fun aScrollUpOrDownDoesNotSwitch() {
        launch().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it).size == 3 }
            scenario.onActivity { activity ->
                val (x, y) = rowCentre(activity, 2)
                // Mostly upwards with a little sideways drift.
                drag(activity, x, y, x - 40, y - 150, durationMs = 200)
                assertEquals(0, selectedTab(activity))
                // A short sideways wobble, too short to be a swipe.
                drag(activity, x, y, x + 20, y, durationMs = 400)
                assertEquals(0, selectedTab(activity))
            }
            two.settle()
            scenario.onActivity { assertEquals(listOf("Person 3", "Person 2", "Person 1"), titles(it)) }
        }
    }

    @Test
    fun aTapStillOpensTheConversation() {
        launch().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it).size == 3 }
            scenario.onActivity { activity ->
                val (x, y) = rowCentre(activity, 0)
                drag(activity, x, y, x, y, durationMs = 50, steps = 1)
            }
            two.settle()
            scenario.onActivity { activity ->
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(ThreadActivity::class.java.name, started?.component?.className)
                assertEquals(0, selectedTab(activity))
            }
        }
    }

    @Test
    fun pullingDownStillRefreshes() {
        launch(Intent(two.app, ConversationsActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, whatsApp.key)).use { scenario ->
            scenario.waitFor("the WhatsApp inbox") { titles(it) == listOf("Carol") }
            scenario.onActivity { activity ->
                val (x, y) = rowCentre(activity, 0)
                drag(activity, x, y, x + 10, y + 260, durationMs = 500, steps = 20)
                // The pull went to the list's pull-to-refresh, which took it as one: the spinner is
                // up (its listener runs at the end of the spinner's animation, which Robolectric
                // does not play).
                assertTrue(activity.findViewById<SwipeRefreshLayout>(R.id.swipe).isRefreshing)
                assertEquals("still WhatsApp", 3, selectedTab(activity))
            }
        }
    }

    @Test
    fun aSwipeFromTheEdgeIsLeftToAndroidsBackGesture() {
        launch().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it).size == 3 }
            scenario.onActivity { activity ->
                val (_, y) = rowCentre(activity, 1)
                val w = width(activity)
                val density = activity.resources.displayMetrics.density
                drag(activity, 4 * density, y, w * 0.8f, y, durationMs = 300)
                assertEquals("from the left edge", 0, selectedTab(activity))
                drag(activity, w - 4 * density, y, w * 0.2f, y, durationMs = 300)
                assertEquals("from the right edge", 0, selectedTab(activity))
            }
        }
    }

    @Test
    fun whichWayASwipeGoes() {
        val d = 1f
        assertEquals("far to the left: the next", 1, AppSwipe.stepFor(dx = -100f, dy = 5f, vx = 0f, widthPx = 320f, density = d))
        assertEquals("far to the right: the one before", -1, AppSwipe.stepFor(dx = 100f, dy = 5f, vx = 0f, widthPx = 320f, density = d))
        assertEquals("short and slow", 0, AppSwipe.stepFor(dx = -60f, dy = 0f, vx = -200f, widthPx = 320f, density = d))
        assertEquals("short but quick", 1, AppSwipe.stepFor(dx = -60f, dy = 0f, vx = -1500f, widthPx = 320f, density = d))
        assertEquals("a fling back the other way", 0, AppSwipe.stepFor(dx = -60f, dy = 0f, vx = 1500f, widthPx = 320f, density = d))
        assertEquals("too steep", 0, AppSwipe.stepFor(dx = -200f, dy = 90f, vx = -3000f, widthPx = 320f, density = d))
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

    /** A finger from (x0, y0) to (x1, y1) over [durationMs], through the screen's own dispatch. */
    private fun drag(activity: Activity, x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long, steps: Int = 10) {
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

    private fun rowCentre(activity: Activity, index: Int): Pair<Float, Float> {
        val list = activity.findViewById<RecyclerView>(R.id.list)
        val row: View = list.getChildAt(index)
        val at = IntArray(2)
        row.getLocationOnScreen(at)
        return (at[0] + row.width / 2f) to (at[1] + row.height / 2f)
    }

    private fun width(activity: Activity): Float = activity.window.decorView.width.toFloat()

    private fun selectedTab(activity: Activity): Int = activity.findViewById<TabLayout>(R.id.services).selectedTabPosition

    private fun titles(activity: Activity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList.map { it.title }
}
