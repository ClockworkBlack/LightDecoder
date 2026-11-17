package com.lightdecoder.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.sticker.StickerLibrary
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.sticker.StickerMakerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** The sticker panel's "Edit stickers": a tapped sticker opens in the editor instead of being sent. */
@RunWith(AndroidJUnit4::class)
class StickerPanelTest {

    private lateinit var env: RelayTestEnv
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
        env.pairedManager()
        StickerLibrary.list(app).forEach { StickerLibrary.delete(app, it) }
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Nina", folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Nina")),
            )
        )
    }

    @After
    fun tearDown() {
        StickerLibrary.list(app).forEach { StickerLibrary.delete(app, it) }
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    private fun settle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(10)
    }

    @Test
    fun editStickersOpensATappedStickerInTheEditor() {
        val sticker = StickerLibrary.save(app, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF0000.toInt()) })
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { it.findViewById<View>(R.id.stickers).performClick() }
            settle()
            val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
            val edit = sheet.findViewById<View>(R.id.stickersEditMode)!!
            assertTrue("offered when there are stickers", edit.isShown)
            assertTrue(sheet.findViewById<View>(R.id.stickersHint)!!.isShown)
            edit.performClick()
            assertEquals(app.getString(R.string.sticker_edit_mode_title), sheet.findViewById<TextView>(R.id.stickersTitle)!!.text.toString())
            val grid = sheet.findViewById<RecyclerView>(R.id.stickersGrid)!!
            grid.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            grid.layout(0, 0, 1000, grid.measuredHeight)
            grid.getChildAt(0).performClick()
            val next = shadowOf(app).nextStartedActivity
            assertEquals(StickerMakerActivity::class.java.name, next.component?.className)
            assertEquals(sticker.path, next.getStringExtra(StickerMakerActivity.EXTRA_EDIT))
            assertTrue("nothing was sent", env.fake!!.sent.isEmpty())
        }
    }

    @Test
    fun sendSeveralSendsTheTappedStickersTogetherInOrder() {
        env.fake!!.goOnline()
        val colours = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        colours.forEach { c ->
            StickerLibrary.save(app, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(c) })
            Thread.sleep(5)
        }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { it.findViewById<View>(R.id.stickers).performClick() }
            settle()
            val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
            val several = sheet.findViewById<View>(R.id.stickersSeveral)!!
            val send = sheet.findViewById<TextView>(R.id.stickersSendChosen)!!
            assertTrue("offered when there is more than one sticker", several.isShown)
            assertTrue("only while choosing", !send.isShown)
            several.performClick()
            assertEquals(app.getString(R.string.sticker_send_several_title), sheet.findViewById<TextView>(R.id.stickersTitle)!!.text.toString())
            assertTrue(send.isShown && !send.isEnabled)
            val grid = sheet.findViewById<RecyclerView>(R.id.stickersGrid)!!
            fun tile(i: Int): View {
                grid.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                grid.layout(0, 0, 1000, grid.measuredHeight)
                return grid.getChildAt(i)
            }
            val files = StickerLibrary.list(app)
            // The third, then the first; the second is tapped and untapped.
            tile(2).performClick()
            tile(1).performClick()
            tile(1).performClick()
            tile(0).performClick()
            assertTrue("marked", tile(2).isSelected && tile(0).isSelected && !tile(1).isSelected)
            assertTrue("nothing goes out while choosing", env.fake!!.sent.isEmpty())
            assertEquals(app.resources.getQuantityString(R.plurals.sticker_send_count, 2, 2), send.text.toString())
            send.performClick()
            val deadline = System.currentTimeMillis() + 20_000
            while (env.fake!!.sent.size < 2 && System.currentTimeMillis() < deadline) settle()
            settle()
            val sent = env.fake!!.sent
            assertEquals("both, and only those", 2, sent.size)
            fun colour(bytes: ByteArray) = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).let { it.getPixel(it.width / 2, it.height / 2) }
            assertEquals("in the order tapped: the third first", colour(files[2].readBytes()), colour(sent[0].media!!))
            assertEquals("then the first", colour(files[0].readBytes()), colour(sent[1].media!!))
            assertTrue(sent.all { it.conversationId == "c1" })
        }
    }
}
