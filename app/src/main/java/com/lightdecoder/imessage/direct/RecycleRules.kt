package com.lightdecoder.imessage.direct

import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.DeleteTarget
import uniffi.rust_bridge.MoveToRecycleBinMessage
import uniffi.rust_bridge.OperatedChat
import uniffi.rust_bridge.PermanentDeleteMessage
import uniffi.rust_bridge.PushChatMessage
import kotlin.random.Random

/**
 * "Delete on All Devices": the user's devices tell each other of deletes with Apple's recycle bin
 * messages (commands 181/182, sent to the user's own address): a chat moved to Recently Deleted
 * ([PushChatMessage.MoveToRecycleBin]), recovered ([PushChatMessage.RecoverChat]) or deleted for
 * good ([PushChatMessage.PermanentDelete]), and messages deleted.
 *
 * What is taken ([incoming]), only with the setting on, and only when all of these hold:
 * - it is from one of the user's own addresses, and Apple could verify that;
 * - a list of messages is [MAX_MESSAGES] GUIDs at most, each [MAX_GUID_CHARS] at most (any other
 *   and the whole is not taken);
 * - a chat is named well enough to be found here (its members' addresses, a group's id); the main
 *   process takes it only for a chat it has stored ([DirectLink]);
 * - the time a recycle names is clamped to [RETENTION_MS] less a day before now at the earliest and
 *   [DirectArchive.MAX_FUTURE_MS] after at the latest.
 *
 * Neither OpenBubbles checks who sent them. A chat deleted for good is further held to a day in
 * Recently Deleted ([purgeNow]): a remote purge cannot take a chat the moment it was recycled.
 *
 * Pure: no library calls, no archive. Tested in RecycleRulesTest.
 */
object RecycleRules {

    /** The most messages one recycle names (more: not taken). */
    const val MAX_MESSAGES = 500

    /** The longest GUID taken (Apple's are UUIDs). */
    const val MAX_GUID_CHARS = 64

    /** The most members a chat named in one is read with. */
    const val MAX_PARTICIPANTS = 64

    /** How long a chat stays in Recently Deleted, as on an iPhone. */
    const val RETENTION_MS = com.lightdecoder.archive.ArchiveRepository.RETENTION_MS

    /** How long a chat stays in Recently Deleted at least before the user's other device may delete it for good. */
    const val MIN_DELETED_MS = 24L * 60 * 60 * 1000

    /** The request's ops ([DirectWire.RECYCLE]). */
    const val OP_BIN = "bin"
    const val OP_RECOVER = "recover"
    const val OP_PURGE = "purge"
    val OPS = setOf(OP_BIN, OP_RECOVER, OP_PURGE)

    private val GUID = Regex("^[A-Za-z0-9-]{1,$MAX_GUID_CHARS}$")
    private val GROUP_ID = Regex("^[A-Za-z0-9;+._:-]{1,$MAX_GUID_CHARS}$")
    private val NUMBER = Regex("^\\+[0-9]{6,20}$")
    private val EMAIL = Regex("^[^@\\s]{1,200}@[^@\\s]{1,100}\\.[^@\\s]{1,60}$")

    /** Whether [guid] is one a recycle may name: a GUID as Apple makes them. */
    fun isGuid(guid: String?): Boolean = guid != null && GUID.matches(guid)

    /**
     * What a recycle bin message from Apple does, as an archive update; null when [m] is no such
     * message (or is a Send Later one, which [DirectArchive.map] keeps for itself), and
     * [DirectArchive.Update.Ignore] when it is one that is not taken (see the class).
     */
    fun incoming(
        m: PushChatMessage,
        sender: String?,
        verificationFailed: Boolean,
        me: Set<String>,
        enabled: Boolean,
        now: Long,
    ): DirectArchive.Update? {
        val kind = when (m) {
            is PushChatMessage.MoveToRecycleBin, is PushChatMessage.RecoverChat -> m
            is PushChatMessage.PermanentDelete -> m.takeIf { !it.v1.isScheduled } ?: return null
            else -> return null
        }
        if (!enabled || sender == null || sender !in me || verificationFailed) return DirectArchive.Update.Ignore
        return when (kind) {
            is PushChatMessage.MoveToRecycleBin -> {
                val at = clampDate(kind.v1.recoverableDeleteDate, now)
                when (val t = kind.v1.target) {
                    is DeleteTarget.Messages -> messageIds(t.v1)?.let { DirectArchive.Update.Recycle(null, null, it, at) }
                    is DeleteTarget.Chat -> chatOf(t.v1, me)?.let { (id, group) -> DirectArchive.Update.Recycle(id, group, emptyList(), at) }
                }
            }
            is PushChatMessage.RecoverChat -> chatOf(kind.v1, me)?.let { (id, group) -> DirectArchive.Update.Recover(id, group) }
            is PushChatMessage.PermanentDelete -> when (val t = kind.v1.target) {
                is DeleteTarget.Messages -> messageIds(t.v1)?.let { DirectArchive.Update.Purge(null, null, it) }
                is DeleteTarget.Chat -> chatOf(t.v1, me)?.let { (id, group) -> DirectArchive.Update.Purge(id, group, emptyList()) }
            }
            else -> null
        } ?: DirectArchive.Update.Ignore
    }

