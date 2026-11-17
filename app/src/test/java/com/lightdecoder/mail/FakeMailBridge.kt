package com.lightdecoder.mail

import com.lightdecoder.relay.RelayException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scriptable mail server: an inbox and a Sent folder of [MailBridge.Message]s
 * with increasing UIDs, an app password it accepts, and a record of what was
 * sent. Sending puts a copy in Sent, as Gmail does, with the same headers.
 */
class FakeMailBridge(var password: String = "abcdabcdabcdabcd") : MailBridge {
    val inbox = CopyOnWriteArrayList<MailBridge.Message>()
    val sentFolder = CopyOnWriteArrayList<MailBridge.Message>()
    val sent = CopyOnWriteArrayList<MailBridge.Outgoing>()
    val seen = CopyOnWriteArrayList<MailBridge.Location>()
    val attachments = HashMap<Pair<Long, Int>, ByteArray>()
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var offline = false

    private var nextUid = 1L
    private val validity = 7L

    override fun verify(account: MailAccount) {
        calls += "verify"
        check(account)
    }

    override fun fetch(account: MailAccount, role: MailBridge.Role, since: MailBridge.FolderState?, limit: Int): MailBridge.FetchResult {
        calls += "fetch:${role.name}"
        check(account)
        val folder = if (role == MailBridge.Role.INBOX) inbox else sentFolder
        val after = if (since == null || since.uidValidity != validity) 0L else since.lastUid
        val list = folder.filter { it.location.uid > after }.sortedBy { it.location.uid }.let { if (since == null) it.takeLast(limit) else it }
        val last = maxOf(after, folder.maxOfOrNull { it.location.uid } ?: 0L)
        return MailBridge.FetchResult(MailBridge.FolderState(validity, last), list)
    }

    override fun attachment(account: MailAccount, location: MailBridge.Location, index: Int): ByteArray {
        check(account)
        return attachments[location.uid to index] ?: throw RelayException(RelayException.NOT_FOUND, "no attachment")
    }

    override fun markSeen(account: MailAccount, locations: List<MailBridge.Location>) {
        check(account)
        seen += locations
    }

    override fun send(account: MailAccount, outgoing: MailBridge.Outgoing) {
        calls += "send"
        check(account)
        sent += outgoing
        sentFolder += MailBridge.Message(
            location = location("[Gmail]/Sent Mail"), messageId = outgoing.messageId, inReplyTo = outgoing.inReplyTo,
            references = outgoing.references, from = MailBridge.Address(account.email), to = outgoing.to, cc = outgoing.cc,
            subject = outgoing.subject, dateMs = System.currentTimeMillis(), text = outgoing.text, seen = true,
            tmpId = outgoing.tmpId,
        )
    }

    /** An email arriving in the inbox. */
    fun receive(
        from: String,
        subject: String,
        text: String,
        messageId: String = "<${nextUid}.${System.nanoTime()}@example.com>",
        inReplyTo: String = "",
        references: List<String> = emptyList(),
        to: List<String> = listOf(OWN),
        name: String = "",
        seen: Boolean = false,
        attachments: List<MailBridge.Attachment> = emptyList(),
    ): MailBridge.Message {
        val m = MailBridge.Message(
            location = location("INBOX"), messageId = messageId, inReplyTo = inReplyTo, references = references,
            from = MailBridge.Address(from, name), to = to.map { MailBridge.Address(it) }, cc = emptyList(),
            subject = subject, dateMs = System.currentTimeMillis() + nextUid, text = text, seen = seen, attachments = attachments,
        )
        inbox += m
        return m
    }

    private fun location(folder: String) = MailBridge.Location(folder, validity, nextUid++)

    private fun check(account: MailAccount) {
        if (offline) throw RelayException(RelayException.NOT_CONNECTED, "offline")
        if (account.password != password) throw RelayException(MailErrors.AUTH_FAILED, "refused")
    }

    companion object {
        const val OWN = "me@gmail.com"
        const val PASSWORD = "abcd abcd abcd abcd"
    }
}
