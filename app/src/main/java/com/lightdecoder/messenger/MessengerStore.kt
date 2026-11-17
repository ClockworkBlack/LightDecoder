package com.lightdecoder.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A conversation as the inbox lists it. */
data class MessengerConversation(
    val key: String,
    val title: String,
    val isGroup: Boolean,
    val lastTimestampMs: Long,
    val lastText: String,
    val lastKind: Int,
    val lastFromMe: Boolean,
    val unread: Int,
)

/** One message of a conversation. */
data class MessengerMessage(
    val id: String,
    val conversationKey: String,
    val sender: String,
    val fromMe: Boolean,
    val text: String,
    val kind: Int,
    val timestampMs: Long,
    val state: Int,
)

/**
 * The Messenger inbox: what Messenger's notifications showed, kept on the
 * phone in the app's private storage and nowhere else. It is not Messenger's
 * history and does not try to be: it starts when notification access is
 * turned on and holds only what arrived as a notification, plus the replies
 * sent from here. The full history is Facebook's page, one button away.
 *
 * It has nothing in common with the other tabs' archives: its own database
 * file, no shared table. Backups are off for the whole app, so it is never
 * copied to a cloud or another device.
 */
class MessengerStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (key TEXT PRIMARY KEY, title TEXT NOT NULL, is_group INTEGER NOT NULL, " +
                "last_ts INTEGER NOT NULL, last_text TEXT NOT NULL, last_kind INTEGER NOT NULL, " +
                "last_from_me INTEGER NOT NULL, unread INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE messages (id TEXT PRIMARY KEY, conv TEXT NOT NULL, sender TEXT NOT NULL, " +
                "from_me INTEGER NOT NULL, text TEXT NOT NULL, kind INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                "state INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX messages_by_conversation ON messages (conv, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Stores what a notification showed. Messages already stored are left as
     * they are (a notification repeats its recent messages each time it is
     * updated).
     *
     * @return how many messages from other people were new.
     */
    @Synchronized
    fun record(captured: CapturedConversation): Int {
        val db = writableDatabase
        var newIncoming = 0
        var newAny = false
        db.beginTransaction()
        try {
            for (message in captured.messages) {
                val id = MessengerCapture.messageId(captured.key, message)
                if (exists(db, id)) continue
                // A reply sent from here comes back in Messenger's notification
                // as the user's own line: that is the same message, now
                // confirmed, not a second one.
                if (message.fromMe && confirmSent(db, captured.key, message, id)) {
                    newAny = true
                    continue
                }
                db.insertWithOnConflict("messages", null, ContentValues().apply {
                    put("id", id)
                    put("conv", captured.key)
                    put("sender", message.sender)
                    put("from_me", if (message.fromMe) 1 else 0)
                    put("text", message.text)
                    put("kind", message.kind)
                    put("ts", message.timestampMs)
                    put("state", if (message.fromMe) STATE_SENT_CONFIRMED else STATE_RECEIVED)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                newAny = true
                if (!message.fromMe) newIncoming++
            }
            if (newAny || !conversationExists(db, captured.key)) {
                refreshConversation(db, captured.key, captured.title, captured.isGroup, addUnread = newIncoming)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return newIncoming
    }

    /**
     * A reply typed here and handed to Messenger's notification. It is
     * "sent, not yet confirmed" until Messenger shows it in its notification
     * (or for good, if Messenger never does).
     */
    @Synchronized
    fun addOutgoing(conversationKey: String, text: String, nowMs: Long): MessengerMessage {
        val message = CapturedMessage("", true, text, MessengerCapture.KIND_TEXT, nowMs)
        val id = "out:" + MessengerCapture.messageId(conversationKey, message)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("messages", null, ContentValues().apply {
                put("id", id)
                put("conv", conversationKey)
                put("sender", "")
                put("from_me", 1)
                put("text", text)
                put("kind", MessengerCapture.KIND_TEXT)
                put("ts", nowMs)
                put("state", STATE_SENT_UNCONFIRMED)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            refreshConversation(db, conversationKey, title = null, isGroup = null, addUnread = 0)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MessengerMessage(id, conversationKey, "", true, text, MessengerCapture.KIND_TEXT, nowMs, STATE_SENT_UNCONFIRMED)
    }

    /** Newest conversation first. */
    fun conversations(): List<MessengerConversation> =
        readableDatabase.rawQuery(
            "SELECT key, title, is_group, last_ts, last_text, last_kind, last_from_me, unread " +
                "FROM conversations ORDER BY last_ts DESC, key", null
        ).use { c ->
            val out = ArrayList<MessengerConversation>(c.count)
            while (c.moveToNext()) {
                out.add(
                    MessengerConversation(
                        c.getString(0), c.getString(1), c.getInt(2) != 0, c.getLong(3),
                        c.getString(4), c.getInt(5), c.getInt(6) != 0, c.getInt(7),
                    )
                )
            }
            out
        }

    fun conversation(key: String): MessengerConversation? = conversations().firstOrNull { it.key == key }

    /** Oldest first. */
    fun messages(conversationKey: String): List<MessengerMessage> =
        readableDatabase.rawQuery(
            "SELECT id, conv, sender, from_me, text, kind, ts, state FROM messages WHERE conv = ? ORDER BY ts, rowid",
            arrayOf(conversationKey),
        ).use { c ->
            val out = ArrayList<MessengerMessage>(c.count)
            while (c.moveToNext()) {
                out.add(
                    MessengerMessage(
                        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) != 0,
                        c.getString(4), c.getInt(5), c.getLong(6), c.getInt(7),
                    )
                )
            }
            out
        }

    @Synchronized
    fun markRead(conversationKey: String) {
        writableDatabase.execSQL("UPDATE conversations SET unread = 0 WHERE key = ?", arrayOf(conversationKey))
    }

    /** Removes one conversation from this phone's copy. Messenger's own history is not touched. */
    @Synchronized
    fun deleteConversation(conversationKey: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", "conv = ?", arrayOf(conversationKey))
            db.delete("conversations", "key = ?", arrayOf(conversationKey))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Removes everything caught. Messenger's own history is not touched. */
    @Synchronized
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("conversations", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun exists(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM messages WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun conversationExists(db: SQLiteDatabase, key: String): Boolean =
        db.rawQuery("SELECT 1 FROM conversations WHERE key = ?", arrayOf(key)).use { it.moveToFirst() }

    /**
     * Turns the oldest unconfirmed reply with the same text, sent shortly
     * before [echo], into the confirmed message Messenger shows.
     */
    private fun confirmSent(db: SQLiteDatabase, conversationKey: String, echo: CapturedMessage, echoId: String): Boolean {
        val pending = db.rawQuery(
            "SELECT id FROM messages WHERE conv = ? AND state = ? AND text = ? AND ts BETWEEN ? AND ? ORDER BY ts LIMIT 1",
            arrayOf(
                conversationKey, STATE_SENT_UNCONFIRMED.toString(), echo.text,
                (echo.timestampMs - ECHO_WINDOW_MS).toString(), (echo.timestampMs + ECHO_WINDOW_MS).toString(),
            ),
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        db.update("messages", ContentValues().apply {
            put("id", echoId)
            put("state", STATE_SENT_CONFIRMED)
            put("ts", echo.timestampMs)
        }, "id = ?", arrayOf(pending))
        return true
    }

    /** Rewrites the conversation's row from its messages; a null [title] or [isGroup] keeps what is stored. */
    private fun refreshConversation(db: SQLiteDatabase, key: String, title: String?, isGroup: Boolean?, addUnread: Int) {
        val existing = db.rawQuery("SELECT title, is_group, unread FROM conversations WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) Triple(it.getString(0), it.getInt(1) != 0, it.getInt(2)) else null
        }
        val last = db.rawQuery(
            "SELECT ts, text, kind, from_me FROM messages WHERE conv = ? ORDER BY ts DESC, rowid DESC LIMIT 1", arrayOf(key)
        ).use { if (it.moveToFirst()) Last(it.getLong(0), it.getString(1), it.getInt(2), it.getInt(3) != 0) else null }
        db.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("key", key)
            put("title", title ?: existing?.first ?: "")
            put("is_group", if (isGroup ?: existing?.second ?: false) 1 else 0)
            put("last_ts", last?.ts ?: 0L)
            put("last_text", last?.text ?: "")
            put("last_kind", last?.kind ?: MessengerCapture.KIND_TEXT)
            put("last_from_me", if (last?.fromMe == true) 1 else 0)
            put("unread", (existing?.third ?: 0) + addUnread)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private class Last(val ts: Long, val text: String, val kind: Int, val fromMe: Boolean)

    companion object {
        private const val DB_NAME = "messenger_inbox.db"
        private const val DB_VERSION = 1

        /** Someone else's message. */
        const val STATE_RECEIVED = 0

        /** A reply handed to Messenger's notification; Messenger has not shown it yet. */
        const val STATE_SENT_UNCONFIRMED = 1

        /** The user's own message as Messenger's notification showed it. */
        const val STATE_SENT_CONFIRMED = 2

        /** How far apart a reply and its echo in the notification can be and still be one message. */
        private const val ECHO_WINDOW_MS = 3 * 60 * 1000L

        @Volatile
        private var instance: MessengerStore? = null

        fun get(context: Context): MessengerStore =
            instance ?: synchronized(this) {
                instance ?: MessengerStore(context).also { instance = it }
            }

        /** Tests only: forget the open database so the next [get] opens the current one. */
        @androidx.annotation.VisibleForTesting
        internal fun resetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