    /** The archive ids of [guids], or null when there are none, too many, or one is not a GUID. */
    private fun messageIds(guids: List<String>): List<String>? {
        if (guids.isEmpty() || guids.size > MAX_MESSAGES) return null
        if (guids.any { !isGuid(it) }) return null
        return guids.distinct().map(DirectArchive::messageId)
    }

    /**
     * The time a recycle names (ms since 1970, as OpenBubbles sends it), within [RETENTION_MS] less
     * [MIN_DELETED_MS] before [now] (so that retention keeps a chat recycled from elsewhere a day at
     * least, as [purgeNow] does) and [DirectArchive.MAX_FUTURE_MS] after it; [now] for none.
     */
    fun clampDate(date: ULong, now: Long): Long {
        val ms = if (date > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else date.toLong()
        if (ms <= 0) return now
        return clampDeletedAt(ms, now)
    }

    /** [atMs] held within what [clampDate] allows (the main process applies it again to what it is handed). */
    fun clampDeletedAt(atMs: Long, now: Long): Long =
        if (atMs <= 0) now else atMs.coerceIn(now - RETENTION_MS + MIN_DELETED_MS, now + DirectArchive.MAX_FUTURE_MS)

    /**
     * The archive's id of the chat [chat] names, with its group's guid when it has one; null when it
     * names none this phone could have (no other member, a malformed address or group id).
     * Its members come without their scheme (OpenBubbles strips it): it is put back.
     */
    fun chatOf(chat: OperatedChat, me: Set<String>): Pair<String, String?>? {
        if (chat.participants.size > MAX_PARTICIPANTS) return null
        val members = chat.participants.map { handleOf(it) ?: return null }.distinct()
        // A one-to-one chat's guid as group id ("iMessage;-;<address>", as OpenBubbles sends it) names no
        // group: the chat is found by its member.
        val groupId = chat.groupId.trim().takeIf { it.isNotEmpty() && !it.startsWith(ONE_TO_ONE_GUID, ignoreCase = true) }
        val group = groupId?.takeIf { GROUP_ID.matches(it) }
        if (groupId != null && group == null) return null
        val others = members.filter { it !in me }
        if (others.isEmpty()) return null
        val guid = group?.takeIf(::isGroupGuid)
        // A group's chat ("iMessage;+;…") is a group even down to one other person: never the user's
        // one-to-one chat with them. Then found by its guid only (none: not taken).
        if (others.size == 1 && chat.guid.startsWith(GROUP_CHAT_GUID, ignoreCase = true)) {
            return guid?.let { DirectArchive.groupConversationId(it) to it }
        }
        // A chat with one other person by their address (the main process looks for a stored group by
        // the guid it names first); a group by its id, else by its members.
        if (others.size == 1) return DirectArchive.conversationId(others, me, null) to guid
        return DirectArchive.conversationId(others, me, guid) to guid
    }

    private const val ONE_TO_ONE_GUID = "iMessage;-;"
    private const val GROUP_CHAT_GUID = "iMessage;+;"

    /** Whether a chat's group id is a group's guid as LightDecoder keeps them (not "iMessage;-;…"). */
    private fun isGroupGuid(id: String): Boolean = GUID.matches(id)

    /** A member's address as the library writes it ("tel:+1…", "mailto:…"), or null when it is none. */
    fun handleOf(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > DirectWire.MAX_ADDRESS_CHARS) return null
        val lower = s.lowercase()
        val bare = when {
            lower.startsWith("tel:") -> s.substring(4)
            lower.startsWith("mailto:") -> s.substring(7)
            else -> s
        }
        return when {
            NUMBER.matches(bare) -> "tel:$bare"
            EMAIL.matches(bare) -> "mailto:${bare.lowercase()}"
            else -> null
        }
    }

