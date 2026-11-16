package com.lightdecoder.wa

import android.content.Context
import android.net.Uri
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.ContactsResult
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The WhatsApp link at runtime: one per process ([get]). To the conversation
 * screens it is the [ChatBackend] of [ChatService.WHATSAPP]; to the WhatsApp
 * setup and status screens it is also the pairing, connect and unpair API
 * below.
 *
 * NOT IMPLEMENTED YET. This is the agreed public API with placeholder
 * bodies, so that the screens and the runtime can be written against it:
 * WhatsApp is never linked ([isPaired] is false, [state] stays UNPAIRED),
 * every flow keeps its initial value, and whatever would need the link
 * throws [RelayException] with code [RelayException.NOT_PAIRED]. Nothing
 * here touches the network or loads the Go library. The runtime that
 * replaces these bodies keeps every signature.
 *
 * The rules the real thing follows, as the Google Messages link does:
 *  - Linking is never automated. [requestPairingCode] and [startQr] only
 *    ask WhatsApp for a code; the user types it into WhatsApp on their own
 *    phone, or scans it, themselves.
 *  - While connected, [WaService] shows a notification with Disconnect, and
 *    the link does not run without it. [stopLive] and [unpair] always work.
 *  - Everything stays on this phone: the bridge's store in [storeDir], the
 *    archive in [repo], media in the archive's own directories. All of it is
 *    excluded from backup.
 *  - [WaLog] and anything else that leaves the app's storage never get
 *    message text, names, numbers, JIDs, message ids, pairing or QR codes,
 *    or keys.
 */
