package com.lightdecoder.ui.chat

import android.content.Context
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.archive.SearchHit
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.direct.GroupPhotos
import java.io.File

/**
 * Where the screens find the picture a conversation is shown by ([Avatars]): an iMessage group's
 * photo ([GroupPhotos]); for one person, their picture as Google Messages has it
 * ([com.lightdecoder.relay.ContactPhotos]), by their number. None: the initials. Runs on IO; never throws.
 */
internal object AvatarPhotos {

    /** [items] with the pictures they are shown by ([ConversationItem.photo]). */
    fun withPhotos(context: Context, items: List<ConversationItem>): List<ConversationItem> {
        if (items.isEmpty()) return items
        val groups = groupPhotos(context)
        val people = com.lightdecoder.relay.ContactPhotos.get(context)
        return items.map { item ->
            val photo = when {
                item.service == ChatService.IMESSAGE && item.row.isGroup -> groups[item.row.id]
                item.row.isGroup -> null
                else -> people.forConversation(item.service, item.row.id) ?: sharedPhoto(context, item.service, item.row.id)
            }
            if (photo == null) item else item.copy(photo = photo.path)
        }
    }

    /** The picture conversation [conversationId] of [service] is shown by, with its [participants] as stored; null for none. */
    fun forConversation(context: Context, service: ChatService, conversationId: String, isGroup: Boolean, participants: List<ParticipantRow>): File? =
        runCatching {
            when {
                service == ChatService.IMESSAGE && isGroup -> GroupPhotos(context).pictureOf(conversationId)
                isGroup -> null
                else -> participants.filter { !it.isMe }.singleOrNull()?.let { com.lightdecoder.relay.ContactPhotos.get(context).forPerson(it) }
                    ?: sharedPhoto(context, service, conversationId)
            }
        }.getOrNull()

    /**
     * For an iMessage chat with one person with no contact picture: the photo they shared and the user
     * took ([com.lightdecoder.chat.SharedProfiles], B6.1); null for anything else.
     */
    private fun sharedPhoto(context: Context, service: ChatService, conversationId: String): File? {
        if (service != ChatService.IMESSAGE || !com.lightdecoder.imessage.direct.DirectArchive.isImessage(conversationId)) return null
        if (com.lightdecoder.imessage.direct.DirectArchive.isGroup(conversationId)) return null
        val dir = com.lightdecoder.chat.SharedProfiles.dirOf(context)
        if (!dir.isDirectory) return null
        return runCatching { com.lightdecoder.chat.SharedProfiles.get(context).photoFor(conversationId.removePrefix(com.lightdecoder.imessage.direct.DirectArchive.PREFIX)) }.getOrNull()
    }

    /** A group's members' pictures ([participants] as stored), by their address's key ([com.lightdecoder.imessage.IMessageAddress.key]). */
    fun members(context: Context, participants: List<ParticipantRow>): Map<String, File> = runCatching {
        val people = com.lightdecoder.relay.ContactPhotos.get(context)
        if (people.isEmpty()) return@runCatching emptyMap()
        participants.filter { !it.isMe }.mapNotNull { p ->
            val address = p.number.ifBlank { p.formattedNumber }.ifBlank { p.id }
            val key = com.lightdecoder.imessage.IMessageAddress.key(address) ?: return@mapNotNull null
            people.forPerson(p)?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The pictures search results are shown by, by [key]. */
    fun forHits(context: Context, hits: List<SearchHit>): Map<String, File> {
        if (hits.isEmpty()) return emptyMap()
        val groups = groupPhotos(context)
        val people = com.lightdecoder.relay.ContactPhotos.get(context)
        val out = HashMap<String, File>()
        for (hit in hits) {
            val k = key(hit.service, hit.conversationId)
            if (k in out) continue
            val photo = groups[hit.conversationId].takeIf { hit.service == ChatService.IMESSAGE } ?: people.forConversation(hit.service, hit.conversationId)
            if (photo != null) out[k] = photo
        }
        return out
    }

    /** A conversation's key among several services' ([forHits]). */
    fun key(service: ChatService, conversationId: String): String = service.key + ":" + conversationId

    private fun groupPhotos(context: Context): Map<String, File> = runCatching { GroupPhotos(context).all() }.getOrDefault(emptyMap())
}
