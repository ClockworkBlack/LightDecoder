package com.lightdecoder.mail

import android.os.Build
import android.text.Html
import com.lightdecoder.relay.RelayException
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.SendFailedException
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

/**
 * Gmail over the standard mail protocols: IMAP (imap.gmail.com:993, TLS) to
 * read, SMTP (smtp.gmail.com:465, TLS) to send, signed in with the user's
 * Gmail address and an app password (Google's way for mail apps; it needs
 * 2-step verification on the account). Connections are opened per call and
 * closed after it. Nothing here logs addresses, subjects or text.
 */
class ImapMailBridge : MailBridge {

    private fun session(): Session = Session.getInstance(
        Properties().apply {
            put("mail.imaps.host", IMAP_HOST)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", TIMEOUT_MS)
            put("mail.imaps.timeout", TIMEOUT_MS)
            put("mail.imaps.writetimeout", TIMEOUT_MS)
            put("mail.imaps.partialfetch", "false")
            put("mail.smtps.host", SMTP_HOST)
            put("mail.smtps.port", "465")
            put("mail.smtps.auth", "true")
            put("mail.smtps.ssl.checkserveridentity", "true")
            put("mail.smtps.connectiontimeout", TIMEOUT_MS)
            put("mail.smtps.timeout", SEND_TIMEOUT_MS)
            put("mail.smtps.writetimeout", SEND_TIMEOUT_MS)
            put("mail.mime.address.strict", "false")
        },
    )

    private fun <T> withStore(account: MailAccount, block: (IMAPStore) -> T): T = mapErrors {
        val store = session().getStore("imaps") as IMAPStore
        store.connect(IMAP_HOST, account.email, account.password)
        try {
            block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    override fun verify(account: MailAccount) {
        withStore(account) { it.getFolder("INBOX").exists() }
    }

    override fun fetch(account: MailAccount, role: MailBridge.Role, since: MailBridge.FolderState?, limit: Int): MailBridge.FetchResult =
        withStore(account) { store ->
            val folder = folderFor(store, role)
            folder.open(Folder.READ_ONLY)
            try {
                val validity = folder.uidValidity
                val fresh = since == null || since.uidValidity != validity
                val messages: Array<Message> = if (fresh) {
                    val total = folder.messageCount
                    if (total <= 0) emptyArray() else folder.getMessages(maxOf(1, total - limit + 1), total)
                } else {
                    // An empty range still returns the last message: those at or below the bookmark go.
                    folder.getMessagesByUID(since!!.lastUid + 1, UIDFolder.LASTUID)
                        .filter { folder.getUID(it) > since.lastUid }.takeLast(MAX_PER_FETCH).toTypedArray()
                }
                if (messages.isNotEmpty()) {
                    folder.fetch(
                        messages,
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.FLAGS)
                            add(FetchProfile.Item.CONTENT_INFO)
                            add(UIDFolder.FetchProfileItem.UID)
                            add("References")
                            add("In-Reply-To")
                            add("Message-ID")
                            add(TMP_HEADER)
                        },
                    )
                }
                var lastUid = since?.takeIf { !fresh }?.lastUid ?: 0L
                val out = ArrayList<MailBridge.Message>(messages.size)
                for (m in messages) {
                    val uid = folder.getUID(m)
                    lastUid = maxOf(lastUid, uid)
                    // One unreadable message must not stop the rest.
                    runCatching { convert(m, MailBridge.Location(folder.fullName, validity, uid), role) }
                        .getOrNull()?.let { out += it }
                }
                if (fresh && messages.isEmpty()) lastUid = maxOf(0L, folder.uidNext - 1)
                MailBridge.FetchResult(MailBridge.FolderState(validity, lastUid), out.sortedBy { it.location.uid })
            } finally {
                runCatching { folder.close(false) }
            }
        }

