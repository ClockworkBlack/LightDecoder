package com.lightdecoder.chat

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.Avatars

/**
 * The conversations Android offers in the top row of its share sheet
 * ("direct share"): the newest [MAX_PUBLISHED] of the conversation list, in
 * its order ([publishInbox]), and the ones the user sent something to from
 * here ([push]). Each is a shortcut of the app, so the launcher also
 * lists them when the app's icon is held; they carry the conversation's
 * title and nothing of its messages, and go when the conversation or the
 * service's archive is deleted.
 */
object ShareShortcuts {
    /** The category res/xml/shortcuts.xml ties to the share screen. */
    const val CATEGORY = "com.lightdecoder.category.SHARE_TARGET"

    private const val PREFIX = "chat:"
    private const val ICON_DP = 72
    private const val MAX_LABEL = 40

    /** How many of the conversation list's newest conversations are offered. */
    const val MAX_PUBLISHED = 8

    /** One conversation as it is offered. */
    private data class Entry(
        val service: ChatService,
        val conversationId: String,
        val title: String,
        val avatarColor: String,
        val pinned: Boolean,
        val timestampMs: Long,
    )

    /** By tab: its newest conversations as last offered, and every conversation its list then had. */
    private val offered = HashMap<ChatService, Pair<List<Entry>, Set<String>>>()

    /** What was last handed to Android, so an unchanged list is not handed over again. */
    private var published: List<Entry>? = null

    /**
     * The conversation list of [tab] as it now is ([items], in its order: the Messages tab's with
     * its iMessage chats folded in): its newest [MAX_PUBLISHED] are offered in the share sheet,
     * with the other tabs' newest, in the list's order (pinned first, then the newest). One that
     * left the list (deleted) is no longer offered. Runs on IO; never throws. An unchanged list
     * hands Android nothing, so calling it on every reload of the list costs nothing.
     */
    fun publishInbox(context: Context, tab: ChatService, items: List<com.lightdecoder.ui.chat.ConversationItem>) {
        val entries = items.asSequence()
            .filter { !it.row.readOnly && it.title.isNotBlank() && it.row.id.isNotEmpty() }
            .take(MAX_PUBLISHED)
            .map { Entry(it.service, it.row.id, it.title, it.row.avatarColor, it.row.pinned, it.row.lastMessageTs) }
            .toList()
        val ids = items.map { id(it.service, it.row.id) }.toSet()
        val (top, gone) = synchronized(offered) {
            val before = offered[tab]
            offered[tab] = entries to ids
            val gone = before?.second.orEmpty() - ids
            val top = offered.values.flatMap { it.first }
                .sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.timestampMs })
                .take(MAX_PUBLISHED)
            if (top == published && gone.isEmpty()) return
            published = top
            top to gone
        }
        runCatching {
            if (gone.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, gone.toList())
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                top.mapIndexed { rank, e -> shortcut(context, e.service, e.conversationId, e.title, e.avatarColor, rank) },
            )
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        synchronized(offered) {
            offered.clear()
            published = null
        }
    }

    fun id(service: ChatService, conversationId: String): String = "$PREFIX${service.key}:$conversationId"

    /** The conversation a shortcut id names; null for anything that is not one of [id]'s. */
    fun parse(id: String?): Pair<ChatService, String>? {
        val rest = id?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val key = rest.substringBefore(':', "")
        val conversationId = rest.substringAfter(':', "")
        val service = ChatService.entries.firstOrNull { it.key == key } ?: return null
        return if (conversationId.isEmpty()) null else service to conversationId
    }

    /** Offers the conversation for sharing from now on. Never throws. */
    fun push(context: Context, service: ChatService, conversationId: String, title: String, avatarColor: String) {
        if (title.isBlank() || conversationId.isEmpty()) return
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context, service, conversationId, title, avatarColor, 0)) }
    }

    private fun shortcut(context: Context, service: ChatService, conversationId: String, title: String, avatarColor: String, rank: Int): ShortcutInfoCompat {
        val side = (ICON_DP * context.resources.displayMetrics.density).toInt()
        val icon = Avatars.drawable(context, title, conversationId, avatarColor, side).toBitmap(side, side)
        val open = RelayIntents.thread(context, conversationId, service = service).setAction(Intent.ACTION_VIEW)
        return ShortcutInfoCompat.Builder(context, id(service, conversationId))
            .setShortLabel(title.take(MAX_LABEL))
            .setLongLabel(title)
            .setLongLived(true)
            .setRank(rank)
            .setCategories(setOf(CATEGORY))
            .setPerson(Person.Builder().setName(title).build())
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(open)
            .build()
    }

    fun remove(context: Context, service: ChatService, conversationId: String) {
        // The list's next publish hands its newest over again, without this one.
        synchronized(offered) { published = null }
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id(service, conversationId))) }
    }

    /** Every conversation of [service]: its archive is gone. */
    fun removeAll(context: Context, service: ChatService) {
        synchronized(offered) {
            offered.remove(service)
            published = null
        }
        runCatching {
            val all = ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
            )
            val ids = all.map { it.id }.filter { parse(it)?.first == service }
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }
    }
}
