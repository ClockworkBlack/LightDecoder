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
}
