package com.lightdecoder.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class MailTextTest {

    @Test
    fun quotedHistoryIsCut() {
        assertEquals("Sounds good!", MailText.withoutQuote("Sounds good!\n\nOn Mon, 3 Nov 2026 at 10:00, Ann <ann@example.com> wrote:\n> Lunch?"))
        assertEquals(
            "Sure",
            MailText.withoutQuote("Sure\n\nOn Mon, 3 Nov 2026 at 10:00, Ann Long-Name\n<ann@example.com> wrote:\n> Lunch?"),
        )
        assertEquals("Fine\n\nAnn", MailText.withoutQuote("Fine\n\nAnn\n\n-----Original Message-----\nFrom: Bob"))
        assertEquals("Agreed", MailText.withoutQuote("Agreed\n\n> earlier\n> text\n"))
        assertEquals("a reply that is all quote keeps its text", "> only", MailText.withoutQuote("> only"))
        assertEquals("line one\nline two", MailText.withoutQuote("line one\r\nline two"))
    }

    @Test
    fun subjectsAndAddresses() {
        assertEquals("Lunch", MailText.baseSubject("Re: RE: Fwd: Lunch"))
        assertEquals("Re: Lunch", MailText.replySubject("Re: Lunch"))
        assertEquals("Re: Lunch", MailText.replySubject("Lunch"))
        assertEquals(listOf("<a@x>", "<b@y>"), MailText.ids("<a@x>\n <b@y>"))
        assertTrue(MailText.isAddress("ann@example.com"))
        assertFalse(MailText.isAddress("ann"))
        assertFalse(MailText.isAddress("ann@example"))
        assertEquals(listOf("a@x.com", "b@y.org"), MailText.parseAddresses("a@x.com, <b@y.org>;"))
    }

    @Test
    fun htmlBecomesText() {
        val text = MailText.htmlToText("<style>p{color:red}</style><p>Hello</p>") { it.replace(Regex("<[^>]+>"), "") }
        assertEquals("Hello", text)
    }

    @Test
    fun mediaTokensRoundTrip() {
        val location = MailBridge.Location("[Gmail]/Sent Mail", 9, 42)
        assertEquals(location to 3, MailManager.parseMediaToken(MailManager.mediaToken(location, 3)))
        assertEquals(null, MailManager.parseMediaToken("x1.abc"))
    }
}
