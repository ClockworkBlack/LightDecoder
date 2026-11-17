package com.lightdecoder.mail

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * What the Gmail tab knows about each email beyond what the conversation
 * archive holds: where it is on the server (for attachments and "read"),
 * and its headers (for threading and for replying in the thread). Plus the
 * new conversations started here that have no email yet ([Draft]).
 * App-private, excluded from backup, deleted with the account.
 */
internal class MailIndex(context: Context, name: String = NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    data class Record(
        val id: String,
        val thread: String,
        val location: MailBridge.Location,
        val messageId: String,
        val references: List<String>,
        val subject: String,
        val from: MailBridge.Address,
        val to: List<MailBridge.Address>,
        val cc: List<MailBridge.Address>,
        val dateMs: Long,
        val seen: Boolean,
    )

    /** A conversation started here: who it goes to, its subject, and the Message-ID its first email will have. */
    data class Draft(val thread: String, val messageId: String, val to: List<MailBridge.Address>, val subject: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msgs (id TEXT PRIMARY KEY, thread TEXT NOT NULL, folder TEXT NOT NULL, uidvalidity INTEGER NOT NULL, " +
                "uid INTEGER NOT NULL, header_id TEXT NOT NULL, refs TEXT NOT NULL, subject TEXT NOT NULL, from_addr TEXT NOT NULL, " +
                "from_name TEXT NOT NULL, to_addrs TEXT NOT NULL, cc_addrs TEXT NOT NULL, date_ms INTEGER NOT NULL, seen INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX msgs_header ON msgs(header_id)")
        db.execSQL("CREATE INDEX msgs_thread ON msgs(thread, date_ms)")
        db.execSQL("CREATE TABLE drafts (thread TEXT PRIMARY KEY, header_id TEXT NOT NULL, to_addrs TEXT NOT NULL, subject TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun put(r: Record) {
        writableDatabase.insertWithOnConflict(
            "msgs", null,
            ContentValues().apply {
                put("id", r.id)
                put("thread", r.thread)
                put("folder", r.location.folder)
                put("uidvalidity", r.location.uidValidity)
                put("uid", r.location.uid)
                put("header_id", r.messageId)
                put("refs", r.references.joinToString(" "))
                put("subject", r.subject)
                put("from_addr", r.from.email)
                put("from_name", r.from.name)
                put("to_addrs", encode(r.to))
                put("cc_addrs", encode(r.cc))
                put("date_ms", r.dateMs)
                put("seen", if (r.seen) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun record(id: String): Record? = query("SELECT * FROM msgs WHERE id = ?", arrayOf(id)).firstOrNull()

    /** The thread of the email with this Message-ID header, if it is known. */
    fun threadOfHeader(messageId: String): String? {
        if (messageId.isEmpty()) return null
        readableDatabase.rawQuery("SELECT thread FROM msgs WHERE header_id = ? LIMIT 1", arrayOf(messageId)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        readableDatabase.rawQuery("SELECT thread FROM drafts WHERE header_id = ? LIMIT 1", arrayOf(messageId)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** A thread's emails, oldest first. */
    fun thread(thread: String): List<Record> =
        query("SELECT * FROM msgs WHERE thread = ? ORDER BY date_ms, uid", arrayOf(thread))

    fun unseenIn(thread: String): List<Record> =
        query("SELECT * FROM msgs WHERE thread = ? AND seen = 0", arrayOf(thread))

    fun markSeen(thread: String) {
        writableDatabase.update("msgs", ContentValues().apply { put("seen", 1) }, "thread = ?", arrayOf(thread))
    }

    /** Everyone this account has exchanged email with, most recent first: email → name. */
    fun people(me: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        readableDatabase.rawQuery("SELECT from_addr, from_name, to_addrs, cc_addrs FROM msgs ORDER BY date_ms DESC LIMIT 2000", null).use { c ->
            while (c.moveToNext()) {
                val all = listOf(MailBridge.Address(c.getString(0), c.getString(1))) + decode(c.getString(2)) + decode(c.getString(3))
                for (a in all) if (a.email.isNotEmpty() && !a.email.equals(me, true)) {
                    if (out[a.email].isNullOrEmpty()) out[a.email] = a.name
                }
            }
        }
        return out
    }

    fun putDraft(d: Draft) {
        writableDatabase.insertWithOnConflict(
            "drafts", null,
            ContentValues().apply {
                put("thread", d.thread)
                put("header_id", d.messageId)
                put("to_addrs", encode(d.to))
                put("subject", d.subject)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun draft(thread: String): Draft? =
        readableDatabase.rawQuery("SELECT thread, header_id, to_addrs, subject FROM drafts WHERE thread = ?", arrayOf(thread)).use { c ->
            if (c.moveToFirst()) Draft(c.getString(0), c.getString(1), decode(c.getString(2)), c.getString(3)) else null
        }

    fun clear() {
        writableDatabase.delete("msgs", null, null)
        writableDatabase.delete("drafts", null, null)
    }

    fun deleteThread(thread: String) {
        writableDatabase.delete("msgs", "thread = ?", arrayOf(thread))
        writableDatabase.delete("drafts", "thread = ?", arrayOf(thread))
    }

    private fun query(sql: String, args: Array<String>): List<Record> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Record>()
            while (c.moveToNext()) {
                out += Record(
                    id = c.getString(c.getColumnIndexOrThrow("id")),
                    thread = c.getString(c.getColumnIndexOrThrow("thread")),
                    location = MailBridge.Location(
                        c.getString(c.getColumnIndexOrThrow("folder")),
                        c.getLong(c.getColumnIndexOrThrow("uidvalidity")),
                        c.getLong(c.getColumnIndexOrThrow("uid")),
                    ),
                    messageId = c.getString(c.getColumnIndexOrThrow("header_id")),
                    references = c.getString(c.getColumnIndexOrThrow("refs")).split(' ').filter { it.isNotEmpty() },
                    subject = c.getString(c.getColumnIndexOrThrow("subject")),
                    from = MailBridge.Address(c.getString(c.getColumnIndexOrThrow("from_addr")), c.getString(c.getColumnIndexOrThrow("from_name"))),
                    to = decode(c.getString(c.getColumnIndexOrThrow("to_addrs"))),
                    cc = decode(c.getString(c.getColumnIndexOrThrow("cc_addrs"))),
                    dateMs = c.getLong(c.getColumnIndexOrThrow("date_ms")),
                    seen = c.getInt(c.getColumnIndexOrThrow("seen")) != 0,
                )
            }
            out
        }

    private fun encode(list: List<MailBridge.Address>): String =
        JSONArray().apply { list.forEach { put(JSONArray().put(it.email).put(it.name)) } }.toString()

    private fun decode(text: String): List<MailBridge.Address> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).map { i -> a.getJSONArray(i).let { MailBridge.Address(it.optString(0), it.optString(1)) } }
    }.getOrDefault(emptyList())

    companion object {
        const val NAME = "mail-index.db"
        private const val VERSION = 1
    }
}
