package com.lightdecoder.imessage.direct

import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import org.json.JSONArray
import org.json.JSONObject

/**
 * What crosses between the ":imessage" process (OpenBubbles' library, [DirectService]) and the
 * app's main process (the Messages archive, [DirectLink]), as JSON in a Bundle. Pure and
 * tested (DirectWireTest), so both sides always agree.
 *
 * Main -> :imessage: [SEND], [UNSEND], [REACH], [EDIT], [REACT], [TYPING], [READ], [DOWNLOAD],
 * [EXTENSION] (requests, answered with the same request id; a failure is answered with
 * [error]). Never raw media bytes: files are passed by path, in the app's own storage.
 * :imessage -> main: [UPDATE] (an archive change from Apple), answered once it is stored; only
 * then does the library forget the event.
 */
object DirectWire {

    const val SEND = 1
    const val UNSEND = 2
    const val REACH = 3

    /** Edits a part of one of the user's messages: [edit], answered with [ok]. */
    const val EDIT = 4

    /** Adds or takes back a tapback: [react], answered with [guid] (the tapback's own). */
    const val REACT = 5

    /** Tells the chat the user is typing, or stopped: [typing], answered with [ok] at once. */
    const val TYPING = 6

    /** Says a message was read: [read], answered with [ok] at once. */
    const val READ = 7

    /** Fetches an attachment into a file: [download], answered with [downloaded]. */
    const val DOWNLOAD = 8

    /**
     * Sends an iMessage app's session update (a game's move) to the card that started the session:
     * [extension], answered as a [SEND] is ([SendReply]: the move's GUID, its time, its picture's part).
     */
    const val EXTENSION = 9

    const val UPDATE = 10
    const val REPLY = 100

    const val KEY_ID = "id"
    const val KEY_JSON = "json"

    /**
     * Where a message goes: the conversation's other members (the user's own address left out:
     * the library adds the one sent from), its group guid and name, the address to send from, and
     * the GUID of the conversation's latest message ([afterGuid], null when it has none). Built
     * from the archive by the main process.
     */
    data class Target(
        val participants: List<String>,
        val groupName: String?,
        val groupGuid: String?,
        val from: String,
        val afterGuid: String? = null,
    ) {
        /** The other person of a one-to-one conversation, else null. [from] is not counted, should it be listed. */
        val peer: String? get() = participants.filter { it != from }.distinct().singleOrNull()?.takeIf { groupGuid == null }

        /** A conversation with one other person. */
        val isOneToOne: Boolean get() = peer != null

        fun toJson(): JSONObject = JSONObject()
            .put("participants", JSONArray(participants))
            .put("groupName", groupName ?: JSONObject.NULL)
            .put("groupGuid", groupGuid ?: JSONObject.NULL)
            .put("from", from)
            .apply { if (afterGuid != null) put("afterGuid", afterGuid) }

        companion object {
            fun fromJson(o: JSONObject) = Target(
                o.getJSONArray("participants").let { a -> (0 until a.length()).map { a.getString(it) } },
                o.optStringOrNull("groupName"),
                o.optStringOrNull("groupGuid"),
                o.getString("from"),
                o.optStringOrNull("afterGuid"),
            )
        }
    }

    /** A file to send: a path the ":imessage" process can read, its type and its name. */
    data class Attachment(val path: String, val mime: String, val name: String) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("mime", mime).put("name", name)

