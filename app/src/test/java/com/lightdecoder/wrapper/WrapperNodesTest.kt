package com.lightdecoder.wrapper

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Finding OpenBubbles' bubbles, menu items, field and send button in what its screen shows. */
@RunWith(AndroidJUnit4::class)
class WrapperNodesTest {

    private class N(
        override val text: String = "",
        override val description: String = "",
        override val hint: String = "",
        override val className: String = "android.view.View",
        override val bounds: Rect = Rect(),
        override val clickable: Boolean = false,
        override val longClickable: Boolean = false,
        override val editable: Boolean = false,
        override val scrollable: Boolean = false,
        override val tooltip: String = "",
        kids: List<N> = emptyList(),
    ) : UiNode {
        override var parent: UiNode? = null
        override val children: List<UiNode> = kids
        init { kids.forEach { it.parent = this } }
    }

    // A chat screen: a scrolling list of bubbles (Flutter: the label sits on a child of the long-pressable bubble),
    // the field at the bottom, the send button to its right, the attach button to its left.
    private val oldDinner = N(description = "Dinner at 7?")
    private val oldBubble = N(longClickable = true, bounds = Rect(0, 100, 600, 200), kids = listOf(oldDinner))
    private val newDinner = N(description = "Dinner at 7? I can do 8")
    private val newBubble = N(longClickable = true, bounds = Rect(0, 900, 600, 1000), kids = listOf(newDinner))
    private val list = N(scrollable = true, bounds = Rect(0, 0, 1080, 1800), kids = listOf(oldBubble, newBubble))
    private val attach = N(clickable = true, bounds = Rect(0, 1850, 100, 1950))
    private val field = N(hint = "iMessage", editable = true, bounds = Rect(110, 1850, 900, 1950))
    private val sendIcon = N(clickable = true, bounds = Rect(910, 1850, 1060, 1950))
    private val screen = N(bounds = Rect(0, 0, 1080, 2000), kids = listOf(list, attach, field, sendIcon))

    @Test
    fun theNewestBubbleWithTheMessagesWordsIsFound() {
        assertSame(newDinner, WrapperNodes.message(screen, "Dinner at 7? I can do 8"))
        assertSame("the one saying exactly that", oldDinner, WrapperNodes.message(screen, "dinner   AT 7?"))
        assertSame("the lowest on screen, the newest, of those holding it", newDinner, WrapperNodes.message(screen, "Dinner at"))
        assertNull(WrapperNodes.message(screen, "Something else"))
        assertSame("its bubble takes the long press", newBubble, WrapperNodes.longPressTarget(newDinner))
    }

    @Test
    fun iconButtonsAreFoundByTheirTooltip() {
        // Flutter gives an IconButton's name as its tooltip, not its description.
        val undo = N(tooltip = "Undo Send", clickable = true)
        val more = N(tooltip = "Show menu", clickable = true)
        val menu = N(kids = listOf(N(tooltip = "Reply", clickable = true), undo, more))
        assertSame(undo, WrapperNodes.action(menu, "Undo Send"))
        assertSame(more, WrapperNodes.more(menu))
    }

    @Test
    fun ofSeveralAlikeTheUsersOwnOnTheRightIsPicked() {
        val theirs = N(description = "Test")
        val theirBubble = N(longClickable = true, bounds = Rect(20, 1200, 300, 1300), kids = listOf(theirs))
        val mine = N(description = "Test")
        val myBubble = N(longClickable = true, bounds = Rect(780, 1000, 1060, 1100), kids = listOf(mine))
        val chat = N(bounds = Rect(0, 0, 1080, 2000), kids = listOf(myBubble, theirBubble))
        assertSame("the newest, whoever sent it", theirs, WrapperNodes.message(chat, "Test"))
        assertSame("the user's own", mine, WrapperNodes.message(chat, "Test", mine = true))
    }

    @Test
    fun menuNamesAreFoundInAnyFieldOrLineAndListedForTheLog() {
        val unsend = N(description = "Unsend\nTooltip", clickable = true)
        val menu = N(kids = listOf(N(text = "Copy", clickable = true), unsend))
        assertSame("another wording, on a line of a merged label", unsend, WrapperNodes.action(menu, "Undo Send", "Unsend"))
        assertEquals("copy | unsend | tooltip", WrapperNodes.buttonNames(menu))
    }

    @Test
    fun aBubbleSayingExactlyThatIsPickedOverOneThatOnlyHoldsIt() {
        val exact = N(description = "Hi", bounds = Rect(700, 100, 1000, 200))
        val holds = N(description = "This is it", bounds = Rect(700, 900, 1000, 1000))
        val chat = N(bounds = Rect(0, 0, 1080, 2000), kids = listOf(exact, holds))
        assertSame(exact, WrapperNodes.message(chat, "Hi", mine = true))
    }

