package com.lightdecoder.relay

import com.lightdecoder.gm.gmbridge.Callback
import com.lightdecoder.gm.gmbridge.GMBridge
import com.lightdecoder.gm.gmbridge.Gmbridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RelayBridge] backed by the Go bridge (gmbridge, libgm compiled with
 * gomobile). This is the only class that touches the generated
 * com.lightdecoder.gm code; everything else sees plain Kotlin and
 * [RelayException].
 *
 * Constructing it loads the Go library, so do it off the main thread. Every
 * call blocks (some up to a minute) and must also run off the main thread.
 *
 * The [Callback] is called from Go's single dispatcher goroutine. Its bodies
 * catch everything: an exception that escaped into Go would be turned into a
 * Go panic there, and a crash in the dispatcher would stop all later events.
 */
class GoRelayBridge(session: ByteArray?, private val sink: RelayBridge.Sink) : RelayBridge {

    private val callback = object : Callback {
        override fun onSession(data: ByteArray?) {
            try {
                sink.onSession(data?.takeIf { it.isNotEmpty() })
            } catch (t: Throwable) {
                // Never into Go.
            }
        }

        override fun onEvent(kind: String?, payload: ByteArray?) {
            try {
                if (kind.isNullOrEmpty()) return
                sink.onEvent(kind, payload?.toString(Charsets.UTF_8) ?: "{}")
            } catch (t: Throwable) {
                // Never into Go.
            }
        }
    }

    private val go: GMBridge = call { Gmbridge.newGMBridge(session?.takeIf { it.isNotEmpty() }, callback) }

    override val isPaired: Boolean get() = go.isPaired
    override val isConnected: Boolean get() = go.isConnected

    override fun status(): RelayStatus = call { RelayStatus.fromJson(JSONObject(go.statusJSON())) }

    override fun startPairing(cookies: Map<String, String>): PairingChallenge = call {
        val json = JSONObject(cookies as Map<*, *>).toString().toByteArray()
        PairingChallenge.fromJson(JSONObject(go.startPairing(json)))
    }

    override fun finishPairing(timeoutMs: Long): String = call { go.finishPairing(timeoutMs) }

    override fun cancelPairing() = call { go.cancelPairing() }

    override fun connect() = call { go.connect() }

    override fun disconnect() = call { go.disconnect() }

    override fun setActiveSession() = call { go.setActiveSession() }

    override fun unpair() = call { go.unpair() }

    override fun exportSession(): ByteArray? = call { go.exportSession()?.takeIf { it.isNotEmpty() } }

    override fun listConversations(folder: Folder, count: Int): List<ConversationDto> = call {
        ConversationDto.listFromJson(array(go.listConversations(folder.wire, count.toLong())))
    }

    override fun getConversation(id: String): ConversationDto = call {
        ConversationDto.fromJson(obj(go.getConversation(id)))
    }

    override fun fetchMessages(conversationId: String, count: Int, cursor: Cursor?): MessagePage = call {
        val cursorJson = cursor?.toJson()?.toString()?.toByteArray()
        MessagePage.fromJson(obj(go.fetchMessages(conversationId, count.toLong(), cursorJson)))
    }

    override fun listContacts(): List<ContactDto> = call { ContactDto.listFromJson(array(go.listContacts())) }

    override fun participantThumbnails(ids: List<String>): List<ThumbnailDto> = call {
        ThumbnailDto.listFromJson(array(go.participantThumbnails(JSONArray(ids).toString().toByteArray())))
    }

    override fun downloadMedia(mediaId: String, key: ByteArray): ByteArray = call {
        go.downloadMedia(mediaId, key) ?: throw RelayException(RelayException.NOT_FOUND, "media is empty")
    }

    override fun requestFullSizeImage(messageId: String, partId: String) = call {
        go.requestFullSizeImage(messageId, partId)
    }

    override fun sendText(conversationId: String, tmpId: String, text: String, replyToId: String?): SendResult = call {
        SendResult.fromJson(obj(go.sendText(conversationId, tmpId, text, replyToId.orEmpty())))
    }

    override fun sendMedia(
        conversationId: String,
        tmpId: String,
        data: ByteArray,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): SendResult = call {
        SendResult.fromJson(
            obj(go.sendMedia(conversationId, tmpId, data, fileName, mime, caption.orEmpty(), replyToId.orEmpty()))
        )
    }

    override fun sendReaction(messageId: String, emoji: String, action: String) =
        call { go.sendReaction(messageId, emoji, action) }

    override fun markRead(conversationId: String, messageId: String) = call { go.markRead(conversationId, messageId) }

    override fun setTyping(conversationId: String) = call { go.setTyping(conversationId) }

    override fun deleteMessage(messageId: String) = call { go.deleteMessage(messageId) }

    override fun setConversationStatus(conversationId: String, status: String) =
        call { go.setConversationStatus(conversationId, status) }

    override fun setConversationMuted(conversationId: String, muted: Boolean) =
        call { go.setConversationMuted(conversationId, muted) }

    override fun deleteConversation(conversationId: String) = call { go.deleteConversation(conversationId) }

    override fun getOrCreateConversation(numbers: List<String>, rcsGroupName: String?): ConversationDto = call {
        ConversationDto.fromJson(obj(go.getOrCreateConversation(JSONArray(numbers).toString().toByteArray(), rcsGroupName.orEmpty())))
    }

    override fun close() = call { go.close() }

    /**
     * Runs one call into Go. Go throws "<code>: <detail>" exceptions; JSON
     * that does not parse becomes "internal". Errors (out of memory, a
     * missing native library) are not caught here: they are not the bridge
     * reporting a failure.
     */
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw RelayException.fromThrowable(e)
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
