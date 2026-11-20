package com.lightdecoder.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.SearchHit
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.direct.GroupPhotos
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Avatars with a picture (a group's photo, a person's): drawn in a circle, decoded no larger than shown, the initials without one. */
@RunWith(AndroidJUnit4::class)
class AvatarsPhotoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        GroupPhotos.dir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        ArchiveRepository.resetForTesting()
        db.close()
        GroupPhotos.dir(context).deleteRecursively()
    }

    private fun picture(width: Int, height: Int, color: Int = Color.RED): File {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(context.cacheDir, "avatar-${width}x$height-$color.jpg").apply {
            writeBytes(ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())
        }
    }

    private fun avatarView(sizePx: Int = 120) = TextView(context).apply { layoutParams = ViewGroup.LayoutParams(sizePx, sizePx) }

    @Test
    fun aPictureIsDrawnInACircleAndTheInitialsWithoutOne() {
        val view = avatarView()
        Avatars.bind(view, "Ski Trip", "imsg:group:G-1", "", picture(800, 400))
        assertEquals("", view.text.toString())
        val drawn = view.background as RoundedBitmapDrawable
        assertTrue(drawn.isCircular)
        assertEquals("decoded no larger than shown", 120, drawn.bitmap!!.width)
        assertEquals(120, drawn.bitmap!!.height)
        assertNull(view.backgroundTintList)
        // The same row drawn again for someone without one (a list's row is reused): the initials.
        Avatars.bind(view, "Ski Trip", "imsg:group:G-1", "")
        assertEquals("ST", view.text.toString())
        assertNotNull(view.backgroundTintList)
        // A picture that is gone, or no picture at all: the initials.
        Avatars.bind(view, "Ski Trip", "imsg:group:G-1", "", File(context.cacheDir, "gone.jpg"))
        assertEquals("ST", view.text.toString())
        Avatars.bind(view, "Ski Trip", "imsg:group:G-1", "", File(context.cacheDir, "junk.jpg").apply { writeText("not a picture") })
        assertEquals("ST", view.text.toString())
    }

    @Test
    fun aPictureIsDecodedOnceForTheSizeItIsShownAt() {
        val file = picture(1000, 1000, Color.BLUE)
        val first = Avatars.photo(file, 96)!!
        assertEquals(96, first.width)
        assertSame("kept decoded", first, Avatars.photo(file, 96))
        assertEquals(48, Avatars.photo(file, 48)!!.width)
        val circle = Avatars.circlePhoto(file, 64)!!
        assertEquals(64, circle.width)
        assertEquals("outside the circle: nothing", 0, Color.alpha(circle.getPixel(0, 0)))
        assertTrue(Color.blue(circle.getPixel(32, 32)) > 200)
        // A drawable (the toolbar's logo) too.
        assertTrue(Avatars.drawable(context, "Bob", "k", "", 64, file) is RoundedBitmapDrawable)
        assertTrue(Avatars.drawable(context, "Bob", "k", "", 64) is android.graphics.drawable.BitmapDrawable)
    }

    @Test
    fun aGroupsPhotoIsTheListsAndTheSearchResultsPicture() {
        val group = "imsg:group:G-PHOTO"
        repo.upsertConversation(
            ConversationDto(
                id = group, name = "Trip", isGroup = true, type = "IMESSAGE",
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15551234567", number = "+15551234567")),
            ),
        )
        val incoming = GroupPhotos.incomingFile(context).apply { writeBytes(picture(570, 570).readBytes()) }
        GroupPhotos(context).apply(group, incoming, 1)
        val row = repo.conversation(group)!!
        val items = AvatarPhotos.withPhotos(context, listOf(ConversationItem(row, "Trip", ChatService.IMESSAGE)))
        assertEquals(GroupPhotos(context).pictureOf(group)!!.path, items.single().photo)
        // A Messages conversation's id is no iMessage group's.
        assertEquals("", AvatarPhotos.withPhotos(context, listOf(ConversationItem(row, "Trip", ChatService.MESSAGES))).single().photo)
        val hits = listOf(SearchHit("imsg:M1", group, "Trip", "hi", 1, ChatService.IMESSAGE))
        assertEquals(GroupPhotos(context).pictureOf(group), AvatarPhotos.forHits(context, hits)[AvatarPhotos.key(ChatService.IMESSAGE, group)])
        assertEquals(GroupPhotos(context).pictureOf(group), AvatarPhotos.forConversation(context, ChatService.IMESSAGE, group, true, repo.participants(group)))
    }

    companion object {
        private const val DB = "avatars-photo-test.db"
    }
}
