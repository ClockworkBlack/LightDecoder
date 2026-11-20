package com.lightdecoder.imessage.direct

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.Balloon
import uniffi.rust_bridge.BalloonLayout
import uniffi.rust_bridge.CertifiedContext
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.ExtensionApp
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NsDictionaryClass
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType

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
        if (sendReadReceipts) listOfNotNull(target.peer) else emptyList()

    /**
     * A group's members after a change the main process asked for ([DirectWire.MembersRequest]):
     * [participants], the user among them by [targetFrom] (the address the chat is sent from) or any
     * of the user's addresses [mine], which is then the one actually sent from ([from]), last; with
     * none of the user's: the user leaves it. Each once, the others in the order given.
     */
    fun newParticipants(participants: List<String>, targetFrom: String, from: String, mine: Set<String>): List<String> {
        val own = mine + from + listOfNotNull(targetFrom.takeIf { it.isNotBlank() })
        val stays = participants.any { it in own }
        val others = participants.filter { it.isNotBlank() && it !in own }.distinct()
        return if (stays) others + from else others
    }

    /** A message's parts as they go to Apple, and its attachments as the archive numbers them. */
    class Outgoing(val parts: List<IndexedMessagePart>, val attachments: List<DirectWire.SentPart>)

    /**
     * The parts of a message with [text] and the uploaded [attachments], as OpenBubbles builds one:
     * the text first (none when it is empty), then the attachments. Numbered as rustpush numbers
     * them ([DirectArchive.partIndexes]): with text, it is part 0 and the attachments 1 to n. With
     * [runs] (bold, a text effect, ...), the text is one `Text` part per stretch of one style
     * ([TextRuns.toParts]), all of them part 0, as an iPhone sends formatted text.
     */
    fun outgoing(text: String, attachments: List<MessageAttachment>, runs: List<TextRun> = emptyList()): Outgoing {
        val parts = TextRuns.toParts(text, runs).map { IndexedMessagePart(it, null, null) } +
            attachments.map { IndexedMessagePart(MessagePart.Attachment(it), null, null) }
        val indexes = DirectArchive.partIndexes(parts)
        val sent = parts.indices.mapNotNull { i ->
            val att = (parts[i].part as? MessagePart.Attachment)?.v1 ?: return@mapNotNull null
            val size = (att.aType as? AttachmentType.Mmcs)?.v1?.maxOfOrNull { it.size }?.toLong()
                ?: (att.aType as? AttachmentType.Inline)?.v1?.size?.toLong() ?: 0L
            DirectWire.SentPart(indexes[i], DirectArchive.partId(indexes[i]), att.mime, att.name, size)
        }
        return Outgoing(parts, sent)
    }

    /** The new parts of an edited part reading [text] with [runs] (plain: one `Text` part). */
    fun editParts(text: String, runs: List<TextRun> = emptyList()): List<IndexedMessagePart> =
        TextRuns.toParts(text, runs).ifEmpty { listOf(MessagePart.Text(text, TextRuns.format(TextStyle.PLAIN))) }
            .map { IndexedMessagePart(it, null, null) }

    /**
     * The parts of an iMessage app's card or move, as OpenBubbles builds them: the app's summary
     * ([IMessageExtra.App.ldText], what a device without the app shows) as the placeholder part,
     * then the picture (uploaded with MMCS: one inside the message would carry no data in a move).
     * A new card leaves the placeholder out when it has no summary; a move always has one.
     */
    fun appParts(app: IMessageExtra.App, attachments: List<MessageAttachment>, move: Boolean): Outgoing {
        val parts = listOfNotNull(
            app.ldText.takeIf { move || it.isNotEmpty() }?.let { IndexedMessagePart(MessagePart.Object(it), null, null) },
        ) + attachments.map { IndexedMessagePart(MessagePart.Attachment(it), null, null) }
        val indexes = DirectArchive.partIndexes(parts)
        val sent = parts.indices.mapNotNull { i ->
            val att = (parts[i].part as? MessagePart.Attachment)?.v1 ?: return@mapNotNull null
            val size = (att.aType as? AttachmentType.Mmcs)?.v1?.maxOfOrNull { it.size }?.toLong() ?: 0L
            DirectWire.SentPart(indexes[i], DirectArchive.partId(indexes[i]), att.mime, att.name, size)
        }
        return Outgoing(parts, sent)
    }

    /**
     * [app]'s balloon as the library sends it (OpenBubbles' `toExtensionApp`): its name, App Store
     * id and bundle, its address and session (none when empty: the library takes only a UUID), its
     * summary and captions (as Apple's template layout), and whether it is live. [icon] is the
     * app's icon as a JPEG, if any.
     */
    fun extensionApp(app: IMessageExtra.App, icon: ByteArray? = null): ExtensionApp =
        ExtensionApp(
            app.name,
            app.appId?.toULong(),
            app.bundleId,
            Balloon(
                app.url,
                app.session.ifEmpty { null },
                BalloonLayout.TemplateLayout(
                    app.imageSubtitle, app.imageTitle, app.caption,
                    app.secondarySubcaption, app.tertiarySubcaption, app.subcaption, NsDictionaryClass.NS_DICTIONARY,
                ),
                app.ldText.ifEmpty { null },
                app.isLive,
                icon,
            ),
        )

    /**
     * An iMessage app's update (a game's move) to the session message [rootGuid] started: a
     * tapback-like message to it, carrying [app]'s new balloon and the [parts] ([appParts]).
     */
    fun appUpdate(rootGuid: String, app: IMessageExtra.App, parts: List<IndexedMessagePart>, isMeta: Boolean): ReactMessage =
        ReactMessage(rootGuid, null, ReactMessageType.Extension(extensionApp(app), parts, isMeta), "", null)

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
