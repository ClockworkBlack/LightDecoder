package com.lightdecoder.mail

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.OutboxRow
import com.lightdecoder.archive.OutboxStatus
import com.lightdecoder.chat.AttachmentPrep
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ContactsResult
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * The Gmail tab at runtime: one per process ([get]). To the conversation
 * screens it is the [ChatBackend] of [ChatService.GMAIL]: an email thread is
 * a conversation, an email a message, and a message sent in a thread is a
 * reply to its latest email (to everyone on it).
 *
 * Gmail is reached over IMAP and SMTP ([MailBridge]) with the user's own
 * address and app password ([MailVault]). No connection stays open: the
 * mailbox is checked when a Gmail screen is shown (and every minute while
 * it is), when the user pulls to refresh, after each send, and in the
 * background about every 15 minutes ([MailCheckJob]). New emails are
 * notified like messages ([MessageNotifier]).
 *
 * Emails are threaded the standard way, by their Message-ID, In-Reply-To
 * and References headers; a conversation's id is a hash of its first
 * email's Message-ID, so it names no one.
 */
class MailManager private constructor(
    context: Context,
    override val repo: ArchiveRepository,
    private val timing: Timing,
) : ChatBackend {

    data class Timing(
        val visiblePollMs: Long = 60_000,
        val retryDelaysMs: List<Long> = listOf(5_000, 20_000, 60_000),
        val echoSyncDelayMs: Long = 2_000,
        val initialPerFolder: Int = 40,
    )

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val bridge: MailBridge by lazy { MailBridges.create() }
    private val index: MailIndex by lazy { MailIndex(appContext, indexName) }

    @Volatile
    private var account: MailAccount? = MailVault.load(appContext)

    override val service: ChatService get() = ChatService.GMAIL
    override val capabilities: Set<Capability> = setOf(Capability.SUBJECT)

    private val _state = MutableStateFlow(idleState())
    override val state: StateFlow<RelayState> = _state.asStateFlow()
    override val typing: StateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap())
    override val presence: StateFlow<Map<String, Presence>> = MutableStateFlow(emptyMap())
    private val _backfill = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))
    override val backfill: StateFlow<BackfillProgress> = _backfill.asStateFlow()

    private val syncLock = Mutex()
    private val sendLock = Mutex()
    private val outboxKicks = Channel<Unit>(Channel.CONFLATED)
    private var visibleScreens = 0
    private var poller: Job? = null

    init {
        scope.launch { for (kick in outboxKicks) drainOutbox() }
        if (account != null) kickOutbox()
    }

    /** The Gmail address added, or null. Never shows the password. */
    fun accountEmail(): String? = account?.email

    override fun isPaired(): Boolean = account != null

    private fun idleState(): RelayState =
        if (account == null) RelayState(RelayState.Phase.UNPAIRED) else RelayState(RelayState.Phase.DISCONNECTED)

    // ============================== account ==============================

    /**
     * Checks [email] and [appPassword] with Gmail and, if Google accepts
     * them, stores them (encrypted) and fetches the recent mail. Throws
     * [RelayException] with [MailErrors.AUTH_FAILED], not_connected or
     * invalid_argument.
     */
    suspend fun addAccount(email: String, appPassword: String) = withContext(Dispatchers.IO) {
        val address = email.trim().lowercase()
        if (!MailText.isAddress(address)) throw RelayException(RelayException.INVALID_ARGUMENT, "not an email address")
        // App passwords are shown in groups of four; the spaces are not part of it.
        val password = appPassword.filterNot { it.isWhitespace() }
        if (password.isEmpty()) throw RelayException(MailErrors.AUTH_FAILED, "no password")
        val candidate = MailAccount(address, password)
        _state.value = RelayState(RelayState.Phase.CONNECTING)
        try {
            bridge.verify(candidate)
        } catch (e: RelayException) {
            _state.value = idleState()
            throw e
        }
        val previous = account?.email
        if (previous != null && previous != address) wipeLocal(deleteArchive = true)
        MailVault.save(appContext, candidate)
        account = candidate
        MailCheckJob.schedule(appContext)
        _state.value = RelayState(RelayState.Phase.CONNECTED)
        syncNow(notify = false)
    }

    /** Removes the account from this phone (its app password is deleted); with [deleteArchive] the downloaded mail too. Does not throw. */
    suspend fun removeAccount(deleteArchive: Boolean) = withContext(Dispatchers.IO) {
        syncLock.withLock {
            sendLock.withLock { wipeLocal(deleteArchive) }
        }
    }

    private fun wipeLocal(deleteArchive: Boolean) {
        MailVault.save(appContext, null)
        account = null
        MailCheckJob.cancel(appContext)
        MailPrefs.clearSync(appContext)
        runCatching { index.clear() }
        // Queued emails never go out under another account.
        for (row in runCatching { repo.pendingOutbox() }.getOrDefault(emptyList())) {
            repo.setLocalState(row.idempotencyKey, LocalState.FAILED, NO_ACCOUNT)
            repo.updateOutbox(row.idempotencyKey, OutboxStatus.FAILED, error = NO_ACCOUNT)
        }
        if (deleteArchive) runCatching { repo.clearAll() }
        MessageNotifier.cancelAll(appContext, ChatService.GMAIL)
        _state.value = RelayState(RelayState.Phase.UNPAIRED)
    }

    // ============================== syncing ==============================

    /** Checks for new mail in the background; screens call this, it never throws. */
    fun syncSoon(notify: Boolean = true) {
        if (account == null) return
        scope.launch { runCatching { syncNow(notify) } }
    }

    /**
     * Fetches what is new in the inbox and in Sent, and stores it. New
     * incoming email is notified when [notify] (the first fetch of an
     * account never is). Returns how many emails arrived. Throws
     * [RelayException] (not_paired, not_connected, auth_failed).
     */
    suspend fun syncNow(notify: Boolean = true): Int = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val acct = account ?: throw RelayException(RelayException.NOT_PAIRED, "no Gmail account")
            if (_state.value.phase != RelayState.Phase.CONNECTED) _state.value = RelayState(RelayState.Phase.CONNECTING)
            var arrived = 0
            try {
                for (role in MailBridge.Role.entries) {
                    val since = MailPrefs.folderState(appContext, role)
                    val first = since == null
                    val result = bridge.fetch(acct, role, since, timing.initialPerFolder)
                    if (account !== acct) return@withLock arrived
                    arrived += store(acct, result.messages, old = first || !notify)
                    MailPrefs.setFolderState(appContext, role, result.state)
                }
                _state.value = RelayState(RelayState.Phase.CONNECTED)
                repo.updateHealth(status = "connected", lastEventTs = System.currentTimeMillis(), lastError = "", account = acct.email)
            } catch (e: RelayException) {
                _state.value = when (e.code) {
                    MailErrors.AUTH_FAILED -> RelayState(RelayState.Phase.LOGGED_OUT, MailErrors.AUTH_FAILED)
                    else -> RelayState(RelayState.Phase.DISCONNECTED, e.code)
                }
                runCatching { repo.updateHealth(status = "error", lastError = e.code) }
                throw e
            }
            kickOutbox()
            arrived
        }
    }

    /** Stores fetched emails as messages of their threads; returns how many were new. */
    private fun store(acct: MailAccount, messages: List<MailBridge.Message>, old: Boolean): Int {
        var added = 0
        val touched = LinkedHashSet<String>()
        for (m in messages) {
            val id = messageIdFor(m)
            val known = index.record(id) != null
            val thread = threadFor(m)
            index.put(
                MailIndex.Record(
                    id = id, thread = thread, location = m.location, messageId = m.messageId,
                    references = (m.references + m.inReplyTo).filter { it.isNotEmpty() }.distinct(),
                    subject = m.subject, from = m.from, to = m.to, cc = m.cc, dateMs = m.dateMs,
                    seen = m.seen || (known && index.record(id)?.seen == true),
                ),
            )
            val fromMe = m.from.email.equals(acct.email, ignoreCase = true)
            val dto = MessageDto(
                id = id,
                conversationId = thread,
                tmpId = m.tmpId,
                senderId = m.from.email,
                senderName = if (fromMe) "" else m.from.label,
                fromMe = fromMe,
                timestampMs = m.dateMs,
                direction = if (fromMe) "out" else "in",
                status = if (fromMe) "OUTGOING_COMPLETE" else "",
                transport = "EMAIL",
                // The thread's title is its subject: not repeated on every email.
                text = m.text,
                parts = m.attachments.map { a ->
                    PartDto(
                        partId = "a${a.index}", kind = "media", mediaId = mediaToken(m.location, a.index),
                        mime = a.mime, fileName = a.fileName, size = a.size,
                    )
                },
                isOld = old || m.seen || known,
            )
            touched += thread
            upsertConversation(acct, thread, newUnread = !fromMe && !m.seen && !known)
            repo.upsertMessage(dto)
            // An echo of a send from here: its queue entry and attachment copy are done with.
            if (m.tmpId.isNotEmpty()) {
                repo.outbox(m.tmpId)?.let { repo.deleteOutbox(m.tmpId) }
                repo.outboxFileFor(m.tmpId).delete()
            }
            if (!known) {
                added++
                MessageNotifier.onIncoming(appContext, repo, dto)
            }
        }
        return added
    }

    private fun upsertConversation(acct: MailAccount, thread: String, newUnread: Boolean) {
        val records = index.thread(thread)
        val first = records.firstOrNull()
        val subject = MailText.baseSubject(records.lastOrNull()?.subject ?: index.draft(thread)?.subject.orEmpty())
        val people = LinkedHashMap<String, String>()
        for (r in records) for (a in listOf(r.from) + r.to + r.cc) {
            if (a.email.isNotEmpty() && !a.email.equals(acct.email, true) && people[a.email].isNullOrEmpty()) people[a.email] = a.name
        }
        index.draft(thread)?.to?.forEach { if (people[it.email].isNullOrEmpty()) people[it.email] = it.name }
        val existing = repo.conversation(thread)
        repo.upsertConversation(
            ConversationDto(
                id = thread,
                name = subject.ifEmpty { people.values.firstOrNull { it.isNotEmpty() } ?: people.keys.firstOrNull().orEmpty() },
                isGroup = people.size > 1,
                type = "EMAIL",
                folder = Folder.INBOX.wire,
                unread = newUnread || existing?.unread == true,
                muted = existing?.muted == true,
                lastMessageTs = maxOf(records.maxOfOrNull { it.dateMs } ?: 0L, existing?.lastMessageTs ?: 0L),
                participants = listOf(ParticipantDto(id = acct.email, name = acct.email, isMe = true)) +
                    people.map { (email, name) -> ParticipantDto(id = email, name = name.ifEmpty { email }, number = email, formattedNumber = email) },
            ),
        )
        if (first == null) return
    }

    /**
     * The thread an email belongs to: the thread of any email it refers to
     * that is known here, else one named after the first id it refers to
     * (the thread's first email), else its own Message-ID.
     */
    private fun threadFor(m: MailBridge.Message): String {
        val refs = (m.references + m.inReplyTo).filter { it.isNotEmpty() }
        for (ref in refs.asReversed()) index.threadOfHeader(ref)?.let { return it }
        index.threadOfHeader(m.messageId)?.let { return it }
        val root = refs.firstOrNull() ?: m.messageId.ifEmpty { "${m.location.folder}/${m.location.uidValidity}/${m.location.uid}" }
        return threadIdFor(root)
    }

    private fun messageIdFor(m: MailBridge.Message): String =
        "mail:" + hash(m.messageId.ifEmpty { "${m.location.folder}/${m.location.uidValidity}/${m.location.uid}" })

    // ============================== screens in front ==============================

    override fun setAppVisible(visible: Boolean) {
        synchronized(this) {
            visibleScreens = (visibleScreens + if (visible) 1 else -1).coerceAtLeast(0)
            if (visibleScreens > 0 && poller?.isActive != true && account != null) {
                poller = scope.launch {
                    while (true) {
                        runCatching { syncNow(notify = true) }
                        delay(timing.visiblePollMs)
                    }
                }
            } else if (visibleScreens == 0) {
                poller?.cancel()
                poller = null
            }
        }
    }

    override fun typing(conversationId: String) = Unit
    override fun watchPresence(conversationId: String) = Unit

    // ============================== sending ==============================

    /**
     * Starts a new email to [to] (addresses) about [subject]: an empty
     * conversation until its first message is sent from the thread screen.
     * Returns its id. Throws invalid_argument for an address that is not one.
     */
    fun startEmail(to: List<String>, subject: String): String {
        requireAccount()
        val addresses = to.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (addresses.isEmpty() || addresses.any { !MailText.isAddress(it) }) {
            throw RelayException(RelayException.INVALID_ARGUMENT, "not an email address")
        }
        val acct = account!!
        val messageId = newMessageId(acct)
        val thread = threadIdFor(messageId)
        index.putDraft(MailIndex.Draft(thread, messageId, addresses.map { MailBridge.Address(it) }, subject.trim()))
        upsertConversation(acct, thread, newUnread = false)
        return thread
    }

    override suspend fun startConversation(numbers: List<String>, groupName: String?): String =
        withContext(Dispatchers.IO) { startEmail(numbers, groupName.orEmpty()) }

    override fun sendText(conversationId: String, text: String, replyToId: String?): String {
        requireAccount()
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        repo.insertLocalOutgoing(tmpId, conversationId, text, null, null, null, null, now)
        repo.enqueueOutbox(OutboxRow(idempotencyKey = tmpId, conversationId = conversationId, textBody = text, createdAt = now))
        kickOutbox()
        return tmpId
    }

    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String {
        requireAccount()
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val file = repo.outboxFileFor(tmpId)
        var name = displayName(uri)
        var mime = mimeOf(uri, name)
        val copied = copy(uri, file)
        if (copied) AttachmentPrep.prepare(file, mime, name, AttachmentPrep.LARGE)?.let { name = it.name; mime = it.mime }
        val text = caption?.trim().orEmpty()
        repo.insertLocalOutgoing(tmpId, conversationId, text, file.path, mime, name, null, now)
        repo.enqueueOutbox(
            OutboxRow(
                idempotencyKey = tmpId, conversationId = conversationId, textBody = text, mediaLocalPath = file.path,
                mediaMime = mime, mediaName = name, status = if (copied) OutboxStatus.PENDING else OutboxStatus.FAILED,
                lastError = if (copied) "" else "attachment_unreadable", createdAt = now,
            ),
        )
        if (!copied) repo.setLocalState(tmpId, LocalState.FAILED, "attachment_unreadable")
        kickOutbox()
        return tmpId
    }

    override fun retry(tmpId: String) {
        scope.launch {
            val row = repo.outbox(tmpId) ?: return@launch
            if (row.status != OutboxStatus.FAILED) return@launch
            repo.setLocalState(tmpId, LocalState.PENDING)
            repo.updateOutbox(tmpId, OutboxStatus.PENDING)
            kickOutbox()
        }
    }

    override fun discardFailed(tmpId: String) {
        scope.launch {
            sendLock.withLock {
                if (repo.outbox(tmpId)?.status == OutboxStatus.SENDING) return@withLock
                repo.deleteOutbox(tmpId)
                repo.applyDeletion(MessageRow.tmpMessageId(tmpId))
                repo.outboxFileFor(tmpId).delete()
            }
        }
    }

    private fun kickOutbox() {
        outboxKicks.trySend(Unit)
    }

    private suspend fun drainOutbox() = sendLock.withLock {
        while (true) {
            val acct = account ?: return@withLock
            val row = runCatching { repo.pendingOutbox() }.getOrDefault(emptyList())
                .firstOrNull { it.status == OutboxStatus.PENDING } ?: return@withLock
            if (!sendOne(acct, row)) return@withLock
        }
    }

    /** False when sending should stop for now (no network, or the account was refused). */
    private suspend fun sendOne(acct: MailAccount, row: OutboxRow): Boolean {
        val key = row.idempotencyKey
        repo.updateOutbox(key, OutboxStatus.SENDING, attemptsDelta = 1)
        repo.setLocalState(key, LocalState.SENDING)
        var attempt = 0
        while (true) {
            val outgoing = try {
                outgoingFor(acct, row)
            } catch (e: RelayException) {
                fail(key, e.code)
                return true
            }
            try {
                bridge.send(acct, outgoing)
                repo.updateOutbox(key, OutboxStatus.SENT)
                // Its copy in Sent replaces the local one (X-LightDecoder-Tmp); fetched right away.
                scope.launch {
                    delay(timing.echoSyncDelayMs)
                    runCatching { syncNow(notify = false) }
                }
                return true
            } catch (e: RelayException) {
                when (e.code) {
                    RelayException.NOT_CONNECTED -> {
                        if (attempt < timing.retryDelaysMs.size) {
                            delay(timing.retryDelaysMs[attempt++])
                            continue
                        }
                        // Waits for the next check; the user can also retry.
                        repo.setLocalState(key, LocalState.PENDING)
                        repo.updateOutbox(key, OutboxStatus.PENDING, error = e.code)
                        _state.value = RelayState(RelayState.Phase.DISCONNECTED, e.code)
                        return false
                    }
                    MailErrors.AUTH_FAILED -> {
                        fail(key, e.code)
                        _state.value = RelayState(RelayState.Phase.LOGGED_OUT, MailErrors.AUTH_FAILED)
                        return false
                    }
                    else -> {
                        fail(key, e.code)
                        return true
                    }
                }
            }
        }
    }

    private fun fail(key: String, code: String) {
        repo.setLocalState(key, LocalState.FAILED, code)
        repo.updateOutbox(key, OutboxStatus.FAILED, error = code)
    }

    /** The email for a queued message: a reply to everyone on the thread's latest email, or the first email of a new one. */
    private fun outgoingFor(acct: MailAccount, row: OutboxRow): MailBridge.Outgoing {
        val records = index.thread(row.conversationId)
        val attachment = row.mediaLocalPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        if (row.mediaLocalPath.isNotEmpty() && attachment == null) throw RelayException(RelayException.NOT_FOUND, "attachment_missing")
        val last = records.lastOrNull()
        if (last == null) {
            val draft = index.draft(row.conversationId) ?: throw RelayException(RelayException.NOT_FOUND, "conversation unknown")
            return MailBridge.Outgoing(
                to = draft.to, cc = emptyList(), subject = draft.subject, text = row.textBody, messageId = draft.messageId,
                tmpId = row.idempotencyKey, attachment = attachment, attachmentName = row.mediaName, attachmentMime = row.mediaMime,
            )
        }
        val me = acct.email
        val to: List<MailBridge.Address>
        val cc: List<MailBridge.Address>
        if (last.from.email.equals(me, true)) {
            to = last.to
            cc = last.cc
        } else {
            to = (listOf(last.from) + last.to).filterNot { it.email.equals(me, true) }.distinctBy { it.email }
            cc = last.cc.filterNot { it.email.equals(me, true) || to.any { t -> t.email == it.email } }
        }
        return MailBridge.Outgoing(
            to = to.ifEmpty { listOf(last.from) }, cc = cc, subject = MailText.replySubject(last.subject), text = row.textBody,
            messageId = newMessageId(acct), inReplyTo = last.messageId,
            references = (last.references + last.messageId).filter { it.isNotEmpty() }.distinct().takeLast(MAX_REFERENCES),
            tmpId = row.idempotencyKey, attachment = attachment, attachmentName = row.mediaName, attachmentMime = row.mediaMime,
        )
    }

    // ============================== the rest of the backend ==============================

    override suspend fun react(messageId: String, emoji: String, remove: Boolean): Unit =
        throw RelayException(RelayException.INVALID_ARGUMENT, "email has no reactions")

    override suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        runCatching { repo.markConversationRead(conversationId) }
        MessageNotifier.cancel(appContext, conversationId, ChatService.GMAIL)
        val acct = account ?: return@withContext
        val unseen = runCatching { index.unseenIn(conversationId) }.getOrDefault(emptyList())
        if (unseen.isEmpty()) return@withContext
        runCatching {
            bridge.markSeen(acct, unseen.map { it.location })
            index.markSeen(conversationId)
        }
    }

    override suspend fun loadOlder(conversationId: String, count: Int): Int = 0

    /** Removes the email from this phone only; it stays in Gmail. */
    override suspend fun deleteMessage(messageId: String): Unit = withContext(Dispatchers.IO) {
        if (messageId.startsWith(MessageRow.TMP_PREFIX)) {
            discardFailed(messageId.removePrefix(MessageRow.TMP_PREFIX))
            return@withContext
        }
        repo.applyDeletion(messageId)
    }

    /** Removes the thread from this phone only; it stays in Gmail, and a new email in it brings it back. */
    override suspend fun deleteConversation(conversationId: String): Unit = withContext(Dispatchers.IO) {
        repo.deleteConversationLocal(conversationId)
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        repo.setConversationFolder(conversationId, if (archived) Folder.ARCHIVE else Folder.INBOX)
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean): Unit = withContext(Dispatchers.IO) {
        repo.setConversationMuted(conversationId, muted)
    }

    override suspend fun refreshConversations(folder: Folder): Int {
        if (folder != Folder.INBOX) return 0
        syncNow(notify = true)
        return repo.conversations(Folder.INBOX).size
    }

    override suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean): File? = withContext(Dispatchers.IO) {
        val part = repo.mediaPart(messageId, partId) ?: return@withContext null
        part.localPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }?.let { return@withContext it }
        val acct = account ?: throw RelayException(RelayException.NOT_CONNECTED, "no Gmail account")
        val (location, index) = parseMediaToken(part.mediaId) ?: return@withContext null
        val bytes = bridge.attachment(acct, location, index)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(part.mime)
            ?: part.fileName.substringAfterLast('.', "bin").take(8)
        val file = repo.mediaFileFor(hash(part.mediaId), extension)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
        repo.setMediaLocalPath(messageId, partId, file.path, thumbnail = false)
        file
    }

    override suspend fun requestFullSize(messageId: String, partId: String): Unit =
        throw RelayException(RelayException.INVALID_ARGUMENT, "not needed for email")

    override suspend fun contacts(refresh: Boolean): ContactsResult = withContext(Dispatchers.IO) {
        val me = account?.email.orEmpty()
        val people = runCatching { index.people(me) }.getOrDefault(LinkedHashMap())
        if (people.isNotEmpty()) {
            repo.upsertContacts(people.map { (email, name) -> ContactDto(id = email, name = name.ifEmpty { email }, number = email, formattedNumber = email) })
        }
        ContactsResult(repo.contacts(), fromPhone = false)
    }

    override suspend fun markNotSpam(conversationId: String): Unit =
        throw RelayException(RelayException.INVALID_ARGUMENT, "no spam folder here")

    override suspend fun restoreDeletedLocally(conversationId: String): Boolean = false

    override suspend fun purgeDeleted(conversationId: String?): Int = 0

    override suspend fun deleteLocalArchive(): Boolean = withContext(Dispatchers.IO) {
        if (account != null) return@withContext false
        runCatching { repo.clearAll() }.isSuccess
    }

    override suspend fun useHere(): Unit = throw RelayException(RelayException.INVALID_ARGUMENT, "not for email")

    // ============================== helpers ==============================

    private fun requireAccount() {
        if (account == null) throw RelayException(RelayException.NOT_PAIRED, "no Gmail account")
    }

    private fun newMessageId(acct: MailAccount) = "<${UUID.randomUUID()}.lightdecoder@${acct.domain}>"

    private fun displayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }.substringAfterLast('/').take(120).ifBlank { "attachment" }

    private fun mimeOf(uri: Uri, name: String): String =
        runCatching { appContext.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty()

    private fun copy(uri: Uri, target: File): Boolean = try {
        target.parentFile?.mkdirs()
        val input = appContext.contentResolver.openInputStream(uri) ?: return false
        var total = 0L
        input.use { stream ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ATTACHMENT_BYTES) return false
                    out.write(buffer, 0, n)
                }
            }
        }
        total > 0
    } catch (e: Exception) {
        false
    }

    internal fun shutdownForTesting() {
        scope.cancel()
        runCatching { index.close() }
    }

    companion object {
        /** The error a queued email waits with when the account was removed. */
        internal const val NO_ACCOUNT = "no_account"
        private const val MAX_REFERENCES = 20
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

        /** The index database's name (tests use their own). */
        @Volatile
        internal var indexName: String = MailIndex.NAME

        internal fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

        internal fun threadIdFor(rootMessageId: String) = "mail:t" + hash(rootMessageId)

        /** The media id of an attachment: where it is on the server, to fetch it on demand. */
        internal fun mediaToken(location: MailBridge.Location, index: Int): String {
            val json = JSONObject().put("f", location.folder).put("v", location.uidValidity).put("u", location.uid).put("i", index)
            return "mail1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
        }

        internal fun parseMediaToken(token: String): Pair<MailBridge.Location, Int>? = runCatching {
            if (!token.startsWith("mail1.")) return null
            val json = JSONObject(String(Base64.getUrlDecoder().decode(token.removePrefix("mail1."))))
            MailBridge.Location(json.getString("f"), json.getLong("v"), json.getLong("u")) to json.getInt("i")
        }.getOrNull()

        @Volatile
        private var instance: MailManager? = null

        fun get(context: Context): MailManager =
            instance ?: synchronized(this) {
                instance ?: MailManager(context, ArchiveRepository.get(context, ChatService.GMAIL), Timing()).also { instance = it }
            }

        internal fun installForTesting(context: Context, repo: ArchiveRepository, timing: Timing = Timing()): MailManager {
            require(repo.service == ChatService.GMAIL) { "the repository is ${repo.service}'s, not Gmail's" }
            return synchronized(this) {
                instance?.shutdownForTesting()
                MailManager(context, repo, timing).also { instance = it }
            }
        }

        internal fun resetForTesting() = synchronized(this) {
            instance?.shutdownForTesting()
            instance = null
        }
    }
}
