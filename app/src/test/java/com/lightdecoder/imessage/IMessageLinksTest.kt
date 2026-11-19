package com.lightdecoder.imessage

import com.lightdecoder.imessage.IMessageLinks.Chat
import com.lightdecoder.imessage.IMessageLinks.How
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which iMessage chat shows inside which Messages conversation. */
class IMessageLinksTest {

    private val nina = Chat("c-nina", "#5551234567", "Nina")
    private val bob = Chat("c-bob", "#5559876543", "Bob")

    private fun pair(messages: List<Chat>, iMessages: List<Chat>, manual: Map<String, String> = emptyMap()) =
        IMessageLinks.pair(messages, iMessages, manual)

    @Test
    fun theSameNumberLinks() {
        val p = pair(listOf(nina, bob), listOf(Chat("im-1", "#5551234567", "Nina R")))
        assertEquals(IMessageLinks.Pair("c-nina", How.NUMBER), p["im-1"])
    }

    @Test
    fun withoutANumberTheSameNameLinks() {
        // OpenBubbles left the person's handle out, or the chat is with their Apple ID email.
        val p = pair(listOf(nina, bob), listOf(Chat("im-1", null, " nina "), Chat("im-2", "bob@icloud.com", "Bob")))
        assertEquals(IMessageLinks.Pair("c-nina", How.NAME), p["im-1"])
        assertEquals(IMessageLinks.Pair("c-bob", How.NAME), p["im-2"])
    }

    @Test
    fun aNameIsNotEnoughWhenItCouldBeSomeoneElse() {
        // Two Messages conversations named Nina.
        assertNull(pair(listOf(nina, Chat("c-nina2", "#5550000000", "Nina")), listOf(Chat("im-1", null, "Nina")))["im-1"])
        // Two iMessage chats named Nina.
        assertNull(pair(listOf(nina), listOf(Chat("im-1", null, "Nina"), Chat("im-2", null, "Nina")))["im-1"])
        // A Nina with another phone number is another Nina.
        assertNull(pair(listOf(nina), listOf(Chat("im-1", "#5557777777", "Nina")))["im-1"])
        // A number for a name is no name.
        assertNull(pair(listOf(Chat("c-x", null, "+1 555 222 3333")), listOf(Chat("im-1", null, "+1 555 222 3333")))["im-1"])
    }

    @Test
    fun aLinkByHandWinsAndAnUnlinkHolds() {
        val ims = listOf(Chat("im-1", "#5551234567", "Nina"), Chat("im-2", null, "Someone"))
        val byHand = pair(listOf(nina, bob), ims, manual = mapOf("im-2" to "c-bob"))
        assertEquals(IMessageLinks.Pair("c-bob", How.BY_HAND), byHand["im-2"])
        assertEquals("the number still links the other", "c-nina", byHand["im-1"]?.messagesId)
        val unlinked = pair(listOf(nina, bob), ims, manual = mapOf("im-1" to ""))
        assertNull("unlinked by hand: not linked by number again", unlinked["im-1"])
        // A hand link to a conversation that is gone is ignored.
        assertNull(pair(listOf(nina), ims, manual = mapOf("im-2" to "c-gone"))["im-2"])
    }

    @Test
    fun aConversationSendsThroughOneIMessageChat() {
        val p = pair(listOf(nina), listOf(Chat("im-1", "#5551234567", "Nina"), Chat("im-2", null, "Nina")))
        assertEquals("c-nina", p["im-1"]?.messagesId)
        assertNull("no number to tell it is the same person", p["im-2"])
    }

    @Test
    fun thePersonsOtherChatsShowInTheSameConversation() {
        // OpenBubbles set up again: a new chat for Nina, and the old one with the history.
        val p = pair(listOf(nina, bob), listOf(Chat("im-new", "#5551234567", "Nina"), Chat("im-old", "+1 5551234567".let { IMessageAddress.key(it) }, "Nina")))
        assertEquals(IMessageLinks.Pair("c-nina", How.NUMBER), p["im-new"])
        assertEquals(IMessageLinks.Pair("c-nina", How.SAME_PERSON), p["im-old"])
        // An unlink by hand still holds.
        assertNull(pair(listOf(nina), listOf(Chat("im-new", "#5551234567", "Nina"), Chat("im-old", "#5551234567", "Nina")), mapOf("im-old" to ""))["im-old"])
    }
}
