package com.lightdecoder.imessage.direct

import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.BalloonLayout
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.ExtensionApp
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.LinkMeta
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.PartExtension
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.Reaction

/**
 * How iMessages live in the Messages tab's archive, next to the Google Messages conversations:
 * every iMessage conversation and message id starts with [PREFIX], which Google Messages ids
 * never do, so the two never collide and [com.lightdecoder.relay.RelayManager] knows which link
 * a conversation belongs to.
 *
 * Conversation ids: `imsg:tel:+15551234567` (one other person, by their address) or
 * `imsg:group:<group guid>`. Message ids: `imsg:<message GUID>`, so the GUID Apple knows a
 * message by is always in its id (what unsend needs, also after a restart). A message's text
 * part is part "0"; its attachments are media parts `p<idx>` (idx being Apple's part index) with
 * media id `<GUID>/p<idx>`, and the library's description of the file as their key, from which
 * the file is downloaded when it is first shown.
 *
 * Pure: no library, no database. Tested in DirectArchiveTest.
 */
object DirectArchive {

    const val PREFIX = "imsg:"
    private const val GROUP = "group:"

    /** What the thread screen shows as the transport of an iMessage. */
    const val TRANSPORT = "IMESSAGE"

    /** The status names the thread screen draws ticks for (Google Messages' names). */
    const val STATUS_SENT = "OUTGOING_COMPLETE"
    const val STATUS_DELIVERED = "OUTGOING_DELIVERED"
    const val STATUS_READ = "OUTGOING_DISPLAYED"
    const val STATUS_RECEIVED = "INCOMING_COMPLETE"

    /** One of the user's messages Apple could not deliver. */
    const val STATUS_FAILED = "OUTGOING_FAILED"

    /** The tapback a sticker placed on a message shows as. */
    const val STICKER_TAPBACK = "🖼️"

    fun isImessage(id: String): Boolean = id.startsWith(PREFIX)

    fun messageId(guid: String): String = PREFIX + guid

    /** The GUID of an iMessage's archive id, or null for any other message. */
    fun guidOf(messageId: String): String? = messageId.takeIf(::isImessage)?.removePrefix(PREFIX)

    /** The archive part id of an attachment at Apple's part index [idx]. */
    fun partId(idx: Int): String = "p$idx"

    /** The media id of an attachment part: `<GUID>/p<idx>`. */
    fun mediaId(guid: String, idx: Int): String = "$guid/${partId(idx)}"

    /**
     * The conversation a message belongs to. One other person: by their address; a group: by
     * the group's guid when it has one, else by its members.
     */
    fun conversationId(participants: List<String>, me: Set<String>, groupGuid: String?): String {
        val others = participants.filter { it !in me }.distinct().sorted()
        return when {
            others.size <= 1 && (others.isNotEmpty() || participants.isNotEmpty()) ->
                PREFIX + (others.firstOrNull() ?: participants.first())
            !groupGuid.isNullOrBlank() -> groupConversationId(groupGuid)
            else -> PREFIX + GROUP + "members:" + others.joinToString(",")
        }
    }

    fun isGroup(conversationId: String): Boolean = conversationId.startsWith(PREFIX + GROUP)

    /** The conversation id of the group whose guid is [guid]. */
    fun groupConversationId(guid: String): String = PREFIX + GROUP + guid

    /** A group's guid from its conversation id, null for one-to-one or a members-only group. */
    fun groupGuid(conversationId: String): String? =
        conversationId.takeIf { isGroup(it) }?.removePrefix(PREFIX + GROUP)?.takeIf { !it.startsWith("members:") }

    /**
     * The library's description of a stored conversation, for sending to it: its members' addresses
     * from the archive (the user's own included) and the group's guid and name.
     */
    fun conversationData(conversationId: String, participants: List<ParticipantRow>, groupName: String?): ConversationData {
        val handles = participants.map { it.id }.filter { it.startsWith("tel:") || it.startsWith("mailto:") }.distinct()
        return ConversationData(handles, if (isGroup(conversationId)) groupName?.takeIf { it.isNotBlank() } else null, groupGuid(conversationId), null)
    }

