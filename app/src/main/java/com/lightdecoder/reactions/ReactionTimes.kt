package com.lightdecoder.reactions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ReactionDto

/** One person's reaction on one message: which emoji, and who put it there. */
data class ReactionKey(val emoji: String, val participantId: String)

/** When a reaction was made, as far as LightDecoder can tell. */
data class ReactionTime(val atMs: Long, val kind: Kind) {
    enum class Kind {
        /** The time it was made: an iPhone's reaction text carries its own. */
        EXACT,

        /** Received while the link was connected, so within moments of being made. */
        SEEN,

        /** Received as the link caught up after being away, so made at that time or before. */
        BY,
    }
}

/**
 * When each reaction on a message was made.
 *
 * Google Messages does not say: a reaction reaches the link as an emoji and
 * the people who put it there, with no time. So the time is the moment the
 * link first saw it on a message it already had ([note]). A reaction that was
 * already there when the message was first seen (history imported, or a
 * message loaded later) has no time here, and the screens say so rather than
 * guess.
 *
 * Kept in a database of its own (reaction_times.db), apart from the archive,
 * and cleared with it. Holds emoji, participant ids and times only: no text,
 * names or numbers.
 */
class ReactionTimes private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val databaseFile = context.applicationContext.getDatabasePath(DB_NAME)

    /**
     * False until a reaction has been timed. Reading and clearing then have
     * nothing to do and must not create the database: [clear] runs inside the
     * archive wipe, which is time-limited when another phone's archive goes.
     */
    private fun exists(): Boolean = databaseFile.exists()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE times (service TEXT NOT NULL, message_id TEXT NOT NULL, emoji TEXT NOT NULL, " +
                "participant TEXT NOT NULL, at_ms INTEGER NOT NULL, kind INTEGER NOT NULL, " +
                "PRIMARY KEY (service, message_id, emoji, participant))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * A message's reactions as they now stand ([after]) next to how they
     * stood before ([before], null when the message is new here). Reactions
     * that appeared are given [nowMs]; reactions that went are forgotten.
     *
     * @param live true when the message came as it happened, false when the
     *   phone re-sent it while the link caught up (the time is then "by").
     */
    @Synchronized
    fun note(service: ChatService, messageId: String, before: List<ReactionDto>?, after: List<ReactionDto>, nowMs: Long, live: Boolean) {
        if (messageId.isEmpty()) return
        val change = diff(before, after) ?: return
        if (change.added.isEmpty() && change.removed.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (key in change.removed) {
                db.delete(
                    "times", "service = ? AND message_id = ? AND emoji = ? AND participant = ?",
                    arrayOf(service.key, messageId, key.emoji, key.participantId),
                )
            }
            val kind = if (live) ReactionTime.Kind.SEEN else ReactionTime.Kind.BY
            for (key in change.added) {
                db.insertWithOnConflict("times", null, ContentValues().apply {
                    put("service", service.key)
                    put("message_id", messageId)
                    put("emoji", key.emoji)
                    put("participant", key.participantId)
                    put("at_ms", nowMs)
                    put("kind", kind.ordinal)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The known times of the reactions on [messageId]. */
    fun timesFor(service: ChatService, messageId: String): Map<ReactionKey, ReactionTime> =
        if (!exists()) emptyMap() else readableDatabase.rawQuery(
            "SELECT emoji, participant, at_ms, kind FROM times WHERE service = ? AND message_id = ?",
            arrayOf(service.key, messageId),
        ).use { c ->
            val out = HashMap<ReactionKey, ReactionTime>()
            while (c.moveToNext()) {
                val kind = ReactionTime.Kind.entries.getOrElse(c.getInt(3)) { ReactionTime.Kind.SEEN }
                out[ReactionKey(c.getString(0), c.getString(1))] = ReactionTime(c.getLong(2), kind)
            }
            out
        }

    /** Forgets everything of [service] (its archive was deleted). */
    @Synchronized
    fun clear(service: ChatService) {
        if (!exists()) return
        writableDatabase.delete("times", "service = ?", arrayOf(service.key))
    }

    /** What changed between two lists of a message's reactions. */
    data class Change(val added: Set<ReactionKey>, val removed: Set<ReactionKey>)

    companion object {
        private const val DB_NAME = "reaction_times.db"
        private const val DB_VERSION = 1

        /**
         * The reactions that appeared and went. Null when [before] is null:
         * a message seen for the first time brings reactions of unknown age,
         * and those get no time.
         */
        fun diff(before: List<ReactionDto>?, after: List<ReactionDto>): Change? {
            if (before == null) return null
            val was = keys(before)
            val now = keys(after)
            return Change(added = now - was, removed = was - now)
        }

        fun keys(reactions: List<ReactionDto>): Set<ReactionKey> =
            reactions.filter { it.emoji.isNotEmpty() }
                .flatMap { r -> r.participantIds.map { ReactionKey(r.emoji, it) } }
                .toSet()

        @Volatile
        private var instance: ReactionTimes? = null

        fun get(context: Context): ReactionTimes =
            instance ?: synchronized(this) {
                instance ?: ReactionTimes(context).also { instance = it }
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
