package com.lightdecoder.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import com.lightdecoder.R
import com.lightdecoder.TestMedia
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.AttachmentPrep
import com.lightdecoder.chat.ChatService
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.PictureGalleryActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ZoomImageView
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
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

/**
 * Pictures in a conversation: tapping one opens the full-screen, zoomable
 * gallery of the conversation's pictures; a picture attached in the thread
 * is sent (converted first when it is HEIC).
 */
@RunWith(AndroidJUnit4::class)
class PictureGalleryTest {

    private lateinit var env: RelayTestEnv
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
    }

    @After
    fun tearDown() {
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    // --- the gallery ------------------------------------------------------------------------

    @Test
    fun tappingAPictureOpensTheGalleryAtThatPicture() {
        env.pairedManager()
        seedConversation()
        seedPicture("m1", 1_000, Color.RED)
        seedPicture("m2", 2_000, Color.GREEN)
        val row = env.repo.message("m2")!!
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { it.onMediaTap(row, row.media.single()) }
            val opened = shadowOf(app).nextStartedActivity
            assertEquals(PictureGalleryActivity::class.java.name, opened.component?.className)
            assertEquals("m2", opened.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID))
            assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(opened))
        }
    }

    @Test
    fun theGallerySwipesThroughTheConversationsPictures() {
        env.pairedManager()
        seedConversation()
        seedPicture("m1", 1_000, Color.RED)
        env.repo.upsertMessage(
            MessageDto(id = "t1", conversationId = "c1", senderId = "p1", timestampMs = 1_500, text = "no picture here", isOld = true)
        )
        seedPicture("m2", 2_000, Color.GREEN)
        seedPicture("m3", 3_000, Color.BLUE, outgoing = true)

        val intent = PictureGalleryActivity.intent(app, ChatService.MESSAGES, "c1", "m2", "0", "Alice")
        ActivityScenario.launch<PictureGalleryActivity>(intent).use { scenario ->
            scenario.waitFor("the picture") { picture(it)?.drawable != null }
            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager2>(R.id.pager)
                assertEquals("opened at the tapped picture", 1, pager.currentItem)
                assertEquals(3, pager.adapter?.itemCount)
                assertEquals(activity.getString(R.string.gallery_counter, 2, 3), counter(activity))
                assertEquals("Alice", activity.supportActionBar?.title ?: activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title)
                // The picture shown is m2's (green), decoded from the file on this phone.
                val shown = (picture(activity)!!.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertEquals(Color.GREEN, shown.getPixel(shown.width / 2, shown.height / 2))
                assertFalse("fitted, not zoomed, when it opens", picture(activity)!!.isZoomed)
                pager.setCurrentItem(2, false)
            }
            scenario.waitFor("the next picture") { counter(it) == it.getString(R.string.gallery_counter, 3, 3) }
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                assertEquals("your own picture", activity.getString(R.string.chat_you), toolbar.title.toString())
            }
        }
    }

    @Test
    fun aFittedPictureLeavesSidewaysSwipesToThePager() {
        env.pairedManager()
        seedConversation()
        seedPicture("m1", 1_000, Color.RED)
        val intent = PictureGalleryActivity.intent(app, ChatService.MESSAGES, "c1", "m1", "0", "Alice")
        ActivityScenario.launch<PictureGalleryActivity>(intent).use { scenario ->
            scenario.waitFor("the picture") { picture(it)?.drawable != null }
            scenario.onActivity { activity ->
                val view = picture(activity)!!
                assertFalse(view.canScrollHorizontally(1))
                assertFalse(view.canScrollHorizontally(-1))
                view.resetZoom()
                assertEquals(1f, view.zoom, 0.001f)
            }
        }
    }

    // --- sending a picture --------------------------------------------------------------------

    @Test
    fun aPictureAttachedInTheThreadIsSentWithItsText() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        manager.awaitIdleForTesting()
        seedConversation()
        val picked = File(app.cacheDir, "picked.png").apply { writeBytes(png(Color.MAGENTA)) }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                activity.setAttachment(Uri.fromFile(picked))
                activity.findViewById<TextView>(R.id.input).text = "look at this"
            }
            scenario.waitFor("send to be possible") { it.findViewById<View>(R.id.send).isEnabled }
            scenario.onActivity { it.findViewById<View>(R.id.send).performClick() }
            env.eventually(manager, what = "the picture to go out") { fake.sent.isNotEmpty() }
            val sent = fake.sent.single()
            assertEquals("c1", sent.conversationId)
            assertEquals("look at this", sent.text)
            assertEquals("picked.png", sent.fileName)
            assertNotNull("handed over as a file", sent.path)
            val decoded = BitmapFactory.decodeByteArray(sent.media, 0, sent.media!!.size)
            assertEquals(Color.MAGENTA, decoded.getPixel(5, 5))
            scenario.onActivity { activity ->
                assertFalse("the attachment bar is cleared", activity.findViewById<View>(R.id.attachmentBar).isShown)
            }
        }
    }

    @Test
    fun heicIsSentAsAPictureEveryPhoneCanShow() {
        val dir = File(app.cacheDir, "prep").apply { mkdirs() }

        val opaque = File(dir, "photo.heic").apply { writeBytes(TestMedia.bytes("still_opaque.heic")) }
        val asJpeg = AttachmentPrep.prepare(opaque, "image/heic", "photo.heic")
        assertEquals(AttachmentPrep.Prepared("image/jpeg", "photo.jpg"), asJpeg)
        assertFalse(ImageSniffer.isHeif(opaque.readBytes()))
        assertNotNull(BitmapFactory.decodeFile(opaque.path))

        val sticker = File(dir, "sticker.heic").apply { writeBytes(TestMedia.bytes("still_alpha.heic")) }
        assertEquals("transparency is kept", AttachmentPrep.Prepared("image/png", "sticker.png"),
            AttachmentPrep.prepare(sticker, "image/heic", "sticker.heic"))
        val png = BitmapFactory.decodeFile(sticker.path)
        assertTrue(png.hasAlpha())
        assertEquals("a transparent corner stays transparent", 0, Color.alpha(png.getPixel(0, 0)))

        // An animation would lose its frames as a still: it goes as it is.
        val animated = File(dir, "anim.heics").apply { writeBytes(TestMedia.bytes("anim_alpha.heics")) }
        assertNull(AttachmentPrep.prepare(animated, "image/heic-sequence", "anim.heics"))
        assertTrue(ImageSniffer.isHeif(animated.readBytes()))

        // A small ordinary picture, and anything that is not a picture, are left alone.
        val small = File(dir, "small.png").apply { writeBytes(png(Color.RED)) }
        val before = small.readBytes()
        assertNull(AttachmentPrep.prepare(small, "image/png", "small.png"))
        assertTrue(before.contentEquals(small.readBytes()))
        val text = File(dir, "notes.txt").apply { writeText("hello") }
        assertNull(AttachmentPrep.prepare(text, "text/plain", "notes.txt"))
        dir.deleteRecursively()
    }

    @Test
    fun aHugePhotoIsScaledDownBeforeItIsSent() {
        val dir = File(app.cacheDir, "prep-big").apply { mkdirs() }
        val big = Bitmap.createBitmap(AttachmentPrep.MAX_SIDE + 904, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val file = File(dir, "wide.jpg").apply {
            outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        assertEquals(AttachmentPrep.Prepared("image/jpeg", "wide.jpg"), AttachmentPrep.prepare(file, "image/jpeg", "wide.jpg"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertTrue("longest side ${bounds.outWidth}", bounds.outWidth <= AttachmentPrep.MAX_SIDE)
        dir.deleteRecursively()
    }

    // --- swipe to reply ----------------------------------------------------------------------

    @Test
    fun swipingAMessageOpensTheReplyAndTheMessageStaysInPlace() {
        env.pairedManager()
        seedConversation()
        env.repo.upsertMessage(
            MessageDto(id = "m1", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 1_000, text = "reply to me", isOld = true)
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the message") { firstBubble(it) != null }
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.messages)
                val bubble = firstBubble(activity)!!
                val y = bubble.top + bubble.height / 2f
                val x = list.width / 3f
                val start = android.os.SystemClock.uptimeMillis()
                fun send(action: Int, at: Float, t: Long) {
                    val e = android.view.MotionEvent.obtain(start, start + t, action, at, y, 0)
                    list.dispatchTouchEvent(e)
                    e.recycle()
                }
                send(android.view.MotionEvent.ACTION_DOWN, x, 0)
                val step = 15f * activity.resources.displayMetrics.density
                // A phone draws a frame per move; the drag is shown (and armed) while drawing.
                val frame = android.graphics.Canvas(Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888))
                for (i in 1..20) {
                    send(android.view.MotionEvent.ACTION_MOVE, x + step * i, 16L * i)
                    list.draw(frame)
                }
                val maxShift = 96 * activity.resources.displayMetrics.density
                assertEquals("the bubble follows the finger only so far", maxShift, firstBubble(activity)!!.translationX, 1f)
                send(android.view.MotionEvent.ACTION_UP, x + step * 20, 400)
            }
            settle(20)
            scenario.waitFor("the reply bar") { it.findViewById<View>(R.id.replyBar).isShown }
            scenario.onActivity { activity ->
                val bubble = firstBubble(activity)
                assertNotNull("the message is still in the thread", bubble)
                assertEquals("and back in its place", 0f, bubble!!.translationX, 0.5f)
                assertTrue(bubble.isShown)
                assertEquals(
                    activity.getString(R.string.chat_replying_to, "Alice"),
                    activity.findViewById<TextView>(R.id.replySender).text.toString(),
                )
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun seedConversation() {
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice", folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Alice")),
            )
        )
    }

    /** A picture message whose picture is on this phone already. */
    private fun seedPicture(id: String, ts: Long, color: Int, outgoing: Boolean = false) {
        val mediaId = "media-$id"
        env.repo.upsertMessage(
            MessageDto(
                id = id, conversationId = "c1", senderId = if (outgoing) "me" else "p1",
                senderName = if (outgoing) "" else "Alice", fromMe = outgoing,
                direction = if (outgoing) "out" else "in", status = if (outgoing) "OUTGOING_COMPLETE" else "",
                timestampMs = ts, isOld = true,
                parts = listOf(PartDto(partId = "0", kind = "media", mediaId = mediaId, mime = "image/png", size = 100)),
            )
        )
        val file = env.repo.mediaFileFor(mediaId, "png").apply { parentFile?.mkdirs(); writeBytes(png(color)) }
        env.repo.setMediaLocalPath(id, "0", file.path, thumbnail = false)
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** The first message's row in the thread. */
    private fun firstBubble(activity: Activity): View? {
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { list.getChildViewHolder(it) is com.lightdecoder.ui.chat.MessagesAdapter.MessageHolder }
    }

    /** The picture view of the page on screen. */
    private fun picture(activity: Activity): ZoomImageView? {
        val pager = activity.findViewById<ViewPager2>(R.id.pager)
        val list = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return null
        val holder = list.findViewHolderForAdapterPosition(pager.currentItem) ?: return null
        return holder.itemView.findViewById(R.id.picture)
    }

    private fun counter(activity: Activity): String = activity.findViewById<TextView>(R.id.counter).text.toString()

    private fun settle(rounds: Int = 5) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
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
}
