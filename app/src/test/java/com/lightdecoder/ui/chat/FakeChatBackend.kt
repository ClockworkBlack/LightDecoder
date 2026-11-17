package com.lightdecoder.ui.chat

import android.net.Uri
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.ContactsResult
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ChatBackend] the screens can be pointed at through
 * [ScreenBackends.forTesting]: a real archive, and a link that is whatever
 * the test says (state, typing, presence), does nothing, and remembers what
 * it was asked. By default it looks like WhatsApp, linked and connected.
 */
internal class FakeChatBackend(
    override val service: ChatService,
    override val repo: ArchiveRepository,
    override val capabilities: Set<Capability> = setOf(Capability.PRESENCE, Capability.DELETE_FOR_EVERYONE),
) : ChatBackend {

    val stateFlow = MutableStateFlow(RelayState(RelayState.Phase.CONNECTED))
    val typingFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val presenceFlow = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val backfillFlow = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))

    override val state: StateFlow<RelayState> get() = stateFlow
    override val typing: StateFlow<Map<String, Set<String>>> get() = typingFlow
    override val presence: StateFlow<Map<String, Presence>> get() = presenceFlow
    override val backfill: StateFlow<BackfillProgress> get() = backfillFlow

    @Volatile
    var paired = true

    /** Every call that acts, as "name:arguments", in order. */
    val calls = CopyOnWriteArrayList<String>()

    /** What [setAppVisible] was told, in order. */
    val visibility = CopyOnWriteArrayList<Boolean>()

    /** The conversations [watchPresence] was asked about, in order. */
    val watched = CopyOnWriteArrayList<String>()

    /** Conversation id and text of every [sendText]. */
    val texts = CopyOnWriteArrayList<Pair<String, String>>()

    /** What [startConversation] answers. */
    @Volatile
    var startedConversationId = ""

    /** While set, every suspend call that acts for the user fails with it. */
    @Volatile
    var failure: RelayException? = null

    private val tmpIds = AtomicInteger(0)

    private fun act(call: String) {
        calls += call
        failure?.let { throw it }
    }

    override fun isPaired(): Boolean = paired

    override fun sendText(conversationId: String, text: String, replyToId: String?): String {
        calls += "sendText:$conversationId"
        texts += conversationId to text
        return "tmp-${tmpIds.incrementAndGet()}"
    }

    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String {
        calls += "sendMedia:$conversationId"
        return "tmp-${tmpIds.incrementAndGet()}"
    }

    override fun retry(tmpId: String) {
        calls += "retry:$tmpId"
    }

    override fun discardFailed(tmpId: String) {
        calls += "discardFailed:$tmpId"
    }

    override fun typing(conversationId: String) {
        calls += "typing:$conversationId"
    }

    override fun watchPresence(conversationId: String) {
        watched += conversationId
    }

    override fun setAppVisible(visible: Boolean) {
        visibility += visible
    }

    override suspend fun react(messageId: String, emoji: String, remove: Boolean) = act("react:$messageId:$emoji:$remove")

    override suspend fun markRead(conversationId: String) {
        calls += "markRead:$conversationId"
        repo.markConversationRead(conversationId)
    }

    override suspend fun loadOlder(conversationId: String, count: Int): Int {
        act("loadOlder:$conversationId")
        return 0
    }

    override suspend fun deleteMessage(messageId: String) = act("deleteMessage:$messageId")

    override suspend fun deleteConversation(conversationId: String) = act("deleteConversation:$conversationId")

    override suspend fun setArchived(conversationId: String, archived: Boolean) = act("setArchived:$conversationId:$archived")

    override suspend fun setMuted(conversationId: String, muted: Boolean) = act("setMuted:$conversationId:$muted")

    override suspend fun refreshConversations(folder: Folder): Int {
        act("refreshConversations:${folder.wire}")
        return 0
    }

    override suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean): File? = null

    override suspend fun requestFullSize(messageId: String, partId: String) = act("requestFullSize:$messageId")

    override suspend fun contacts(refresh: Boolean): ContactsResult {
        calls += "contacts:$refresh"
        return ContactsResult(repo.contacts(), fromPhone = true)
    }

    override suspend fun startConversation(numbers: List<String>, groupName: String?): String {
        act("startConversation:${numbers.joinToString(",")}:${groupName.orEmpty()}")
        return startedConversationId
    }

    override suspend fun markNotSpam(conversationId: String) = act("markNotSpam:$conversationId")

    override suspend fun restoreDeletedLocally(conversationId: String): Boolean = false

    override suspend fun purgeDeleted(conversationId: String?): Int = 0

    override suspend fun deleteLocalArchive(): Boolean {
        calls += "deleteLocalArchive"
        if (paired) return false
        repo.clearAll()
        return true
    }

    override suspend fun useHere() = act("useHere")
}
