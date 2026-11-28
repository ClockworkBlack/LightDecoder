package com.lightdecoder.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.lightdecoder.BuildConfig
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ArchiveRestoreHold
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.ChatService
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The user's own backup of their chats, to a file they pick, and its restore. Runs only when the
 * user asks for it (Back up chats / Restore chats), never at app start.
 *
 * In a backup: every service's message archive ([ArchiveDb], one database each) as of the last
 * write that finished ([ArchiveRepository.snapshotTo]), and the media files its rows point to in
 * the archive's own media and outbox folders ([MediaCache]), each up to [MAX_MEDIA_FILE_BYTES].
 * Not in it: any sign-in or pairing (Google Messages, WhatsApp, Gmail, Discord), the iMessage
 * registration and library state (tied to this phone's Keystore), Keystore material, passwords,
 * and the link-health row of an archive (it names the paired account). Queued sends are kept,
 * as failed (their bubbles too, with Retry): a restore never sends anything by itself. What was
 * deleted on the phone is not in it either: the copy is rewritten so no freed space keeps it.
 *
 * The ZIP inside ([BackupCipher] encrypts it) holds manifest.json first ([BackupManifest]), then
 * "db/<archive file>" for each archive, then "media/<folder>/<file>".
 *
 * A restore checks the whole file (every chunk authenticated, every entry name and size, each
 * database's integrity) in a work folder before anything on the phone changes, then swaps the
 * files by renaming, with every archive closed ([ArchiveRepository.closeAllForRestore]); the app
 * must then restart ([AppRestart]).
 */
object ChatBackup {

    const val MAX_MEDIA_FILE_BYTES = 100L shl 20
    const val MAX_DB_BYTES = 8L shl 30
    const val MAX_MANIFEST_BYTES = 1 shl 20
    const val MAX_TOTAL_BYTES = 64L shl 30
    const val MAX_ENTRIES = 2_000_000
    const val RESTORE_LOCK_TIMEOUT_MS = 20_000L

    /** What a restore leaves free on the phone, at the least, while it unpacks. */
    const val FREE_SPACE_MARGIN_BYTES = 200L shl 20

    /** Free bytes where a restore unpacks (tests replace it). */
    @Volatile
    internal var usableSpace: (File) -> Long = { it.usableSpace }

    const val MANIFEST = "manifest.json"
    const val DB_PREFIX = "db/"
    const val MEDIA_PREFIX = "media/"
    const val FILE_EXTENSION = "ldbackup"
    const val MIME = "application/octet-stream"
    private const val WORK_PREFIX = "chat-backup-"
    private const val NEXT = ".restore-new"
    private const val OLD = ".restore-old"

    /** In filesDir while a restore swaps files: if it is there, that swap was cut off (see [recoverInterrupted]). */
    internal const val PENDING_MARKER = "chat-restore-pending"
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    /** A media file's name as the archive makes them (hashes and ids): no path, no leading dot. */
    private val SAFE_FILE = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")

    /** The archive file names, and whose they are. */
    private val ARCHIVES: Map<String, ChatService> = ChatService.entries.associateBy { ArchiveDb.nameFor(it) }

    /** A service's folders whose files a backup may hold. */
    fun folderNames(service: ChatService) = listOf(MediaCache.mediaDirName(service), MediaCache.outboxDirName(service))

    /**
     * The iMessage chats' backgrounds (their pictures and which chat has which), under this name in a
     * backup: they are no rows of an archive, and an iPhone does not send a chat's background again,
     * so without them a restored phone and the other side's would show different ones for good.
     */
    const val BACKGROUNDS_FOLDER = "imessage-backgrounds"

    /** Where [BACKGROUNDS_FOLDER]'s files live on the phone. */
    private fun backgroundsDir(app: Context): File = com.lightdecoder.imessage.direct.ChatBackgrounds.dir(app)

    private val FOLDERS: Set<String> = ChatService.entries.flatMap(::folderNames).toSet() + BACKGROUNDS_FOLDER

    /** Held by the one backup or restore of this process that may run (from start to end). */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun claim() {
        if (!running.compareAndSet(false, true)) throw BackupException(BackupException.Reason.BUSY, "another backup or restore is running")
    }

    private fun unclaim() = running.set(false)

    /** Tests only: as if no backup or restore ran in this process. */
    internal fun resetForTesting() {
        unclaim()
        usableSpace = { it.usableSpace }
    }

    fun interface Progress {
        fun update(done: Long, total: Long)
    }

    /** Throws (a CancellationException) when the user cancelled. */
    fun interface Cancel {
        fun check()
    }

    /** "LightDecoder-backup-2026-11-24.ldbackup". */
    fun defaultFileName(nowMs: Long = System.currentTimeMillis()): String =
        "LightDecoder-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs)) + "." + FILE_EXTENSION

    // --- backup ----------------------------------------------------------------------------

    private class MediaFile(val file: File, val entry: String)

    private class Snapshot(
        val service: ChatService,
        val name: String,
        val copy: File,
        val version: Int,
        val conversations: Int,
        val messages: Int,
        val files: List<MediaFile>,
    )

    /**
     * Writes an encrypted backup of every archive to [out], and closes it. On any failure or
     * cancellation [out] is closed without the final chunk, so what was written can never be
     * restored; the caller deletes it. [passphrase] is not kept (the caller clears it). Only one
     * backup or restore runs at a time ([BackupException.Reason.BUSY] otherwise).
     */
    @Throws(IOException::class)
    fun backup(
        context: Context,
        out: OutputStream,
        passphrase: CharArray,
        progress: Progress = Progress { _, _ -> },
        cancel: Cancel = Cancel {},
        params: BackupCipher.Params = BackupCipher.newParams(),
    ): BackupManifest {
        val app = context.applicationContext
        var sealed: BackupCipher.EncryptingOutputStream? = null
        try {
            claim()
        } catch (e: BackupException) {
            runCatching { out.close() }
            throw e
        }
        try {
            return backupClaimed(app, out, passphrase, progress, cancel, params) { sealed = it }
        } catch (e: Throwable) {
            // Failed before the encrypted stream was made (it closes [out] itself otherwise).
            if (sealed == null) runCatching { out.close() }
            throw e
        } finally {
            unclaim()
        }
    }

    private fun backupClaimed(
        app: Context,
        out: OutputStream,
        passphrase: CharArray,
        progress: Progress,
        cancel: Cancel,
        params: BackupCipher.Params,
        onSealed: (BackupCipher.EncryptingOutputStream) -> Unit,
    ): BackupManifest {
        val work = newWorkDir(app)
        try {
            var tooLarge = 0
            var missing = 0
            val snapshots = ArrayList<Snapshot>()
            for (service in ChatService.entries) {
                cancel.check()
                val repo = ArchiveRepository.get(app, service)
                val name = ArchiveDb.nameFor(service)
                val copy = File(work, name)
                if (!repo.snapshotTo(copy) { cancel.check() }) continue
                cancel.check()
                val info = prepareSnapshot(copy)
                val folders = listOf(repo.mediaDirectory(), repo.outboxDirectory()).map { canonical(it) }
                val files = LinkedHashMap<String, MediaFile>()
                for (path in info.paths) {
                    val file = canonical(File(path))
                    val parent = file.parentFile ?: continue
                    if (parent !in folders || !SAFE_FILE.matches(file.name) || file.name.endsWith(MediaCache.PART_SUFFIX)) continue
                    when {
                        !file.isFile -> missing++
                        file.length() > MAX_MEDIA_FILE_BYTES -> tooLarge++
                        else -> (MEDIA_PREFIX + parent.name + "/" + file.name)
                            .let { files.getOrPut(it) { MediaFile(file, it) } }
                    }
                }
                if (service == ChatService.IMESSAGE) {
                    // The chats' backgrounds go with the iMessage archive.
                    for (file in backgroundsDir(app).listFiles().orEmpty()) {
                        if (!file.isFile || !SAFE_FILE.matches(file.name) || file.name.endsWith(".tmp") || file.length() > MAX_MEDIA_FILE_BYTES) continue
                        (MEDIA_PREFIX + BACKGROUNDS_FOLDER + "/" + file.name).let { files.getOrPut(it) { MediaFile(file, it) } }
                    }
                }
                snapshots += Snapshot(service, name, copy, info.version, info.conversations, info.messages, files.values.toList())
            }
            val manifest = BackupManifest(
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                createdAtMs = System.currentTimeMillis(),
                schemaVersion = snapshots.maxOfOrNull { it.version } ?: ArchiveDb.VERSION,
                archives = snapshots.map {
                    BackupManifest.Archive(it.service.key, it.name, it.version, it.conversations, it.messages, it.files.size)
                },
                skippedTooLarge = tooLarge,
                missingFiles = missing,
            )
            val total = snapshots.sumOf { s -> s.copy.length() + s.files.sumOf { it.file.length() } }
            var done = 0L
            val step: (Int) -> Unit = { n ->
                done += n
                progress.update(done, total)
            }
            val sealed = BackupCipher.EncryptingOutputStream(out, passphrase, params).also(onSealed)
            val zip = ZipOutputStream(KeepOpen(sealed)).apply { setLevel(Deflater.BEST_SPEED) }
            var ok = false
            try {
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (s in snapshots) {
                    zip.putNextEntry(ZipEntry(DB_PREFIX + s.name))
                    FileInputStream(s.copy).use { copy(it, zip, Long.MAX_VALUE, cancel, step) }
                    zip.closeEntry()
                }
                for (s in snapshots) for (m in s.files) {
                    cancel.check()
                    // Deleted since (no longer used): left out. Once open, it reads whole even if deleted.
                    val input = try {
                        FileInputStream(m.file)
                    } catch (e: FileNotFoundException) {
                        continue
                    }
                    input.use {
                        zip.putNextEntry(ZipEntry(m.entry))
                        copy(it, zip, MAX_MEDIA_FILE_BYTES, cancel, step)
                        zip.closeEntry()
                    }
                }
                zip.close()
                sealed.close()
                ok = true
            } finally {
                if (!ok) {
                    sealed.abort()
                    runCatching { zip.close() }
                }
            }
            return manifest
        } finally {
            work.deleteRecursively()
        }
    }

    private class SnapshotInfo(val version: Int, val conversations: Int, val messages: Int, val paths: Set<String>)

    /**
     * Makes the copy [file] a database on its own (write-ahead log folded in, no journal), with
     * queued sends (and their bubbles) marked failed, the link-health row (it names the paired
     * account) gone, nothing left of what was deleted before ([scrub]), and checked.
     */
    private fun prepareSnapshot(file: File): SnapshotInfo {
        val db = try {
            open(file)
        } catch (e: SQLiteException) {
            throw BackupException(BackupException.Reason.LOCAL_ARCHIVE_DAMAGED, file.name, e)
        }
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            // What is deleted here is overwritten, not just unlinked. (No VACUUM: it may renumber the
            // messages' rowids, which the search index is keyed by.)
            db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
            if (hasTable(db, "outbox_queue")) db.execSQL("UPDATE outbox_queue SET status = 'FAILED' WHERE status <> 'SENT' AND status <> 'FAILED'")
            // Not sent yet: after a restore it shows as failed, with Retry, rather than waiting for good.
            if (hasColumn(db, "messages", "local_state")) {
                db.execSQL(
                    "UPDATE messages SET local_state = ? WHERE local_state IN (?, ?)",
                    arrayOf<Any>(LocalState.FAILED, LocalState.PENDING, LocalState.SENDING),
                )
            }
            if (hasTable(db, "connection_health")) db.execSQL("DELETE FROM connection_health")
            scrub(db, file)
            if (!integrityOk(db)) throw BackupException(BackupException.Reason.LOCAL_ARCHIVE_DAMAGED, file.name)
            val paths = HashSet<String>()
            for ((table, column) in PATH_COLUMNS) {
                if (!hasColumn(db, table, column)) continue
                db.rawQuery("SELECT DISTINCT $column FROM $table WHERE $column <> ''", null).use { c ->
                    while (c.moveToNext()) c.getString(0)?.let(paths::add)
                }
            }
            return SnapshotInfo(db.version, count(db, "conversations"), count(db, "messages"), paths)
        } catch (e: SQLiteException) {
            throw BackupException(BackupException.Reason.LOCAL_ARCHIVE_DAMAGED, file.name, e)
        } finally {
            db.close()
            sidecars(file).forEach { it.delete() }
        }
    }

    /**
     * Leaves nothing in the copy [db] (at [file]) of what was deleted on the phone before: the live
     * archive frees deleted rows without overwriting them, so their text would still be in free
     * space. With secure_delete on: the search index is merged (dropping deleted words), every
     * table is copied out and back with the same rowids (so no row moves and the search index stays
     * right; what was freed inside a page goes), and every free page is overwritten with zeros.
     */
    private fun scrub(db: SQLiteDatabase, file: File) {
        val tables = db.rawQuery("SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1).orEmpty()) }
        }
        for ((name, sql) in tables) {
            val upper = sql.uppercase(Locale.ROOT)
            if (upper.startsWith("CREATE VIRTUAL TABLE") && (upper.contains("FTS4") || upper.contains("FTS3"))) {
                val q = quote(name)
                db.execSQL("INSERT INTO $q($q) VALUES('optimize')")
            }
        }
        val side = File(file.path + ".scrub")
        deleteAny(side)
        // Made here: the copy is opened without the right to create files, and an attached one inherits that.
        if (!side.createNewFile()) throw IOException("no scrub file")
        db.execSQL("ATTACH DATABASE ? AS ld_scrub", arrayOf<Any>(side.path))
        try {
            for ((name, sql) in tables) {
                val upper = sql.uppercase(Locale.ROOT)
                if (upper.startsWith("CREATE VIRTUAL TABLE")) continue
                val q = quote(name)
                val columns = db.rawQuery("PRAGMA main.table_info($q)", null).use { c ->
                    buildList { while (c.moveToNext()) add(quote(c.getString(c.getColumnIndexOrThrow("name")))) }
                }.joinToString(", ")
                if (columns.isEmpty()) continue
                val withoutRowid = upper.replace(Regex("\\s+"), " ").contains("WITHOUT ROWID")
                db.beginTransaction()
                try {
                    db.execSQL("DROP TABLE IF EXISTS ld_scrub.t")
                    if (withoutRowid) {
                        db.execSQL("CREATE TABLE ld_scrub.t AS SELECT $columns FROM main.$q")
                        db.execSQL("DELETE FROM main.$q")
                        db.execSQL("INSERT INTO main.$q ($columns) SELECT $columns FROM ld_scrub.t")
                    } else {
                        db.execSQL("CREATE TABLE ld_scrub.t AS SELECT rowid AS ld_rowid, $columns FROM main.$q")
                        db.execSQL("DELETE FROM main.$q")
                        db.execSQL("INSERT INTO main.$q (rowid, $columns) SELECT ld_rowid, $columns FROM ld_scrub.t")
                    }
                    db.execSQL("DROP TABLE ld_scrub.t")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } finally {
            runCatching { db.execSQL("DETACH DATABASE ld_scrub") }
            deleteAny(side)
            sidecars(side).forEach { it.delete() }
        }
        // Free pages: taken up by zeros (a new row reuses each of them), then freed again.
        val free = pragmaLong(db, "freelist_count")
        if (free > 0) {
            var left = free * pragmaLong(db, "page_size")
            db.execSQL("DROP TABLE IF EXISTS ld_scrub_free")
            db.execSQL("CREATE TABLE ld_scrub_free (x BLOB)")
            while (left > 0) {
                val n = minOf(left, SCRUB_BLOB_BYTES)
                db.execSQL("INSERT INTO ld_scrub_free (x) VALUES (zeroblob($n))")
                left -= n
            }
            db.execSQL("DROP TABLE ld_scrub_free")
        }
    }

    private const val SCRUB_BLOB_BYTES = 16L shl 20

    private fun pragmaLong(db: SQLiteDatabase, name: String): Long =
        db.rawQuery("PRAGMA $name", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    /** Where rows name files on the phone. */
    private val PATH_COLUMNS = listOf(
        "media" to "local_path", "media" to "thumb_path", "contacts" to "avatar_path", "outbox_queue" to "media_local_path",
    )

    // --- restore ---------------------------------------------------------------------------

    /**
     * A backup that decrypted and checked out, waiting in a work folder for [applyRestore]. While
     * it is open no other backup or restore can start; [close] deletes it and lets them.
     */
    class StagedRestore internal constructor(val manifest: BackupManifest, internal val dir: File) : Closeable {
        private val open = java.util.concurrent.atomic.AtomicBoolean(true)

        internal val isOpen get() = open.get()

        internal fun deleteFiles() {
            dir.deleteRecursively()
        }

        /** Deletes the files but keeps every other backup and restore out (the app restarts next). */
        internal fun closeKeepingClaim() {
            deleteFiles()
            open.set(false)
        }

        override fun close() {
            deleteFiles()
            if (open.compareAndSet(true, false)) unclaim()
        }
    }

    /**
     * Decrypts and checks the backup in [input] (closed here) into a work folder, without
     * touching anything on the phone: every chunk authenticated, the file complete, only the
     * entries a backup has (no path out of the folder, sizes capped), each archive's schema not
     * newer than this app's, and each database passing integrity_check. [totalBytes] is the
     * file's size for [progress], or -1. What it unpacks may not leave less than
     * [FREE_SPACE_MARGIN_BYTES] free ([BackupException.Reason.NO_SPACE]). Only one backup or
     * restore runs at a time ([BackupException.Reason.BUSY]); a restore from here until the
     * [StagedRestore] is applied or closed.
     */
    @Throws(IOException::class)
    fun prepareRestore(
        context: Context,
        input: InputStream,
        passphrase: CharArray,
        progress: Progress = Progress { _, _ -> },
        cancel: Cancel = Cancel {},
        totalBytes: Long = -1,
    ): StagedRestore {
        val app = context.applicationContext
        try {
            claim()
        } catch (e: BackupException) {
            runCatching { input.close() }
            throw e
        }
        var ok = false
        var work: File? = null
        try {
            work = newWorkDir(app)
            val counted = CountingInput(input) { progress.update(it, totalBytes) }
            BackupCipher.DecryptingInputStream(counted, passphrase).use { sealed ->
                val manifest = readEntries(ZipInputStream(sealed), work, cancel)
                cancel.check()
                sealed.drainAndVerify()
                for (archive in manifest.archives) {
                    cancel.check()
                    val db = File(work, DB_PREFIX + archive.file)
                    if (!db.isFile) throw invalid("no database for ${archive.service}")
                    checkAndRelocate(app, db, ARCHIVES.getValue(archive.file), work)
                }
                ok = true
                return StagedRestore(manifest, work)
            }
        } finally {
            if (!ok) {
                runCatching { input.close() }
                work?.deleteRecursively()
                unclaim()
            }
        }
    }

    /** How much a restore may still unpack: [MAX_TOTAL_BYTES] in all, and never the phone's last free space. */
    private class Budget(private val limit: Long, private val bySpace: Boolean) {
        private var used = 0L

        fun take(n: Long) {
            used += n
            if (used > limit) {
                if (bySpace) throw BackupException(BackupException.Reason.NO_SPACE, "not enough free space")
                throw invalid("too large")
            }
        }
    }

    private fun budgetFor(work: File): Budget {
        val bySpace = (usableSpace(work) - FREE_SPACE_MARGIN_BYTES).coerceAtLeast(0)
        return if (bySpace < MAX_TOTAL_BYTES) Budget(bySpace, bySpace = true) else Budget(MAX_TOTAL_BYTES, bySpace = false)
    }

    private fun readEntries(zip: ZipInputStream, work: File, cancel: Cancel): BackupManifest {
        var manifest: BackupManifest? = null
        val seen = HashSet<String>()
        val budget = budgetFor(work)
        while (true) {
            cancel.check()
            val entry = try {
                zip.nextEntry
            } catch (e: ZipException) {
                throw invalid("zip", e)
            } ?: break
            val name = entry.name
            if (seen.size >= MAX_ENTRIES) throw invalid("too many entries")
            if (!seen.add(name)) throw invalid("duplicate entry")
            if (entry.isDirectory) throw invalid("directory entry")
            if (manifest == null) {
                if (name != MANIFEST) throw invalid("manifest is not first")
                val bytes = java.io.ByteArrayOutputStream().also { copy(zip, it, MAX_MANIFEST_BYTES.toLong(), cancel) }.toByteArray()
                manifest = BackupManifest.parse(String(bytes, Charsets.UTF_8)).also(::checkManifest)
                continue
            }
            val (dest, cap) = destinationOf(name, manifest, work)
            dest.parentFile?.mkdirs()
            try {
                // Counted as it is written, so a file that unpacks to more than there is room for stops early.
                FileOutputStream(dest).use { copy(zip, it, cap, cancel) { n -> budget.take(n.toLong()) } }
            } catch (e: ZipException) {
                throw invalid("zip", e)
            }
        }
        return manifest ?: throw invalid("empty")
    }

    /** Where entry [name] goes in [work], and its size cap; anything a backup never holds is refused. */
    private fun destinationOf(name: String, manifest: BackupManifest, work: File): Pair<File, Long> {
        if (name.startsWith(DB_PREFIX)) {
            val file = name.removePrefix(DB_PREFIX)
            if (file !in ARCHIVES || manifest.archives.none { it.file == file }) throw invalid("unknown database")
            return File(File(work, "db"), file) to MAX_DB_BYTES
        }
        if (name.startsWith(MEDIA_PREFIX)) {
            val parts = name.removePrefix(MEDIA_PREFIX).split('/')
            if (parts.size != 2 || parts[0] !in FOLDERS || !SAFE_FILE.matches(parts[1])) throw invalid("unsafe name")
            return File(File(File(work, "media"), parts[0]), parts[1]) to MAX_MEDIA_FILE_BYTES
        }
        throw invalid("unknown entry")
    }

    private fun checkManifest(manifest: BackupManifest) {
        if (manifest.format != BackupManifest.FORMAT) throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT, "manifest ${manifest.format}")
        if (manifest.schemaVersion > ArchiveDb.VERSION || manifest.archives.any { it.schemaVersion > ArchiveDb.VERSION }) {
            throw BackupException(BackupException.Reason.NEWER_ARCHIVE)
        }
        val files = HashSet<String>()
        for (a in manifest.archives) {
            val service = ARCHIVES[a.file] ?: throw invalid("unknown archive")
            if (service.key != a.service || !files.add(a.file)) throw invalid("archive list")
        }
    }

    /**
     * Checks the database [file] of [service] (opens, integrity_check, schema version, tables),
     * then points its file paths at this phone's folders: to the restored file when the backup
     * has it, to nothing ("") otherwise.
     */
    private fun checkAndRelocate(app: Context, file: File, service: ChatService, work: File) {
        val db = try {
            open(file)
        } catch (e: SQLiteException) {
            throw invalid("database does not open", e)
        }
        try {
            if (!integrityOk(db)) throw invalid("database check")
            val version = db.version
            if (version > ArchiveDb.VERSION) throw BackupException(BackupException.Reason.NEWER_ARCHIVE)
            if (version < 1 || !hasTable(db, "conversations") || !hasTable(db, "messages")) throw invalid("not an archive")
            val folders = folderNames(service)
            db.beginTransaction()
            try {
                for ((table, column) in PATH_COLUMNS) {
                    if (!hasColumn(db, table, column)) continue
                    val paths = db.rawQuery("SELECT DISTINCT $column FROM $table WHERE $column <> ''", null).use { c ->
                        buildList { while (c.moveToNext()) c.getString(0)?.let(::add) }
                    }
                    for (path in paths) {
                        val old = File(path)
                        val folder = old.parentFile?.name
                        val restored = folder != null && folder in folders && SAFE_FILE.matches(old.name) &&
                            File(File(File(work, "media"), folder), old.name).isFile
                        val now = if (restored) File(File(app.filesDir, folder!!), old.name).path else ""
                        if (now != path) db.execSQL("UPDATE $table SET $column = ? WHERE $column = ?", arrayOf<Any>(now, path))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: SQLiteException) {
            throw invalid("database", e)
        } finally {
            db.close()
            sidecars(file).forEach { it.delete() }
        }
    }

    private class Swap(val live: File, val next: File?, val old: File, val database: Boolean) {
        /** What was live is at [old] now. */
        var movedOut = false

        /** [next] is live now. */
        var movedIn = false
    }

    /** Every file and folder a restore replaces: each service's database, then its folders. */
    private fun targets(app: Context): List<Pair<File, Boolean>> = ChatService.entries.flatMap { service ->
        listOf(app.getDatabasePath(ArchiveDb.nameFor(service)) to true) +
            folderNames(service).map { File(app.filesDir, it) to false }
    } + (backgroundsDir(app) to false)

    private fun marker(app: Context) = File(app.filesDir, PENDING_MARKER)

    /**
     * Replaces every archive and its folders on this phone with [staged] (an archive the backup
     * does not list is removed: the phone then has exactly the backup's chats; one it lists must
     * be there, or nothing changes). Everything is first copied next to where it goes; then, with
     * every archive closed and held ([ArchiveRepository.closeAllForRestore]), each is swapped in by
     * renaming, and undone if a rename fails. While the renames run, a marker ([PENDING_MARKER])
     * says so: a restore cut off there (the process died) is undone by the next one before it
     * does anything else ([recoverInterrupted]), so the chats that were here are never lost.
     * Afterwards nothing in this process may read an archive again: the hold is kept (unless
     * [releaseAfter], for tests) and the caller restarts the app ([AppRestart]). [staged] is
     * closed either way.
     */
    @Throws(IOException::class)
    fun applyRestore(context: Context, staged: StagedRestore, releaseAfter: Boolean = false) {
        val app = context.applicationContext
        if (!staged.isOpen) throw IOException("restore already closed")
        val listed = staged.manifest.archives.map { it.file }.toSet()
        var hold: ArchiveRestoreHold? = null
        var applied = false
        try {
            for (file in listed) {
                if (!File(File(staged.dir, "db"), file).isFile) throw invalid("no database for $file")
            }
            if (marker(app).exists()) {
                hold = ArchiveRepository.closeAllForRestore(RESTORE_LOCK_TIMEOUT_MS) ?: throw BackupException(BackupException.Reason.BUSY)
                recoverInterrupted(app)
            }
            val swaps = ArrayList<Swap>()
            try {
                for (service in ChatService.entries) {
                    val name = ArchiveDb.nameFor(service)
                    val inBackup = name in listed
                    val live = app.getDatabasePath(name)
                    val from = if (inBackup) File(File(staged.dir, "db"), name) else null
                    val db = Swap(live, from?.let { File(live.path + NEXT) }, File(live.path + OLD), database = true)
                    swaps += db
                    db.prepare(from)
                    for (folder in folderNames(service)) {
                        val liveDir = File(app.filesDir, folder)
                        val fromDir = if (inBackup) File(File(staged.dir, "media"), folder).takeIf { it.isDirectory } else null
                        val dir = Swap(liveDir, fromDir?.let { File(liveDir.path + NEXT) }, File(liveDir.path + OLD), database = false)
                        swaps += dir
                        dir.prepare(fromDir)
                    }
                    if (service == ChatService.IMESSAGE) {
                        // The chats' backgrounds, when the backup has them (one made before 0.5.73.3 has none: those here stay).
                        val fromDir = File(File(staged.dir, "media"), BACKGROUNDS_FOLDER).takeIf { inBackup && it.isDirectory }
                        if (fromDir != null) {
                            val liveDir = backgroundsDir(app)
                            liveDir.parentFile?.mkdirs()
                            val dir = Swap(liveDir, File(liveDir.path + NEXT), File(liveDir.path + OLD), database = false)
                            swaps += dir
                            dir.prepare(fromDir)
                        }
                    }
                }
            } catch (e: IOException) {
                swaps.forEach { s -> s.next?.let(::deleteAny) }
                throw e
            }
            staged.deleteFiles()
            val held = hold ?: ArchiveRepository.closeAllForRestore(RESTORE_LOCK_TIMEOUT_MS) ?: run {
                swaps.forEach { s -> s.next?.let(::deleteAny) }
                throw BackupException(BackupException.Reason.BUSY)
            }
            hold = held
            writeMarker(app)
            val done = ArrayList<Swap>()
            try {
                for (s in swaps) {
                    done += s
                    s.moveOut()
                    if (s.next != null) {
                        if (!s.next.renameTo(s.live)) throw IOException("could not move ${s.live.name} into place")
                        s.movedIn = true
                    }
                }
            } catch (e: Throwable) {
                var undone = true
                for (s in done.asReversed()) if (runCatching { s.moveBack() }.isFailure) undone = false
                swaps.forEach { s -> s.next?.let(::deleteAny) }
                // Only when all is back as it was; otherwise the next restore puts it back.
                if (undone) marker(app).delete()
                throw if (e is IOException) e else IOException(e)
            }
            // Committed: the marker goes first, then what was there before.
            marker(app).delete()
            for (s in swaps) {
                deleteAny(s.old)
                if (s.database) SIDECARS.forEach { deleteAny(File(s.old.path + it)) }
            }
            for (service in ChatService.entries) {
                // The user's own pins and unread marks for the chats that were here (LocalMarks).
                runCatching { app.deleteSharedPreferences("archive_marks_" + ArchiveDb.nameFor(service)) }
            }
            applied = true
        } finally {
            if (applied && !releaseAfter) {
                // Nothing may read an archive or start another backup until the app restarts.
                staged.closeKeepingClaim()
            } else {
                hold?.release()
                staged.close()
            }
        }
    }

    /**
     * Puts back what a restore that was cut off during its renames (the [PENDING_MARKER] is
     * there) had moved aside: each "<name>.restore-old" becomes "<name>" again, replacing whatever
     * is there now (part of the backup, or an empty archive made since), and the leftovers of the
     * new files go. Every archive must be closed ([ArchiveRepository.closeAllForRestore]).
     */
    @Throws(IOException::class)
    internal fun recoverInterrupted(app: Context) {
        val marker = marker(app)
        if (!marker.exists()) return
        for ((live, database) in targets(app)) {
            val old = File(live.path + OLD)
            deleteAny(File(live.path + NEXT))
            if (!old.exists()) continue
            if (live.exists()) {
                deleteAny(live)
                // They belong to what is being dropped. (With no live file, any left belong to [old]: kept.)
                if (database) SIDECARS.forEach { deleteAny(File(live.path + it)) }
            }
            if (!old.renameTo(live)) throw IOException("could not put back ${live.name}")
            if (database) for (suffix in SIDECARS) {
                val from = File(old.path + suffix)
                if (!from.exists()) continue
                val to = File(live.path + suffix)
                deleteAny(to)
                if (!from.renameTo(to)) throw IOException("could not put back ${to.name}")
            }
        }
        if (!marker.delete() && marker.exists()) throw IOException("marker stays")
    }

    private fun writeMarker(app: Context) {
        val marker = marker(app)
        marker.parentFile?.mkdirs()
        FileOutputStream(marker).use { out ->
            out.write(System.currentTimeMillis().toString().toByteArray(Charsets.US_ASCII))
            runCatching { out.fd.sync() }
        }
    }

    /**
     * Copies (or moves, when it can) [from] to [Swap.next]. A "<name>.restore-old" left here is
     * from a restore that finished (one cut off was put back by [recoverInterrupted] first): it goes.
     */
    private fun Swap.prepare(from: File?) {
        deleteAny(old)
        if (database) SIDECARS.forEach { deleteAny(File(old.path + it)) }
        val target = next ?: return
        deleteAny(target)
        target.parentFile?.mkdirs()
        if (!from!!.renameTo(target)) {
            if (database) from.copyTo(target, overwrite = true)
            else if (!from.copyRecursively(target, overwrite = true)) throw IOException("copy failed")
        }
    }

    /** Moves what is live (and, for a database, its journal files) aside to [Swap.old]. */
    private fun Swap.moveOut() {
        if (live.exists()) {
            if (!live.renameTo(old)) throw IOException("could not move ${live.name} aside")
            movedOut = true
        }
        if (database) for (suffix in SIDECARS) {
            val f = File(live.path + suffix)
            if (f.exists() && !f.renameTo(File(old.path + suffix))) throw IOException("could not move ${f.name} aside")
        }
    }

    /** Undoes [moveOut] and the move in; throws when what was live could not be put back. */
    private fun Swap.moveBack() {
        if (movedIn && next != null && !live.renameTo(next)) deleteAny(live)
        if (movedOut && !old.renameTo(live)) throw IOException("could not put back ${live.name}")
        if (database) for (suffix in SIDECARS) {
            val f = File(old.path + suffix)
            if (f.exists()) f.renameTo(File(live.path + suffix))
        }
    }

    // --- shared ----------------------------------------------------------------------------

    /** A fresh work folder under the cache; ones left by a run that was killed are deleted first. */
    private fun newWorkDir(app: Context): File {
        app.cacheDir.listFiles()?.filter { it.name.startsWith(WORK_PREFIX) }?.forEach { it.deleteRecursively() }
        val id = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        return File(app.cacheDir, WORK_PREFIX + id).apply {
            if (!mkdirs()) throw IOException("no work folder")
        }
    }

    private fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun integrityOk(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA integrity_check", null).use { c ->
            c.moveToFirst() && c.count == 1 && c.getString(0) == "ok"
        }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        hasTable(db, table) && db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var found = false
            while (!found && c.moveToNext()) found = c.getString(c.getColumnIndexOrThrow("name")) == column
            found
        }

    private fun count(db: SQLiteDatabase, table: String): Int =
        if (!hasTable(db, table)) 0 else db.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun sidecars(file: File) = SIDECARS.map { File(file.path + it) }

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (e: IOException) {
        file.absoluteFile
    }

    private fun deleteAny(file: File) {
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    internal fun invalid(what: String, cause: Throwable? = null) =
        BackupException(BackupException.Reason.INVALID_CONTENT, what, cause)

    /** Copies at most [cap] bytes; more is [BackupException.Reason.INVALID_CONTENT]. Returns how many. */
    private fun copy(input: InputStream, out: OutputStream, cap: Long, cancel: Cancel, step: (Int) -> Unit = {}): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > cap) throw invalid("entry too large")
            out.write(buffer, 0, n)
            step(n)
            cancel.check()
        }
        buffer.fill(0)
        return total
    }

    /** Lets the ZIP stream be closed (ending its compressor) without closing the cipher under it. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    /** Reports how much of the encrypted file has been read, every 256 KiB or so. */
    private class CountingInput(input: InputStream, private val report: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it) }

        private fun add(n: Int) {
            count += n
            if (count - reported >= 256 * 1024) {
                reported = count
                report(count)
            }
        }
    }
}
