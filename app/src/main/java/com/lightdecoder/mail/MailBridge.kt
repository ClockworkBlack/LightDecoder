package com.lightdecoder.mail

import java.io.File

/**
 * Everything the Gmail tab asks of the mail server, as plain Kotlin. The
 * real one is [ImapMailBridge] (IMAP to read, SMTP to send, with the user's
 * app password); tests use a fake. Every call blocks (network): call off the
 * main thread. Failures are [com.lightdecoder.relay.RelayException] with a
 * content-free message and one of the codes in [MailErrors].
 */
interface MailBridge {

    /** The two mailbox folders the tab reads: what arrived, and what the user sent (from anywhere). */
    enum class Role { INBOX, SENT }

    /** Where a sync of one folder stopped: IMAP's UIDVALIDITY and the highest UID seen. */
    data class FolderState(val uidValidity: Long, val lastUid: Long)

    /** One message's place on the server. */
    data class Location(val folder: String, val uidValidity: Long, val uid: Long)

    data class Address(val email: String, val name: String = "") {
        val label: String get() = name.ifBlank { email }
    }

    data class Attachment(val index: Int, val fileName: String, val mime: String, val size: Long)

    data class Message(
        val location: Location,
        /** The Message-ID header, with its angle brackets; may be empty. */
        val messageId: String,
        val inReplyTo: String,
        /** The References header's ids, oldest first. */
        val references: List<String>,
        val from: Address,
        val to: List<Address>,
        val cc: List<Address>,
        val subject: String,
        val dateMs: Long,
        /** The readable text: plain text, or the HTML part made plain. */
        val text: String,
        val seen: Boolean,
        val attachments: List<Attachment> = emptyList(),
        /** The tmpId this app put in a message it sent (X-LightDecoder-Tmp), so the copy in Sent replaces the local one. */
        val tmpId: String = "",
    )

    data class FetchResult(val state: FolderState, val messages: List<Message>)

    /** An email to send. [attachment] is a file in app storage. */
    data class Outgoing(
        val to: List<Address>,
        val cc: List<Address>,
        val subject: String,
        val text: String,
        val messageId: String,
        val inReplyTo: String = "",
        val references: List<String> = emptyList(),
        val tmpId: String = "",
        val attachment: File? = null,
        val attachmentName: String = "",
        val attachmentMime: String = "",
    )

    /** Signs in once to check the address and app password. */
    fun verify(account: MailAccount)

    /**
     * Messages of [role]'s folder newer than [since] (or, with null or a
     * different UIDVALIDITY, the newest [limit]), oldest first, and where
     * this fetch stopped.
     */
    fun fetch(account: MailAccount, role: Role, since: FolderState?, limit: Int): FetchResult

    /** The bytes of one attachment of the message at [location]. */
    fun attachment(account: MailAccount, location: Location, index: Int): ByteArray

    /** Marks messages read on the server (best effort). */
    fun markSeen(account: MailAccount, locations: List<Location>)

    fun send(account: MailAccount, outgoing: Outgoing)
}

/** Lets tests replace the bridge the manager creates. */
object MailBridges {
    @Volatile
    var factory: (() -> MailBridge)? = null

    fun create(): MailBridge = factory?.invoke() ?: ImapMailBridge()
}
