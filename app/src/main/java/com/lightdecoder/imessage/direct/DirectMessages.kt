package com.lightdecoder.imessage.direct

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject
import uniffi.rust_bridge.CertifiedContext
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.PushChatMessage

/**
 * The ":imessage" process's rules for what goes to Apple, as OpenBubbles 2.0.0 follows them.
 * Pure (the library's records are plain Kotlin), so tested without the library: DirectMessagesTest.
 */
object DirectMessages {

    /** What to tell the sender once an incoming message is stored (OpenBubbles' `markCertified`). */
    sealed class DeliveryAck {
        /** Apple's certified delivery: [notify] also shows the sender "Delivered". */
        data class Certify(val context: CertifiedContext, val notify: Boolean) : DeliveryAck()

        /** A "Delivered" receipt to the sender, in [conversation], from the user's address [from]. */
        data class Delivered(val conversation: ConversationData, val from: String) : DeliveryAck()

        object None : DeliveryAck()
    }

    /**
     * The delivery receipt for [inst], which arrived for the user whose addresses are [me]. The
     * sender asked for one ([MessageInst.sendDelivered]; never for a forwarded text message); a
     * certified message is certified either way, and nothing goes to the user's own devices.
     */
    fun deliveryAck(inst: MessageInst, me: Set<String>): DeliveryAck {
        val sms = (inst.message as? PushChatMessage.Message)?.v1?.service is MessageType.Sms
        val notify = inst.sendDelivered && !sms
        inst.certifiedContext?.let { return DeliveryAck.Certify(it, notify) }
        val sender = inst.sender ?: return DeliveryAck.None
        if (!notify || sender in me) return DeliveryAck.None
        val from = sendingHandle(inst.conversation, me) ?: return DeliveryAck.None
        val c = inst.conversation
        return DeliveryAck.Delivered(ConversationData(listOf(sender), c?.cvName, c?.senderGuid, null), from)
    }

    /** The user's address in [conversation], else the one the user sends from. */
    fun sendingHandle(conversation: ConversationData?, me: Set<String>): String? =
        conversation?.participants?.firstOrNull { it in me } ?: Handles.preferredSender(me.toList())

    /**
     * The other person and the chat guid of a one-to-one iMessage (from them, or from the user's
     * other device), to keep sending in that chat; null for anything else.
     */
    fun oneToOneChat(inst: MessageInst, me: Set<String>): Pair<String, String>? {
        val n = (inst.message as? PushChatMessage.Message)?.v1 ?: return null
        if (n.service is MessageType.Sms) return null
        val c = inst.conversation ?: return null
        val guid = c.senderGuid?.takeIf { it.isNotBlank() } ?: return null
        val others = (c.participants + listOfNotNull(inst.sender)).filter { it !in me }.distinct()
        return others.singleOrNull()?.let { it to guid }
    }

    /**
     * Who a read receipt for [target] goes to besides the user's own devices: the other person,
     * only in a one-to-one chat and only with read receipts on, as on an iPhone.
     */
    fun readReceiptParticipants(target: DirectWire.Target, sendReadReceipts: Boolean): List<String> =
        if (sendReadReceipts && target.isOneToOne) target.participants else emptyList()

    /**
     * The library's conversation for [target], sent from [from]: its members with the user, the
     * group's name and guid ([chatGuid] for a one-to-one chat), and the latest message's GUID.
     */
    fun conversation(target: DirectWire.Target, from: String, chatGuid: String?): ConversationData =
        ConversationData(
            (target.participants + from).distinct(),
            target.groupName,
            target.groupGuid ?: chatGuid.takeIf { target.isOneToOne },
            target.afterGuid,
        )
}

/**
 * The chat guid of each one-to-one iMessage chat, by the other person's address: the first one
 * seen on a message in it, else one made up on the first send. iPhones keep a chat together by
 * it; without it every message would start a new one. Kept in [file] by the ":imessage" process.
 */
class ChatGuids(private val file: File) {

    private var loaded: MutableMap<String, String>? = null

    /** Keeps [guid] for [peer] unless the chat already has one. */
    @Synchronized
    fun remember(peer: String, guid: String) {
        val map = map()
        if (map.putIfAbsent(key(peer), guid) == null) save(map)
    }

    /** The chat guid for [peer], made up (an upper-case UUID, as Apple's) and kept if there is none. */
    @Synchronized
    fun guidFor(peer: String): String {
        val map = map()
        map[key(peer)]?.let { return it }
        val guid = UUID.randomUUID().toString().uppercase()
        map[key(peer)] = guid
        save(map)
        return guid
    }

    private fun key(peer: String) = if (peer.startsWith("mailto:")) peer.lowercase() else peer

    private fun map(): MutableMap<String, String> = loaded ?: read().also { loaded = it }

    private fun read(): MutableMap<String, String> {
        if (!file.isFile) return HashMap()
        return try {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associateWithTo(HashMap()) { o.getString(it) }
        } catch (e: IOException) {
            Log.w(TAG, "chat guids unreadable", e)
            HashMap()
        } catch (e: JSONException) {
            Log.w(TAG, "chat guids unreadable", e)
            HashMap()
        }
    }

    private fun save(map: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject(map).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(JSONObject(map).toString())
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "chat guids not saved", e)
        }
    }

    private companion object {
        const val TAG = "ChatGuids"
    }
}
