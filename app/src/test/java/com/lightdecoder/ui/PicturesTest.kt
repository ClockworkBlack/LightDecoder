package com.lightdecoder.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.MediaImageView
import com.lightdecoder.ui.chat.MediaLoader
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.ThreadItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import java.time.Duration

/**
 * Pictures in a conversation: the picture itself, full size, never the
 * phone's small inline preview, and pictures on their own drawn without a
 * bubble.
 */
@RunWith(AndroidJUnit4::class)
class PicturesTest {

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

    // --- the picture itself, not the preview ----------------------------------------------

    @Test
    fun aPictureWithAnInlinePreviewShowsThePictureItselfAtFullSize() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.media["full"] = png(800, 600, Color.RED)
        env.repo.upsertMessage(
            message("m1", PartDto(partId = "0", kind = "media", mediaId = "full", mime = "image/jpeg", size = 5_000,
                width = 800, height = 600, inlineData = png(16, 12, Color.BLUE)))
        )
        val row = env.repo.messages("c1", limit = 10).single()
        assertTrue(row.media.single().inlineIsPreview)

        withAdapter(manager, row) { holder ->
            waitUntil("the picture") { shown(holder)?.let { !image(holder)!!.showingPreview } == true }
            val bitmap = shown(holder)!!
            // Decoded at the size it is shown (at most the bubble's), not the 16-pixel preview.
            assertTrue("the picture itself, not the 16-pixel preview: ${bitmap.width}", bitmap.width >= 400)
            assertEquals(4f / 3f, bitmap.width.toFloat() / bitmap.height, 0.02f)
            assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            assertTrue("it was downloaded", "full" in fake.downloads)
        }
    }

    @Test
    fun theStandInShowsAtThePicturesSizeWhileThePictureLoads() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.media["full"] = png(800, 600, Color.RED)
        val release = java.util.concurrent.CountDownLatch(1)
        fake.beforeDownloadReturns = { release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        env.repo.upsertMessage(
            message("m1", PartDto(partId = "0", kind = "media", mediaId = "full", mime = "image/jpeg", size = 5_000,
                width = 800, height = 600, inlineData = png(16, 12, Color.BLUE)))
        )
        val row = env.repo.messages("c1", limit = 10).single()
        withAdapter(manager, row) { holder ->
            waitUntil("the stand-in") { image(holder)?.showingPreview == true }
            assertEquals(Color.BLUE, shown(holder)!!.getPixel(8, 6))
            // The view already has the picture's shape (4:3), not the preview's pixel size.
            val view = image(holder)!!
            view.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals(4f / 3f, view.measuredWidth.toFloat() / view.measuredHeight, 0.02f)
            assertTrue(view.measuredWidth > 16 * 4)
            release.countDown()
            waitUntil("the picture") {
                image(holder)?.showingPreview == false && shown(holder)?.let { it.width >= 400 && it.getPixel(5, 5) == Color.RED } == true
            }
        }
    }

    @Test
    fun openingAPictureGivesTheOriginalNotThePreviewEvenIfAnOlderVersionStoredThePreview() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.media["full"] = png(640, 480, Color.RED)
        env.repo.upsertMessage(
            message("m1", PartDto(partId = "0", kind = "media", mediaId = "full", mime = "image/png", size = 5_000,
                inlineData = png(8, 6, Color.BLUE)))
        )
        // What 0.3.0 did on a tap: the inline preview written and recorded as the part's file.
        val stale = MediaCache.fileFor(app, MediaCache.inlineKey("m1", "0"), "png").apply { writeBytes(png(8, 6, Color.BLUE)) }
        env.repo.setMediaLocalPath("m1", "0", MediaCache.inlineKey("m1", "0"), stale.path, thumbnail = false)
        assertEquals(stale.path, env.repo.mediaPart("m1", "0")!!.localPath)

        val file = runBlocking { manager.mediaFile("m1", "0") }
        assertNotNull(file)
        val decoded = BitmapFactory.decodeFile(file!!.path)
        assertEquals("the original", 640, decoded.width)
        assertTrue("full" in fake.downloads)
    }

    @Test
    fun inlineBytesWithNothingElseToDownloadAreThePicture() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        env.repo.upsertMessage(
            message("m1", PartDto(partId = "0", kind = "media", mime = "image/png", size = 500, inlineData = png(30, 20, Color.GREEN)))
        )
        val row = env.repo.messages("c1", limit = 10).single()
        assertTrue(row.media.single().inlineIsMedia)
        withAdapter(manager, row) { holder ->
            waitUntil("the picture") { shown(holder) != null && !image(holder)!!.showingPreview }
            assertEquals(30, shown(holder)!!.width)
            assertTrue("nothing to download", fake.downloads.isEmpty())
        }
    }

    @Test
    fun aThumbnailOnlyPictureShowsTheThumbnailAndAsksForTheOriginal() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.media["thumb"] = png(120, 90, Color.YELLOW)
        env.repo.upsertMessage(
            message("m1", PartDto(partId = "0", kind = "media", thumbMediaId = "thumb", mime = "image/jpeg", size = 5_000,
                width = 1600, height = 1200, inlineData = png(8, 6, Color.BLUE), needsFullSize = true))
        )
        val row = env.repo.messages("c1", limit = 10).single()
        val asked = ArrayList<String>()
        withAdapter(manager, row, onLoadFullSize = { r, p -> asked += "${r.id}/${p.partId}" }) { holder ->
            waitUntil("the thumbnail") { shown(holder)?.width == 120 }
            assertEquals(Color.YELLOW, shown(holder)!!.getPixel(60, 45))
            assertEquals("the original is asked for, without a tap", listOf("m1/0"), asked)
            // No "load full size" button any more.
            val button = (holder as MessagesAdapter.MessageHolder).binding.mediaContainer.getChildAt(0).findViewById<View>(R.id.mediaFullSize)
            assertEquals(View.GONE, button.visibility)
        }
    }

    @Test
    fun anIncomingPictureWithOnlyAThumbnailIsAskedForAtOnceAndShowsFullSizeWhenItArrives() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        val now = System.currentTimeMillis()
        fake.emitMessage(
            message("m1", PartDto(partId = "0", kind = "media", thumbMediaId = "thumb", mime = "image/jpeg", size = 5_000,
                width = 1600, height = 1200, needsFullSize = true), timestampMs = now)
        )
        env.eventually(manager, what = "the original asked for") {
            fake.fullSizeRequests.contains("m1" to "0") && env.repo.mediaPart("m1", "0")!!.fullSizeRequested
        }
        // The phone re-sends the message with the original.
        fake.emitMessage(
            message("m1", PartDto(partId = "0", kind = "media", mediaId = "full", thumbMediaId = "thumb", mime = "image/jpeg",
                size = 5_000, width = 1600, height = 1200), timestampMs = now)
        )
        env.eventually(manager, what = "the original stored") { env.repo.mediaPart("m1", "0")!!.mediaId == "full" }
        assertEquals("asked once", 1, fake.fullSizeRequests.count { it == ("m1" to "0") })
    }

    @Test
    fun oldHistoryIsNotAskedForOriginals() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        fake.emitMessage(
            message("old", PartDto(partId = "0", kind = "media", thumbMediaId = "thumb", mime = "image/jpeg", needsFullSize = true),
                timestampMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        )
        env.eventually(manager, what = "stored") { env.repo.mediaPart("old", "0") != null }
        settle()
        assertTrue(fake.fullSizeRequests.isEmpty())
    }

    @Test
    fun theThreadAsksForOriginalsShownWhileOfflineOnceItConnects() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.media["thumb"] = png(120, 90, Color.YELLOW)
        env.repo.upsertMessage(
            message("m1", PartDto(partId = "0", kind = "media", thumbMediaId = "thumb", mime = "image/jpeg", size = 5_000,
                width = 1600, height = 1200, needsFullSize = true), timestampMs = 1_000)
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            scenario.onActivity { }
            settle()
            assertTrue("not asked while offline", fake.fullSizeRequests.isEmpty())
            fake.goOnline()
            env.eventually(manager, what = "online") { manager.state.value.isOnline }
            waitUntil("asked once connected") { fake.fullSizeRequests.contains("m1" to "0") }
        }
    }

    // --- no bubble around a picture on its own -----------------------------------------------

    @Test
    fun aPictureOnItsOwnHasNoBubbleAndATextMessageKeepsItsBubble() {
        val (manager, fake) = env.pairedManager()
        seedConversation()
        fake.media["s1"] = png(512, 512, Color.TRANSPARENT, sticker = true)
        env.repo.upsertMessage(message("m1", PartDto(partId = "0", kind = "media", mediaId = "s1", mime = "image/png", size = 900)))
        env.repo.upsertMessage(
            MessageDto(id = "m2", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 2_000, isOld = true,
                text = "Man what", parts = listOf(PartDto(partId = "0", text = "Man what")))
        )
        val rows = env.repo.messages("c1", limit = 10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val adapter = MessagesAdapter(MediaLoader(manager, scope), NoCallbacks)
            val items = ThreadItems.build(rows, false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>()
            adapter.submitList(items)
            val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_LightDecoder))
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as MessagesAdapter.MessageHolder
            val sticker = items.indexOfFirst { it.row.id == "m1" }
            val text = items.indexOfFirst { it.row.id == "m2" }

            adapter.onBindViewHolder(holder, sticker)
            assertNull("no bubble behind the sticker", holder.binding.bubble.background)
            assertEquals(0, holder.binding.bubble.paddingStart)
            waitUntil("the sticker") { (image(holder)?.drawable as? BitmapDrawable)?.bitmap?.hasAlpha() == true }
            assertNull("no box behind a see-through picture", image(holder)!!.background)
            assertFalse("a see-through picture is not clipped to a rounded box", image(holder)!!.clipToOutline)
            assertEquals("its time is still shown", View.VISIBLE, holder.binding.time.visibility)

            // The same holder reused for a text message: the bubble is back.
            adapter.onBindViewHolder(holder, text)
            assertNotNull(holder.binding.bubble.background)
            assertTrue(holder.binding.bubble.paddingStart > 0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aPictureWithTextOrAReplyKeepsItsBubble() {
        val (manager, _) = env.pairedManager()
        seedConversation()
        env.repo.upsertMessage(
            MessageDto(id = "m1", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 1_000, isOld = true,
                text = "Look",
                parts = listOf(
                    PartDto(partId = "0", kind = "media", mediaId = "x", mime = "image/png", size = 100),
                    PartDto(partId = "1", text = "Look"),
                ))
        )
        val row = env.repo.messages("c1", limit = 10).single()
        withAdapter(manager, row) { holder ->
            assertNotNull((holder as MessagesAdapter.MessageHolder).binding.bubble.background)
        }
    }

    // --- sizing ----------------------------------------------------------------------------

    @Test
    fun picturesAreSizedLikeAMessagingAppSizesThem() {
        // A big photo shrinks to fit, keeping its shape.
        assertEquals(700 to 525, MediaImageView.fit(4032, 3024, 700, 900, 300))
        // A tall one is limited by the height.
        assertEquals(450 to 600, MediaImageView.fit(3024, 4032, 700, 600, 300))
        // A sticker shows at its own size.
        assertEquals(512 to 512, MediaImageView.fit(512, 512, 700, 900, 300))
        // A tiny one grows to the minimum.
        assertEquals(300 to 150, MediaImageView.fit(64, 32, 700, 900, 300))
    }

    // --- helpers ---------------------------------------------------------------------------

    private object NoCallbacks : MessagesAdapter.Callbacks {
        override fun onMessageLongPress(row: MessageRow) = Unit
        override fun onFailedTap(row: MessageRow) = Unit
        override fun onMediaTap(row: MessageRow, part: MediaRow) = Unit
        override fun onLoadFullSize(row: MessageRow, part: MediaRow) = Unit
        override fun onQuoteTap(messageId: String) = Unit
        override fun onReactionTap(row: MessageRow, reaction: ReactionDto) = Unit
        override fun onReply(row: MessageRow) = Unit
    }

    private fun withAdapter(
        manager: com.lightdecoder.relay.RelayManager,
        row: MessageRow,
        onLoadFullSize: (MessageRow, MediaRow) -> Unit = { _, _ -> },
        block: (RecyclerView.ViewHolder) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val callbacks = object : MessagesAdapter.Callbacks by NoCallbacks {
                override fun onLoadFullSize(row: MessageRow, part: MediaRow) = onLoadFullSize(row, part)
            }
            val adapter = MessagesAdapter(MediaLoader(manager, scope), callbacks)
            adapter.submitList(
                ThreadItems.build(listOf(row), false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>()
            )
            val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_LightDecoder))
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)
            block(holder)
        } finally {
            scope.cancel()
        }
    }

    private fun message(id: String, part: PartDto, timestampMs: Long = 1_000L) = MessageDto(
        id = id, conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = timestampMs,
        isOld = timestampMs < 10_000, parts = listOf(part),
    )

    private fun image(holder: RecyclerView.ViewHolder): MediaImageView? =
        (holder as MessagesAdapter.MessageHolder).binding.mediaContainer.getChildAt(0)?.findViewById(R.id.mediaImage)

    private fun shown(holder: RecyclerView.ViewHolder): Bitmap? = (image(holder)?.drawable as? BitmapDrawable)?.bitmap

    private fun seedConversation() {
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice", folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Alice")),
            )
        )
    }

    private fun png(width: Int, height: Int, color: Int, sticker: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        if (sticker) bitmap.setPixel(width / 2, height / 2, Color.YELLOW)
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
}