    /**
     * How many days a chat in Recently Deleted since [deletedAt] is kept still at [now] ("29 days", as
     * an iPhone's list says): days begun count, 1 at least, [RETENTION_MS] in days at most (also for
     * one whose time is not known).
     */
    fun daysLeft(deletedAt: Long, now: Long): Int {
        val most = (RETENTION_MS / DAY_MS).toInt()
        if (deletedAt <= 0) return most
        val left = deletedAt + RETENTION_MS - now
        return ((left + DAY_MS - 1) / DAY_MS).toInt().coerceIn(1, most)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** What a remote purge of a chat in Recently Deleted since [deletedAt] does at [now]. */
    sealed class PurgeNow {
        /** Deleted for good now. */
        object Delete : PurgeNow()

        /** Kept, its time moved so that it is deleted at the end of its first day ([deletedAt] to set). */
        data class Defer(val deletedAt: Long) : PurgeNow()
    }

    /**
     * A remote purge of a chat in Recently Deleted since [deletedAt]: there less than [MIN_DELETED_MS],
     * it is kept until that day is over (its time set so that retention takes it then); else it goes.
     * [holdUntil]: the end of its first day as this phone counts it (0: none noted), from when it
     * moved there here, whatever date another device's recycle named ([deletedAt] may be one from
     * weeks before, as that device says it) and whatever an earlier purge deferred it to: a recycle
     * dated long ago and a purge right after it, or two purges, never delete at once a chat that was
     * in the inbox a moment before.
     */
    fun purgeNow(deletedAt: Long, now: Long, holdUntil: Long = 0): PurgeNow {
        val young = (deletedAt > 0 && now - deletedAt < MIN_DELETED_MS) || now < holdUntil
        return if (young) PurgeNow.Defer(now - RETENTION_MS + MIN_DELETED_MS) else PurgeNow.Delete
    }

    /** The end of the first day ([purgeNow]'s `holdUntil`) of a chat in Recently Deleted since [deletedAt], as of [now]. */
    fun holdUntil(deletedAt: Long, now: Long, noted: Long): Long =
        maxOf(noted, if (deletedAt > 0 && now - deletedAt < MIN_DELETED_MS) deletedAt + MIN_DELETED_MS else 0)

    /**
     * Whether a message arriving in a chat in Recently Deleted (since [deletedAt]) brings the chat back:
     * only a new one ([stored] or [tombstoned] it is not), of someone's (not a row of the chat's own),
     * sent after the chat was deleted.
     */
    fun revives(stored: Boolean, tombstoned: Boolean, system: Boolean, timestampMs: Long, deletedAt: Long): Boolean =
        !stored && !tombstoned && !system && timestampMs > deletedAt

    /**
     * The chat [target] as the user's devices know it, as OpenBubbles names it: its members without
     * their scheme (the user not among them), its group id ([DirectWire.Target.groupGuid], else the
     * one-to-one chat's guid [chatGuid], else a new one), and its guid ("iMessage;-;<address>" for one
     * person, "iMessage;+;chat<digits>" for a group, made up as OpenBubbles makes it).
     */
    fun operatedChat(target: DirectWire.Target, from: String, myHandles: Collection<String>, chatGuid: String?, random: Random = Random.Default): OperatedChat {
        val others = target.participants.filter { it != from && it !in myHandles }.distinct()
        val peer = target.peer
        val guid = if (target.isOneToOne && peer != null) "iMessage;-;${Handles.display(peer)}" else "iMessage;+;chat" + (1..18).joinToString("") { random.nextInt(10).toString() }
        val groupId = target.groupGuid ?: chatGuid ?: java.util.UUID(random.nextLong(), random.nextLong()).toString().uppercase()
        return OperatedChat(others.map(Handles::display), groupId, guid, null, null)
    }

    /**
     * What goes for [op] on [target] ([guids]: messages, else the whole chat): to the user's own
     * address alone ([from]), never to anyone else. Throws [IllegalArgumentException] for what cannot
     * go (an op no message is made for, messages to recover).
     */
    fun outgoing(
        target: DirectWire.Target,
        op: String,
        guids: List<String>?,
        from: String,
        myHandles: Collection<String>,
        chatGuid: String?,
        now: Long,
        random: Random = Random.Default,
    ): Pair<ConversationData, PushChatMessage> {
        val toMe = ConversationData(listOf(from), null, null, null)
        if (guids != null) require(guids.isNotEmpty() && guids.size <= MAX_MESSAGES && guids.all(::isGuid)) { "no messages" }
        val chat by lazy { operatedChat(target, from, myHandles, chatGuid, random) }
        val message = when (op) {
            OP_BIN -> PushChatMessage.MoveToRecycleBin(
                MoveToRecycleBinMessage(if (guids != null) DeleteTarget.Messages(guids) else DeleteTarget.Chat(chat), now.toULong()),
            )
            OP_RECOVER -> {
                require(guids == null) { "messages are not recovered" }
                PushChatMessage.RecoverChat(chat)
            }
            OP_PURGE -> PushChatMessage.PermanentDelete(
                PermanentDeleteMessage(if (guids != null) DeleteTarget.Messages(guids) else DeleteTarget.Chat(chat), false),
            )
            else -> throw IllegalArgumentException("no such op")
        }
        return toMe to message
    }
}
