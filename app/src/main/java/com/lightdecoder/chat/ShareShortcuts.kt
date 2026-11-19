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
 * ("direct share"): the ones the user sent something to from here. Android
 * ranks them itself. Each is a shortcut of the app, so the launcher also
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
        runCatching {
            val side = (ICON_DP * context.resources.displayMetrics.density).toInt()
            val icon = Avatars.drawable(context, title, conversationId, avatarColor, side).toBitmap(side, side)
            val open = RelayIntents.thread(context, conversationId, service = service).setAction(Intent.ACTION_VIEW)
            val shortcut = ShortcutInfoCompat.Builder(context, id(service, conversationId))
                .setShortLabel(title.take(MAX_LABEL))
                .setLongLabel(title)
                .setLongLived(true)
                .setCategories(setOf(CATEGORY))
                .setPerson(Person.Builder().setName(title).build())
                .setIcon(IconCompat.createWithBitmap(icon))
                .setIntent(open)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    fun remove(context: Context, service: ChatService, conversationId: String) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id(service, conversationId))) }
    }

    /** Every conversation of [service]: its archive is gone. */
    fun removeAll(context: Context, service: ChatService) {
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