    override fun attachment(account: MailAccount, location: MailBridge.Location, index: Int): ByteArray =
        withStore(account) { store ->
            val folder = store.getFolder(location.folder) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                if (folder.uidValidity != location.uidValidity) throw RelayException(RelayException.NOT_FOUND, "mailbox changed")
                val message = folder.getMessageByUID(location.uid) ?: throw RelayException(RelayException.NOT_FOUND, "message gone")
                val part = attachmentParts(message).getOrNull(index) ?: throw RelayException(RelayException.NOT_FOUND, "no such attachment")
                part.inputStream.use { readCapped(it) }
            } finally {
                runCatching { folder.close(false) }
            }
        }

    override fun markSeen(account: MailAccount, locations: List<MailBridge.Location>) {
        if (locations.isEmpty()) return
        withStore(account) { store ->
            for ((name, inFolder) in locations.groupBy { it.folder }) {
                val folder = store.getFolder(name) as IMAPFolder
                folder.open(Folder.READ_WRITE)
                try {
                    for (location in inFolder) {
                        if (location.uidValidity != folder.uidValidity) continue
                        folder.getMessageByUID(location.uid)?.setFlag(Flags.Flag.SEEN, true)
                    }
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        }
    }

    override fun send(account: MailAccount, outgoing: MailBridge.Outgoing) = mapErrors {
        if (outgoing.to.isEmpty() && outgoing.cc.isEmpty()) throw RelayException(RelayException.INVALID_ARGUMENT, "no recipient")
        val session = session()
        val message = object : MimeMessage(session) {
            // Keep the Message-ID this app chose: the thread is found by it.
            override fun updateMessageID() = setHeader("Message-ID", outgoing.messageId)
        }
        message.setFrom(InternetAddress(account.email))
        message.setRecipients(Message.RecipientType.TO, outgoing.to.map(::internet).toTypedArray())
        if (outgoing.cc.isNotEmpty()) message.setRecipients(Message.RecipientType.CC, outgoing.cc.map(::internet).toTypedArray())
        message.setSubject(outgoing.subject, "UTF-8")
        message.sentDate = java.util.Date()
        if (outgoing.inReplyTo.isNotEmpty()) message.setHeader("In-Reply-To", outgoing.inReplyTo)
        if (outgoing.references.isNotEmpty()) message.setHeader("References", outgoing.references.joinToString(" "))
        if (outgoing.tmpId.isNotEmpty()) message.setHeader(TMP_HEADER, outgoing.tmpId)
        val attachment = outgoing.attachment
        if (attachment == null) {
            message.setText(outgoing.text, "UTF-8")
        } else {
            val body = MimeBodyPart().apply { setText(outgoing.text, "UTF-8") }
            val file = MimeBodyPart().apply {
                dataHandler = DataHandler(object : FileDataSource(attachment) {
                    override fun getContentType(): String = outgoing.attachmentMime.ifBlank { "application/octet-stream" }
                })
                fileName = MimeUtility.encodeText(outgoing.attachmentName.ifBlank { attachment.name }, "UTF-8", null)
                disposition = Part.ATTACHMENT
            }
            message.setContent(MimeMultipart().apply { addBodyPart(body); addBodyPart(file) })
        }
        message.saveChanges()
        val transport = session.getTransport("smtps")
        transport.connect(SMTP_HOST, 465, account.email, account.password)
        try {
            transport.sendMessage(message, message.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
    }

    // --- reading ----------------------------------------------------------------------------

    /** INBOX, or the folder the server marks as \Sent (its name is in the account's language). */
    private fun folderFor(store: IMAPStore, role: MailBridge.Role): IMAPFolder {
        if (role == MailBridge.Role.INBOX) return store.getFolder("INBOX") as IMAPFolder
        val all = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
        all.firstOrNull { f -> f.attributes.any { it.equals("\\Sent", ignoreCase = true) } }?.let { return it }
        return (all.firstOrNull { it.fullName.equals("[Gmail]/Sent Mail", true) || it.fullName.equals("[Google Mail]/Sent Mail", true) }
            ?: throw RelayException(RelayException.NOT_FOUND, "no Sent folder"))
    }

    private fun convert(m: Message, location: MailBridge.Location, role: MailBridge.Role): MailBridge.Message {
        val from = (m.from?.firstOrNull() as? InternetAddress)?.let(::address) ?: MailBridge.Address("")
        val body = bodyText(m)
        return MailBridge.Message(
            location = location,
            messageId = m.getHeader("Message-ID")?.firstOrNull()?.trim().orEmpty(),
            inReplyTo = MailText.ids(m.getHeader("In-Reply-To")?.firstOrNull()).lastOrNull().orEmpty(),
            references = MailText.ids(m.getHeader("References")?.joinToString(" ")),
            from = from,
            to = addresses(m, Message.RecipientType.TO),
            cc = addresses(m, Message.RecipientType.CC),
            subject = m.subject.orEmpty(),
            dateMs = (m.receivedDate ?: m.sentDate)?.time ?: System.currentTimeMillis(),
            text = MailText.withoutQuote(body),
            seen = role == MailBridge.Role.SENT || m.isSet(Flags.Flag.SEEN),
            attachments = attachmentParts(m).mapIndexed { i, p ->
                MailBridge.Attachment(
                    index = i,
                    fileName = runCatching { p.fileName?.let { MimeUtility.decodeText(it) } }.getOrNull().orEmpty().ifBlank { "attachment" },
                    mime = p.contentType.orEmpty().substringBefore(';').trim().lowercase(),
                    size = p.size.toLong().coerceAtLeast(0),
                )
            },
            tmpId = m.getHeader(TMP_HEADER)?.firstOrNull()?.trim().orEmpty(),
        )
    }

    private fun addresses(m: Message, type: Message.RecipientType): List<MailBridge.Address> =
        runCatching { m.getRecipients(type) }.getOrNull().orEmpty().filterIsInstance<InternetAddress>().map(::address)

    private fun address(a: InternetAddress) = MailBridge.Address(a.address.orEmpty().lowercase(), a.personal.orEmpty())

    private fun internet(a: MailBridge.Address): InternetAddress = try {
        InternetAddress(a.email, true).apply { if (a.name.isNotBlank()) setPersonal(a.name, "UTF-8") }
    } catch (e: AddressException) {
        throw RelayException(RelayException.INVALID_ARGUMENT, "not an email address")
    }

    /** The message's text: its text/plain part, else its HTML made plain. */
    private fun bodyText(part: Part): String {
        var plain: String? = null
        var html: String? = null
        fun walk(p: Part, depth: Int) {
            if (depth > 8) return
            val disposition = runCatching { p.disposition }.getOrNull()
            if (Part.ATTACHMENT.equals(disposition, true)) return
            when {
                p.isMimeType("text/plain") && plain == null -> plain = runCatching { p.content as? String }.getOrNull()
                p.isMimeType("text/html") && html == null -> html = runCatching { p.content as? String }.getOrNull()
                p.isMimeType("multipart/*") -> {
                    val mp = runCatching { p.content as? Multipart }.getOrNull() ?: return
                    for (i in 0 until mp.count) walk(mp.getBodyPart(i), depth + 1)
                }
            }
        }
        walk(part, 0)
        plain?.takeIf { it.isNotBlank() }?.let { return it }
        val h = html ?: return ""
        return MailText.htmlToText(h) { s ->
            if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString()
            else @Suppress("DEPRECATION") Html.fromHtml(s).toString()
        }
    }

    /** The parts that are files: marked as attachments, or inline with a file name (pictures). */
    private fun attachmentParts(message: Part): List<Part> {
        val out = ArrayList<Part>()
        fun walk(p: Part, depth: Int) {
            if (depth > 8) return
            if (p.isMimeType("multipart/*")) {
                val mp = runCatching { p.content as? Multipart }.getOrNull() ?: return
                for (i in 0 until mp.count) walk(mp.getBodyPart(i), depth + 1)
                return
            }
            if (depth == 0) return // the message itself is its body, not an attachment
            val disposition = runCatching { p.disposition }.getOrNull()
            val name = runCatching { p.fileName }.getOrNull()
            if (Part.ATTACHMENT.equals(disposition, true) || !name.isNullOrBlank()) out += p
        }
        walk(message, 0)
        return out
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_ATTACHMENT_BYTES) throw RelayException(RelayException.TOO_LARGE, "attachment too large")
        }
        return out.toByteArray()
    }

    /** Mail exceptions as content-free [RelayException]s (their messages can quote addresses). */
    private inline fun <T> mapErrors(block: () -> T): T = try {
        block()
    } catch (e: RelayException) {
        throw e
    } catch (e: AuthenticationFailedException) {
        throw RelayException(MailErrors.AUTH_FAILED, "sign-in refused")
    } catch (e: SendFailedException) {
        throw RelayException(RelayException.INVALID_ARGUMENT, "an address was refused")
    } catch (e: MessagingException) {
        val cause = generateSequence<Throwable>(e) { it.cause ?: (it as? MessagingException)?.nextException }.drop(1).firstOrNull()
        throw when (cause) {
            is UnknownHostException, is SocketTimeoutException, is IOException ->
                RelayException(RelayException.NOT_CONNECTED, "no connection to Gmail")
            else -> RelayException(RelayException.SEND_FAILED, "mail error: ${e.javaClass.simpleName}")
        }
    } catch (e: IOException) {
        throw RelayException(RelayException.NOT_CONNECTED, "no connection to Gmail")
    }

    companion object {
        const val IMAP_HOST = "imap.gmail.com"
        const val SMTP_HOST = "smtp.gmail.com"

        /** Put in every email this app sends, so its copy in Sent can replace the local one. */
        const val TMP_HEADER = "X-LightDecoder-Tmp"

        private const val TIMEOUT_MS = "30000"
        private const val SEND_TIMEOUT_MS = "120000"
        private const val MAX_PER_FETCH = 200
        private const val MAX_ATTACHMENT_BYTES = 30L * 1024 * 1024
    }
}