    /** What one event from Apple does to the archive. */
    sealed class Update {
        /**
         * A new or changed message, in a conversation that is created or refreshed with it. Also
         * a group's events (renamed, members changed, photo changed): the group as it now is, and
         * a row of the conversation's own (direction "system") saying what happened.
         */
        data class Upsert(
            val conversation: ConversationDto,
            val message: MessageDto,
            /**
             * Apple's group id when [conversation] is a one-to-one chat by its members but the
             * message carries one: a group that is down to one other person, which stays the group
             * when it is stored as one.
             */
            val groupGuid: String? = null,
            /** Apple's name for the group of [groupGuid] ("" for none): [conversation] is named as a one-to-one chat. */
            val groupName: String = "",
            /** A group's event filed by [groupGuid] alone: only stored in that group, if there is one. */
            val groupOnly: Boolean = false,
            /** The group was renamed: [conversation]'s name is its new one, even blank (the name taken off). */
            val renamed: Boolean = false,
        ) : Update()

        /** A message to take out of the archive. */
        data class Remove(val messageId: String) : Update()

        /** A delivery or read receipt for messages the user sent. */
        data class Receipt(val messageIds: List<String>, val status: String) : Update()

        /** The sender edited part [part] of a message, at [atMs]: that part's new text. */
        data class Edit(val messageId: String, val text: String, val part: Int = 0, val atMs: Long = 0) : Update()

        /**
         * Someone put a tapback on a message ([enable]) or took it back. [participantId] is their
         * address without its scheme; [fromMe] when it is the user, from another device.
         */
        data class Reaction(
            val messageId: String,
            val participantId: String,
            val emoji: String,
            val enable: Boolean,
            val fromMe: Boolean,
            val atMs: Long,
        ) : Update()

        /** The sender unsent part [part] of a message ([fromMe]: the user, from another device). */
        data class Unsent(val messageId: String, val part: Int, val fromMe: Boolean, val senderName: String) : Update()

        /** [participantId] (an address without its scheme) started or stopped typing. */
        data class Typing(val conversationId: String, val participantId: String, val typing: Boolean) : Update()

        /** The user read this message on another of their devices. */
        data class ReadElsewhere(val messageId: String) : Update()

        /** Apple could not deliver one of the user's messages: [error] says why. */
        data class Failed(val messageId: String, val error: String) : Update()

        object Ignore : Update()
    }

    /**
     * The words of the rows a group's events add to it, from the app's string resources (the
     * ":imessage" process has them too; see [DirectTexts]). Names are already filled in.
     */
    interface Texts {
        /** The user, as the one who did something ("You"). */
        val you: String

        /** The user, as the one something was done to ("you"). */
        val youObject: String

        fun named(who: String, name: String): String
        fun added(who: String, whom: String): String
        fun removed(who: String, whom: String): String
        fun left(who: String): String
        fun changedPhoto(who: String): String
        fun removedPhoto(who: String): String

        /** Someone voted in a poll or did the like in an iMessage app ([app]: its name, empty when unknown). */
        fun updated(who: String, app: String): String
    }

