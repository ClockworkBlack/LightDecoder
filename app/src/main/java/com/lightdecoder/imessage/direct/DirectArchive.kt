package com.lightdecoder.imessage.direct

import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.BalloonLayout
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.DeleteTarget
import uniffi.rust_bridge.ExtensionApp
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.LinkMeta
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.PartExtension
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.Reaction
import uniffi.rust_bridge.SetTranscriptBackgroundMessage
import uniffi.rust_bridge.ShareProfileMessage

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
            /**
             * Whether the user is in the group after this: false when they were removed from it or
             * left it (on another device), true when they are in its members again; null when this
             * says nothing about it (most messages).
             */
            val member: Boolean? = null,
            /**
             * A change of the group's members ([PushChatMessage.ChangeParticipants]): [conversation]'s
             * members are the group's from now on. Anything else leaves a stored group's members as
             * they are (it may only add someone, said in a row of its own: see DirectLink).
             */
            val membersChanged: Boolean = false,
            /**
             * The group's version as of this event (a change of its members or of its photo carries
             * one: [GroupVersions]); null when it says nothing about it.
             */
            val groupVersion: Long? = null,
            /**
             * A group's own event ([groupEvent]: renamed, its members or photo changed): its state,
             * taken even from someone the user blocked. Any other row of the conversation's own
             * (direction "system", an iMessage app's "updated", say) is from its sender like any message.
             */
            val groupEvent: Boolean = false,
        ) : Update()

        /**
         * A message to take out of the archive; with [scheduledOnly] only while it is one of the
         * user's messages still waiting for its Send Later time (one taken back on another device).
         */
        data class Remove(val messageId: String, val scheduledOnly: Boolean = false) : Update()

        /**
         * A delivery or read receipt for messages the user sent, from [sender] (their address, as
         * the library has it: "tel:+15551234567"; only someone in the message's chat is taken).
         */
        data class Receipt(val messageIds: List<String>, val status: String, val sender: String = "") : Update()

        /**
         * The sender edited part [part] of a message, at [atMs]: that part's new text, and its
         * formatting ([runs], over [text]; none: plain). Taken only from the message's own sender:
         * [sender] (their address), or the user ([fromMe], from another device) for the user's
         * own; and, when Apple said in which chat it was ([conversationId], [groupGuid]), only in
         * the message's chat.
         */
        data class Edit(
            val messageId: String,
            val text: String,
            val part: Int = 0,
            val atMs: Long = 0,
            val runs: List<TextRun> = emptyList(),
            val sender: String = "",
            val fromMe: Boolean = false,
            val conversationId: String = "",
            val groupGuid: String? = null,
            /** The members the new text mentions ([Mentions]), over [text]. */
            val mentions: List<Mention> = emptyList(),
        ) : Update()

        /**
         * Someone put a tapback on a message ([enable]) or took it back. [participantId] is their
         * address without its scheme; [fromMe] when it is the user, from another device. Taken only
         * from someone in the message's chat (and, when Apple said in which chat it was:
         * [conversationId], [groupGuid], in that one). A sticker as a tapback (iOS 18) is
         * [STICKER_TAPBACK], its picture kept under the key [sticker] ([IMessageExtra.tapbackStickerKey])
         * when it came with one: written aside by the ":imessage" process when it came inside the
         * message, else on Apple's file servers, where the library's description of it ([stickerFile])
         * says, fetched by the main process only once it took the tapback.
         */
        data class Reaction(
            val messageId: String,
            val participantId: String,
            val emoji: String,
            val enable: Boolean,
            val fromMe: Boolean,
            val atMs: Long,
            val conversationId: String = "",
            val groupGuid: String? = null,
            val sticker: String? = null,
            val stickerFile: String = "",
            /** The part of the message Apple says it is on (a photo of several sent together, the first one 0); null when none is named. */
            val part: Int? = null,
        ) : Update()

        /**
         * Someone put a sticker on message [messageId] (dragged it onto its bubble, iOS 17): the
         * message that did ([stickerGuid]), by [participantId] (their address without its scheme;
         * [fromMe]: the user, on another device), its picture kept under [IMessageExtra.stickerKey]:
         * written aside by the ":imessage" process when it came inside the message, else fetched from
         * where [file] (the library's description of it) says by the main process once it took it.
         * Taken as a tapback is: from someone in the chat, in it.
         */
        data class Sticker(
            val messageId: String,
            val stickerGuid: String,
            val participantId: String,
            val fromMe: Boolean,
            val atMs: Long,
            val conversationId: String = "",
            val groupGuid: String? = null,
            val file: String = "",
        ) : Update()

        /**
         * A vote in a poll (iOS 26, [Polls]): [voter] (their address; [fromMe]: the user, on another
         * device) chose [options] on the poll's card [messageId] (that card, or a later one of its
         * session), as of [atMs]; none: took their vote back. Taken only from someone in the chat, in it.
         */
        data class PollVote(
            val messageId: String,
            val voter: String,
            val fromMe: Boolean,
            val options: List<String>,
            val atMs: Long,
            val conversationId: String = "",
            val groupGuid: String? = null,
        ) : Update()

        /**
         * A poll's card sent again with its new state (a choice added): the session that card
         * [rootId] started. Where that card is stored, it shows the new state in its place (as an
         * iPhone updates a poll); else [row] is stored as the poll's card.
         */
        data class PollChange(val rootId: String, val row: Upsert) : Update()

        /**
         * The sender unsent part [part] of a message ([fromMe]: the user, from another device), at
         * [atMs] (0: now). Taken as an [Edit] is: only from the message's own [sender], in its chat.
         */
        data class Unsent(
            val messageId: String,
            val part: Int,
            val fromMe: Boolean,
            val senderName: String,
            val sender: String = "",
            val atMs: Long = 0,
            val conversationId: String = "",
            val groupGuid: String? = null,
        ) : Update()

        /**
         * [participantId] (an address without its scheme) started or stopped typing, in the chat
         * whose guid is [groupGuid] if it has one (a group down to one other person is still that group).
         */
        data class Typing(val conversationId: String, val participantId: String, val typing: Boolean, val groupGuid: String? = null) : Update()

        /** The user read this message on another of their devices. */
        data class ReadElsewhere(val messageId: String) : Update()

        /**
         * The user marked the chat of message [messageId] unread on another of their devices (Apple's
         * MarkUnread, naming the newest message someone else sent in it). Only from the user's own
         * devices, verified: it is unread here too, without a notification.
         */
        data class MarkUnread(val messageId: String) : Update()

        /**
         * "Delete on All Devices" ([RecycleRules]), from the user's own verified devices: the chat
         * [conversationId] (a group's guid [groupGuid] when it names one) went to Recently Deleted as of
         * [atMs], or, with no chat, the messages [messageIds] were deleted.
         */
        data class Recycle(val conversationId: String?, val groupGuid: String?, val messageIds: List<String>, val atMs: Long) : Update()

        /** The chat [conversationId] came back from Recently Deleted on the user's other device. */
        data class Recover(val conversationId: String, val groupGuid: String?) : Update()

        /**
         * The chat [conversationId] was deleted for good on the user's other device (taken only for a chat
         * in Recently Deleted here, and held there a day at least: [RecycleRules.purgeNow]); or, with no
         * chat, the messages [messageIds].
         */
        data class Purge(val conversationId: String?, val groupGuid: String?, val messageIds: List<String>) : Update()

        /**
         * The user's devices changed (one was added: Apple's library says so of the user's own account).
         * The people of the user's recent chats are to be told, so that their phones look the user's
         * devices up again (OpenBubbles' `invalidatePeerCaches`; DirectLink works out whom).
         */
        object PeerCacheInvalidate : Update()

        /** Apple could not deliver one of the user's messages: [error] says why. */
        data class Failed(val messageId: String, val error: String) : Update()

        /**
         * The chat's background (iOS 26) was set or taken off, as of [version] (Apple's, nanoseconds
         * since 2001; an older one than the chat's is ignored): the chat [conversationId] (a group by
         * [groupGuid], a chat with one person by [peer]), the picture to show from now on at [path]
         * (written by the ":imessage" process; null: none), and the row saying who did it ([row]).
         */
        data class Background(
            val conversationId: String,
            val groupGuid: String?,
            val peer: String?,
            val version: Long,
            val path: String?,
            val row: Upsert?,
            /** A gradient or dynamic background (drawn here, no picture): set, though [path] is null. */
            val style: BackgroundStyle? = null,
        ) : Update()

        /**
         * A group's photo was set or taken off ([PushChatMessage.IconChange]), as of the group's
         * [version] (an older one than the photo's is ignored): the group of [groupGuid] (stored
         * as [conversationId]), the photo to show from now on: on Apple's file servers, where the
         * library's description of it ([file]) says, fetched by the main process only once it took
         * the change (the group stored, its sender in it); or a square picture already made at [path]
         * (a new one where the group photos are kept, [GroupPhotos.isNew]); neither: none. And the row
         * saying who did it ([row], "Bob changed the group photo.").
         */
        data class GroupPhoto(
            val conversationId: String,
            val groupGuid: String,
            val version: Long,
            val path: String?,
            val row: Upsert,
            val file: String = "",
        ) : Update() {
            /** Whether the photo was taken off. */
            val removed: Boolean get() = path == null && file.isEmpty()
        }

        /**
         * A FaceTime call that ended ([com.lightdecoder.imessage.facetime.CallLogEntry]): its row in the
         * chat with [peer] (their address as the library has it), whoever placed it ([outgoing]: the
         * user), audio or [video], when it began and, for one that was talked in, how long it was;
         * [outcome] is an [com.lightdecoder.imessage.facetime.Outcome]'s name on the wire. A missed
         * one is also told in a notice ([quiet]: without a sound). [conversationId]: the chat it was
         * placed from, when the ":imessage" process was told (else it is found by [peer]). A [group]
         * call also says its people besides the user ([members]: whom it was placed to, or an incoming
         * one's member list as its caller wrote it), only to find the group chat its row goes in.
         */
        data class Call(
            val guid: String,
            val peer: String,
            val outgoing: Boolean,
            val video: Boolean,
            val startedMs: Long,
            val durationMs: Long,
            val outcome: String,
            val quiet: Boolean = false,
            val conversationId: String = "",
            val group: Boolean = false,
            val members: List<String> = emptyList(),
        ) : Update()

        /**
         * Where FaceTime stands in the ":imessage" process ([FaceTimeState]): told whenever a call
         * comes to hold the line or lets go of it, for the main process's "return to call" bar.
         */
        data class FaceTime(val state: FaceTimeState) : Update()

        /**
         * A line of the ":imessage" process for the iMessage log (what FaceTime did: kinds and
         * outcomes only, [com.lightdecoder.imessage.facetime.FaceTimeLog]); the main process keeps the log.
         */
        data class Note(val line: String) : Update()

        /**
         * An event from Apple the ":imessage" process did not take: [what] kind of event it was and
         * [why] (kinds and reasons only, never its text, names or addresses), for the iMessage log.
         */
        data class Dropped(val what: String, val why: String) : Update()

        /**
         * Where the optional Apple Account now stands ([AccountState], Booleans only), told by the
         * ":imessage" process when it changes: nothing of the archive's, for the main process's gating.
         * [restarting]: told right before that process ends for its restart (the main process checks
         * that it came back, [DirectLink]).
         */
        data class Account(val state: AccountState, val restarting: Boolean = false, val removeImported: Boolean = false) : Update()

        /**
         * Someone shared their name and photo (B6, Apple Account only): [sender] in the chat with one
         * person [conversationId], and where it is ([descriptor], a [ProfileDescriptor]'s text; no
         * poster). Nothing is fetched for it here: the main process asks ([DirectWire.PROFILE_FETCH])
         * once it took it (a chat it has with them).
         */
        data class ProfileOffer(val conversationId: String, val sender: String, val descriptor: String) : Update()

        /** Someone's Focus status (B6): [handle] has notifications silenced ([silenced]) or not, as of [atMs]. */
        data class Focus(val handle: String, val silenced: Boolean, val atMs: Long) : Update()

        /**
         * Older messages imported from Messages in iCloud (B6, read-only): chats and messages as the
         * archive keeps them, each message marked [IMessageExtra.imported]. Never notified, never
         * answered with receipts.
         */
        data class CloudBatch(val chats: List<ConversationDto>, val messages: List<MessageDto>) : Update()

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

        /** Someone took the group's name off (a rename to nothing): "Bob removed the name from the conversation." */
        fun nameRemoved(who: String): String
        fun added(who: String, whom: String): String
        fun removed(who: String, whom: String): String
        fun left(who: String): String
        fun changedPhoto(who: String): String
        fun removedPhoto(who: String): String

        /** Someone voted in a poll or did the like in an iMessage app ([app]: its name, empty when unknown). */
        fun updated(who: String, app: String): String

        /** Someone set the chat's background (iOS 26): "Bob changed the chat background." */
        fun changedBackground(who: String): String

        /** Someone took the chat's background off. */
        fun removedBackground(who: String): String

        /** What a poll's card stands for as a message (the list's snippet, a notification): "Poll: [question]". */
        fun poll(question: String): String = question.ifBlank { Polls.NAME }
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
        now: Long = System.currentTimeMillis(),
        /** "Delete on All Devices" ([DirectPrefs.deleteOnAllDevices]): without it no recycle is taken. */
        deleteSync: Boolean = false,
    ): Update {
        val m = inst.message
        // The user's own devices changed: the library says so of the user's account itself, from nobody
        // (or, should it name one, from one of the user's own addresses); never anyone else's word.
        if (m is PushChatMessage.PeerCacheInvalidate) {
            val own = inst.sender == null || inst.sender in me
            return if (own && !inst.verificationFailed) Update.PeerCacheInvalidate else Update.Ignore
        }
        // From nobody: nothing to take (an iPhone shows none either).
        val sender = inst.sender ?: return Update.Ignore
        // Apple could not verify who sent it. As OpenBubbles, nothing that changes or removes what
        // is stored, or a group, is taken from it; and it is never the user's: claiming one of the
        // user's own addresses, it is from nobody else either (a message would show as the other
        // person's in a chat with one person), so it is dropped.
        if (inst.verificationFailed && (sender in me || droppedUnverified(m))) return Update.Ignore
        val fromMe = sender in me
        val at = timestampOf(inst, now)
        return when (m) {
            is PushChatMessage.Message -> {
                val n = m.v1
                // Send Later from another of the user's devices: Apple sends it then (two weeks ahead at most).
                val scheduled = n.scheduled?.takeIf { it.schedule && fromMe }?.ms?.toLong()
                    ?.coerceAtMost(now + SendLater.MAX_AHEAD_MS + MAX_FUTURE_MS) ?: 0L
                // A rich link's balloon is no app's card: its preview is the link's.
                val app = n.app?.takeUnless { it.bundleId == IMessageExtra.LINK_BALLOON }
                // Its picture, written aside by the ":imessage" process (DirectEngine) under the message's key.
                val link = linkOf(n.linkMeta)?.let { l -> if (linkImage(n.linkMeta) != null) l.copy(image = IMessageExtra.linkImageKey(inst.id)) else l }
                val body = Body(n.parts, n.effect, n.voice, link, appOf(app, messageId(inst.id)), n.subject, n.replyGuid, scheduled)
                message(inst, body, me, nameOf, texts, describe, now)
            }
            is PushChatMessage.React -> when (val r = m.v1.reaction) {
                is ReactMessageType.React -> {
                    val (chat, guid) = chatOf(inst, me)
                    // A sticker as a tapback: its picture, when it came with one, is kept by who put it there (one each, as tapbacks are).
                    val sticker = (r.reaction as? Reaction.Sticker)?.takeIf { r.enable && stickerAttachment(m) != null }
                        ?.let { IMessageExtra.tapbackStickerKey(m.v1.toUuid, if (fromMe) TAPBACK_ME else Handles.display(sender)) }
                    val file = if (sticker != null) stickerFile(m, describe) else ""
                    Update.Reaction(
                        messageId(m.v1.toUuid), Handles.display(sender), emojiOf(r.reaction), r.enable, fromMe, at, chat, guid, sticker, file,
                        part = tapbackPartOf(m.v1.toPart),
                    )
                }
                is ReactMessageType.Extension -> appUpdate(inst, m.v1.toUuid, r, me, nameOf, texts, describe, now)
            }
            is PushChatMessage.Unsend -> {
                val (chat, guid) = chatOf(inst, me)
                Update.Unsent(messageId(m.v1.tuuid), m.v1.editPart.toInt(), fromMe, if (fromMe) "" else nameOf(sender), sender, at, chat, guid)
            }
            is PushChatMessage.Edit -> {
                val (chat, guid) = chatOf(inst, me)
                m.v1.newParts.map { it.part }.let { parts ->
                    Update.Edit(
                        messageId(m.v1.tuuid), textOf(parts), m.v1.editPart.toInt(), at, TextRuns.fromEdit(parts), sender, fromMe, chat, guid,
                        Mentions.fromEdit(parts, me),
                    )
                }
            }
            // From one of the user's own devices: a Delivered says nothing; a Read means they read it there.
            is PushChatMessage.Delivered ->
                if (fromMe) Update.Ignore else Update.Receipt(listOf(messageId(inst.id)), STATUS_DELIVERED, sender)
            is PushChatMessage.Read ->
                if (fromMe) Update.ReadElsewhere(messageId(inst.id)) else Update.Receipt(listOf(messageId(inst.id)), STATUS_READ, sender)
            // "Mark as Unread" on one of the user's own devices (its id is the message it names); from anyone else, nothing.
            is PushChatMessage.MarkUnread ->
                if (fromMe && inst.id.isNotBlank() && inst.id.length <= MAX_GUID_CHARS) Update.MarkUnread(messageId(inst.id)) else Update.Ignore
            is PushChatMessage.Typing -> {
                val conversation = inst.conversation
                if (fromMe || conversation == null) return Update.Ignore
                val id = conversationId((conversation.participants + sender).distinct(), me, conversation.senderGuid)
                Update.Typing(id, Handles.display(sender), m.v1, conversation.senderGuid?.takeIf { it.isNotBlank() })
            }
            is PushChatMessage.RenameMessage -> {
                val conversation = inst.conversation ?: return Update.Ignore
                val who = actor(sender, fromMe, nameOf, texts) ?: return Update.Ignore
                val newName = m.v1.newName
                val text = if (newName.isBlank()) texts.nameRemoved(who) else texts.named(who, newName)
                groupEvent(inst, conversation, conversation.participants, newName, me, nameOf, text, renamed = true, now = now)
            }
            is PushChatMessage.ChangeParticipants -> {
                val conversation = inst.conversation ?: return Update.Ignore
                val text = participantsText(conversation.participants, m.v1.newParticipants, sender, fromMe, me, nameOf, texts)
                    ?: return Update.Ignore
                val event = groupEvent(inst, conversation, m.v1.newParticipants, conversation.cvName, me, nameOf, text, now = now)
                // Removed from it, left it, or added back: whether the user can still write in it.
                (event as? Update.Upsert)?.copy(
                    member = m.v1.newParticipants.any { it in me }, membersChanged = true, groupVersion = groupVersionOf(m.v1.groupVersion),
                ) ?: event
            }
            // A group's (by its guid): the change, with where the photo is on Apple's file servers, which
            // the main process fetches once it took the change ([groupPhoto]). Else (no guid, or a photo
            // the library cannot describe) the row alone, with the group's version.
            is PushChatMessage.IconChange -> {
                val conversation = inst.conversation ?: return Update.Ignore
                val photo = m.v1.file
                val file = photo?.let { groupPhotoFile(it, describe) }.orEmpty()
                if (!conversation.senderGuid.isNullOrBlank() && (photo == null || file.isNotEmpty())) {
                    return groupPhoto(inst, me, nameOf, texts, path = null, file = file, now = now)
                }
                val who = actor(sender, fromMe, nameOf, texts) ?: return Update.Ignore
                val text = if (photo != null) texts.changedPhoto(who) else texts.removedPhoto(who)
                val event = groupEvent(inst, conversation, conversation.participants, conversation.cvName, me, nameOf, text, now = now)
                (event as? Update.Upsert)?.copy(groupVersion = groupVersionOf(m.v1.groupVersion)) ?: event
            }
            is PushChatMessage.Error ->
                if (fromMe) Update.Failed(messageId(m.v1.forUuid), m.v1.statusStr) else Update.Ignore
            // A Send Later message taken back on another of the user's devices: it is gone there too
            // (only while it is one still waiting for its time).
            is PushChatMessage.PermanentDelete -> {
                val target = m.v1.target as? DeleteTarget.Messages
                val guid = target?.v1?.singleOrNull()
                if (m.v1.isScheduled) {
                    if (fromMe && guid != null) Update.Remove(messageId(guid), scheduledOnly = true) else Update.Ignore
                } else {
                    RecycleRules.incoming(m, sender, inst.verificationFailed, me, deleteSync, now) ?: Update.Ignore
                }
            }
            is PushChatMessage.MoveToRecycleBin, is PushChatMessage.RecoverChat ->
                RecycleRules.incoming(m, sender, inst.verificationFailed, me, deleteSync, now) ?: Update.Ignore
            // Someone's name and photo: an offer, only as [profileOfferOf] allows it.
            is PushChatMessage.ShareProfile -> profileOfferOf(inst, m.v1, me) ?: Update.Ignore
            // From the user's own devices only, and never to this phone with the account as it is
            // signed in here ("iCloud services only": the iPhone never sends them to this number).
            is PushChatMessage.UpdateProfile, is PushChatMessage.UpdateProfileSharing -> Update.Ignore
            // Never past Android's Do Not Disturb: nothing is done with it.
            PushChatMessage.NotifyAnyways -> Update.Ignore
            else -> Update.Ignore
        }
    }

    /**
     * An offer of [profile] (someone's name and photo) from [inst], only when all hold: Apple verified
     * its sender, who is not the user, it came in a chat with that one person, and its descriptor is
     * [DirectWire.MAX_DESCRIPTOR_CHARS] at most. The poster that may come with it is never taken.
     * Also for a name and photo that came with a message or a tapback ([embeddedProfileOf]).
     */
    fun profileOfferOf(inst: MessageInst, profile: ShareProfileMessage, me: Set<String>): Update.ProfileOffer? {
        if (inst.verificationFailed) return null
        val sender = inst.sender ?: return null
        if (sender in me) return null
        val conversation = inst.conversation ?: return null
        val others = (conversation.participants + sender).filter { it !in me }.distinct()
        if (others != listOf(sender)) return null
        val descriptor = ProfileDescriptor.of(profile)?.encode()?.takeIf { it.length <= DirectWire.MAX_DESCRIPTOR_CHARS } ?: return null
        return Update.ProfileOffer(conversationId(others, me, null), sender, descriptor)
    }

    /** The name and photo a message or a tapback came with, as an offer ([profileOfferOf]); null with none. */
    fun embeddedProfileOf(inst: MessageInst, me: Set<String>): Update.ProfileOffer? {
        val profile = when (val m = inst.message) {
            is PushChatMessage.Message -> m.v1.embeddedProfile
            is PushChatMessage.React -> m.v1.embeddedProfile
            else -> null
        } ?: return null
        return profileOfferOf(inst, profile, me)
    }

    /**
     * What is not taken from an event Apple could not verify (as OpenBubbles): what changes or removes
     * what is stored, and a group's events. Of an iMessage app's updates and the stickers put on
     * messages too: a vote would count for whoever it claims to be from, a poll's change rewrite its
     * card, a sticker be fetched and shown as theirs.
     */
    private fun droppedUnverified(m: PushChatMessage): Boolean =
        m is PushChatMessage.Delivered || m is PushChatMessage.Read || m is PushChatMessage.Edit ||
            m is PushChatMessage.Unsend || m is PushChatMessage.Typing || m is PushChatMessage.RenameMessage ||
            m is PushChatMessage.ChangeParticipants || m is PushChatMessage.IconChange || m is PushChatMessage.Error ||
            m is PushChatMessage.PermanentDelete || m is PushChatMessage.MoveToRecycleBin || m is PushChatMessage.RecoverChat ||
            m is PushChatMessage.SetTranscriptBackground ||
            m is PushChatMessage.MarkUnread || m is PushChatMessage.PeerCacheInvalidate ||
            m is PushChatMessage.ShareProfile || m is PushChatMessage.UpdateProfile || m is PushChatMessage.UpdateProfileSharing ||
            (m is PushChatMessage.React && m.v1.reaction.let { r -> r is ReactMessageType.Extension || (r as? ReactMessageType.React)?.reaction is Reaction.Sticker })

    /** The longest GUID taken from Apple's events (Apple's are UUIDs). */
    private const val MAX_GUID_CHARS = 64

    /**
     * How far ahead of this phone's clock an event's time is taken (a device's clock may be a little
     * ahead): later, it is taken as this much after it arrived. A time in the past is taken as it is
     * (an event can come late: after the phone was off, or Apple's journal after a restart).
     */
    const val MAX_FUTURE_MS = 5 * 60 * 1000L

    /** Apple's time of the event ([MAX_FUTURE_MS] after [now] at the latest), else [now]. */
    fun timestampOf(inst: MessageInst, now: Long = System.currentTimeMillis()): Long =
        inst.sentTimestamp.toLong().takeIf { it > 0 }?.coerceAtMost(now + MAX_FUTURE_MS) ?: now

    /** The chat an event names (its id as [conversationId] makes it, and its group's guid), or "" and null when it names none. */
    private fun chatOf(inst: MessageInst, me: Set<String>): Pair<String, String?> {
        val c = inst.conversation ?: return "" to null
        val id = conversationId((c.participants + listOfNotNull(inst.sender)).distinct(), me, c.senderGuid)
        return id to c.senderGuid?.takeIf { it.isNotBlank() }
    }

    /** What a stored message is made of: an iMessage's, or an iMessage app's new move's. */
    private class Body(
        val parts: List<IndexedMessagePart>,
        val effect: String?,
        val voice: Boolean,
        val link: IMessageExtra.Link?,
        val app: IMessageExtra.App?,
        val subject: String?,
        val replyGuid: String?,
        val scheduledAt: Long = 0,
    )

    private fun message(
        inst: MessageInst,
        n: Body,
        me: Set<String>,
        nameOf: (String) -> String,
        texts: Texts,
        describe: (MessageAttachment) -> String,
        now: Long,
    ): Update {
        val conversation = inst.conversation ?: return Update.Ignore
        val sender = inst.sender
        val fromMe = sender != null && sender in me
        val participants = (conversation.participants + listOfNotNull(sender)).distinct()
        val id = conversationId(participants, me, conversation.senderGuid)
        val conv = conversationDto(id, participants, conversation.cvName, me, nameOf)
        val partTexts = partTexts(n.parts)
        // An app's card has no words of its own on the wire: what it shows stands for them (a
        // poll: its question, as an iPhone names one).
        val poll = n.app?.takeIf(Polls::isPoll)?.let { Polls.decode(it.url) }
        val text = poll?.let { texts.poll(it.title) } ?: n.app?.summary()?.takeIf { it.isNotEmpty() } ?: IMessageExtra(parts = partTexts).text()
        val attachments = attachments(n.parts)
        val extra = IMessageExtra(
            parts = partTexts,
            effect = n.effect?.takeIf { it.isNotBlank() },
            voice = n.voice,
            link = n.link,
            app = n.app,
            sticker = attachments.any { it.sticker },
            // An app's card shows its own lines, not the text that stands for them.
            runs = if (n.app == null) TextRuns.fromParts(n.parts).filterKeys { it in partTexts } else emptyMap(),
            scheduledAt = n.scheduledAt,
            mentions = if (n.app == null) Mentions.fromParts(n.parts, me).filterKeys { it in partTexts } else emptyMap(),
            live = liveOf(attachments),
            stickerParts = attachments.filter { it.sticker }.map { it.partId }.take(IMessageExtra.MAX_MEDIA_MARKS),
        )
        val msg = MessageDto(
            id = messageId(inst.id),
            conversationId = id,
            senderId = sender.orEmpty(),
            senderName = if (fromMe || sender == null) "" else nameOf(sender),
            fromMe = fromMe,
            // A Send Later message is at the time Apple sends it, as on an iPhone.
            timestampMs = n.scheduledAt.takeIf { it > 0 } ?: timestampOf(inst, now),
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
     * as an iPhone shows it; an update that is no card of its own ([ReactMessageType.Extension.isMeta])
     * is a row of the conversation's own saying who did it. A poll's are its own: a vote counts
     * on its card ([Update.PollVote]), a choice added changes it ([Update.PollChange]), and no row
     * says either. Without a card at all it is a sticker dragged onto a message ([Update.Sticker]).
     */
    private fun appUpdate(
        inst: MessageInst,
        rootGuid: String,
        ext: ReactMessageType.Extension,
        me: Set<String>,
        nameOf: (String) -> String,
        texts: Texts,
        describe: (MessageAttachment) -> String,
        now: Long,
    ): Update {
        val conversation = inst.conversation ?: return Update.Ignore
        val sender = inst.sender ?: return Update.Ignore
        val fromMe = sender in me
        val app = appOf(ext.spec, messageId(rootGuid)) ?: return Update.Ignore
        // Without a card: a sticker dragged onto the message (iOS 17), else nothing an iPhone shows.
        if (ext.spec.balloon == null) {
            if (ext.isMeta || stickerAttachment(inst.message) == null) return Update.Ignore
            val (chat, guid) = chatOf(inst, me)
            return Update.Sticker(
                messageId(rootGuid), inst.id, if (fromMe) TAPBACK_ME else Handles.display(sender), fromMe, timestampOf(inst, now), chat, guid,
                stickerFile(inst.message, describe),
            )
        }
        if (Polls.isPoll(app)) {
            if (ext.isMeta) {
                // A vote: who sent it chose these (whom its JSON names does not count); one that
                // cannot be read changes nothing, and no row says anything of it (as on an iPhone).
                val choices = Polls.decodeVotes(app.url) ?: return Update.Ignore
                val (chat, guid) = chatOf(inst, me)
                return Update.PollVote(messageId(rootGuid), sender, fromMe, choices, timestampOf(inst, now), chat, guid)
            }
            // A choice added: the poll's new state, in place of its card where it is stored.
            val row = message(inst, Body(ext.body, null, false, null, app, null, null), me, nameOf, texts, describe, now) as? Update.Upsert
                ?: return Update.Ignore
            return Update.PollChange(messageId(rootGuid), row)
        }
        if (ext.isMeta) {
            val who = actor(sender, fromMe, nameOf, texts) ?: return Update.Ignore
            val members = (conversation.participants + sender).distinct()
            return eventRow(inst, conversation, members, conversation.cvName, me, nameOf, texts.updated(who, app.shownName), now)
        }
        return message(inst, Body(ext.body, null, false, null, app, null, null), me, nameOf, texts, describe, now)
    }

    /** Who a sticker or a tapback of the user's own is kept by (the archive's id for the user, as DirectLink keeps tapbacks). */
    const val TAPBACK_ME = "me"

    /**
     * The sticker that [m] puts on a message, if it is one: a sticker as a tapback (iOS 18) put on,
     * or a sticker dragged onto a bubble (an app's update without a card); its picture, the first of
     * its attachments. Null for anything else.
     */
    fun stickerAttachment(m: PushChatMessage): MessageAttachment? {
        val react = (m as? PushChatMessage.React)?.v1 ?: return null
        val body = when (val r = react.reaction) {
            is ReactMessageType.React -> (r.reaction as? Reaction.Sticker)?.takeIf { r.enable }?.body
            is ReactMessageType.Extension -> r.body.takeIf { !r.isMeta && r.spec.balloon == null }
        } ?: return null
        return attachments(body).firstOrNull { it.mime.startsWith("image/") }?.source
    }

    /**
     * The picture of a link's preview, as it came inside the message: the attachment its metadata
     * names ([uniffi.rust_bridge.RichLinkImageAttachmentSubstitute]), if there is one and it is no
     * larger than [PreviewImages.MAX_SOURCE_BYTES]; null otherwise.
     */
    fun linkImage(meta: LinkMeta?): ByteArray? {
        val index = meta?.data?.image?.richLinkImageAttachmentSubstituteIndex ?: return null
        if (index > Int.MAX_VALUE.toULong()) return null
        val bytes = meta.attachments.getOrNull(index.toInt()) ?: return null
        return bytes.takeIf { it.isNotEmpty() && it.size <= PreviewImages.MAX_SOURCE_BYTES }
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
    class Attachment(
        val idx: Int,
        val mime: String,
        val name: String,
        val size: Long,
        val sticker: Boolean,
        val source: MessageAttachment,
        /** A Live Photo's moving part: the Apple part index of the still it belongs to; null for any other attachment. */
        val liveOf: Int? = null,
    ) {
        val partId: String get() = DirectArchive.partId(idx)

        /** The file's bytes when they came inside the message (a small file), else null: it is downloaded. */
        val inlineBytes: ByteArray? get() = (source.aType as? AttachmentType.Inline)?.v1
    }

    /**
     * A message's attachments, in order. The moving part of a Live Photo (an `iris` attachment
     * right after a picture, whether or not the still is marked `iris` too) is kept as an attachment
     * of its own marked [Attachment.liveOf] its still: stored, never shown, fetched only when the user
     * holds the still in the gallery. A sticker is a picture.
     */
    fun attachments(parts: List<IndexedMessagePart>): List<Attachment> {
        val out = ArrayList<Attachment>()
        val indexes = partIndexes(parts)
        // The picture just kept, whose moving part may come next (once: a moving part has no other).
        var still: Int? = null
        for ((i, p) in parts.withIndex()) {
            val att = (p.part as? MessagePart.Attachment)?.v1
            if (att == null) {
                still = null
                continue
            }
            val stillIdx = still
            if (att.iris && stillIdx != null) {
                still = null
                val mime = DirectMedia.mimeFor(att.mime, att.utiType, att.name)
                val size = when (val t = att.aType) {
                    is AttachmentType.Inline -> t.v1.size.toLong()
                    is AttachmentType.Mmcs -> t.v1.maxOfOrNull { it.size }?.toLong() ?: 0L
                }
                out += Attachment(indexes[i], mime, att.name, size, false, att, liveOf = stillIdx)
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
            still = indexes[i].takeIf { mime.startsWith("image/") }
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

    /** A tapback's part (Apple's index), bounded: 0 when there is none or it is out of range. */
    internal fun partOf(toPart: ULong?): Int = toPart?.takeIf { it <= MAX_PART_INDEX.toULong() }?.toInt() ?: 0

    /** A tapback's part as Apple names it (the first part is 0), bounded: null when none is named or it is out of range. */
    internal fun tapbackPartOf(toPart: ULong?): Int? = toPart?.takeIf { it <= MAX_PART_INDEX.toULong() }?.toInt()

    /** The highest part index a tapback may name. */
    const val MAX_PART_INDEX = 9_999

    /** Each Live Photo's still and moving part ([IMessageExtra.live]), by part id: at most [IMessageExtra.MAX_LIVE]. */
    internal fun liveOf(attachments: List<Attachment>): Map<String, String> =
        attachments.mapNotNull { a -> a.liveOf?.let { partId(it) to a.partId } }.take(IMessageExtra.MAX_LIVE).toMap()

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
        now: Long = System.currentTimeMillis(),
    ): Update {
        // The group as it was, so that members leaving never turn it into a one-to-one chat.
        val id = conversationId((conversation.participants + listOfNotNull(inst.sender)).distinct(), me, conversation.senderGuid)
        // Down to one other person, it is the group only by its guid: the main process knows if it is one.
        if (!isGroup(id) && conversation.senderGuid.isNullOrBlank()) return Update.Ignore
        val row = eventRow(inst, conversation, members, groupName, me, nameOf, text, now) as Update.Upsert
        return row.copy(groupOnly = !isGroup(id), renamed = renamed, groupEvent = true)
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
        now: Long,
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
            timestampMs = timestampOf(inst, now),
            direction = "system",
            transport = TRANSPORT,
            text = text,
            parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
        )
        return byGuid(Update.Upsert(conv, msg), id, conversation, groupName ?: conversation.cvName)
    }

    /**
     * The chat a background change ([PushChatMessage.SetTranscriptBackground]) is for, as
     * OpenBubbles finds it: its `chatId` is the other person's address (a chat with one person, as
     * the user's own devices are told), else a group's guid; with none, the chat with its sender.
     * Null when it names no chat, and for one nobody can take it from: one from no one, one Apple
     * could not verify, and one naming a chat by an address from anyone but the user's own devices
     * (anyone else's is about the chat with themselves; it names none). Whether the sender is in
     * a group named by its guid is the main process's to tell (DirectLink).
     */
    data class BackgroundChat(val conversationId: String, val members: List<String>, val groupGuid: String?, val peer: String?)

    fun backgroundChat(inst: MessageInst, me: Set<String>): BackgroundChat? {
        val m = (inst.message as? PushChatMessage.SetTranscriptBackground)?.v1 ?: return null
        val sender = inst.sender ?: return null
        if (inst.verificationFailed) return null
        val fromMe = sender in me
        val chatId = when (m) {
            is SetTranscriptBackgroundMessage.Set -> m.chatId
            is SetTranscriptBackgroundMessage.Remove -> m.chatId
        }?.trim()?.takeIf { it.isNotEmpty() }
        val conversation = inst.conversation
        if (chatId != null && ('+' in chatId || '@' in chatId)) {
            if (!fromMe) return null
            val peer = Handles.normalize(chatId) ?: chatId
            if (peer in me) return null
            val members = (listOf(peer) + sender).distinct()
            return BackgroundChat(conversationId(members, me, null), members, null, peer)
        }
        if (chatId != null) {
            val members = ((conversation?.participants ?: emptyList()) + sender).distinct()
            return BackgroundChat(groupConversationId(chatId), members, chatId, null)
        }
        val peer = when {
            !fromMe -> sender
            else -> conversation?.participants?.filter { it !in me }?.distinct()?.singleOrNull()
        } ?: return null
        val members = (listOf(peer) + sender).distinct()
        return BackgroundChat(conversationId(members, me, null), members, null, peer)
    }

    /** The version a background change is as of ([Update.Background.version]). */
    fun backgroundVersion(m: SetTranscriptBackgroundMessage): Long = when (m) {
        is SetTranscriptBackgroundMessage.Set -> m.bid.toLong()
        is SetTranscriptBackgroundMessage.Remove -> m.bid.toLong()
    }

    /**
     * A background change from Apple as the archive takes it: the chat it is for
     * ([backgroundChat]), the picture the ":imessage" process saved for it at [path] or the gradient
     * or dynamic background [style] (neither: the background was taken off), and the row saying who did it
     * ("Bob changed the chat background.", as an iPhone puts it). Ignored when it names no chat.
     */
    fun background(
        inst: MessageInst,
        me: Set<String>,
        nameOf: (String) -> String,
        texts: Texts,
        path: String?,
        style: BackgroundStyle? = null,
        now: Long = System.currentTimeMillis(),
    ): Update {
        val m = (inst.message as? PushChatMessage.SetTranscriptBackground)?.v1 ?: return Update.Ignore
        val chat = backgroundChat(inst, me) ?: return Update.Ignore
        val sender = inst.sender
        val fromMe = sender != null && sender in me
        val who = actor(sender, fromMe, nameOf, texts) ?: return Update.Ignore
        val text = if (path != null || style != null) texts.changedBackground(who) else texts.removedBackground(who)
        val members = (chat.members + listOfNotNull(sender)).distinct()
        val conv = conversationDto(chat.conversationId, members, inst.conversation?.cvName.takeIf { chat.groupGuid != null }, me, nameOf)
        val row = MessageDto(
            id = messageId(inst.id),
            conversationId = chat.conversationId,
            senderId = sender.orEmpty(),
            senderName = if (fromMe || sender == null) "" else nameOf(sender),
            fromMe = fromMe,
            timestampMs = timestampOf(inst, now),
            direction = "system",
            transport = TRANSPORT,
            text = text,
            parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
        )
        return Update.Background(chat.conversationId, chat.groupGuid, chat.peer, backgroundVersion(m), path, Update.Upsert(conv, row), style)
    }

    /** A group's version as Apple counts it (a ULong), as the archive keeps it: never past what a Long holds. */
    fun groupVersionOf(version: ULong): Long = if (version > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else version.toLong()

    /**
     * A group's photo change from Apple ([PushChatMessage.IconChange]) as the archive takes it: the
     * group (by its guid), the photo: the library's description of where it is on Apple's file
     * servers ([file], fetched by the main process once it took the change) or a picture already
     * made at [path] (neither: the photo was taken off), as of the group's version, and the row
     * saying who did it ("Bob changed the group photo.", as an iPhone puts it). Ignored for one
     * nobody can take it from (from no one, or one Apple could not verify) and for anything that is
     * not a group's (no guid). Whether the sender is in the group, and whether its version is one to
     * take, is the main process's to tell (DirectLink).
     */
    fun groupPhoto(
        inst: MessageInst,
        me: Set<String>,
        nameOf: (String) -> String,
        texts: Texts,
        path: String?,
        now: Long = System.currentTimeMillis(),
        file: String = "",
    ): Update {
        val m = (inst.message as? PushChatMessage.IconChange)?.v1 ?: return Update.Ignore
        val sender = inst.sender ?: return Update.Ignore
        if (inst.verificationFailed) return Update.Ignore
        val conversation = inst.conversation ?: return Update.Ignore
        val guid = conversation.senderGuid?.takeIf { it.isNotBlank() } ?: return Update.Ignore
        val who = actor(sender, sender in me, nameOf, texts) ?: return Update.Ignore
        val text = if (path != null || file.isNotEmpty()) texts.changedPhoto(who) else texts.removedPhoto(who)
        val version = groupVersionOf(m.groupVersion)
        val row = groupEvent(inst, conversation, conversation.participants, conversation.cvName, me, nameOf, text, now = now) as? Update.Upsert
            ?: return Update.Ignore
        return Update.GroupPhoto(groupConversationId(guid), guid, version, path, row.copy(groupVersion = version), file)
    }

    /** The longest description of a file kept with a change (a sticker's, a group photo's: the library's are a few hundred characters). */
    const val MAX_FILE_CHARS = 8 * 1024

    /**
     * The library's description ([describe], `saveAttachment`) of the sticker [m] puts on a message,
     * when its picture is on Apple's file servers, for the main process to fetch it once it took the
     * change; "" when it came inside the message (the ":imessage" process writes it aside), has none,
     * or cannot be described.
     */
    fun stickerFile(m: PushChatMessage, describe: (MessageAttachment) -> String): String {
        val attachment = stickerAttachment(m)?.takeIf { it.aType is AttachmentType.Mmcs } ?: return ""
        return describe(attachment).takeIf { it.length <= MAX_FILE_CHARS }.orEmpty()
    }

    /** The library's description of a group's photo on Apple's file servers ([file]), as an attachment's is made; "" when it cannot be described. */
    fun groupPhotoFile(file: MmcsFile, describe: (MessageAttachment) -> String): String =
        describe(MessageAttachment(AttachmentType.Mmcs(listOf(file)), 0UL, GROUP_PHOTO_UTI, GROUP_PHOTO_MIME, GROUP_PHOTO_NAME, false))
            .takeIf { it.length <= MAX_FILE_CHARS }.orEmpty()

    /** How a group's photo is described as a file: a JPEG, as Apple keeps it ("GroupPhotoImage"). */
    private const val GROUP_PHOTO_UTI = "public.jpeg"
    private const val GROUP_PHOTO_MIME = "image/jpeg"
    private const val GROUP_PHOTO_NAME = "GroupPhotoImage.jpg"

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
        val urls = listOfNotNull(d.url, d.originalUrl).map { it.relative }.filter { it.isNotBlank() }
        // A web page's address before any other (which the card does not open).
        val url = urls.firstOrNull(IMessageExtra::isWebAddress) ?: urls.firstOrNull() ?: return null
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