    @Test
    fun theChatsMessagesAreReadOldestFirstWithWhoseTheyAre() {
        fun bubble(words: String, top: Int, mine: Boolean) = N(
            longClickable = true, bounds = if (mine) Rect(500, top, 1060, top + 90) else Rect(20, top, 600, top + 90),
            kids = listOf(N(description = words)),
        )
        val chatList = N(
            scrollable = true, bounds = Rect(0, 0, 1080, 1800),
            kids = listOf(
                N(text = "Today, 11:55 PM", bounds = Rect(400, 20, 680, 60)),
                bubble("Are you coming at 7:30?", 500, mine = false),
                bubble("Yes", 300, mine = true),
                N(text = "You unsent a message. Others may still see it", bounds = Rect(100, 700, 900, 740)),
                bubble("See you then", 800, mine = true),
                N(text = "Delivered", bounds = Rect(900, 900, 1060, 930)),
            ),
        )
        val chat = N(bounds = Rect(0, 0, 1080, 2000), kids = listOf(chatList, N(hint = "iMessage", editable = true, bounds = Rect(110, 1850, 900, 1950))))
        assertEquals(
            listOf(WrapperNodes.Bubble("Yes", true), WrapperNodes.Bubble("Are you coming at 7:30?", false), WrapperNodes.Bubble("See you then", true)),
            WrapperNodes.bubbles(chat),
        )
        assertEquals(false, WrapperNodes.isMessageText("Nov 18, 2026, 8:50 PM"))
        assertEquals(false, WrapperNodes.isMessageText("Read 9:14 PM"))
        assertEquals(true, WrapperNodes.isMessageText("Read the book yet? It is really worth the time, I promise"))
    }

    @Test
    fun screensReadScrollingBackAreJoinedWithoutRepeats() {
        fun b(vararg words: String) = words.map { WrapperNodes.Bubble(it, false) }
        val newest = b("c", "d", "e")
        val older = WrapperNodes.mergeOlder(b("a", "b", "c", "d"), newest)
        assertEquals(b("a", "b", "c", "d", "e"), older)
        assertEquals("the top of the chat again: nothing new", older, WrapperNodes.mergeOlder(b("a", "b", "c"), older))
        assertEquals("a scroll the other way: nothing new", older, WrapperNodes.mergeOlder(b("c", "d", "e"), older))
        assertEquals("nothing shared: all older", b("x", "y", "a", "b", "c", "d", "e"), WrapperNodes.mergeOlder(b("x", "y"), older))
    }

    @Test
    fun theUnsentLinesAreCounted() {
        val chat = N(kids = listOf(N(text = "You unsent a message. Others may still see it"), N(text = "Test"), N(text = "You unsent a message.")))
        assertEquals(2, WrapperNodes.unsentCount(chat))
    }

    @Test
    fun theMenuItemsAreFoundByTheirTitle() {
        val reply = N(description = "Reply", clickable = true)
        val more = N(description = "Show menu", clickable = true)
        val menu = N(kids = listOf(N(description = "Reply all"), reply, more))
        assertSame("exactly that title", reply, WrapperNodes.action(menu, "reply"))
        assertNull(WrapperNodes.action(menu, "Undo Send"))
        assertSame(more, WrapperNodes.more(menu))
        val popup = N(kids = listOf(N(text = "Undo Send", kids = emptyList())))
        assertEquals("Undo Send", WrapperNodes.said(WrapperNodes.action(popup, "Undo Send")!!))
    }

    @Test
    fun theFieldAndTheUnlabelledSendButtonAreFoundByPlace() {
        assertSame(field, WrapperNodes.field(screen))
        assertSame("right of the field, on its row", sendIcon, WrapperNodes.send(screen, field))
        val noSend = N(kids = listOf(attach, field))
        assertNull("the attach button is left of the field", WrapperNodes.send(noSend, field))
        assertSame(list, WrapperNodes.scrollable(screen))
    }

    @Test
    fun theEditFieldAndAReactionAreFound() {
        val heart = N(text = "\u2764", clickable = true)
        val thumbs = N(text = "\uD83D\uDC4D", clickable = true)
        val reactions = N(scrollable = true, bounds = Rect(100, 300, 900, 380), kids = listOf(heart, thumbs))
        val editing = N(hint = "Edited Message", editable = true, bounds = Rect(100, 500, 800, 600))
        val arrow = N(clickable = true, bounds = Rect(810, 500, 900, 600))
        val cancel = N(clickable = true, bounds = Rect(0, 500, 90, 600))
        val screen2 = N(bounds = Rect(0, 0, 1080, 2000), kids = listOf(reactions, cancel, editing, arrow, field, sendIcon))
        assertSame("❤️ and ❤ are the same", heart, WrapperNodes.reaction(screen2, "\u2764\uFE0F"))
        assertSame(thumbs, WrapperNodes.reaction(screen2, "\uD83D\uDC4D"))
        assertNull(WrapperNodes.reaction(screen2, "\uD83D\uDD25"))
        assertSame(reactions, WrapperNodes.rows(screen2).first())
        assertSame(editing, WrapperNodes.editField(screen2))
        assertSame("the edit's arrow, right of its field", arrow, WrapperNodes.send(screen2, editing))
    }

    @Test
    fun theReceiptUnderTheNewestMessageIsRead() {
        val read = N(kids = listOf(N(text = "Read"), N(text = "10:23 PM")))
        assertEquals(WrapperNodes.Receipt.READ, WrapperNodes.receipt(N(kids = listOf(N(text = "Delivered"), read))))
        assertEquals(WrapperNodes.Receipt.DELIVERED, WrapperNodes.receipt(N(kids = listOf(N(text = "Delivered Quietly")))))
        assertNull("a message about reading is no receipt", WrapperNodes.receipt(N(kids = listOf(N(description = "Have you read the book I sent?")))))
    }
}