        companion object {
            fun fromJson(o: JSONObject) = Attachment(o.getString("path"), o.optString("mime"), o.optString("name"))
        }
    }

    /** One attachment part of a sent message, as Apple numbers it: archive part id `p<idx>`. */
    data class SentPart(val idx: Int, val partId: String, val mime: String, val name: String, val size: Long) {
        fun toJson(): JSONObject =
            JSONObject().put("idx", idx).put("partId", partId).put("mime", mime).put("name", name).put("size", size)

        companion object {
            fun fromJson(o: JSONObject) =
                SentPart(o.getInt("idx"), o.getString("partId"), o.optString("mime"), o.optString("name"), o.optLong("size"))
        }
    }

    /** A send's answer: the message's GUID, Apple's timestamp and its attachment parts. */
    data class SendReply(val guid: String, val timestampMs: Long, val parts: List<SentPart>) {
        fun toJson(): JSONObject = sent(guid, timestampMs, parts)

        companion object {
            fun fromJson(o: JSONObject) = SendReply(o.getString("guid"), o.optLong("ts"), sentParts(o))
        }
    }

    /** What a [SEND] request asks for, read by the ":imessage" process. */
    data class SendRequest(
        val target: Target,
        val text: String,
        val replyGuid: String?,
        val replyPart: String?,
        val effect: String?,
        val attachments: List<Attachment>,
        val voice: Boolean,
        val app: IMessageExtra.App? = null,
    ) {
        companion object {
            fun fromJson(o: JSONObject) = SendRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.optString("text"),
                o.optStringOrNull("replyGuid")?.ifEmpty { null },
                o.optStringOrNull("replyPart")?.ifEmpty { null },
                o.optStringOrNull("effect")?.ifEmpty { null },
                o.objects("attachments").map(Attachment::fromJson),
                o.optBoolean("voice"),
                o.optJSONObject("app")?.let(IMessageExtra.App::fromJson),
            )
        }
    }

    /** What an [EXTENSION] request asks for, read by the ":imessage" process. */
    data class ExtensionRequest(
        val target: Target,
        val rootGuid: String,
        val isMeta: Boolean,
        val app: IMessageExtra.App,
        val imagePath: String?,
    ) {
        companion object {
            fun fromJson(o: JSONObject) = ExtensionRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.getString("rootGuid"),
                o.optBoolean("isMeta"),
                IMessageExtra.App.fromJson(o.getJSONObject("app")),
                o.optStringOrNull("imagePath")?.ifEmpty { null },
            )
        }
    }

    /**
     * A message to send: [text] (may be empty with attachments), then [attachments]. An inline
     * reply names the message it answers ([replyGuid]) and the part of it ([replyPart],
     * "<part>:<start>:<end>" of that message's text, as iMessage expects). [effect] is Apple's
     * effect id; [voice] marks a recorded audio message. [app] makes it an iMessage app's card
     * (a new game): the app's balloon, with its picture as the attachment.
     */
    fun send(
        target: Target,
        text: String,
        replyGuid: String?,
        replyPart: String? = null,
        effect: String? = null,
        attachments: List<Attachment> = emptyList(),
        voice: Boolean = false,
        app: IMessageExtra.App? = null,
    ): JSONObject =
        JSONObject().put("target", target.toJson()).put("text", text)
            .put("replyGuid", replyGuid ?: JSONObject.NULL).put("replyPart", replyPart ?: JSONObject.NULL)
            .put("effect", effect ?: JSONObject.NULL)
            .put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
            .put("voice", voice)
            .apply { if (app != null) put("app", app.toJson()) }

    /**
     * An iMessage app's update to the session that message [rootGuid] started: [app]'s new state
     * (a game's move), with the picture at [imagePath] (a JPEG in the app's own storage) if it has
     * a new one. [isMeta] marks an update that is no card of its own (a vote in a poll).
     */
    fun extension(target: Target, rootGuid: String, isMeta: Boolean, app: IMessageExtra.App, imagePath: String?): JSONObject =
        JSONObject().put("target", target.toJson()).put("rootGuid", rootGuid).put("isMeta", isMeta)
            .put("app", app.toJson()).put("imagePath", imagePath ?: JSONObject.NULL)

    /** iMessage's reply part for a reply to part 0 of a message whose text is [targetText]. */
    fun replyPart(targetText: String): String = "0:0:" + targetText.length

    fun unsend(target: Target, guid: String, part: Int): JSONObject =
        JSONObject().put("target", target.toJson()).put("guid", guid).put("part", part)

    fun reach(recipient: String): JSONObject = JSONObject().put("recipient", recipient)

    /** Part [part] of the user's message [guid] now reads [text]. */
    fun edit(target: Target, guid: String, part: Int, text: String): JSONObject =
        JSONObject().put("target", target.toJson()).put("guid", guid).put("part", part).put("text", text)

    /**
     * The user's tapback [emoji] on part [part] of message [guid], put on ([enable]) or taken
     * back. [targetText] is that message's text, for the notification on the other side.
     */
    fun react(target: Target, guid: String, part: Int, emoji: String, enable: Boolean, targetText: String): JSONObject =
        JSONObject().put("target", target.toJson()).put("guid", guid).put("part", part).put("emoji", emoji)
            .put("enable", enable).put("targetText", targetText)

    /** The user started ([typing]) or stopped typing in [target]. */
    fun typing(target: Target, typing: Boolean): JSONObject = JSONObject().put("target", target.toJson()).put("typing", typing)

    /** The user read the chat up to message [guid]. */
    fun read(target: Target, guid: String): JSONObject = JSONObject().put("target", target.toJson()).put("guid", guid)

    /** Fetch the attachment [descriptor] (as the library saved it) into the file [path]. */
    fun download(descriptor: String, path: String): JSONObject = JSONObject().put("descriptor", descriptor).put("path", path)

    /** A send's answer: the GUID, Apple's timestamp and the attachment parts, or an error. */
    fun sent(guid: String, timestampMs: Long, parts: List<SentPart> = emptyList()): JSONObject =
        JSONObject().put("guid", guid).put("ts", timestampMs).put("parts", JSONArray().apply { parts.forEach { put(it.toJson()) } })

    /** The attachment parts of a [sent] answer. */
    fun sentParts(o: JSONObject): List<SentPart> = o.objects("parts").map(SentPart::fromJson)

    /** An answer naming the GUID of what was sent. */
    fun guid(guid: String): JSONObject = JSONObject().put("guid", guid)

    /** A download's answer: the file's size. */
    fun downloaded(size: Long): JSONObject = ok().put("size", size)

    fun ok(): JSONObject = JSONObject().put("ok", true)

    fun error(message: String): JSONObject = JSONObject().put("error", message)

    /**
     * The answer to a request that failed with [e], in words; marked when Apple did not answer in
     * time ([DirectEngine.Unconfirmed]), since what was asked may have gone through all the same.
     */
    fun failure(e: Throwable): JSONObject =
        error(DirectEngine.readable(e)).apply { if (e is DirectEngine.Unconfirmed) put(KEY_UNCONFIRMED, true) }

    fun errorOf(o: JSONObject): String? = o.optStringOrNull("error")

    /**
     * Whether the failure [o] leaves it open whether the request went through: Apple did not
     * confirm it, or the other process stopped or did not answer while it was asked.
     */
    fun unconfirmed(o: JSONObject): Boolean =
        errorOf(o) != null && (o.optBoolean(KEY_UNCONFIRMED) || errorOf(o) == DirectIpc.Client.DIED || errorOf(o) == DirectIpc.Client.NO_ANSWER)

    private const val KEY_UNCONFIRMED = "unconfirmed"

    /**
     * The answer to an [UPDATE] the main process can never store, whatever the number of tries
     * (it cannot be read, or storing it fails): the ":imessage" process gives up on it, after a few
     * tries ([UpdateDelivery]), instead of holding every later one behind it.
     */
    fun rejected(e: Throwable): JSONObject = error(DirectEngine.readable(e)).put(KEY_REJECTED, true)

    /** Whether [o] says the [UPDATE] can never be stored: [rejected], or too large for the other process to be handed. */
    fun isRejected(o: JSONObject): Boolean =
        errorOf(o) != null && (o.optBoolean(KEY_REJECTED) || errorOf(o) == DirectIpc.Client.TOO_LARGE)

    private const val KEY_REJECTED = "rejected"

    fun encode(u: DirectArchive.Update): JSONObject = when (u) {
        is DirectArchive.Update.Upsert -> JSONObject().put("kind", "upsert")
            .put("conversation", u.conversation.toJson()).put("message", u.message.toJson())
            .apply { u.groupGuid?.let { put("groupGuid", it).put("groupName", u.groupName) } }.put("groupOnly", u.groupOnly).put("renamed", u.renamed)
            .apply { u.member?.let { put("member", it) } }
        is DirectArchive.Update.Remove -> JSONObject().put("kind", "remove").put("messageId", u.messageId)
        is DirectArchive.Update.Receipt -> JSONObject().put("kind", "receipt")
            .put("messageIds", JSONArray(u.messageIds)).put("status", u.status)
        is DirectArchive.Update.Edit -> JSONObject().put("kind", "edit").put("messageId", u.messageId).put("text", u.text)
            .put("part", u.part).put("atMs", u.atMs)
        is DirectArchive.Update.Reaction -> JSONObject().put("kind", "reaction").put("messageId", u.messageId)
            .put("participantId", u.participantId).put("emoji", u.emoji).put("enable", u.enable).put("fromMe", u.fromMe)
            .put("atMs", u.atMs)
        is DirectArchive.Update.Unsent -> JSONObject().put("kind", "unsent").put("messageId", u.messageId).put("part", u.part)
            .put("fromMe", u.fromMe).put("senderName", u.senderName)
        is DirectArchive.Update.Typing -> JSONObject().put("kind", "typing").put("conversationId", u.conversationId)
            .put("participantId", u.participantId).put("typing", u.typing).apply { u.groupGuid?.let { put("groupGuid", it) } }
        is DirectArchive.Update.ReadElsewhere -> JSONObject().put("kind", "readElsewhere").put("messageId", u.messageId)
        is DirectArchive.Update.Failed -> JSONObject().put("kind", "failed").put("messageId", u.messageId).put("error", u.error)
        DirectArchive.Update.Ignore -> JSONObject().put("kind", "ignore")
    }

    /**
     * The most an [UPDATE]'s JSON may take, in characters. A Bundle carries a string as UTF-16 (two
     * bytes a character), and a Messenger's one-way Binder transaction gets at most about half of
     * the 1 MB buffer: this keeps one well under that.
     */
    const val MAX_UPDATE_CHARS = 100_000

    /**
     * [u] as it crosses to the main process ([UPDATE]): [encode]d, never more than
     * [MAX_UPDATE_CHARS]. A message that is larger drops, one step at a time until it fits: its
     * attachments' largest descriptions (those attachments then cannot be downloaded), then the
     * per-part copy of its text kept in its extra, then halves of its longest text (or its extra,
     * when that is longest). Only one that still does not fit with all of that cut (a conversation
     * of thousands of members) becomes [DirectArchive.Update.Ignore]. Either way it is taken,
     * instead of being handed over again and again, ahead of every later one.
     */
    fun encodeUpdate(u: DirectArchive.Update): JSONObject {
        var update = u
        while (true) {
            val json = encode(update)
            val upsert = update as? DirectArchive.Update.Upsert ?: return json
            if (json.toString().length <= MAX_UPDATE_CHARS) return json
            update = smaller(upsert) ?: return encode(DirectArchive.Update.Ignore)
        }
    }

    /** [u] one step smaller (see [encodeUpdate]), or null when nothing is left to cut. */
    private fun smaller(u: DirectArchive.Update.Upsert): DirectArchive.Update.Upsert? {
        val m = u.message
        m.parts.filter { it.key.isNotEmpty() }.maxByOrNull { it.key.size }?.let { largest ->
            return u.copy(message = m.copy(parts = m.parts.map { if (it === largest) it.copy(key = ByteArray(0)) else it }))
        }
        val extra = IMessageExtra.parse(m.extra)
        if (extra.parts.isNotEmpty()) return u.copy(message = m.copy(extra = extra.copy(parts = emptyMap()).toJson()))
        val longestPart = m.parts.filter { !it.isMedia }.maxByOrNull { it.text.length }
        val longest = listOf(m.text.length, m.extra.length, longestPart?.text?.length ?: 0).max()
        if (longest < MIN_CUT_CHARS) return null
        return u.copy(
            message = when (longest) {
                m.text.length -> m.copy(text = halved(m.text))
                m.extra.length -> m.copy(extra = "")
                else -> m.copy(parts = m.parts.map { if (it === longestPart) it.copy(text = halved(it.text)) else it })
            },
        )
    }

    /** Below this many characters a text is not cut any further. */
    private const val MIN_CUT_CHARS = 64

    /** The first half of [s], with no half of a character pair left at its end, then "…". */
    private fun halved(s: String): String {
        var end = s.length / 2
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end) + "…"
    }

    fun decode(o: JSONObject): DirectArchive.Update = when (o.optString("kind")) {
        "upsert" -> DirectArchive.Update.Upsert(
            ConversationDto.fromJson(o.getJSONObject("conversation")),
            MessageDto.fromJson(o.getJSONObject("message")),
            groupGuid = o.optString("groupGuid").takeIf { it.isNotEmpty() },
            groupName = o.optString("groupName"),
            groupOnly = o.optBoolean("groupOnly"),
            renamed = o.optBoolean("renamed"),
            member = if (o.has("member")) o.optBoolean("member") else null,
        )
        "remove" -> DirectArchive.Update.Remove(o.getString("messageId"))
        "receipt" -> DirectArchive.Update.Receipt(
            o.getJSONArray("messageIds").let { a -> (0 until a.length()).map { a.getString(it) } },
            o.getString("status"),
        )
        "edit" -> DirectArchive.Update.Edit(o.getString("messageId"), o.getString("text"), o.optInt("part"), o.optLong("atMs"))
        "reaction" -> DirectArchive.Update.Reaction(
            o.getString("messageId"), o.optString("participantId"), o.getString("emoji"), o.optBoolean("enable"),
            o.optBoolean("fromMe"), o.optLong("atMs"),
        )
        "unsent" -> DirectArchive.Update.Unsent(o.getString("messageId"), o.optInt("part"), o.optBoolean("fromMe"), o.optString("senderName"))
        "typing" -> DirectArchive.Update.Typing(
            o.getString("conversationId"), o.optString("participantId"), o.optBoolean("typing"),
            o.optString("groupGuid").takeIf { it.isNotEmpty() },
        )
        "readElsewhere" -> DirectArchive.Update.ReadElsewhere(o.getString("messageId"))
        "failed" -> DirectArchive.Update.Failed(o.getString("messageId"), o.optString("error"))
        else -> DirectArchive.Update.Ignore
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
}