class WaManager private constructor(
    context: Context,
    override val repo: ArchiveRepository,
) : ChatBackend {

    /** The application's context, never a screen's (the manager outlives every screen). */
    private val appContext: Context = context.applicationContext

    override val service: ChatService get() = ChatService.WHATSAPP

    /**
     * Presence and "delete for everyone". No spam or deleted folder, no
     * full-size requests, no "use here", no subjects; and no new groups,
     * which the bridge does not create.
     */
    override val capabilities: Set<Capability> = setOf(Capability.PRESENCE, Capability.DELETE_FOR_EVERYONE)

    private val _state = MutableStateFlow(RelayState(RelayState.Phase.UNPAIRED))
    override val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())

    /** Participant id (a user's JID) → presence, for the people [watchPresence] asked about. */
    override val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    private val _backfill = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))

    /** The phone's history sync after linking, as far as it has got. */
    override val backfill: StateFlow<BackfillProgress> = _backfill.asStateFlow()

    // ============================== pairing, for the setup screen ==============================

    private val _pairing = MutableStateFlow<WaPairing>(WaPairing.Idle)

    /**
     * Where linking stands. Idle until [requestPairingCode] or [startQr];
     * then WaitingForCode / WaitingForQr while the user confirms on their
     * phone; then Paired or Failed. [cancelPairing] takes it back to Idle.
     */
    val pairing: StateFlow<WaPairing> = _pairing.asStateFlow()

    private val _qr = MutableStateFlow<String?>(null)

    /**
     * The text of the QR code to show now, after [startQr]; null when there
     * is none (not asked for, linked, failed or cancelled). WhatsApp replaces
     * it every few seconds. Never log it.
     */
    val qr: StateFlow<String?> = _qr.asStateFlow()

    private val _unpairing = MutableStateFlow(false)

    /** True while an [unpair] is running; the status screen keeps Unpair and Disconnect disabled meanwhile. */
    val unpairing: StateFlow<Boolean> = _unpairing.asStateFlow()

    /** Whether this device is linked to a WhatsApp account. Cheap; safe on the main thread. */
    override fun isPaired(): Boolean = false

    /**
     * Asks WhatsApp for a pairing code for the user's own [phoneNumber]
     * (international format; "+", spaces and dashes are ignored) and returns
     * its 8 characters; [pairing] becomes WaitingForCode with the same code.
     * The user types it into WhatsApp on that phone themselves (Settings >
     * Linked devices > Link a device > Link with phone number instead);
     * [pairing] then becomes Paired, or Failed. Throws [RelayException]
     * (invalid_argument, rate_limited, timeout, already_paired, ...).
     * The code is a secret while it is valid: show it, never log it.
     */
    @Suppress("UNUSED_PARAMETER") // placeholder body
    suspend fun requestPairingCode(phoneNumber: String): String = notLinked()

    /**
     * Starts linking by QR code instead: [qr] (and [pairing], as
     * WaitingForQr) then carry each code WhatsApp hands out, until the user
     * scans one with their phone, the codes run out, or [cancelPairing].
     * Throws [RelayException] when linking cannot start.
     */
    suspend fun startQr(): Unit = notLinked()

    /** Abandons the linking in progress: [pairing] goes back to Idle and [qr] to null. Nothing happens when none runs. */
    fun cancelPairing() = Unit

    /**
     * Unlinks this device: tells WhatsApp (best effort), disconnects and
     * wipes the bridge's store. With [deleteArchive] the local archive and
     * its media go too. Does not throw. Ends in UNPAIRED; with code
     * [RelayState.UNPAIR_REMOTE_FAILED] when WhatsApp could not be told, so
     * the user can remove the device in WhatsApp on the phone themselves.
     * [unpairing] is true meanwhile.
     */
    @Suppress("UNUSED_PARAMETER") // placeholder body
    suspend fun unpair(deleteArchive: Boolean) = Unit

    /**
     * Turns on "stay connected" ([WaPrefs.liveEnabled]) and starts
     * [WaService], which connects. Without a link it does nothing (and
     * leaves "stay connected" off). Cheap and safe on the main thread, like
     * [stopLive].
     */
    fun startLive() = Unit

    /** Disconnects and stops the service, keeping the link. */
    fun stopLive() = Unit

    // ============================== ChatBackend ==============================

    override fun sendText(conversationId: String, text: String, replyToId: String?): String = notLinked()

    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String = notLinked()

    override fun retry(tmpId: String) = Unit

    override fun discardFailed(tmpId: String) = Unit

    override fun typing(conversationId: String) = Unit

    /** Subscribes to the presence of the other person in a one-to-one chat; it then shows up in [presence]. */
    override fun watchPresence(conversationId: String) = Unit

    /** Shows this account as online while a WhatsApp conversation screen is in front, and as away otherwise. */
    override fun setAppVisible(visible: Boolean) = Unit

    override suspend fun react(messageId: String, emoji: String, remove: Boolean): Unit = notLinked()

    override suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        try {
            repo.markConversationRead(conversationId)
        } catch (e: RuntimeException) {
            // markRead does not throw.
        }
        MessageNotifier.cancel(appContext, conversationId, ChatService.WHATSAPP)
    }

    override suspend fun loadOlder(conversationId: String, count: Int): Int = notLinked()

    /** "Delete for me": the same as [deleteMessage] with forEveryone false. */
    override suspend fun deleteMessage(messageId: String) = deleteMessage(messageId, forEveryone = false)

    /**
     * Deletes a message: with [forEveryone] for everyone in the chat (one's
     * own messages, or any as a group admin), otherwise only on this
     * account's devices. Either way it goes from the archive. What
     * [Capability.DELETE_FOR_EVERYONE] offers.
     */
    @Suppress("UNUSED_PARAMETER") // placeholder body
    suspend fun deleteMessage(messageId: String, forEveryone: Boolean): Unit = notLinked()

    override suspend fun deleteConversation(conversationId: String): Unit = notLinked()

    override suspend fun setArchived(conversationId: String, archived: Boolean): Unit = notLinked()

    override suspend fun setMuted(conversationId: String, muted: Boolean): Unit = notLinked()

    override suspend fun refreshConversations(folder: Folder): Int = notLinked()

    override suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean): File? = null

    /** WhatsApp has no full-size requests (see [capabilities]): always throws. */
    override suspend fun requestFullSize(messageId: String, partId: String): Unit =
        throw RelayException(WaErrors.UNSUPPORTED, "WhatsApp has no full-size requests")

    override suspend fun contacts(refresh: Boolean): ContactsResult = withContext(Dispatchers.IO) {
        val stored = repo.contacts()
        if (refresh && stored.isEmpty()) notLinked()
        ContactsResult(stored, fromPhone = !refresh)
    }

    override suspend fun startConversation(numbers: List<String>, groupName: String?): String = notLinked()

    /** WhatsApp has no spam folder (see [capabilities]): always throws. */
    override suspend fun markNotSpam(conversationId: String): Unit =
        throw RelayException(WaErrors.UNSUPPORTED, "WhatsApp has no spam folder")

    /**
     * WhatsApp keeps no local deleted folder (see [capabilities]): a chat
     * deleted on the phone is deleted here too, since no screen would show
     * what was kept. So there is never anything to restore: always false.
     */
    override suspend fun restoreDeletedLocally(conversationId: String): Boolean = false

    /** As [restoreDeletedLocally]: nothing is ever kept in a deleted folder, so always 0. */
    override suspend fun purgeDeleted(conversationId: String?): Int = 0

    override suspend fun deleteLocalArchive(): Boolean {
        if (isPaired()) return false
        return withContext(Dispatchers.IO) {
            try {
                repo.clearAll()
                true
            } catch (e: RuntimeException) {
                false
            }
        }
    }

    /** WhatsApp has no "use here" (see [capabilities]): always throws. */
    override suspend fun useHere(): Unit = throw RelayException(WaErrors.UNSUPPORTED, "WhatsApp has no use here")

    /** What every call that needs the link answers until the runtime exists (and afterwards, while nothing is linked). */
    private fun notLinked(): Nothing = throw RelayException(RelayException.NOT_PAIRED, "WhatsApp is not linked")

    internal fun shutdownForTesting() = Unit

    companion object {
        /** The name this device has in WhatsApp's "Linked devices" list on the phone. */
        const val DEVICE_NAME = "LightDecoder"

        private const val STORE_DIR = "whatsapp"

        /**
         * The app-private directory the bridge keeps its store in (keys,
         * chat index, message cache): filesDir/whatsapp. Excluded from
         * backup like everything else.
         */
        fun storeDir(context: Context): File = File(context.applicationContext.filesDir, STORE_DIR)

        @Volatile
        private var instance: WaManager? = null

        /** The process's WhatsApp manager, over [ArchiveRepository.get] for [ChatService.WHATSAPP]. Cheap; safe on the main thread. */
        fun get(context: Context): WaManager =
            instance ?: synchronized(this) {
                instance ?: WaManager(context, ArchiveRepository.get(context, ChatService.WHATSAPP)).also { instance = it }
            }

        /**
         * Replaces the process instance with one over [repo] (tests only);
         * the old one is shut down. [repo] must be a WhatsApp archive
         * ([ArchiveRepository.forTesting] with [ChatService.WHATSAPP]).
         */
        internal fun installForTesting(context: Context, repo: ArchiveRepository): WaManager {
            require(repo.service == ChatService.WHATSAPP) { "the repository is ${repo.service}'s, not WhatsApp's" }
            return synchronized(this) {
                instance?.shutdownForTesting()
                WaManager(context, repo).also { instance = it }
            }
        }

        internal fun resetForTesting() = synchronized(this) {
            instance?.shutdownForTesting()
            instance = null
        }
    }
}
