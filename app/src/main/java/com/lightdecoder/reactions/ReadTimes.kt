package com.lightdecoder.reactions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import com.lightdecoder.chat.ChatService

/**
 * When each of the user's messages was read.
 *
 * Google Messages does not say: a message's status turns to "read" with no
 * time. So the time is the moment the link saw that happen ([note]), as for
 * reactions ([ReactionTimes]): within moments while connected, "at or before"
 * when it came in as the link caught up. A message that was already read
 * when it was first loaded has no time here, and the screen says so.
 *
 * A database of its own (read_times.db), cleared with the archive. Message
 * ids and times only. Like [ReactionTimes] it is not created until there is
 * something to keep ([clear] runs inside the time-limited archive wipe).
 */
class ReadTimes private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val databaseFile = context.applicationContext.getDatabasePath(DB_NAME)

    private fun exists(): Boolean = databaseFile.exists()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE times (service TEXT NOT NULL, message_id TEXT NOT NULL, at_ms INTEGER NOT NULL, " +
                "kind INTEGER NOT NULL, PRIMARY KEY (service, message_id))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * [messageId] was just seen to turn to read. The first time stays: a
     * later repeat of the same status does not move it.
     *
     * @param live false when the phone re-sent the message while the link
     *   caught up (the time is then "at or before").
     */
    @Synchronized
    fun note(service: ChatService, messageId: String, nowMs: Long, live: Boolean) {
        if (messageId.isEmpty()) return
        writableDatabase.insertWithOnConflict("times", null, ContentValues().apply {
            put("service", service.key)
            put("message_id", messageId)
            put("at_ms", nowMs)
            put("kind", (if (live) ReactionTime.Kind.SEEN else ReactionTime.Kind.BY).ordinal)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** When [messageId] was read, or null when that is not known. */
    fun timeFor(service: ChatService, messageId: String): ReactionTime? {
        if (!exists()) return null
        return readableDatabase.rawQuery(
            "SELECT at_ms, kind FROM times WHERE service = ? AND message_id = ?", arrayOf(service.key, messageId),
        ).use { c ->
            if (!c.moveToFirst()) null
            else ReactionTime(c.getLong(0), ReactionTime.Kind.entries.getOrElse(c.getInt(1)) { ReactionTime.Kind.SEEN })
        }
    }

    /** Forgets everything of [service] (its archive was deleted). */
    @Synchronized
    fun clear(service: ChatService) {
        if (!exists()) return
        writableDatabase.delete("times", "service = ?", arrayOf(service.key))
    }

    companion object {
        private const val DB_NAME = "read_times.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: ReadTimes? = null

        fun get(context: Context): ReadTimes =
            instance ?: synchronized(this) {
                instance ?: ReadTimes(context).also { instance = it }
            }

        /** Tests only: forget the open database so the next [get] opens the current one. */
        @VisibleForTesting
        internal fun resetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
