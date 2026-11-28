package com.lightdecoder.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Back up chats / Restore chats against real archives and files. */
@RunWith(AndroidJUnit4::class)
class ChatBackupTest {

    private lateinit var context: Context
    private val pass = "a fine passphrase".toCharArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ArchiveRepository.resetForTesting()
        ChatBackup.resetForTesting()
        AppRestart.resetForTesting()
        File(context.filesDir, ChatBackup.PENDING_MARKER).delete()
        for (service in ChatService.entries) {
            context.deleteDatabase(ArchiveDb.nameFor(service))
            ChatBackup.folderNames(service).forEach { File(context.filesDir, it).deleteRecursively() }
        }
    }

    @After
    fun tearDown() {
        ArchiveRepository.closeAllForRestore(5_000)?.release()
        ArchiveRepository.resetForTesting()
        ChatBackup.resetForTesting()
        AppRestart.resetForTesting()
    }

    // --- seeding ---------------------------------------------------------------------------

    private fun repo(service: ChatService = ChatService.MESSAGES) = ArchiveRepository.get(context, service)

    private fun seed(service: ChatService, conv: String, messages: Int, withPicture: Boolean = true): File? {
        val repo = repo(service)
        repo.upsertConversation(ConversationDto(id = conv, name = "Chat $conv", type = "RCS", lastMessageTs = 1_000L * messages))
        for (i in 1..messages) {
            repo.upsertMessage(
                MessageDto(
                    id = "$conv-m$i", conversationId = conv, senderId = "2", senderName = "Sam", timestampMs = 1_000L * i,
                    direction = "in", status = "INCOMING_COMPLETE", transport = "RCS", text = "hello $i from $conv",
                    parts = if (withPicture && i == 1) listOf(
                        PartDto(partId = "p1", kind = "media", mediaId = "media-$conv", key = ByteArray(32) { 3 }, mime = "image/jpeg", size = 4)
                    ) else emptyList(),
                )
            )
        }
        if (!withPicture) return null
        val file = repo.mediaFileFor("media-$conv", "jpg")
        file.writeBytes("picture of $conv".toByteArray())
        assertTrue(repo.setMediaLocalPath("$conv-m1", "p1", "media-$conv", file.path, thumbnail = false))
        return file
    }

    private fun backup(params: BackupCipher.Params = BackupCipher.newParams(), cancel: ChatBackup.Cancel = ChatBackup.Cancel {}): ByteArray {
        val out = ByteArrayOutputStream()
        ChatBackup.backup(context, out, pass.copyOf(), cancel = cancel, params = params)
        return out.toByteArray()
    }

    private fun restore(bytes: ByteArray, passphrase: CharArray = pass): BackupManifest {
        val staged = ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), passphrase.copyOf())
        val manifest = staged.manifest
        ChatBackup.applyRestore(context, staged, releaseAfter = true)
        ArchiveRepository.resetForTesting()
        return manifest
    }

    private fun expectFailure(reason: BackupException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: BackupException) {
            assertEquals(reason, e.reason)
        }
    }

    /** Every row of every table that a backup keeps as it is, blobs as hex. */
    private fun dump(service: ChatService): Map<String, List<String>> {
        val file = context.getDatabasePath(ArchiveDb.nameFor(service))
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        try {
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.filter { it !in setOf("android_metadata", "connection_health", "outbox_queue") && !it.startsWith("messages_fts_") && !it.startsWith("sqlite_") }
            return tables.associateWith { table ->
                db.rawQuery("SELECT * FROM $table", null).use { c ->
                    buildList {
                        while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i ->
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString("") { "%02x".format(it) }
                                else -> c.getString(i) ?: "null"
                            }
                        })
                    }.sorted()
                }
            }
        } finally {
            db.close()
        }
    }

    /** The decrypted ZIP of [bytes]: entry name to content. */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val input = BackupCipher.DecryptingInputStream(ByteArrayInputStream(bytes), pass.copyOf())
        val zip = ZipInputStream(input)
        val out = LinkedHashMap<String, ByteArray>()
        while (true) {
            val e = zip.nextEntry ?: break
            out[e.name] = zip.readBytes()
        }
        input.drainAndVerify()
        return out
    }

    /** A backup made by hand: [entries] in a ZIP, encrypted. */
    private fun sealed(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = BackupCipher.EncryptingOutputStream(out, pass.copyOf(), BackupCipher.newParams(iterations = BackupCipher.MIN_ITERATIONS))
        ZipOutputStream(enc).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(schema: Int = ArchiveDb.VERSION, archives: List<BackupManifest.Archive> = emptyList()) =
        BackupManifest(appVersionCode = 1, appVersionName = "t", createdAtMs = 1, schemaVersion = schema, archives = archives)
            .toJson().toByteArray()

    private fun messagesArchive(version: Int) =
        BackupManifest.Archive(ChatService.MESSAGES.key, ArchiveDb.NAME, version, 1, 1, 0)

    /** A database file of schema [version], made with just enough of the tables. */
    private fun handMadeDatabase(version: Int, extra: (SQLiteDatabase) -> Unit = {}): ByteArray {
        val file = File(context.cacheDir, "hand-made-$version.db").apply { delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        db.execSQL(
            "CREATE TABLE conversations (conversation_id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', is_group INTEGER NOT NULL DEFAULT 0, " +
                "is_rcs INTEGER NOT NULL DEFAULT 0, folder TEXT NOT NULL DEFAULT 'inbox', status_code INTEGER NOT NULL DEFAULT 0, " +
                "unread INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, read_only INTEGER NOT NULL DEFAULT 0, " +
                "muted INTEGER NOT NULL DEFAULT 0, last_message_ts INTEGER NOT NULL DEFAULT 0, latest_message_id TEXT NOT NULL DEFAULT '', " +
                "snippet TEXT NOT NULL DEFAULT '', snippet_from_me INTEGER NOT NULL DEFAULT 0, snippet_sender TEXT NOT NULL DEFAULT '', " +
                "avatar_color TEXT NOT NULL DEFAULT '', default_outgoing_id TEXT NOT NULL DEFAULT '', raw_json TEXT, updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE messages (message_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, timestamp_ms INTEGER NOT NULL, " +
                "text_body TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)"
        )
        db.execSQL("INSERT INTO conversations (conversation_id, name, updated_at) VALUES ('old-c', 'Old chat', 1)")
        db.execSQL("INSERT INTO messages (message_id, conversation_id, timestamp_ms, text_body, updated_at) VALUES ('old-m', 'old-c', 5, 'from long ago', 1)")
        extra(db)
        db.version = version
        db.close()
        return file.readBytes().also { file.delete() }
    }

    private fun workDirs() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("chat-backup-") }

    // --- tests -----------------------------------------------------------------------------

    @Test
    fun roundTripGivesIdenticalRowsAndMedia() {
        val picture = seed(ChatService.MESSAGES, "c1", 3)!!
        val blue = seed(ChatService.IMESSAGE, "i1", 2)!!
        seed(ChatService.WHATSAPP, "w1", 1, withPicture = false)
        val before = ChatService.entries.associateWith { if (context.getDatabasePath(ArchiveDb.nameFor(it)).isFile) dump(it) else null }
        val pictureBytes = picture.readBytes()
        val blueBytes = blue.readBytes()

        val bytes = backup()

        // The phone changes after the backup: all of that goes on restore.
        seed(ChatService.MESSAGES, "c2", 1)
        picture.delete()
        repo(ChatService.IMESSAGE).deleteConversationLocal("i1")
        assertFalse(blue.exists())

        val manifest = restore(bytes)
        assertEquals(3, manifest.conversations)
        assertEquals(6, manifest.messages)
        assertEquals(2, manifest.files)
        assertEquals(ArchiveDb.VERSION, manifest.schemaVersion)
        for (service in ChatService.entries) assertEquals(service.name, before[service], if (context.getDatabasePath(ArchiveDb.nameFor(service)).isFile) dump(service) else null)
        assertArrayEquals(pictureBytes, picture.readBytes())
        assertArrayEquals(blueBytes, blue.readBytes())

        // And the app reads them as before.
        assertEquals(null, repo().conversation("c2"))
        val restored = repo().messages("c1")
        assertEquals(3, restored.size)
        assertEquals(picture.path, restored.first { it.id == "c1-m1" }.media.single().localPath)
        assertEquals(2, repo(ChatService.IMESSAGE).messages("i1").size)
        assertEquals(1, repo(ChatService.WHATSAPP).messages("w1").size)
        assertTrue(workDirs().isEmpty())
    }

    @Test
    fun theChatsBackgroundsGoWithTheBackupAndABackupWithoutThemLeavesThemAlone() {
        val backgrounds = { com.lightdecoder.imessage.direct.ChatBackgrounds(context) }
        fun picture(color: Int): File = com.lightdecoder.imessage.direct.ChatBackgrounds.incomingFile(context, "test").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(color.toByte(), 1, 2, 3))
        }
        seed(ChatService.IMESSAGE, "i1", 1, withPicture = false)
        // A backup made before any background was set has none in it.
        val without = backup()
        backgrounds().apply("i1", picture(7), 100)
        val hearts = backgrounds().pictureOf("i1")!!.readBytes()
        val with = backup()
        // Changed after the backup: the restore brings back the one the backup has (what the other side's phone still shows).
        backgrounds().apply("i1", picture(9), 200)
        restore(with)
        assertEquals(100L, backgrounds().of("i1").version)
        assertArrayEquals(hearts, backgrounds().pictureOf("i1")!!.readBytes())
        // An older backup, without backgrounds: the ones here stay as they are.
        restore(without)
        assertEquals(100L, backgrounds().of("i1").version)
        assertArrayEquals(hearts, backgrounds().pictureOf("i1")!!.readBytes())
        assertTrue(workDirs().isEmpty())
    }

    @Test
    fun wrongPassphraseFailsWithoutChanges() {
        seed(ChatService.MESSAGES, "c1", 2)
        val bytes = backup()
        seed(ChatService.MESSAGES, "c2", 1)
        val before = dump(ChatService.MESSAGES)
        expectFailure(BackupException.Reason.WRONG_PASSPHRASE) { restore(bytes, "the wrong one!".toCharArray()) }
        assertEquals(before, dump(ChatService.MESSAGES))
        assertNotNull(repo().conversation("c2"))
        assertTrue(workDirs().isEmpty())
    }

    @Test
    fun aChangedByteFailsWithoutChanges() {
        seed(ChatService.MESSAGES, "c1", 2)
        // Small chunks, so the changed byte is past the first chunk (a change there reads as a wrong passphrase).
        val bytes = backup(BackupCipher.newParams(chunkSize = 1024))
        seed(ChatService.MESSAGES, "c2", 1)
        val before = dump(ChatService.MESSAGES)
        val changed = bytes.copyOf().also { it[it.size - 30] = (it[it.size - 30].toInt() xor 0x40).toByte() }
        expectFailure(BackupException.Reason.DAMAGED) { restore(changed) }
        assertEquals(before, dump(ChatService.MESSAGES))
    }

    @Test
    fun aCutShortFileFailsWithoutChanges() {
        seed(ChatService.MESSAGES, "c1", 2)
        val bytes = backup()
        seed(ChatService.MESSAGES, "c2", 1)
        val before = dump(ChatService.MESSAGES)
        expectFailure(BackupException.Reason.DAMAGED) { restore(bytes.copyOfRange(0, bytes.size - 100)) }
        assertEquals(before, dump(ChatService.MESSAGES))
        assertTrue(workDirs().isEmpty())
    }

    @Test
    fun reorderedChunksFail() {
        seed(ChatService.MESSAGES, "c1", 20)
        val bytes = backup(BackupCipher.newParams(chunkSize = 1024))
        val headerLength = BackupCipher.readHeader(ByteArrayInputStream(bytes)).headerBytes().size
        val records = ArrayList<ByteArray>()
        var at = headerLength
        while (at < bytes.size) {
            val length = ByteBuffer.wrap(bytes, at + 1, 4).int
            records += bytes.copyOfRange(at, at + 5 + length)
            at += 5 + length
        }
        assertTrue(records.size > 3)
        val swapped = ByteArrayOutputStream().apply {
            write(bytes, 0, headerLength)
            write(records[0])
            write(records[2])
            write(records[1])
            records.drop(3).forEach { write(it) }
        }.toByteArray()
        val before = dump(ChatService.MESSAGES)
        expectFailure(BackupException.Reason.DAMAGED) { restore(swapped) }
        assertEquals(before, dump(ChatService.MESSAGES))
    }

    @Test
    fun pathTraversalEntriesAreRejected() {
        val outside = File(context.filesDir, "shared_prefs_evil.xml")
        for (name in listOf(
            "media/relay-media/../../shared_prefs_evil.xml", "../shared_prefs_evil.xml", "/data/evil", "media/relay-media/.hidden",
            "media/somewhere-else/file.jpg", "media/relay-media/sub/file.jpg", "media/relay-media\\..\\x", "db/other.db", "random.txt",
        )) {
            val bytes = sealed(listOf(ChatBackup.MANIFEST to manifest(), name to "evil".toByteArray()))
            expectFailure(BackupException.Reason.INVALID_CONTENT) { ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf()) }
            assertFalse(name, outside.exists())
        }
        // The manifest must come first, and only once.
        val late = sealed(listOf("media/relay-media/a.jpg" to ByteArray(1), ChatBackup.MANIFEST to manifest()))
        expectFailure(BackupException.Reason.INVALID_CONTENT) { ChatBackup.prepareRestore(context, ByteArrayInputStream(late), pass.copyOf()) }
        assertTrue(workDirs().isEmpty())
    }

    @Test
    fun aNewerSchemaIsRefused() {
        seed(ChatService.MESSAGES, "c1", 1)
        val before = dump(ChatService.MESSAGES)
        val byManifest = sealed(listOf(ChatBackup.MANIFEST to manifest(schema = ArchiveDb.VERSION + 1)))
        expectFailure(BackupException.Reason.NEWER_ARCHIVE) { restore(byManifest) }
        // A database newer than its manifest says.
        val byDatabase = sealed(
            listOf(
                ChatBackup.MANIFEST to manifest(archives = listOf(messagesArchive(ArchiveDb.VERSION))),
                ChatBackup.DB_PREFIX + ArchiveDb.NAME to handMadeDatabase(ArchiveDb.VERSION + 1),
            )
        )
        expectFailure(BackupException.Reason.NEWER_ARCHIVE) { restore(byDatabase) }
        assertEquals(before, dump(ChatService.MESSAGES))
    }

    @Test
    fun anOlderSchemaIsAcceptedAndUpgraded() {
        seed(ChatService.MESSAGES, "c1", 1)
        val bytes = sealed(
            listOf(
                ChatBackup.MANIFEST to manifest(schema = 2, archives = listOf(messagesArchive(2))),
                ChatBackup.DB_PREFIX + ArchiveDb.NAME to handMadeDatabase(2),
            )
        )
        restore(bytes)
        assertEquals("Old chat", repo().conversation("old-c")?.name)
        assertEquals(null, repo().conversation("c1"))
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(ArchiveDb.NAME).path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        try {
            assertEquals(ArchiveDb.VERSION, db.version)
            val columns = db.rawQuery("PRAGMA table_info(messages)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
            assertTrue(columns.containsAll(listOf("edited_at", "extra_json")))
        } finally {
            db.close()
        }
    }

    @Test
    fun aDamagedDatabaseIsRefused() {
        val bytes = sealed(
            listOf(
                ChatBackup.MANIFEST to manifest(archives = listOf(messagesArchive(ArchiveDb.VERSION))),
                ChatBackup.DB_PREFIX + ArchiveDb.NAME to ByteArray(8192) { (it * 7).toByte() },
            )
        )
        expectFailure(BackupException.Reason.INVALID_CONTENT) { ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf()) }
        // A manifest naming an archive that is not there.
        val missing = sealed(listOf(ChatBackup.MANIFEST to manifest(archives = listOf(messagesArchive(ArchiveDb.VERSION)))))
        expectFailure(BackupException.Reason.INVALID_CONTENT) { ChatBackup.prepareRestore(context, ByteArrayInputStream(missing), pass.copyOf()) }
    }

    @Test
    fun secretsAreNotInTheBackup() {
        val marker = "SECRET-MARKER-7f3a"
        val secrets = listOf(
            File(context.filesDir, "relay/session.bin"), File(context.filesDir, "mail/account.bin"),
            File(context.filesDir, "imessage/registered"), File(context.filesDir, "imessage/lib/state.bin"),
            File(context.filesDir, "imessage/status"), File(context.filesDir, "whatsapp/session.db"),
            File(context.dataDir, "app_webview_discord/Cookies"),
        )
        secrets.forEach { it.parentFile!!.mkdirs(); it.writeText(marker) }
        context.getSharedPreferences("imessage_direct", Context.MODE_PRIVATE).edit().putString("k", marker).commit()
        context.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString("k", marker).commit()
        seed(ChatService.MESSAGES, "c1", 2)
        seed(ChatService.IMESSAGE, "i1", 1)
        // A row pointing at a file outside the archive's own folders is not followed.
        val live = SQLiteDatabase.openDatabase(context.getDatabasePath(ArchiveDb.NAME).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        live.execSQL("UPDATE media SET thumb_path = ?", arrayOf(secrets[0].path))
        live.execSQL("INSERT OR REPLACE INTO connection_health (id, status, account, phone_id) VALUES (1, 'ok', 'someone@example.com', 'phone-$marker')")
        live.close()

        val entries = unzip(backup())
        val names = entries.keys
        assertTrue(names.all { it == ChatBackup.MANIFEST || it.startsWith("db/") || it.startsWith("media/") })
        for (secret in secrets + listOf(File("imessage_direct.xml"), File("relay.xml"))) {
            assertTrue(secret.name, names.none { it.endsWith("/" + secret.name) || it == secret.name })
        }
        for ((name, data) in entries) {
            val text = String(data, Charsets.ISO_8859_1)
            assertFalse("$name holds a secret", text.contains(marker))
            assertFalse("$name holds the account", text.contains("someone@example.com"))
        }
        assertEquals(setOf("manifest.json", "db/relay-archive.db", "db/imessage-archive.db", "media/relay-media/" + MediaCache.mediaDir(context).listFiles()!!.single().name,
            "media/imessage-media/" + MediaCache.mediaDir(context, ChatService.IMESSAGE).listFiles()!!.single().name), names)
    }

    @Test
    fun kdfParametersAreReadFromTheHeader() {
        seed(ChatService.MESSAGES, "c1", 1)
        val params = BackupCipher.newParams(iterations = 320_000)
        val bytes = backup(params)
        val header = BackupCipher.readHeader(ByteArrayInputStream(bytes))
        assertEquals(320_000, header.iterations)
        assertArrayEquals(params.salt, header.salt)
        restore(bytes)
        assertEquals(1, repo().messages("c1").size)
    }

    @Test
    fun cancellationCleansUp() {
        seed(ChatService.MESSAGES, "c1", 5)
        seed(ChatService.IMESSAGE, "i1", 5)
        var checks = 0
        val out = ByteArrayOutputStream()
        try {
            ChatBackup.backup(context, out, pass.copyOf(), cancel = { if (++checks > 8) throw CancellationException() })
            fail("not cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(workDirs().isEmpty())
        // What was written is never a complete backup.
        try {
            ChatBackup.prepareRestore(context, ByteArrayInputStream(out.toByteArray()), pass.copyOf())
            fail("a cancelled backup restored")
        } catch (e: BackupException) {
            // expected
        }

        val bytes = backup()
        seed(ChatService.MESSAGES, "c2", 1)
        val before = dump(ChatService.MESSAGES)
        var restoreChecks = 0
        try {
            ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf(), cancel = { if (++restoreChecks > 2) throw CancellationException() })
            fail("not cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(workDirs().isEmpty())
        assertEquals(before, dump(ChatService.MESSAGES))
    }

    @Test
    fun queuedSendsComeBackFailedAndNothingIsSentByItself() {
        seed(ChatService.MESSAGES, "c1", 1)
        val live = SQLiteDatabase.openDatabase(context.getDatabasePath(ArchiveDb.NAME).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        live.execSQL("INSERT INTO outbox_queue (idempotency_key, conversation_id, text_body, status, created_at) VALUES ('k1', 'c1', 'later', 'PENDING', 1)")
        live.execSQL("UPDATE messages SET local_state = 'sending' WHERE message_id = 'c1-m1'")
        live.close()
        seed(ChatService.IMESSAGE, "i1", 2, withPicture = false)
        val blue = SQLiteDatabase.openDatabase(context.getDatabasePath(ArchiveDb.nameFor(ChatService.IMESSAGE)).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        blue.execSQL("UPDATE messages SET local_state = 'pending' WHERE message_id = 'i1-m1'")
        blue.close()
        restore(backup())
        assertEquals("FAILED", repo().outbox("k1")?.status)
        // Their bubbles show as failed (with Retry), not as waiting for good.
        assertEquals(LocalState.FAILED, repo().messages("c1").single { it.id == "c1-m1" }.localState)
        val restoredBlue = repo(ChatService.IMESSAGE).messages("i1")
        assertEquals(LocalState.FAILED, restoredBlue.single { it.id == "i1-m1" }.localState)
        assertEquals(LocalState.SYNCED, restoredBlue.single { it.id == "i1-m2" }.localState)
    }

    @Test
    fun deletedChatsAreNotInTheBackup() {
        val marker = "gonemarker91"
        seed(ChatService.MESSAGES, "keep", 30, withPicture = false)
        seed(ChatService.MESSAGES, marker, 6, withPicture = false)
        seed(ChatService.MESSAGES, "keep2", 30, withPicture = false)
        repo().deleteConversationLocal(marker)
        assertEquals(null, repo().conversation(marker))
        // The live archive still has the words in its freed space (so the check below means something).
        val dbFile = context.getDatabasePath(ArchiveDb.NAME)
        val liveBytes = dbFile.readBytes() + File(dbFile.path + "-wal").let { if (it.isFile) it.readBytes() else ByteArray(0) }
        assertTrue(String(liveBytes, Charsets.ISO_8859_1).contains(marker))

        val bytes = backup()
        val archive = unzip(bytes).getValue(ChatBackup.DB_PREFIX + ArchiveDb.NAME)
        assertFalse(String(archive, Charsets.ISO_8859_1).lowercase().contains(marker))

        // What was kept is all there, and found by search as before.
        restore(bytes)
        assertEquals(30, repo().messages("keep").size)
        assertEquals(30, repo().messages("keep2").size)
        assertEquals(null, repo().conversation(marker))
        assertTrue(repo().search("hello").isNotEmpty())
    }

    @Test
    fun anEarlyFailureClosesTheOutput() {
        seed(ChatService.MESSAGES, "c1", 1)
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }
        try {
            ChatBackup.backup(context, out, pass.copyOf(), cancel = { throw CancellationException() })
            fail("not cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(closed)
        assertEquals(0, out.size())
        assertTrue(workDirs().isEmpty())
    }

    @Test
    fun aRestoreThatWouldFillThePhoneIsRefused() {
        seed(ChatService.MESSAGES, "c1", 3)
        val bytes = backup()
        seed(ChatService.MESSAGES, "c2", 1)
        val before = dump(ChatService.MESSAGES)
        ChatBackup.usableSpace = { ChatBackup.FREE_SPACE_MARGIN_BYTES + 2_000 }
        expectFailure(BackupException.Reason.NO_SPACE) { ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf()) }
        assertTrue(workDirs().isEmpty())
        assertEquals(before, dump(ChatService.MESSAGES))
        // With room, it restores.
        ChatBackup.usableSpace = { Long.MAX_VALUE }
        restore(bytes)
        assertEquals(null, repo().conversation("c2"))
    }

    @Test
    fun aDownloadAndACleanupDoNotDeadlockABackup() {
        val picture = seed(ChatService.MESSAGES, "gone", 1)!!
        seed(ChatService.MESSAGES, "c2", 1, withPicture = false)
        val repo = repo()
        val inLock = java.util.concurrent.CountDownLatch(1)
        val letGo = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        fun thread(name: String, body: () -> Unit) = Thread({
            try {
                body()
            } catch (e: Throwable) {
                failures += e
            }
        }, name).apply { isDaemon = true }
        // A: a download that holds the media file lock, then records its path.
        val download = thread("download") {
            repo.withMediaFileLock {
                inLock.countDown()
                letGo.await()
                val file = repo.mediaFileFor("late", "jpg").apply { writeBytes(ByteArray(3)) }
                repo.setMediaLocalPath("c2-m1", "p1", "late", file.path, thumbnail = false)
            }
        }
        download.start()
        assertTrue(inLock.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // B: a write whose cleanup needs the media file lock.
        val cleanup = thread("cleanup") { repo.deleteConversationLocal("gone") }
        cleanup.start()
        // It holds the gates and waits for the lock (before the backup queues up behind it).
        waitUntil { cleanup.state == Thread.State.BLOCKED }
        // C: a backup that waits for the writes.
        val out = ByteArrayOutputStream()
        val backup = thread("backup") { ChatBackup.backup(context, out, pass.copyOf()) }
        backup.start()
        waitUntil { backup.stackTrace.any { it.methodName == "snapshotTo" } }
        Thread.sleep(300)
        letGo.countDown()
        for (t in listOf(download, cleanup, backup)) t.join(20_000)
        assertTrue("stuck: " + listOf(download, cleanup, backup).filter { it.isAlive }.map { it.name }, listOf(download, cleanup, backup).none { it.isAlive })
        assertTrue(failures.toString(), failures.isEmpty())
        assertFalse(picture.exists())
        assertTrue(out.size() > 0)
    }

    @Test
    fun aBackupWaitingForAWriteCanBeCancelled() {
        seed(ChatService.MESSAGES, "c1", 1)
        val repo = repo()
        val inLock = java.util.concurrent.CountDownLatch(1)
        val letGo = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            repo.withMediaFileLock {
                inLock.countDown()
                letGo.await()
            }
        }.apply { isDaemon = true; start() }
        assertTrue(inLock.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var result: Throwable? = null
        val backup = Thread {
            try {
                ChatBackup.backup(context, ByteArrayOutputStream(), pass.copyOf(), cancel = { if (cancelled.get()) throw CancellationException() })
            } catch (e: Throwable) {
                result = e
            }
        }.apply { isDaemon = true; start() }
        waitUntil { backup.stackTrace.any { it.methodName == "snapshotTo" } }
        cancelled.set(true)
        backup.join(10_000)
        assertFalse(backup.isAlive)
        assertTrue(result.toString(), result is CancellationException)
        letGo.countDown()
        writer.join(10_000)
        assertTrue(workDirs().isEmpty())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("timed out")
            Thread.sleep(10)
        }
    }

    @Test
    fun onlyOneBackupOrRestoreRunsAtATime() {
        seed(ChatService.MESSAGES, "c1", 1)
        val bytes = backup()
        val staged = ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf())
        expectFailure(BackupException.Reason.BUSY) { backup() }
        expectFailure(BackupException.Reason.BUSY) { ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf()) }
        // The staged files were not touched by the refused runs.
        assertTrue(File(File(staged.dir, "db"), ArchiveDb.NAME).isFile)
        staged.close()
        assertTrue(backup().isNotEmpty())
    }

    @Test
    fun aListedArchiveThatIsMissingChangesNothing() {
        seed(ChatService.MESSAGES, "c1", 1)
        seed(ChatService.IMESSAGE, "i1", 1)
        val bytes = backup()
        seed(ChatService.MESSAGES, "c2", 1)
        val before = dump(ChatService.MESSAGES)
        val blueBefore = dump(ChatService.IMESSAGE)
        val staged = ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf())
        // Something removed a staged database after the check.
        assertTrue(File(File(staged.dir, "db"), ArchiveDb.NAME).delete())
        expectFailure(BackupException.Reason.INVALID_CONTENT) { ChatBackup.applyRestore(context, staged, releaseAfter = true) }
        ArchiveRepository.resetForTesting()
        assertEquals(before, dump(ChatService.MESSAGES))
        assertEquals(blueBefore, dump(ChatService.IMESSAGE))
        assertNotNull(repo().conversation("c2"))
        assertTrue(workDirs().isEmpty())
        // And the next run may go.
        assertTrue(backup().isNotEmpty())
    }

    @Test
    fun aSwapCutOffByTheProcessDyingIsPutBack() {
        val picture = seed(ChatService.MESSAGES, "c1", 2)!!
        val bytes = backup()
        seed(ChatService.MESSAGES, "c2", 1)
        val before = dump(ChatService.MESSAGES)
        val pictureBytes = picture.readBytes()
        // As a restore leaves it when the process dies between moving the chats aside and moving the new ones in.
        ArchiveRepository.closeAllForRestore(5_000)!!.release()
        ArchiveRepository.resetForTesting()
        val live = context.getDatabasePath(ArchiveDb.NAME)
        val mediaDir = File(context.filesDir, MediaCache.mediaDirName(ChatService.MESSAGES))
        File(context.filesDir, ChatBackup.PENDING_MARKER).writeText("1")
        assertTrue(live.renameTo(File(live.path + ".restore-old")))
        listOf("-wal", "-shm").forEach { File(live.path + it).let { f -> if (f.exists()) assertTrue(f.renameTo(File(live.path + ".restore-old" + it))) } }
        assertTrue(mediaDir.renameTo(File(mediaDir.path + ".restore-old")))
        // The app started again meanwhile: an empty archive.
        assertEquals(null, repo().conversation("c2"))

        // A backup checked meanwhile does not touch what was moved aside; putting back brings it all back.
        val staged = ChatBackup.prepareRestore(context, ByteArrayInputStream(bytes), pass.copyOf())
        val hold = ArchiveRepository.closeAllForRestore(5_000)!!
        try {
            ChatBackup.recoverInterrupted(context)
        } finally {
            hold.release()
            ArchiveRepository.resetForTesting()
        }
        assertEquals(before, dump(ChatService.MESSAGES))
        assertArrayEquals(pictureBytes, picture.readBytes())
        assertFalse(File(context.filesDir, ChatBackup.PENDING_MARKER).exists())
        assertFalse(File(live.path + ".restore-old").exists())

        // Cut off again, then a restore: it puts back first, then restores the backup, leaving nothing behind.
        ArchiveRepository.closeAllForRestore(5_000)!!.release()
        ArchiveRepository.resetForTesting()
        File(context.filesDir, ChatBackup.PENDING_MARKER).writeText("1")
        assertTrue(live.renameTo(File(live.path + ".restore-old")))
        ChatBackup.applyRestore(context, staged, releaseAfter = true)
        ArchiveRepository.resetForTesting()
        assertEquals(2, repo().messages("c1").size)
        assertEquals(null, repo().conversation("c2"))
        assertFalse(File(context.filesDir, ChatBackup.PENDING_MARKER).exists())
        assertTrue(context.filesDir.walk().none { it.name.endsWith(".restore-old") || it.name.endsWith(".restore-new") })
        assertTrue(context.getDatabasePath(ArchiveDb.NAME).parentFile!!.listFiles()!!.none { it.name.contains(".restore-") })
    }

    @Test
    fun theScreenSurvivesFoldingAndThemeChanges() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, com.lightdecoder.ui.backup.BackupActivity::class.java), 0,
        )
        val needed = android.content.pm.ActivityInfo.CONFIG_DENSITY or android.content.pm.ActivityInfo.CONFIG_UI_MODE or
            android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE or android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            android.content.pm.ActivityInfo.CONFIG_FONT_SCALE or android.content.pm.ActivityInfo.CONFIG_LOCALE or
            android.content.pm.ActivityInfo.CONFIG_ORIENTATION
        assertEquals(needed, info.configChanges and needed)
    }

    @Test
    fun aScreenMadeAfterTheRestoreAsksForTheRestart() {
        AppRestart.markRestored(4, 9)
        val controller = org.robolectric.Robolectric.buildActivity(
            com.lightdecoder.ui.backup.BackupActivity::class.java,
            com.lightdecoder.ui.backup.BackupActivity.restoreIntent(context),
        ).setup()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        val text = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertEquals(context.getString(com.lightdecoder.R.string.restore_done, 4, 9), text)
        assertFalse(controller.get().findViewById<android.view.View>(com.lightdecoder.R.id.start).isEnabled)
    }

    @Test
    fun defaultFileNameHasTheDate() {
        val name = ChatBackup.defaultFileName(java.util.GregorianCalendar(2026, 10, 24, 12, 0).timeInMillis)
        assertEquals("LightDecoder-backup-2026-11-24.ldbackup", name)
    }
}
