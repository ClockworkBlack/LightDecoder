package com.lightdecoder.messenger

import com.lightdecoder.messenger.MessengerMedia.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which notification lines stand for a photo or video rather than being the message. */
class MessengerMediaTest {

    @Test
    fun theLinesMessengerWritesForMediaAreRecognised() {
        assertEquals(Kind.PHOTO, MessengerMedia.kindOf("Sent a photo."))
        assertEquals(Kind.PHOTO, MessengerMedia.kindOf("sent 3 photos"))
        assertEquals(Kind.VIDEO, MessengerMedia.kindOf("Sent a video."))
        assertEquals(Kind.VIDEO, MessengerMedia.kindOf("You sent a video"))
        assertEquals(Kind.VIDEO, MessengerMedia.kindOf("David Fuchs sent a video."))
        assertEquals(Kind.GIF, MessengerMedia.kindOf("Sent a GIF."))
        assertEquals(Kind.STICKER, MessengerMedia.kindOf("Sent a sticker."))
        assertEquals(Kind.VOICE, MessengerMedia.kindOf("Sent a voice message."))
        assertEquals(Kind.ATTACHMENT, MessengerMedia.kindOf("Sent an attachment."))
    }

    @Test
    fun ordinaryMessagesAreNotMedia() {
        assertNull(MessengerMedia.kindOf("That's her!"))
        assertNull(MessengerMedia.kindOf("LOL"))
        assertNull(MessengerMedia.kindOf("I sent a photo to mum yesterday, did you see it?"))
        assertNull(MessengerMedia.kindOf("sent a postcard"))
        assertNull(MessengerMedia.kindOf(""))
    }
}
