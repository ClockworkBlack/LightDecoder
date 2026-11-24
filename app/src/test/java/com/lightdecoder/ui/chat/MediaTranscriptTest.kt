package com.lightdecoder.ui.chat

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ReactionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * SPEC2 B4 in the transcript: photos sent together as a collage or stack, a download's ring updated
 * without a rebind, a location card that opens nothing of the sender's, and a Live Photo's moving
 * part that is never drawn.
 */
@RunWith(AndroidJUnit4::class)
class MediaTranscriptTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        scope.cancel()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun part(id: String, mime: String = "image/png", name: String = "$id.png") = MediaRow(
        messageId = "imsg:G", partId = id, mediaId = "G/$id", key = ByteArray(0), thumbMediaId = "", thumbKey = ByteArray(0),
        mime = mime, fileName = name, size = 10, width = 0, height = 0, inlineData = ByteArray(0), needsFullSize = false,
        localPath = "", thumbPath = "", fullSizeRequested = false, stickerChecked = false,
    )

    private fun row(parts: List<MediaRow>, extra: IMessageExtra = IMessageExtra(), id: String = "imsg:G") = MessageRow(
        id = id, conversationId = "imsg:c", tmpId = "", senderId = "+15551234567", senderName = "Bob", isOutgoing = false, timestampMs = 1,
        direction = "in", status = "", statusCode = 0, errorText = "", transport = "iMessage", text = "", subject = "", replyToId = "",
        reactions = emptyList(), localState = "", media = parts, updatedAt = 0, extra = extra.toJson(),
    )

    private val taps = ArrayList<String>()

    private val callbacks = object : MessagesAdapter.Callbacks {
        override fun onMessageLongPress(row: MessageRow) = Unit
        override fun onFailedTap(row: MessageRow) = Unit
        override fun onMediaTap(row: MessageRow, part: MediaRow) { taps += "media ${part.partId}" }
        override fun onLoadFullSize(row: MessageRow, part: MediaRow) = Unit
        override fun onQuoteTap(messageId: String) = Unit
        override fun onReactionTap(row: MessageRow, reaction: ReactionDto) = Unit
        override fun onReply(row: MessageRow) = Unit
        override fun onStackTap(row: MessageRow, parts: List<MediaRow>) { taps += "stack " + parts.joinToString(",") { it.partId } }
        override fun onTransferCancel(key: String) { taps += "cancel $key" }
        override fun onLocationTap(row: MessageRow, part: MediaRow, coordinates: Pair<Double, Double>?) { taps += "location $coordinates" }
    }

    private fun adapter() = MessagesAdapter(MediaLoader(IMessageManager.get(context), scope), callbacks)

    private fun bound(adapter: MessagesAdapter, r: MessageRow): MessagesAdapter.MessageHolder {
        adapter.submitList(ThreadItems.build(listOf(r), false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_LightDecoder))
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as MessagesAdapter.MessageHolder
        adapter.onBindViewHolder(holder, 0)
        return holder
    }

    @Test
    fun twoOrThreePhotosAreACollageFourOrMoreAStackAndATapShowsThemAll() {
        val a = adapter()
        val three = bound(a, row((0..2).map { part("p$it") })).binding.mediaContainer
        assertEquals(1, three.childCount)
        val collage = three.getChildAt(0).findViewById<MediaStackView>(R.id.stackView)
        assertNotNull(collage)
        assertEquals(3, collage.cards.size)
        assertEquals(View.GONE, three.getChildAt(0).findViewById<TextView>(R.id.stackCount).visibility)
        assertTrue(collage.cards.all { it.rotation != 0f })
        assertEquals(context.getString(R.string.media_stack_description, 3), three.getChildAt(0).contentDescription)
        three.getChildAt(0).performClick()
        assertEquals(listOf("stack p0,p1,p2"), taps)

        val five = bound(a, row((0..4).map { part("p$it") }, id = "imsg:H")).binding.mediaContainer
        val stack = five.getChildAt(0).findViewById<MediaStackView>(R.id.stackView)
        assertEquals(3, stack.cards.size)
        val badge = five.getChildAt(0).findViewById<TextView>(R.id.stackCount)
        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals("5", badge.text.toString())
    }

    @Test
    fun aSinglePhotoAndAnotherServicesPhotosAreNoStack() {
        val a = adapter()
        val one = bound(a, row(listOf(part("p0")))).binding.mediaContainer
        assertEquals(1, one.childCount)
        assertNull(one.getChildAt(0).findViewById<MediaStackView>(R.id.stackView))
        assertNotNull(one.getChildAt(0).findViewById<View>(R.id.mediaImage))
        // Not an iMessage: each as it always was.
        val other = bound(a, row((0..2).map { part("p$it") }, id = "m1")).binding.mediaContainer
        assertEquals(3, other.childCount)
    }

    @Test
    fun aLivePhotosMovingPartIsNeverDrawnAndItsStillSaysLive() {
        val a = adapter()
        val holder = bound(a, row(listOf(part("p0"), part("p1", "video/quicktime", "IMG.MOV")), IMessageExtra(live = mapOf("p0" to "p1"))))
        val box = holder.binding.mediaContainer
        assertEquals("only the still", 1, box.childCount)
        assertEquals(View.VISIBLE, box.getChildAt(0).findViewById<View>(R.id.mediaLive).visibility)
        assertEquals(View.GONE, box.getChildAt(0).findViewById<View>(R.id.mediaPlay).visibility)
        // Nor does it count for "only pictures".
        assertTrue(ThreadItems.picturesOnly(row(listOf(part("p0"), part("p1", "video/quicktime", "IMG.MOV")), IMessageExtra(live = mapOf("p0" to "p1")))))
    }

    @Test
    fun aDownloadsRingFollowsItsProgressWithoutARebind() {
        val a = adapter()
        val list = RecyclerView(ContextThemeWrapper(context, R.style.Theme_LightDecoder)).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
            layout(0, 0, 1080, 1920)
        }
        a.submitList(ThreadItems.build(listOf(row(listOf(part("p0")))), false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1080, 1920)
        val holder = list.getChildViewHolder(list.getChildAt(0)) as MessagesAdapter.MessageHolder
        val ring = holder.binding.mediaContainer.getChildAt(0).findViewById<TransferRing>(R.id.transferRing)
        assertEquals(View.GONE, ring.visibility)
        a.updateTransferViews(list, mapOf("G/p0" to DirectLink.Transfer(25, 100, 0, 1, upload = false)))
        val same = holder.binding.mediaContainer.getChildAt(0).findViewById<TransferRing>(R.id.transferRing)
        assertSame("not rebound", ring, same)
        assertEquals(View.VISIBLE, ring.visibility)
        assertEquals(0.25f, ring.fraction, 0.001f)
        ring.performClick()
        assertEquals(listOf("cancel G/p0"), taps)
        // An upload shows no ring (iPhone has none); the end takes it away.
        a.updateTransferViews(list, mapOf("G/p0" to DirectLink.Transfer(25, 100, 0, 1, upload = true)))
        assertEquals(View.GONE, ring.visibility)
        a.updateTransferViews(list, emptyMap())
        assertEquals(View.GONE, ring.visibility)
    }

    @Test
    fun aLocationCardShowsNoNumbersUntilItsCardWasReadAndOpensOnlyOurOwnAddress() {
        val a = adapter()
        var known: Pair<Double, Double>? = null
        a.locationOf = { _, _ -> known }
        val r = row(listOf(part("p0", "text/x-vlocation", "G-CL.loc.vcf")))
        val card = bound(a, r).binding.mediaContainer.getChildAt(0)
        assertEquals(context.getString(R.string.location_untitled), card.findViewById<TextView>(R.id.locationTitle).text.toString())
        assertEquals(View.GONE, card.findViewById<View>(R.id.locationCoordinates).visibility)
        assertNull("no file row", card.findViewById<View>(R.id.mediaFile))
        known = 37.33182 to -122.03118
        val read = bound(a, r).binding.mediaContainer.getChildAt(0)
        assertEquals(context.getString(R.string.location_current), read.findViewById<TextView>(R.id.locationTitle).text.toString())
        assertEquals("37.33182, -122.03118", read.findViewById<TextView>(R.id.locationCoordinates).text.toString())
        assertEquals(View.GONE, read.findViewById<View>(R.id.locationAddress).visibility)
        read.performClick()
        assertEquals(listOf("location (37.33182, -122.03118)"), taps)
    }

    @Test
    fun theNotificationNeverCountsAMovingPart() {
        // The still is the photo: "Photo", from the message's own media.
        val m = MessageDto(
            id = "imsg:N", conversationId = "imsg:c", senderId = "+1", timestampMs = 1, direction = "in", transport = "iMessage",
            parts = listOf(
                PartDto(partId = "p0", kind = "media", mediaId = "N/p0", mime = "video/quicktime", fileName = "IMG.MOV"),
                PartDto(partId = "p1", kind = "media", mediaId = "N/p1", mime = "image/heic", fileName = "IMG.HEIC"),
            ),
            extra = IMessageExtra(live = mapOf("p1" to "p0")).toJson(),
        )
        val body = com.lightdecoder.relay.MessageNotifier::class.java.getDeclaredMethod("bodyOf", Context::class.java, MessageDto::class.java)
            .apply { isAccessible = true }.invoke(com.lightdecoder.relay.MessageNotifier, context, m)
        assertEquals(context.getString(R.string.relay_notify_photo), body.toString())
    }

    private companion object {
        const val DB = "media-transcript-test.db"
    }
}
