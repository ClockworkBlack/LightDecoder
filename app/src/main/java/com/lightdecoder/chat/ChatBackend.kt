package com.lightdecoder.chat

import android.content.Context
import android.net.Uri
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.ContactsResult
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.wa.WaManager
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What the conversation screens need from one messaging service.
 *
 * One per service and process ([ChatBackends.get]). The screens read through
 * [repo] and act through the functions here, and never ask which service
 * they are showing except through [service] and [capabilities].
 *
 * Unless a function says otherwise it behaves as
 * [com.lightdecoder.relay.RelayManager]'s function of the same name:
 *  - the plain functions are cheap and safe on the main thread; sends are
 *    queued and survive restarts;
 *  - the suspend functions that act for the user throw
 *    [com.lightdecoder.relay.RelayException] when the phone or the service
 *    says no; [markRead], [restoreDeletedLocally], [purgeDeleted] and
 *    [deleteLocalArchive] do not throw, and [mediaFile] only throws
 *    not_connected;
 *  - a function whose capability the service lacks ([requestFullSize],
 *    [markNotSpam], [useHere], a group in [startConversation]) throws
 *    RelayException, and the screens do not offer it.
 */
interface ChatBackend {
    val service: ChatService
    val capabilities: Set<Capability>

    /** This service's own archive; never another service's. */
    val repo: ArchiveRepository

    val state: StateFlow<RelayState>

    /** Conversation id → names of the people typing there now. */
    val typing: StateFlow<Map<String, Set<String>>>

    /** Participant id → presence, for the people asked about with [watchPresence]. Always empty without [Capability.PRESENCE]. */
    val presence: StateFlow<Map<String, Presence>>

    val backfill: StateFlow<BackfillProgress>

    /** Whether a pairing is stored. Cheap; safe on the main thread. */
    fun isPaired(): Boolean

    /** Queues a text message and returns its tmpId at once; it shows in [repo] as "tmp:<tmpId>" right away. */
    fun sendText(conversationId: String, text: String, replyToId: String? = null): String

    /** Like [sendText] for an attachment (at most 100 MB), copied into app storage first. */
    fun sendMedia(conversationId: String, uri: Uri, caption: String? = null, replyToId: String? = null): String

    /** Sends a failed message again. */
    fun retry(tmpId: String)

    /** Drops a failed (or still queued) message and its local copy. */
    fun discardFailed(tmpId: String)

    /** Tells the other side the user is typing (throttled; best effort). */
    fun typing(conversationId: String)

    /**
     * Asks for the presence of the other person in a one-to-one conversation;
     * it then shows up in [presence]. Does nothing for groups, or without
     * [Capability.PRESENCE].
     */
    fun watchPresence(conversationId: String)

    /**
     * Whether one of this service's conversation screens is in front. WhatsApp
     * shows the user as online meanwhile (and only then sends other people's
     * presence); nothing happens for Messages.
     */
    fun setAppVisible(visible: Boolean)

    /** Adds, switches or (with [remove]) removes the user's reaction on a message. */
    suspend fun react(messageId: String, emoji: String, remove: Boolean)

    /** Marks a conversation read here at once and on the phone when connected; clears its notification. Does not throw. */
    suspend fun markRead(conversationId: String)

    /** Fetches a page of older messages; returns how many arrived, 0 when there are none. */
    suspend fun loadOlder(conversationId: String, count: Int = 50): Int

    /**
     * Deletes a message on the phone and here; one still being sent is just
     * dropped. With [Capability.DELETE_FOR_EVERYONE] this is "delete for me";
     * the service's own manager has the other choice.
     */
    suspend fun deleteMessage(messageId: String)

    /** Deletes a conversation on the phone and here, with its queued sends. */
    suspend fun deleteConversation(conversationId: String)

    suspend fun setArchived(conversationId: String, archived: Boolean)

    suspend fun setMuted(conversationId: String, muted: Boolean)

    /** Re-lists one folder into [repo]; returns how many conversations came back (0 for a folder the service does not have). */
    suspend fun refreshConversations(folder: Folder = Folder.INBOX): Int

    /** The file of one media part (or its thumbnail), downloaded first if needed; null if it cannot be had now. */
    suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean = false): File?

    /** Asks the phone for the original of a part that only has a thumbnail ([Capability.FULL_SIZE_REQUEST]). */
    suspend fun requestFullSize(messageId: String, partId: String)

    /** The contacts stored in [repo]; with [refresh], fetched from the service first. */
    suspend fun contacts(refresh: Boolean): ContactsResult

    /** Opens (or creates) the conversation with these numbers and returns its id. More than one number needs [Capability.NEW_GROUP]. */
    suspend fun startConversation(numbers: List<String>, groupName: String?): String

    /** Moves a conversation out of spam ([Capability.SPAM_FOLDER]). */
    suspend fun markNotSpam(conversationId: String)

    /** Moves a conversation out of the local deleted folder, here only. False if it was not there. Does not throw. */
    suspend fun restoreDeletedLocally(conversationId: String): Boolean

    /** Deletes for good, here only, one conversation of the local deleted folder, or with null all of them. Does not throw. */
    suspend fun purgeDeleted(conversationId: String?): Int

    /** Deletes this service's local archive while nothing is paired; false (deleting nothing) while something is. Does not throw. */
    suspend fun deleteLocalArchive(): Boolean

    /** Takes the session back from another device ([Capability.USE_HERE]). */
    suspend fun useHere()
}

/** The app's [ChatBackend]s: one per [ChatService], each a process-wide singleton. */
object ChatBackends {

    /** Cheap, and safe on the main thread: neither manager touches the network or loads the Go library here. */
    fun get(context: Context, service: ChatService): ChatBackend = when (service) {
        ChatService.MESSAGES -> RelayManager.get(context)
        ChatService.WHATSAPP -> WaManager.get(context)
        ChatService.GMAIL -> com.lightdecoder.mail.MailManager.get(context)
        ChatService.IMESSAGE -> com.lightdecoder.imessage.IMessageManager.get(context)
    }

    /** Every service's backend, in the order of [ChatService]. */
    fun all(context: Context): List<ChatBackend> = ChatService.entries.map { get(context, it) }
}