    /**
     * Turns one incoming event into its archive change. [me] are the user's own addresses (a message
     * from one of them is one the user sent from another device); [nameOf] gives a display name
     * for an address; [texts] words the rows of a group's events; [describe] is the library's
     * description of an attachment (`saveAttachment`), kept so that it can be downloaded later
     * (empty: none kept).
     */
    fun map(
        inst: MessageInst,
        me: Set<String>,
        nameOf: (String) -> String,
        texts: Texts,
        describe: (MessageAttachment) -> String = { "" },
    ): Update {
        val sender = inst.sender
        val fromMe = sender != null && sender in me
        val m = inst.message
        // OpenBubbles drops these when Apple could not verify who sent them.
        if (inst.verificationFailed && droppedUnverified(m)) return Update.Ignore
        return when (m) {
            is PushChatMessage.Message -> {
                val n = m.v1
                val body = Body(n.parts, n.effect, n.voice, linkOf(n.linkMeta), appOf(n.app, messageId(inst.id)), n.subject, n.replyGuid)
                message(inst, body, me, nameOf, describe)
            }
            is PushChatMessage.React -> when (val r = m.v1.reaction) {
                is ReactMessageType.React -> {
                    if (sender == null) return Update.Ignore
                    Update.Reaction(messageId(m.v1.toUuid), Handles.display(sender), emojiOf(r.reaction), r.enable, fromMe, timestampOf(inst))
                }
                is ReactMessageType.Extension -> appUpdate(inst, m.v1.toUuid, r, me, nameOf, texts, describe)
            }
            is PushChatMessage.Unsend ->
                Update.Unsent(messageId(m.v1.tuuid), m.v1.editPart.toInt(), fromMe, if (fromMe || sender == null) "" else nameOf(sender))
            is PushChatMessage.Edit ->
                Update.Edit(messageId(m.v1.tuuid), textOf(m.v1.newParts.map { it.part }), m.v1.editPart.toInt(), timestampOf(inst))
            // From one of the user's own devices: a Delivered says nothing; a Read means they read it there.
            is PushChatMessage.Delivered ->
                if (fromMe) Update.Ignore else Update.Receipt(listOf(messageId(inst.id)), STATUS_DELIVERED)
            is PushChatMessage.Read ->
                if (fromMe) Update.ReadElsewhere(messageId(inst.id)) else Update.Receipt(listOf(messageId(inst.id)), STATUS_READ)
            is PushChatMessage.Typing -> {
                val conversation = inst.conversation
                if (sender == null || fromMe || conversation == null) return Update.Ignore
                val id = conversationId((conversation.participants + sender).distinct(), me, conversation.senderGuid)
                Update.Typing(id, Handles.display(sender), m.v1)
            }
            is PushChatMessage.RenameMessage -> {
                val conversation = inst.conversation ?: return Update.Ignore
                val who = actor(sender, fromMe, nameOf, texts) ?: return Update.Ignore
                groupEvent(inst, conversation, conversation.participants, m.v1.newName, me, nameOf, texts.named(who, m.v1.newName), renamed = true)
            }
            is PushChatMessage.ChangeParticipants -> {
                val conversation = inst.conversation ?: return Update.Ignore
                val text = participantsText(conversation.participants, m.v1.newParticipants, sender, fromMe, me, nameOf, texts)
                    ?: return Update.Ignore
                groupEvent(inst, conversation, m.v1.newParticipants, conversation.cvName, me, nameOf, text)
            }
            is PushChatMessage.IconChange -> {
                val conversation = inst.conversation ?: return Update.Ignore
                val who = actor(sender, fromMe, nameOf, texts) ?: return Update.Ignore
                val text = if (m.v1.file != null) texts.changedPhoto(who) else texts.removedPhoto(who)
                groupEvent(inst, conversation, conversation.participants, conversation.cvName, me, nameOf, text)
            }
            is PushChatMessage.Error ->
                if (fromMe) Update.Failed(messageId(m.v1.forUuid), m.v1.statusStr) else Update.Ignore
            else -> Update.Ignore
        }
    }

    private fun droppedUnverified(m: PushChatMessage): Boolean =
        m is PushChatMessage.Delivered || m is PushChatMessage.Read || m is PushChatMessage.Edit ||
            m is PushChatMessage.Unsend || m is PushChatMessage.Typing || m is PushChatMessage.RenameMessage

    /** Apple's time of the event, else now. */
    private fun timestampOf(inst: MessageInst): Long = inst.sentTimestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis()

    /** What a stored message is made of: an iMessage's, or an iMessage app's new move's. */
    private class Body(
        val parts: List<IndexedMessagePart>,
        val effect: String?,
        val voice: Boolean,
        val link: IMessageExtra.Link?,
        val app: IMessageExtra.App?,
        val subject: String?,
        val replyGuid: String?,
    )

