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
 * Main -> :imessage: [SEND] (with its mentions and link preview), [UNSEND], [REACH], [EDIT], [REACT], [TYPING], [READ], [DOWNLOAD],
 * [EXTENSION], [BACKGROUND], [UNSCHEDULE], [RENAME], [MEMBERS], [GROUP_PHOTO], [MARK_UNREAD], [REPORT_JUNK], [PEERS]
 * (requests, answered with the same request id; a failure is answered with [error]). Never raw media bytes: files are
 * passed by path, in the app's own storage.
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
     * Sends an iMessage app's session update (a game's move, a vote in a poll, a choice added to one)
     * to the card that started the session: [extension], answered as a [SEND] is ([SendReply]: the
     * move's GUID, its time, its picture's part).
     */
    const val EXTENSION = 9

    const val UPDATE = 10

    /**
     * Sets the chat's background (iOS 26) to the picture at a path, or takes it off: [background],
     * answered with [ok] once Apple confirmed it.
     */
    const val BACKGROUND = 11

    /** Takes back a Send Later message before Apple sends it ("Cancel Send Later"): [unschedule], answered with [ok]. */
    const val UNSCHEDULE = 12

    /** Renames a group (an empty name takes its name off): [rename], answered with [guid] (the rename's own) once Apple confirmed it. */
    const val RENAME = 13

    /**
     * Changes a group's members: [members], the full new list (without the user's own address: the
     * user leaves it), answered with [guid] once Apple confirmed it.
     */
    const val MEMBERS = 14

    /** Sets a group's photo to the picture at a path, or takes it off: [groupPhoto], answered with [guid] once Apple confirmed it. */
    const val GROUP_PHOTO = 15

    /**
     * Tells the user's other devices a chat is unread again ("Mark as Unread"): [markUnread], answered
     * with [ok] at once (Apple does not answer it).
     */
    const val MARK_UNREAD = 16

    /** Reports a chat's last messages to Apple as junk ("Report Junk"): [reportJunk], answered with [ok] once Apple took it. */
    const val REPORT_JUNK = 17

    /**
     * Tells the people of the user's recent chats that the user's devices changed, so their phones
     * look the user's devices up again ([peers]: who, by the address the user writes to them from),
     * answered with [ok] at once.
     */
    const val PEERS = 18

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

    /**
     * A link's preview to send with a message ([SendRequest.link]), fetched by the main process
     * ([LinkPreviews]): the page's address ([url]) and the link as typed ([originalUrl]), its title
     * and summary, and its picture's address with the picture itself at [imagePath] (a JPEG in the
     * app's own storage, [imageMime]; none: no picture).
     */
    data class LinkPreviewRequest(
        val url: String,
        val originalUrl: String,
        val title: String = "",
        val summary: String = "",
        val imageUrl: String? = null,
        val imagePath: String? = null,
        val imageMime: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("url", url).put("originalUrl", originalUrl).put("title", title).put("summary", summary)
            .apply {
                if (imageUrl != null) put("imageUrl", imageUrl)
                if (imagePath != null) put("imagePath", imagePath)
                if (imageMime.isNotEmpty()) put("imageMime", imageMime)
            }

        companion object {
            /** Reads what [toJson] wrote; null without a web address. */
            fun fromJson(o: JSONObject): LinkPreviewRequest? {
                val url = o.optStringOrNull("url")?.takeIf { IMessageExtra.isWebAddress(it) && it.length <= LinkPreviews.MAX_URL_CHARS } ?: return null
                return LinkPreviewRequest(
                    url,
                    o.optStringOrNull("originalUrl")?.takeIf { IMessageExtra.isWebAddress(it) && it.length <= LinkPreviews.MAX_URL_CHARS } ?: url,
                    o.optString("title").take(LinkPreviews.MAX_TITLE_CHARS + 1),
                    o.optString("summary").take(LinkPreviews.MAX_SUMMARY_CHARS + 1),
                    o.optStringOrNull("imageUrl")?.takeIf { IMessageExtra.isWebAddress(it) && it.length <= LinkPreviews.MAX_URL_CHARS },
                    o.optStringOrNull("imagePath")?.ifEmpty { null },
                    o.optString("imageMime"),
                )
            }
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
        /** The text's formatting and text effects, over [text] ([TextRuns]). */
        val runs: List<TextRun> = emptyList(),
        /** Send Later: when Apple is to send it (ms), null to send it now ([SendLater]). */
        val scheduledMs: Long? = null,
        /** The members its text mentions ([Mentions]), over [text]. */
        val mentions: List<Mention> = emptyList(),
        /** The preview of the link in its text, if one was made ([LinkPreviews]). */
        val link: LinkPreviewRequest? = null,
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
                TextRuns.fromJsonArray(o.optJSONArray("runs")),
                o.optLong("scheduledMs").takeIf { it > 0 },
                Mentions.fromJsonArray(o.optJSONArray("mentions")),
                o.optJSONObject("link")?.let(LinkPreviewRequest::fromJson),
            )
        }
    }

    /**
     * What a [BACKGROUND] request asks for: the chat's background is now the picture at [path] (a
     * JPEG in the app's own storage), or the gradient or dynamic background [style], or none
     * (neither), as of [version] (Apple's, nanoseconds since 2001: a later one wins on every device).
     */
    data class BackgroundRequest(val target: Target, val path: String?, val version: Long, val style: BackgroundStyle? = null) {
        companion object {
            fun fromJson(o: JSONObject) = BackgroundRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.optStringOrNull("path")?.ifEmpty { null },
                o.getLong("version"),
                BackgroundStyle.of(o.optStringOrNull("styleType"), o.optStringOrNull("styleData")),
            )
        }
    }

    /** What a [RENAME] request asks for: the group [target] is called [name] from now on ("": it has no name). */
    data class RenameRequest(val target: Target, val name: String) {
        companion object {
            fun fromJson(o: JSONObject) = RenameRequest(Target.fromJson(o.getJSONObject("target")), o.optString("name"))
        }
    }

    /**
     * What a [MEMBERS] request asks for: the group [target]'s members from now on, [participants]
     * (addresses as the library has them, "tel:+15551234567"), the user's own ([Target.from]) among
     * them unless the user leaves; as of [groupVersion] (the group's version after the change).
     */
    data class MembersRequest(val target: Target, val participants: List<String>, val groupVersion: Long) {
        companion object {
            fun fromJson(o: JSONObject) = MembersRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.optJSONArray("participants")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() },
                o.optLong("groupVersion"),
            )
        }
    }

    /**
     * What a [GROUP_PHOTO] request asks for: the group [target]'s photo is now the picture at [path]
     * (a square JPEG where the group photos are kept, [GroupPhotos]), or none ([path] null), as of
     * [groupVersion].
     */
    data class GroupPhotoRequest(val target: Target, val path: String?, val groupVersion: Long) {
        companion object {
            fun fromJson(o: JSONObject) = GroupPhotoRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.optStringOrNull("path")?.ifEmpty { null },
                o.optLong("groupVersion"),
            )
        }
    }

    /**
     * What a [READ] request asks for: the user read [target] up to message [guid]; [receipts] is the
     * chat's own choice whether the other person is told (null: as the iMessage screen's setting says).
     */
    data class ReadRequest(val target: Target, val guid: String, val receipts: Boolean?) {
        companion object {
            fun fromJson(o: JSONObject) = ReadRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.getString("guid"),
                if (o.has("receipts") && !o.isNull("receipts")) o.optBoolean("receipts") else null,
            )
        }
    }

    /**
     * What a [MARK_UNREAD] request asks for: the chat [target] is unread again, from message [guid]
     * (the newest one someone else sent in it, as an iPhone names it) on.
     */
    data class MarkUnreadRequest(val target: Target, val guid: String) {
        companion object {
            fun fromJson(o: JSONObject) = MarkUnreadRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.getString("guid").also { if (it.isBlank() || it.length > MAX_GUID_CHARS) throw org.json.JSONException("no message") },
            )
        }
    }

    /**
     * One message of a [REPORT_JUNK] request, as OpenBubbles reports one: its GUID, its sender's
     * address as the library has it ("tel:+15551234567"), how many people the chat has besides the
     * user, its text, when it was sent (seconds since 1970, a fraction allowed: OpenBubbles'
     * `microsecondsSinceEpoch / 1000000`), and the library's descriptions of its attachments (from
     * which Apple's copies of them are named). Read with limits: a message without a GUID or sender
     * is none, its text and attachments are cut to [MAX_JUNK_TEXT_CHARS] and [MAX_JUNK_ATTACHMENTS].
     */
    data class JunkMessage(
        val guid: String,
        val sender: String,
        val conversationSize: Int,
        val text: String,
        val timeOfMessage: Double,
        val attachments: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().put("guid", guid).put("sender", sender).put("size", conversationSize)
            .put("text", text).put("at", timeOfMessage)
            .apply { if (attachments.isNotEmpty()) put("attachments", JSONArray(attachments)) }

        companion object {
            fun fromJson(o: JSONObject): JunkMessage? {
                val guid = o.optString("guid").takeIf { it.isNotBlank() && it.length <= MAX_GUID_CHARS } ?: return null
                val sender = o.optString("sender").takeIf { it.isNotBlank() && it.length <= MAX_ADDRESS_CHARS } ?: return null
                val at = o.optDouble("at", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
                val attachments = o.optJSONArray("attachments")?.let { a ->
                    (0 until minOf(a.length(), MAX_JUNK_ATTACHMENTS)).map { a.optString(it) }.filter { it.isNotBlank() && it.length <= MAX_DESCRIPTOR_CHARS }
                }.orEmpty()
                return JunkMessage(guid, sender, o.optInt("size", 1).coerceIn(1, MAX_PEERS), o.optString("text").take(MAX_JUNK_TEXT_CHARS), at, attachments)
            }
        }
    }

    /** What a [REPORT_JUNK] request asks for: [messages] (at most [MAX_JUNK_MESSAGES]) of the chat [target] reported to Apple as junk. */
    data class ReportJunkRequest(val target: Target, val messages: List<JunkMessage>) {
        companion object {
            fun fromJson(o: JSONObject) = ReportJunkRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.objects("messages").take(MAX_JUNK_MESSAGES).mapNotNull(JunkMessage::fromJson),
            )
        }
    }

    /**
     * The people one of the user's addresses ([from]) writes to in the user's recent chats ([PEERS]):
     * they are told the user's devices changed. [participants] are the library's addresses, [from] among them.
     */
    data class PeerGroup(val from: String, val participants: List<String>) {
        fun toJson(): JSONObject = JSONObject().put("from", from).put("participants", JSONArray(participants))

        companion object {
            fun fromJson(o: JSONObject): PeerGroup? {
                val from = o.optString("from").takeIf { it.isNotBlank() && it.length <= MAX_ADDRESS_CHARS } ?: return null
                val people = o.optJSONArray("participants")?.let { a ->
                    (0 until minOf(a.length(), MAX_PEERS)).map { a.optString(it) }.filter { it.isNotBlank() && it.length <= MAX_ADDRESS_CHARS }
                }.orEmpty().distinct()
                return PeerGroup(from, people).takeIf { people.any { it != from } }
            }
        }
    }

    /** What a [PEERS] request asks for: each of [groups] (at most [MAX_PEER_GROUPS]) is told. */
    data class PeersRequest(val groups: List<PeerGroup>) {
        companion object {
            fun fromJson(o: JSONObject) = PeersRequest(o.objects("groups").take(MAX_PEER_GROUPS).mapNotNull(PeerGroup::fromJson))
        }
    }

    /** The most messages a junk report carries (OpenBubbles reports the last five). */
    const val MAX_JUNK_MESSAGES = 5

    /** The most of a reported message's text that goes, and of its attachments. */
    const val MAX_JUNK_TEXT_CHARS = 4_000
    const val MAX_JUNK_ATTACHMENTS = 4

    /** The longest attachment description taken (a small file that came inside its message is described with its bytes: those are left out). */
    const val MAX_DESCRIPTOR_CHARS = 16_384

    /** The most of the user's addresses told of new devices at once, and of the people each writes to. */
    const val MAX_PEER_GROUPS = 8
    const val MAX_PEERS = 300

    /** The longest GUID and address taken from the other process. */
    const val MAX_GUID_CHARS = 64
    const val MAX_ADDRESS_CHARS = 320

    /** What an [EDIT] request asks for: part [part] of message [guid] now reads [text], formatted with [runs], mentioning [mentions]. */
    data class EditRequest(
        val target: Target,
        val guid: String,
        val part: Int,
        val text: String,
        val runs: List<TextRun>,
        val mentions: List<Mention> = emptyList(),
    ) {
        companion object {
            fun fromJson(o: JSONObject) = EditRequest(
                Target.fromJson(o.getJSONObject("target")),
                o.getString("guid"),
                o.optInt("part"),
                o.getString("text"),
                TextRuns.fromJsonArray(o.optJSONArray("runs")),
                Mentions.fromJsonArray(o.optJSONArray("mentions")),
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
     * (a new game): the app's balloon, with its picture as the attachment. [runs] are the text's
     * formatting and text effects (iOS 18), over [text]. With [scheduledMs] Apple sends it then
     * (Send Later, [SendLater]). [mentions] name the members it mentions; [link] is its link's preview.
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
        runs: List<TextRun> = emptyList(),
        scheduledMs: Long? = null,
        mentions: List<Mention> = emptyList(),
        link: LinkPreviewRequest? = null,
    ): JSONObject =
        JSONObject().put("target", target.toJson()).put("text", text)
            .put("replyGuid", replyGuid ?: JSONObject.NULL).put("replyPart", replyPart ?: JSONObject.NULL)
            .put("effect", effect ?: JSONObject.NULL)
            .put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
            .put("voice", voice)
            .apply { if (app != null) put("app", app.toJson()) }
            .apply { if (runs.isNotEmpty()) put("runs", TextRuns.toJsonArray(runs)) }
            .apply { if (scheduledMs != null && scheduledMs > 0) put("scheduledMs", scheduledMs) }
            .apply { if (mentions.isNotEmpty()) put("mentions", Mentions.toJsonArray(mentions)) }
            .apply { if (link != null) put("link", link.toJson()) }

    /** The chat [target]'s background is now the picture at [path], or none ([path] null), as of [version] ([BackgroundRequest]). */
    fun background(target: Target, path: String?, version: Long, style: BackgroundStyle? = null): JSONObject =
        JSONObject().put("target", target.toJson()).put("path", path ?: JSONObject.NULL).put("version", version)
            .apply { style?.let { put("styleType", it.type).put("styleData", it.data) } }

    /** Take back the Send Later message [guid] before Apple sends it. */
    fun unschedule(target: Target, guid: String): JSONObject = JSONObject().put("target", target.toJson()).put("guid", guid)

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

    /** Part [part] of the user's message [guid] now reads [text], formatted with [runs] (none: plain), mentioning [mentions]. */
    fun edit(target: Target, guid: String, part: Int, text: String, runs: List<TextRun> = emptyList(), mentions: List<Mention> = emptyList()): JSONObject =
        JSONObject().put("target", target.toJson()).put("guid", guid).put("part", part).put("text", text)
            .apply { if (runs.isNotEmpty()) put("runs", TextRuns.toJsonArray(runs)) }
            .apply { if (mentions.isNotEmpty()) put("mentions", Mentions.toJsonArray(mentions)) }

    /**
     * The user's tapback [emoji] on part [part] of message [guid], put on ([enable]) or taken
     * back. [targetText] is that message's text, for the notification on the other side.
     */
    fun react(target: Target, guid: String, part: Int, emoji: String, enable: Boolean, targetText: String): JSONObject =
        JSONObject().put("target", target.toJson()).put("guid", guid).put("part", part).put("emoji", emoji)
            .put("enable", enable).put("targetText", targetText)

    /** The user started ([typing]) or stopped typing in [target]. */
    fun typing(target: Target, typing: Boolean): JSONObject = JSONObject().put("target", target.toJson()).put("typing", typing)

    /**
     * The user read the chat up to message [guid]; with [receipts] the chat's own choice whether the
     * other person is told (null: the iMessage screen's setting decides).
     */
    fun read(target: Target, guid: String, receipts: Boolean? = null): JSONObject =
        JSONObject().put("target", target.toJson()).put("guid", guid).apply { if (receipts != null) put("receipts", receipts) }

    /** The group [target] is called [name] from now on ("": no name). */
    fun rename(target: Target, name: String): JSONObject = JSONObject().put("target", target.toJson()).put("name", name)

    /** The group [target]'s members are [participants] from now on (without the user's own address: the user leaves), as of [groupVersion]. */
    fun members(target: Target, participants: List<String>, groupVersion: Long): JSONObject =
        JSONObject().put("target", target.toJson()).put("participants", JSONArray(participants)).put("groupVersion", groupVersion)

    /** The group [target]'s photo is the picture at [path] from now on, or none ([path] null), as of [groupVersion]. */
    fun groupPhoto(target: Target, path: String?, groupVersion: Long): JSONObject =
        JSONObject().put("target", target.toJson()).put("path", path ?: JSONObject.NULL).put("groupVersion", groupVersion)

    /** The chat [target] is unread again, from message [guid] (the newest one someone else sent in it) on. */
    fun markUnread(target: Target, guid: String): JSONObject = JSONObject().put("target", target.toJson()).put("guid", guid)

    /** Report [messages] of the chat [target] to Apple as junk. */
    fun reportJunk(target: Target, messages: List<JunkMessage>): JSONObject =
        JSONObject().put("target", target.toJson()).put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })

    /** Tell each of [groups] that the user's devices changed. */
    fun peers(groups: List<PeerGroup>): JSONObject = JSONObject().put("groups", JSONArray().apply { groups.forEach { put(it.toJson()) } })

    /**
     * Fetch the attachment [descriptor] (as the library saved it) into the file [path]: never more than
     * [maxBytes]; with [automaticMs], one fetched without anyone asking (a sticker, a group's photo), only
     * from Apple's own file servers ([DirectMedia.isAppleFileServer]) and in that long at most.
     */
    fun download(descriptor: String, path: String, maxBytes: Long? = null, automaticMs: Long? = null): JSONObject =
        JSONObject().put("descriptor", descriptor).put("path", path)
            .apply { maxBytes?.let { put("maxBytes", it) } }
            .apply { automaticMs?.let { put("automaticMs", it) } }

    /** A [DOWNLOAD]'s request, as [download] makes it: [maxBytes] within [DirectMedia.MAX_DOWNLOAD_BYTES], [automaticMs] within [MAX_AUTOMATIC_MS] (null: one the user asked for). */
    data class DownloadRequest(val descriptor: String, val path: String, val maxBytes: Long, val automaticMs: Long?) {
        companion object {
            fun fromJson(o: JSONObject) = DownloadRequest(
                o.getString("descriptor"),
                o.getString("path"),
                o.optLong("maxBytes", DirectMedia.MAX_DOWNLOAD_BYTES).takeIf { it > 0 }?.coerceAtMost(DirectMedia.MAX_DOWNLOAD_BYTES) ?: DirectMedia.MAX_DOWNLOAD_BYTES,
                if (o.has("automaticMs") && !o.isNull("automaticMs")) o.optLong("automaticMs").coerceIn(1L, MAX_AUTOMATIC_MS) else null,
            )
        }
    }

    /** The longest a download nobody asked for may take. */
    const val MAX_AUTOMATIC_MS = 2 * 60_000L

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
            .apply { if (u.membersChanged) put("membersChanged", true) }
            .apply { u.groupVersion?.let { put("groupVersion", it) } }
        is DirectArchive.Update.Remove -> JSONObject().put("kind", "remove").put("messageId", u.messageId)
            .apply { if (u.scheduledOnly) put("scheduledOnly", true) }
        is DirectArchive.Update.Receipt -> JSONObject().put("kind", "receipt")
            .put("messageIds", JSONArray(u.messageIds)).put("status", u.status).put("sender", u.sender)
        is DirectArchive.Update.Edit -> JSONObject().put("kind", "edit").put("messageId", u.messageId).put("text", u.text)
            .put("part", u.part).put("atMs", u.atMs)
            .apply { if (u.runs.isNotEmpty()) put("runs", TextRuns.toJsonArray(u.runs)) }
            .apply { if (u.mentions.isNotEmpty()) put("mentions", Mentions.toJsonArray(u.mentions)) }
            .put("sender", u.sender).put("fromMe", u.fromMe).put("conversationId", u.conversationId)
            .apply { u.groupGuid?.let { put("groupGuid", it) } }
        is DirectArchive.Update.Reaction -> JSONObject().put("kind", "reaction").put("messageId", u.messageId)
            .put("participantId", u.participantId).put("emoji", u.emoji).put("enable", u.enable).put("fromMe", u.fromMe)
            .put("atMs", u.atMs).put("conversationId", u.conversationId)
            .apply { u.groupGuid?.let { put("groupGuid", it) } }
            .apply { u.sticker?.let { put("sticker", it) } }
            .apply { if (u.stickerFile.isNotEmpty()) put("stickerFile", u.stickerFile) }
        is DirectArchive.Update.Sticker -> JSONObject().put("kind", "sticker").put("messageId", u.messageId).put("stickerGuid", u.stickerGuid)
            .put("participantId", u.participantId).put("fromMe", u.fromMe).put("atMs", u.atMs).put("conversationId", u.conversationId)
            .apply { u.groupGuid?.let { put("groupGuid", it) } }
            .apply { if (u.file.isNotEmpty()) put("file", u.file) }
        is DirectArchive.Update.PollVote -> JSONObject().put("kind", "pollVote").put("messageId", u.messageId).put("voter", u.voter)
            .put("fromMe", u.fromMe).put("options", JSONArray(u.options)).put("atMs", u.atMs).put("conversationId", u.conversationId)
            .apply { u.groupGuid?.let { put("groupGuid", it) } }
        is DirectArchive.Update.PollChange -> JSONObject().put("kind", "pollChange").put("rootId", u.rootId).put("row", encode(u.row))
        is DirectArchive.Update.Unsent -> JSONObject().put("kind", "unsent").put("messageId", u.messageId).put("part", u.part)
            .put("fromMe", u.fromMe).put("senderName", u.senderName)
            .put("sender", u.sender).put("atMs", u.atMs).put("conversationId", u.conversationId)
            .apply { u.groupGuid?.let { put("groupGuid", it) } }
        is DirectArchive.Update.Typing -> JSONObject().put("kind", "typing").put("conversationId", u.conversationId)
            .put("participantId", u.participantId).put("typing", u.typing).apply { u.groupGuid?.let { put("groupGuid", it) } }
        is DirectArchive.Update.ReadElsewhere -> JSONObject().put("kind", "readElsewhere").put("messageId", u.messageId)
        is DirectArchive.Update.MarkUnread -> JSONObject().put("kind", "markUnread").put("messageId", u.messageId)
        DirectArchive.Update.PeerCacheInvalidate -> JSONObject().put("kind", "peerCacheInvalidate")
        is DirectArchive.Update.Failed -> JSONObject().put("kind", "failed").put("messageId", u.messageId).put("error", u.error)
        is DirectArchive.Update.Background -> JSONObject().put("kind", "background").put("conversationId", u.conversationId)
            .put("version", u.version).put("path", u.path ?: JSONObject.NULL)
            .apply { u.style?.let { put("styleType", it.type).put("styleData", it.data) } }
            .apply { u.groupGuid?.let { put("groupGuid", it) } }
            .apply { u.peer?.let { put("peer", it) } }
            .apply { u.row?.let { put("row", encode(it)) } }
        is DirectArchive.Update.GroupPhoto -> JSONObject().put("kind", "groupPhoto").put("conversationId", u.conversationId)
            .put("groupGuid", u.groupGuid).put("version", u.version).put("path", u.path ?: JSONObject.NULL)
            .put("row", encode(u.row))
            .apply { if (u.file.isNotEmpty()) put("file", u.file) }
        is DirectArchive.Update.Dropped -> JSONObject().put("kind", "dropped").put("what", u.what).put("why", u.why)
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
            // A poll's new card is its message: made smaller the same way.
            val change = update as? DirectArchive.Update.PollChange
            val upsert = change?.row ?: update as? DirectArchive.Update.Upsert ?: return json
            if (json.toString().length <= MAX_UPDATE_CHARS) return json
            val cut = smaller(upsert) ?: return encode(DirectArchive.Update.Ignore)
            update = change?.copy(row = cut) ?: cut
        }
    }

    /** [u] one step smaller (see [encodeUpdate]), or null when nothing is left to cut. */
    private fun smaller(u: DirectArchive.Update.Upsert): DirectArchive.Update.Upsert? {
        val m = u.message
        m.parts.filter { it.key.isNotEmpty() }.maxByOrNull { it.key.size }?.let { largest ->
            return u.copy(message = m.copy(parts = m.parts.map { if (it === largest) it.copy(key = ByteArray(0)) else it }))
        }
        val extra = IMessageExtra.parse(m.extra)
        // The formatting and the mentions go with the parts they are laid over.
        if (extra.parts.isNotEmpty()) return u.copy(message = m.copy(extra = extra.copy(parts = emptyMap(), runs = emptyMap(), mentions = emptyMap()).toJson()))
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
            membersChanged = o.optBoolean("membersChanged"),
            groupVersion = if (o.has("groupVersion") && !o.isNull("groupVersion")) o.optLong("groupVersion") else null,
        )
        "remove" -> DirectArchive.Update.Remove(o.getString("messageId"), o.optBoolean("scheduledOnly"))
        "receipt" -> DirectArchive.Update.Receipt(
            o.getJSONArray("messageIds").let { a -> (0 until a.length()).map { a.getString(it) } },
            o.getString("status"),
            o.optString("sender"),
        )
        "edit" -> DirectArchive.Update.Edit(
            o.getString("messageId"), o.getString("text"), o.optInt("part"), o.optLong("atMs"), TextRuns.fromJsonArray(o.optJSONArray("runs")),
            o.optString("sender"), o.optBoolean("fromMe"), o.optString("conversationId"), o.optString("groupGuid").takeIf { it.isNotEmpty() },
            Mentions.fromJsonArray(o.optJSONArray("mentions")),
        )
        "reaction" -> DirectArchive.Update.Reaction(
            o.getString("messageId"), o.optString("participantId"), o.getString("emoji"), o.optBoolean("enable"),
            o.optBoolean("fromMe"), o.optLong("atMs"), o.optString("conversationId"), o.optString("groupGuid").takeIf { it.isNotEmpty() },
            o.optStringOrNull("sticker")?.takeIf { it.isNotEmpty() },
            fileOf(o, "stickerFile"),
        )
        "sticker" -> DirectArchive.Update.Sticker(
            o.getString("messageId"), o.getString("stickerGuid"), o.optString("participantId"), o.optBoolean("fromMe"), o.optLong("atMs"),
            o.optString("conversationId"), o.optString("groupGuid").takeIf { it.isNotEmpty() },
            fileOf(o, "file"),
        )
        "pollVote" -> DirectArchive.Update.PollVote(
            o.getString("messageId"), o.optString("voter"), o.optBoolean("fromMe"),
            o.optJSONArray("options")?.let { a -> (0 until minOf(a.length(), Polls.MAX_OPTIONS)).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
            o.optLong("atMs"), o.optString("conversationId"), o.optString("groupGuid").takeIf { it.isNotEmpty() },
        )
        "pollChange" -> {
            val row = o.optJSONObject("row")?.let { decode(it) as? DirectArchive.Update.Upsert }
            val root = o.optStringOrNull("rootId")?.takeIf { it.isNotBlank() }
            if (row == null || root == null) DirectArchive.Update.Ignore else DirectArchive.Update.PollChange(root, row)
        }
        "unsent" -> DirectArchive.Update.Unsent(
            o.getString("messageId"), o.optInt("part"), o.optBoolean("fromMe"), o.optString("senderName"),
            o.optString("sender"), o.optLong("atMs"), o.optString("conversationId"), o.optString("groupGuid").takeIf { it.isNotEmpty() },
        )
        "typing" -> DirectArchive.Update.Typing(
            o.getString("conversationId"), o.optString("participantId"), o.optBoolean("typing"),
            o.optString("groupGuid").takeIf { it.isNotEmpty() },
        )
        "readElsewhere" -> DirectArchive.Update.ReadElsewhere(o.getString("messageId"))
        "markUnread" -> o.optStringOrNull("messageId")?.takeIf { DirectArchive.isImessage(it) && it.length <= MAX_GUID_CHARS + DirectArchive.PREFIX.length }
            ?.let { DirectArchive.Update.MarkUnread(it) } ?: DirectArchive.Update.Ignore
        "peerCacheInvalidate" -> DirectArchive.Update.PeerCacheInvalidate
        "failed" -> DirectArchive.Update.Failed(o.getString("messageId"), o.optString("error"))
        "background" -> DirectArchive.Update.Background(
            o.getString("conversationId"),
            o.optString("groupGuid").takeIf { it.isNotEmpty() },
            o.optString("peer").takeIf { it.isNotEmpty() },
            o.getLong("version"),
            o.optStringOrNull("path")?.ifEmpty { null },
            o.optJSONObject("row")?.let { decode(it) as? DirectArchive.Update.Upsert },
            BackgroundStyle.of(o.optStringOrNull("styleType"), o.optStringOrNull("styleData")),
        )
        "groupPhoto" -> {
            // Without its row (who did it, in which group) or the group's guid it is nothing to take.
            val row = o.optJSONObject("row")?.let { decode(it) as? DirectArchive.Update.Upsert }
            val guid = o.optStringOrNull("groupGuid")?.takeIf { it.isNotBlank() }
            if (row == null || guid == null) {
                DirectArchive.Update.Ignore
            } else {
                DirectArchive.Update.GroupPhoto(
                    o.optString("conversationId"), guid, o.optLong("version"), o.optStringOrNull("path")?.ifEmpty { null }, row, fileOf(o, "file"),
                )
            }
        }
        "dropped" -> DirectArchive.Update.Dropped(o.optString("what"), o.optString("why"))
        else -> DirectArchive.Update.Ignore
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    /** A file's description ([DirectArchive.MAX_FILE_CHARS] at most) under [key] of [o], or "" for none. */
    private fun fileOf(o: JSONObject, key: String): String =
        o.optStringOrNull(key)?.takeIf { it.length <= DirectArchive.MAX_FILE_CHARS }.orEmpty()

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
}
