package com.lightdecoder.wa

import com.lightdecoder.gm.wa.Callback
import com.lightdecoder.gm.wa.WABridge
import com.lightdecoder.gm.wa.Wa
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Cursor
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessagePage
import com.lightdecoder.relay.RelayException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [WaBridge] backed by the Go bridge (gmbridge/wa, whatsmeow compiled with
 * gomobile into the same library as the Google Messages bridge). This is
 * the only class that touches the generated com.lightdecoder.gm.wa code;
 * everything else sees plain Kotlin and [RelayException].
 *
 * Constructing it loads the Go library and opens the bridge's store in
 * [dataDir] (it never touches the network), so do it off the main thread.
 * Every call blocks, some for over a minute, and must also run off the main
 * thread.
 *
 * The [Callback] is called from Go's single dispatcher goroutine. Its body
 * catches everything: an exception that escaped into Go would come back
 * there as an error for every event, and a crash would stop all later ones.
 */
class GoWaBridge(dataDir: File, private val sink: WaBridge.Sink) : WaBridge {

    private val callback = Callback { kind, payload ->
        try {
            if (!kind.isNullOrEmpty()) sink.onEvent(kind, payload?.toString(Charsets.UTF_8) ?: "{}")
        } catch (t: Throwable) {
            // Never into Go.
        }
    }

    private val go: WABridge = call { Wa.newWABridge(dataDir.absolutePath, callback) }

    override val isPaired: Boolean get() = go.isPaired

    override fun status(): WaStatus = call { WaStatus.fromJson(JSONObject(go.statusJSON())) }

    override fun connect() = call { go.connect() }

    override fun disconnect() = call { go.disconnect() }

    override fun pairPhone(phoneNumber: String): String = call { go.pairPhone(phoneNumber) }

    override fun startQr() = call { go.startQR() }

    override fun cancelPairing() = call { go.cancelPairing() }

    override fun logout() = call { go.logout() }

    override fun listConversations(folder: Folder, count: Int): List<ConversationDto> = call {
        // The local deleted folder is never asked of the bridge.
        if (!folder.listable) throw RelayException(RelayException.INVALID_ARGUMENT, "folder is local only")
        ConversationDto.listFromJson(array(go.listConversations(folder.wire, count.toLong())))
    }

    override fun listContacts(): List<ContactDto> = call { ContactDto.listFromJson(array(go.listContacts())) }

    override fun fetchMessages(conversationId: String, count: Int, cursor: Cursor?): MessagePage = call {
        val cursorJson = cursor?.toJson()?.toString()?.toByteArray()
        MessagePage.fromJson(obj(go.fetchMessages(conversationId, count.toLong(), cursorJson)))
    }

    override fun sendText(conversationId: String, tmpId: String, text: String, replyToId: String?): MessageDto = call {
        MessageDto.fromJson(obj(go.sendText(conversationId, tmpId, text, replyToId.orEmpty())))
    }

    override fun sendMediaFile(
        conversationId: String,
        tmpId: String,
        path: String,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): MessageDto = call {
        MessageDto.fromJson(
            obj(go.sendMediaFile(conversationId, tmpId, path, fileName, mime, caption.orEmpty(), replyToId.orEmpty()))
        )
    }

    override fun sendReaction(conversationId: String, messageId: String, senderId: String, emoji: String) =
        call { go.sendReaction(conversationId, messageId, senderId, emoji) }

    override fun markRead(conversationId: String, messageId: String, senderId: String) =
        call { go.markRead(conversationId, messageId, senderId) }

    override fun setTyping(conversationId: String, typing: Boolean) = call { go.setTyping(conversationId, typing) }

    override fun subscribePresence(participantId: String) = call { go.subscribePresence(participantId) }

    override fun setOnline(online: Boolean) = call { go.setOnline(online) }

    override fun editText(conversationId: String, messageId: String, text: String) =
        call { go.editText(conversationId, messageId, text) }

    override fun deleteMessage(conversationId: String, messageId: String, forEveryone: Boolean) =
        call { go.deleteMessage(conversationId, messageId, forEveryone) }

    override fun setConversationArchived(conversationId: String, archived: Boolean) =
        call { go.setConversationArchived(conversationId, archived) }

    override fun setConversationMuted(conversationId: String, muted: Boolean) =
        call { go.setConversationMuted(conversationId, muted) }

    override fun downloadMedia(mediaId: String, key: ByteArray): ByteArray = call {
        go.downloadMedia(mediaId, key) ?: throw RelayException(RelayException.NOT_FOUND, "media is empty")
    }

    override fun getOrCreateConversation(numbers: List<String>, groupName: String?): ConversationDto = call {
        ConversationDto.fromJson(obj(go.getOrCreateConversation(JSONArray(numbers).toString().toByteArray(), groupName.orEmpty())))
    }

    override fun close() = call { go.close() }

    /**
     * Runs one call into Go. Go throws "<code>: <detail>" exceptions with
     * the WhatsApp bridge's codes ([WaErrors]); JSON that does not parse
     * becomes "internal", without its text. Errors (out of memory, a missing
     * native library) are not caught here: they are not the bridge reporting
     * a failure.
     */
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw WaErrors.fromThrowable(e)
    }

    private fun obj(bytes: ByteArray?): JSONObject {
        if (bytes == null || bytes.isEmpty()) throw RelayException(RelayException.INTERNAL, "empty response")
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun array(bytes: ByteArray?): JSONArray {
        if (bytes == null || bytes.isEmpty()) return JSONArray()
        return JSONArray(bytes.toString(Charsets.UTF_8))
    }
}