    private fun message(
        inst: MessageInst,
        n: Body,
        me: Set<String>,
        nameOf: (String) -> String,
        describe: (MessageAttachment) -> String,
    ): Update {
        val conversation = inst.conversation ?: return Update.Ignore
        val sender = inst.sender
        val fromMe = sender != null && sender in me
        val participants = (conversation.participants + listOfNotNull(sender)).distinct()
        val id = conversationId(participants, me, conversation.senderGuid)
        val conv = conversationDto(id, participants, conversation.cvName, me, nameOf)
        val texts = partTexts(n.parts)
        // An app's card has no words of its own on the wire: what it shows stands for them.
        val text = n.app?.summary()?.takeIf { it.isNotEmpty() } ?: IMessageExtra(parts = texts).text()
        val attachments = attachments(n.parts)
        val extra = IMessageExtra(
            parts = texts,
            effect = n.effect?.takeIf { it.isNotBlank() },
            voice = n.voice,
            link = n.link,
            app = n.app,
            sticker = attachments.any { it.sticker },
        )
        val msg = MessageDto(
            id = messageId(inst.id),
            conversationId = id,
            senderId = sender.orEmpty(),
            senderName = if (fromMe || sender == null) "" else nameOf(sender),
            fromMe = fromMe,
            timestampMs = timestampOf(inst),
            direction = if (fromMe) "out" else "in",
            status = if (fromMe) STATUS_SENT else STATUS_RECEIVED,
            transport = TRANSPORT,
            subject = n.subject.orEmpty(),
            text = text,
            parts = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { PartDto(partId = "0", kind = "text", text = it) }) +
                mediaParts(inst.id, attachments, describe),
            replyToId = n.replyGuid?.let(::messageId).orEmpty(),
            extra = extra.toJson(),
        )
        return byGuid(Update.Upsert(conv, msg), id, conversation, conversation.cvName)
    }

    /** [u], with the group's guid and name when it is filed as a one-to-one chat ([Update.Upsert.groupGuid]). */
    private fun byGuid(u: Update.Upsert, id: String, conversation: ConversationData, groupName: String?): Update.Upsert {
        val guid = conversation.senderGuid?.takeIf { it.isNotBlank() && !isGroup(id) } ?: return u
        return u.copy(groupGuid = guid, groupName = groupName.orEmpty())
    }

    /**
     * An iMessage app's update to the card that started its session (message [rootGuid]). A move
     * (a game's turn) is a card of its own, a new message with the app's new state and picture,
     * as an iPhone shows it; a vote in a poll and the like ([ReactMessageType.Extension.isMeta])
     * is a row of the conversation's own saying who did it.
     */
    private fun appUpdate(
        inst: MessageInst,
        rootGuid: String,
        ext: ReactMessageType.Extension,
        me: Set<String>,
        nameOf: (String) -> String,
        texts: Texts,
        describe: (MessageAttachment) -> String,
    ): Update {
        val conversation = inst.conversation ?: return Update.Ignore
        val sender = inst.sender
        val app = appOf(ext.spec, messageId(rootGuid)) ?: return Update.Ignore
        if (ext.isMeta) {
            val who = actor(sender, sender != null && sender in me, nameOf, texts) ?: return Update.Ignore
            val members = (conversation.participants + listOfNotNull(sender)).distinct()
            return eventRow(inst, conversation, members, conversation.cvName, me, nameOf, texts.updated(who, app.shownName))
        }
        // Without a card it is nothing an iPhone shows.
        if (ext.spec.balloon == null) return Update.Ignore
        return message(inst, Body(ext.body, null, false, null, app, null, null), me, nameOf, describe)
    }

    /**
     * The parts whose attachments [m] stores (see [attachments]): an iMessage's own, or an iMessage
     * app's new move's; none for anything else.
     */
    fun storedParts(m: PushChatMessage): List<IndexedMessagePart> = when (m) {
        is PushChatMessage.Message -> m.v1.parts
        is PushChatMessage.React ->
            (m.v1.reaction as? ReactMessageType.Extension)?.takeIf { !it.isMeta && it.spec.balloon != null }?.body.orEmpty()
        else -> emptyList()
    }

    /**
     * One attachment of a message as the archive keeps it: Apple's part index [idx] (archive part
     * `p<idx>`, media id `<GUID>/p<idx>`), its real type, file name and size, and the library's
     * attachment ([source]) it came as.
     */
    class Attachment(val idx: Int, val mime: String, val name: String, val size: Long, val sticker: Boolean, val source: MessageAttachment) {
        val partId: String get() = DirectArchive.partId(idx)

        /** The file's bytes when they came inside the message (a small file), else null: it is downloaded. */
        val inlineBytes: ByteArray? get() = (source.aType as? AttachmentType.Inline)?.v1
    }

    /**
     * A message's attachments, in order. The moving part of a Live Photo (an `iris` attachment
     * right after a picture, whether or not the still is marked `iris` too) is left out: the still
     * shows, as on an Android phone. A sticker is a picture.
     */
    fun attachments(parts: List<IndexedMessagePart>): List<Attachment> {
        val out = ArrayList<Attachment>()
        val indexes = partIndexes(parts)
        // The picture just kept, whose moving part may come next (once: a moving part has no other).
        var still = false
        for ((i, p) in parts.withIndex()) {
            val att = (p.part as? MessagePart.Attachment)?.v1
            if (att == null) {
                still = false
                continue
            }
            if (att.iris && still) {
                still = false
                continue
            }
            val sticker = p.ext is PartExtension.Sticker
            val mime = DirectMedia.mimeFor(att.mime, att.utiType, att.name)
                .let { if (sticker && !it.startsWith("image/")) STICKER_MIME else it }
            val size = when (val t = att.aType) {
                is AttachmentType.Inline -> t.v1.size.toLong()
                is AttachmentType.Mmcs -> t.v1.maxOfOrNull { it.size }?.toLong() ?: 0L
            }
            out += Attachment(indexes[i], mime, att.name, size, sticker, att)
            still = mime.startsWith("image/")
        }
        return out
    }

    /**
     * The media parts of message [guid]'s [attachments], each with the library's description of it
     * ([describe]) as its key. A small file that came inside the message is also written to its
     * file by the ":imessage" process; its description holds the whole file, so it is only kept
     * while the message's are small together ([MAX_INLINE_KEYS_BYTES]), the rest of the way being
     * through the file. A download's description is always kept: it is all there is to fetch it by.
     */
    private fun mediaParts(guid: String, attachments: List<Attachment>, describe: (MessageAttachment) -> String): List<PartDto> {
        var inline = 0
        return attachments.map { a ->
            val key = when {
                a.inlineBytes == null -> describe(a.source).toByteArray(Charsets.UTF_8)
                // Its description could only be larger than the file itself.
                inline + a.size > MAX_INLINE_KEYS_BYTES -> ByteArray(0)
                else -> describe(a.source).toByteArray(Charsets.UTF_8)
                    .let { if (inline + it.size > MAX_INLINE_KEYS_BYTES) ByteArray(0) else it }
            }
            if (a.inlineBytes != null) inline += key.size
            PartDto(
                partId = a.partId,
                kind = "media",
                mediaId = mediaId(guid, a.idx),
                key = key,
                mime = a.mime,
                fileName = a.name,
                size = a.size,
            )
        }
    }

    /** A sticker whose type Apple did not say: Apple's stickers are HEIC pictures. */
    private const val STICKER_MIME = "image/heic"

    /**
     * How much of a message's inline attachments' descriptions is kept, all of them together. The
     * message crosses between the processes in one Binder transaction, where these become base64
     * in JSON, carried as UTF-16: this keeps it well under what one can take ([DirectWire.MAX_UPDATE_CHARS]).
     */
    const val MAX_INLINE_KEYS_BYTES = 32 * 1024

    /**
     * A conversation as the library describes it: its id, members and name. A group's name is the
     * one its members gave it, empty when it has none (the main process then names it after its
     * members, with contact names).
     */
    private fun conversationDto(id: String, participants: List<String>, cvName: String?, me: Set<String>, nameOf: (String) -> String): ConversationDto {
        val group = isGroup(id)
        val others = participants.filter { it !in me }
        val name = when {
            group -> cvName?.takeIf { it.isNotBlank() }.orEmpty()
            else -> nameOf(others.firstOrNull() ?: participants.first())
        }
        return ConversationDto(
            id = id,
            name = name,
            isGroup = group,
            type = TRANSPORT,
            defaultOutgoingId = participants.firstOrNull { it in me }.orEmpty(),
            participants = participants.map { h ->
                ParticipantDto(id = h, name = if (h in me) "" else nameOf(h), number = displayAddress(h), formattedNumber = displayAddress(h), isMe = h in me)
            },
            otherParticipantIds = others,
        )
    }

    /**
     * A group's event: the group with [members] and [groupName] (the conversation's own when
     * null), and the row [text] in it. Only groups have events; anything else is ignored.
     */
    private fun groupEvent(
        inst: MessageInst,
        conversation: ConversationData,
        members: List<String>,
        groupName: String?,
        me: Set<String>,
        nameOf: (String) -> String,
        text: String,
        renamed: Boolean = false,
    ): Update {
        // The group as it was, so that members leaving never turn it into a one-to-one chat.
        val id = conversationId((conversation.participants + listOfNotNull(inst.sender)).distinct(), me, conversation.senderGuid)
        // Down to one other person, it is the group only by its guid: the main process knows if it is one.
        if (!isGroup(id) && conversation.senderGuid.isNullOrBlank()) return Update.Ignore
        val row = eventRow(inst, conversation, members, groupName, me, nameOf, text) as Update.Upsert
        return row.copy(groupOnly = !isGroup(id), renamed = renamed)
    }

    /**
     * A row of a conversation's own (direction "system") saying [text], with the conversation as it
     * now is: [members] and [groupName] (a group's; the conversation's own when null).
     */
    private fun eventRow(
        inst: MessageInst,
        conversation: ConversationData,
        members: List<String>,
        groupName: String?,
        me: Set<String>,
        nameOf: (String) -> String,
        text: String,
    ): Update {
        val sender = inst.sender
        val id = conversationId((conversation.participants + listOfNotNull(sender)).distinct(), me, conversation.senderGuid)
        val fromMe = sender != null && sender in me
        val conv = conversationDto(id, members.distinct(), groupName, me, nameOf)
        val msg = MessageDto(
            id = messageId(inst.id),
            conversationId = id,
            senderId = sender.orEmpty(),
            senderName = if (fromMe || sender == null) "" else nameOf(sender),
            fromMe = fromMe,
            timestampMs = timestampOf(inst),
            direction = "system",
            transport = TRANSPORT,
            text = text,
            parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
        )
        return byGuid(Update.Upsert(conv, msg), id, conversation, groupName ?: conversation.cvName)
    }

    private fun actor(sender: String?, fromMe: Boolean, nameOf: (String) -> String, texts: Texts): String? = when {
        sender == null -> null
        fromMe -> texts.you
        else -> nameOf(sender)
    }

    /** What a change of a group's members says: who added, removed or left. Null when nothing changed. */
    private fun participantsText(
        before: List<String>,
        after: List<String>,
        sender: String?,
        fromMe: Boolean,
        me: Set<String>,
        nameOf: (String) -> String,
        texts: Texts,
    ): String? {
        val who = actor(sender, fromMe, nameOf, texts) ?: return null
        val added = (after - before.toSet()).distinct()
        val removed = (before - after.toSet()).distinct()
        if (removed == listOf(sender) && added.isEmpty()) return texts.left(who)
        fun names(handles: List<String>) = handles.joinToString(", ") { if (it in me) texts.youObject else nameOf(it) }
        val lines = listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { texts.added(who, names(it)) },
            removed.takeIf { it.isNotEmpty() }?.let { texts.removed(who, names(it)) },
        )
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** The emoji a tapback shows as. */
    fun emojiOf(reaction: Reaction): String = when (reaction) {
        Reaction.Heart -> Tapback.LOVE
        Reaction.Like -> Tapback.LIKE
        Reaction.Dislike -> Tapback.DISLIKE
        Reaction.Laugh -> Tapback.LAUGH
        Reaction.Emphasize -> Tapback.EMPHASIZE
        Reaction.Question -> Tapback.QUESTION
        is Reaction.Emoji -> reaction.v1
        is Reaction.Sticker -> STICKER_TAPBACK
    }

    /** The tapback an emoji is sent as: one of the six classic ones, else an emoji tapback. */
    fun reactionOf(emoji: String): Reaction = when (emoji.removeSuffix("️")) {
        Tapback.LOVE.removeSuffix("️"), "♥" -> Reaction.Heart
        Tapback.LIKE -> Reaction.Like
        Tapback.DISLIKE -> Reaction.Dislike
        Tapback.LAUGH -> Reaction.Laugh
        Tapback.EMPHASIZE.removeSuffix("️") -> Reaction.Emphasize
        Tapback.QUESTION -> Reaction.Question
        else -> Reaction.Emoji(emoji)
    }

    /** The six classic tapbacks' emoji. */
    object Tapback {
        const val LOVE = "❤️"
        const val LIKE = "👍"
        const val DISLIKE = "👎"
        const val LAUGH = "😂"
        const val EMPHASIZE = "‼️"
        const val QUESTION = "❓"
    }

    /**
     * Each part's index: the library's, or, for parts that have none (messages built here), as
     * rustpush numbers them: a run of text (with mentions) is one part, each attachment its own.
     */
    fun partIndexes(parts: List<IndexedMessagePart>): List<Int> {
        if (parts.all { it.idx != null }) return parts.map { it.idx!!.toInt() }
        var idx = -1
        var inText = false
        return parts.map { p ->
            val text = p.part !is MessagePart.Attachment
            if (!text || !inText) idx++
            inText = text
            idx
        }
    }

    /**
     * The text of each part, by part index: its runs of text and mentions together. Parts with no
     * text (attachments, which are media parts of their own, and an app's placeholder) are left out.
     */
    fun partTexts(parts: List<IndexedMessagePart>): Map<Int, String> {
        val out = LinkedHashMap<Int, StringBuilder>()
        for ((idx, p) in partIndexes(parts).zip(parts)) {
            val text = when (val part = p.part) {
                is MessagePart.Text -> part.v1
                is MessagePart.Mention -> part.v2
                is MessagePart.Attachment, is MessagePart.Object -> ""
            }
            out.getOrPut(idx) { StringBuilder() }.append(text)
        }
        return out.mapValues { it.value.toString() }.filterValues { it.isNotEmpty() }
    }

    /** The text of a message: each part's text ([partTexts]), one per line. */
    fun textOf(parts: List<MessagePart>): String =
        IMessageExtra(parts = partTexts(parts.map { IndexedMessagePart(it, null, null) })).text()

    private fun linkOf(meta: LinkMeta?): IMessageExtra.Link? {
        val d = meta?.data ?: return null
        val url = listOfNotNull(d.url, d.originalUrl).map { it.relative }.firstOrNull { it.isNotBlank() } ?: return null
        val site = try {
            java.net.URI(url).host.orEmpty().removePrefix("www.")
        } catch (e: java.net.URISyntaxException) {
            ""
        }
        return IMessageExtra.Link(url, d.title.orEmpty(), d.summary.orEmpty(), site)
    }

    private fun appOf(app: ExtensionApp?, root: String): IMessageExtra.App? {
        if (app == null) return null
        val balloon = app.balloon
        val layout = balloon?.layout as? BalloonLayout.TemplateLayout
        return IMessageExtra.App(
            name = app.name,
            appId = app.appId?.toLong(),
            bundleId = app.bundleId,
            url = balloon?.url.orEmpty(),
            session = balloon?.session.orEmpty(),
            ldText = balloon?.ldText.orEmpty(),
            isLive = balloon?.isLive == true,
            imageTitle = layout?.imageTitle.orEmpty(),
            imageSubtitle = layout?.imageSubtitle.orEmpty(),
            caption = layout?.caption.orEmpty(),
            subcaption = layout?.subcaption.orEmpty(),
            secondarySubcaption = layout?.secondarySubcaption.orEmpty(),
            tertiarySubcaption = layout?.tertiarySubcaption.orEmpty(),
            root = root,
        )
    }

    /** For showing: the address without its scheme. */
    fun displayAddress(handle: String): String = Handles.display(handle)

    /** Orders outgoing statuses so a late or repeated receipt never moves a message backwards. */
    fun statusRank(status: String): Int = when (status) {
        STATUS_SENT -> 1
        STATUS_DELIVERED -> 2
        STATUS_READ -> 3
        else -> if (status.startsWith("OUTGOING_FAILED")) -1 else 0
    }

    fun statusCode(status: String): Int = when (status) {
        STATUS_DELIVERED -> 2
        STATUS_READ -> 11
        else -> 1
    }
}
