package com.lightdecoder.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageAddress
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * People's pictures from Google Messages' participant thumbnails: fetched through the link once it
 * is up (no access to the phone's contacts), kept by the whole international number, asked for
 * again after a week, and shown for the person in Messages and in their iMessage chat.
 */
@RunWith(AndroidJUnit4::class)
class ContactPhotosTest {

    private lateinit var env: RelayTestEnv

    @Before
    fun setUp() {
        env = RelayTestEnv()
        // The archive the pictures' conversations are looked up in.
        com.lightdecoder.archive.ArchiveRepository.installForTesting(env.repo)
        ContactPhotos.resetForTesting()
        ContactPhotos.get(env.context).clear()
        IMessageAddress.setRegionForTesting("US")
    }

    @After
    fun tearDown() {
        ContactPhotos.get(env.context).clear()
        ContactPhotos.resetForTesting()
        IMessageAddress.resetForTesting()
        env.tearDown()
        com.lightdecoder.archive.ArchiveRepository.resetForTesting()
    }

    private fun jpeg(color: Int, size: Int = 96): ByteArray {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun person(id: String, number: String) = ParticipantRow("c", id, "", "", number, number, false, "", "", true)

    @Test
    fun aPictureIsKeptByTheWholeNumberAndAskedForAgainAfterAWeek() {
        val photos = ContactPhotos.get(env.context)
        val now = 1_800_000_000_000L
        val people = mapOf("p1" to "+55 11 98765-4321", "p2" to "(555) 123-4567", "p3" to "short")
        // Never asked: all with a number are due.
        assertEquals(setOf("p1", "p2"), photos.due(people, now).keys)
        assertTrue(photos.store(people.filterKeys { it != "p3" }, listOf(ThumbnailDto("p1", jpeg(Color.RED))), now))
        val ana = photos.forAddress("+5511987654321")!!
        assertTrue(ana.isFile)
        assertEquals(ContactPhotos.SIDE, BitmapFactory.decodeFile(ana.path).width)
        // Written as an iMessage address too, or as Google Messages stores it.
        assertEquals(ana, photos.forAddress("tel:+5511987654321"))
        assertEquals(ana, photos.forPerson(person("p1", "+55 11 98765-4321")))
        // A number that only ends the same is someone else.
        assertNull(photos.forAddress("+55 21 98765-4321"))
        // Someone with no picture: none, and not asked about again for a week.
        assertNull(photos.forAddress("+15551234567"))
        assertTrue(photos.due(people, now + 60_000).isEmpty())
        assertEquals(setOf("p1", "p2"), photos.due(people, now + ContactPhotos.REFRESH_MS + 1).keys)
        // A new picture replaces the old one (whose file goes); the same again changes nothing.
        assertTrue(photos.store(mapOf("p1" to "+5511987654321"), listOf(ThumbnailDto("p1", jpeg(Color.BLUE))), now + 1))
        val blue = photos.forAddress("+5511987654321")!!
        assertFalse(ana.exists())
        val px = BitmapFactory.decodeFile(blue.path).getPixel(10, 10)
        assertTrue(Color.blue(px) > 200 && Color.red(px) < 60)
        assertFalse(photos.store(mapOf("p1" to "+5511987654321"), listOf(ThumbnailDto("p1", jpeg(Color.BLUE))), now + 2))
        // One that cannot be read keeps the picture there was.
        photos.store(mapOf("p1" to "+5511987654321"), listOf(ThumbnailDto("p1", "garbage".toByteArray())), now + 3)
        assertEquals(blue, photos.forAddress("+5511987654321"))
        // Kept across a restart; gone with the archive.
        ContactPhotos.resetForTesting()
        assertEquals(blue, ContactPhotos.get(env.context).forAddress("+5511987654321"))
        ContactPhotos.get(env.context).clear()
        assertNull(ContactPhotos.get(env.context).forAddress("+5511987654321"))
        assertTrue(ContactPhotos.get(env.context).isEmpty())
    }

    @Test
    fun theLinkAsksGoogleMessagesForThePicturesOfThePeopleItListsOnceItIsUp() {
        val (manager, fake) = env.pairedManager(RelayTestEnv.FAST.copy(contactPhotosDelayMs = 0))
        fake.conversations += ConversationDto(
            id = "c1", name = "Ana",
            participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p9", name = "Ana", number = "+5511987654321")),
        )
        fake.conversations += ConversationDto(
            id = "c2", name = "Bob",
            participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p8", name = "Bob", number = "+15551234567")),
        )
        fake.thumbnails["p9"] = jpeg(Color.GREEN)
        fake.goOnline()
        env.eventually(manager, what = "Ana's picture") { ContactPhotos.get(env.context).forAddress("+55 11 98765-4321") != null }
        assertEquals(setOf("p9", "p8"), fake.thumbnailsAsked.flatten().toSet())
        assertNull(ContactPhotos.get(env.context).forAddress("+15551234567"))
        // The conversation list and the thread show it: in Messages, and in the person's iMessage chat.
        assertEquals(
            ContactPhotos.get(env.context).forAddress("+5511987654321"),
            com.lightdecoder.ui.chat.AvatarPhotos.forConversation(
                env.context, ChatService.MESSAGES, "c1", isGroup = false, participants = env.repo.participants("c1"),
            ),
        )
        assertNotNull(ContactPhotos.get(env.context).forConversation(ChatService.MESSAGES, "c1"))
        assertNull(ContactPhotos.get(env.context).forConversation(ChatService.MESSAGES, "c2"))
        // The next connection within the week asks nobody again.
        val asked = fake.thumbnailsAsked.size
        fake.emitState("reconnecting", RelayException.INTERNAL, "network error")
        fake.goOnline()
        manager.awaitIdleForTesting()
        Thread.sleep(200)
        manager.awaitIdleForTesting()
        assertEquals(asked, fake.thumbnailsAsked.size)
        // Deleting the archive deletes them.
        assertTrue(File(env.context.filesDir, "contact-photos").listFiles().orEmpty().any { it.name.endsWith(".jpg") })
    }
}
