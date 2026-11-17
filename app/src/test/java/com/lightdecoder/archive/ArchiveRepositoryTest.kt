package com.lightdecoder.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ReactionDto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The archive's upsert rules, search, and bookkeeping tables, against real SQLite. */
@RunWith(AndroidJUnit4::class)
class ArchiveRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        db = ArchiveDb(context, DB_NAME)
        repo = ArchiveRepository.forTesting(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- messages ---------------------------------------------------------------

    @Test
    fun insertsThenUpdatesInPlace() {
        val first = repo.upsertMessage(message("m1", text = "hello", status = "INCOMING_COMPLETE"))
        assertTrue(first.inserted)
        assertNull(first.replacedTmpId)

        val second = repo.upsertMessage(
            message("m1", text = "hello", status = "INCOMING_DISPLAYED", reactions = listOf(ReactionDto("👍", listOf("p2"))))
        )
        assertFalse(second.inserted)

        val stored = repo.message("m1")!!
        assertEquals("INCOMING_DISPLAYED", stored.status)
        assertEquals(listOf(ReactionDto("👍", listOf("p2"))), stored.reactions)
        assertEquals(LocalState.SYNCED, stored.localState)
        assertEquals(1, repo.messages(CONV).size)
    }

    @Test
    fun unknownConversationGetsPlaceholderWithLatestMessage() {
        repo.upsertMessage(message("m1", conversationId = "c-new", text = "first", ts = 1_000))
        val conversation = repo.conversation("c-new")
        assertNotNull(conversation)
        assertEquals("", conversation!!.name)
        assertEquals(1_000L, conversation.lastMessageTs)
        assertEquals("m1", conversation.latestMessageId)
        assertEquals("first", conversation.snippet)
    }

    @Test
    fun latestMessageOnlyMovesForward() {
        repo.upsertMessage(message("new", text = "newer", ts = 2_000))
        repo.upsertMessage(message("old", text = "older", ts = 1_000, isOld = true))
        val conversation = repo.conversation(CONV)!!
        assertEquals("new", conversation.latestMessageId)
        assertEquals("newer", conversation.snippet)
    }

    @Test
    fun conversationUpdateKeepsMessages() {
        repo.upsertConversation(conversation(CONV, name = "Alex", ts = 500))
        repo.upsertMessage(message("m1", ts = 400))
        repo.upsertConversation(conversation(CONV, name = "Alex B", ts = 500))
        assertEquals("Alex B", repo.conversation(CONV)!!.name)
        assertEquals(1, repo.messages(CONV).size)
    }

    @Test
    fun localOutgoingIsReplacedByTheRealMessage() {
        repo.insertLocalOutgoing("t1", CONV, "on my way", null, null, null, null, nowMs = 5_000)
        repo.enqueueOutbox(OutboxRow("t1", CONV, textBody = "on my way", createdAt = 5_000))
        val local = repo.message("tmp:t1")!!
        assertTrue(local.isLocal)
        assertEquals(LocalState.PENDING, local.localState)
        assertEquals("tmp:t1", repo.conversation(CONV)!!.latestMessageId)

        repo.setLocalState("t1", LocalState.SENDING)
        assertEquals(LocalState.SENDING, repo.message("tmp:t1")!!.localState)
        repo.updateOutbox("t1", OutboxStatus.SENT)

        val result = repo.upsertMessage(
            message("real1", tmpId = "t1", text = "on my way", fromMe = true, direction = "out", ts = 5_100)
        )
        assertTrue(result.inserted)
        assertEquals("t1", result.replacedTmpId)
        assertNull(repo.message("tmp:t1"))
        assertNull("SENT outbox entry removed", repo.outbox("t1"))
        assertEquals(listOf("real1"), repo.messages(CONV).map { it.id })
        assertEquals("real1", repo.conversation(CONV)!!.latestMessageId)
        assertEquals(listOf("real1"), repo.search("way").map { it.messageId })
    }

    @Test
    fun replacementKeepsAnOutboxEntryThatIsNotSent() {
        repo.insertLocalOutgoing("t2", CONV, "hi", null, null, null, null, nowMs = 1_000)
        repo.enqueueOutbox(OutboxRow("t2", CONV, textBody = "hi", status = OutboxStatus.SENDING, createdAt = 1_000))
        val result = repo.upsertMessage(message("real2", tmpId = "t2", fromMe = true, direction = "out", ts = 1_001))
        assertEquals("t2", result.replacedTmpId)
        assertNotNull(repo.outbox("t2"))
    }

    @Test
    fun echoRetiresAnOutboxEntryThatWouldOtherwiseBeSentAgain() {
        // phone_not_responding left it queued for a retry, but the phone sent it after all.
        val copy = MediaCache.outboxFile(context, "t4").apply { writeText("picture") }
        repo.insertLocalOutgoing("t4", CONV, "", copy.path, "image/png", "a.png", null, nowMs = 1_000)
        repo.enqueueOutbox(OutboxRow("t4", CONV, mediaLocalPath = copy.path, status = OutboxStatus.PENDING, attempts = 1, createdAt = 1_000))

        repo.upsertMessage(message("real4", tmpId = "t4", fromMe = true, direction = "out", ts = 1_001))

        assertNull(repo.outbox("t4"))
        assertTrue(repo.pendingOutbox().isEmpty())
        assertFalse("the attachment copy goes with it", copy.exists())
    }

    @Test
    fun anEchoBeforeTheSendReturnsLeavesNothingBehind() {
        val copy = MediaCache.outboxFile(context, "t5").apply { writeText("picture") }
        repo.insertLocalOutgoing("t5", CONV, "", copy.path, "image/png", "a.png", null, nowMs = 1_000)
        repo.enqueueOutbox(OutboxRow("t5", CONV, mediaLocalPath = copy.path, status = OutboxStatus.SENDING, createdAt = 1_000))

        repo.upsertMessage(message("real5", tmpId = "t5", fromMe = true, direction = "out", ts = 1_001))
        assertNotNull("still being sent", repo.outbox("t5"))
        assertTrue(copy.exists())

        repo.updateOutbox("t5", OutboxStatus.SENT)
        assertNull(repo.outbox("t5"))
        assertFalse(copy.exists())
    }

    @Test
    fun aDeletionWithoutConversationIdStillApplies() {
        repo.upsertMessage(message("m1", text = "unsent"))
        repo.upsertMessage(message("m1", conversationId = "", direction = "deleted"))
        assertNull(repo.message("m1"))
        assertTrue(repo.search("unsent").isEmpty())
    }

    @Test
    fun localOutgoingWithAttachmentAndFailure() {
        repo.insertLocalOutgoing("t3", CONV, "", "/data/x/relay-outbox/t3", "image/heic", "a.heic", "m0", nowMs = 1_000)
        val local = repo.message("tmp:t3")!!
        assertEquals("m0", local.replyToId)
        assertEquals(1, local.media.size)
        assertEquals("/data/x/relay-outbox/t3", local.media[0].localPath)
        assertEquals("image/heic", local.media[0].mime)

        repo.setLocalState("tmp:t3", LocalState.FAILED, "send_failed")
        val failed = repo.message("tmp:t3")!!
        assertEquals(LocalState.FAILED, failed.localState)
        assertEquals("send_failed", failed.errorText)

        // A second insert with the same tmpId changes nothing.
        repo.insertLocalOutgoing("t3", CONV, "again", null, null, null, null, nowMs = 2_000)
        assertEquals(LocalState.FAILED, repo.message("tmp:t3")!!.localState)
    }

    @Test
    fun deletionRemovesMessageAndSearchEntry() {
        repo.upsertMessage(message("m1", text = "secret plans"))
        repo.upsertMessage(message("m2", text = "more plans"))
        assertEquals(2, repo.search("plans").size)

        val result = repo.upsertMessage(message("m1", direction = "deleted"))
        assertFalse(result.inserted)
        assertNull(repo.message("m1"))
        assertEquals(listOf("m2"), repo.search("plans").map { it.messageId })

        repo.applyDeletion("m2")
        assertNull(repo.message("m2"))
        assertTrue(repo.search("plans").isEmpty())

        // Deleting something never stored is harmless and stores nothing.
        repo.upsertMessage(message("ghost", direction = "deleted"))
        assertNull(repo.message("ghost"))
    }

    @Test
    fun messagesComeOldestFirstAndPageBackwards() {
        repo.upsertMessages((1..5).map { message("m$it", ts = it * 1_000L) })
        assertEquals(listOf("m4", "m5"), repo.messages(CONV, limit = 2).map { it.id })
        assertEquals(listOf("m2", "m3"), repo.messages(CONV, beforeTs = 4_000, limit = 2).map { it.id })
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), repo.messages(CONV).map { it.id })
    }

    @Test
    fun upsertMessagesCountsStoredMessagesAndAppliesDeletions() {
        repo.upsertMessage(message("gone", text = "x"))
        val count = repo.upsertMessages(
            listOf(message("a"), message("b"), message("a", text = "edited"), message("gone", direction = "deleted"))
        )
        assertEquals(3, count)
        assertNull(repo.message("gone"))
        assertEquals("edited", repo.message("a")!!.text)
    }

    // --- media ------------------------------------------------------------------

    @Test
    fun mediaKeepsLocalWorkWhileItsIdIsUnchanged() {
        val key = ByteArray(32) { it.toByte() }
        val first = repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "M1", thumbId = "T1", key = key))))
        assertEquals(1, first.newMediaParts.size)
        assertEquals("M1", first.newMediaParts[0].mediaId)
        assertArrayEquals(key, first.newMediaParts[0].key)

        val full = MediaCache.fileFor(context, "M1", "heic").apply { writeText("full") }
        val thumb = MediaCache.fileFor(context, "T1", "jpg").apply { writeText("thumb") }
        repo.setMediaLocalPath("m1", "p1", full.path, thumbnail = false)
        repo.setMediaLocalPath("m1", "p1", thumb.path, thumbnail = true)
        repo.markStickerChecked("m1", "p1")
        repo.markFullSizeRequested("m1", "p1")

        // A status update with the same media: nothing new, everything kept.
        val again = repo.upsertMessage(
            message("m1", status = "INCOMING_DISPLAYED", parts = listOf(mediaPart("p1", mediaId = "M1", thumbId = "T1", key = key)))
        )
        assertTrue(again.newMediaParts.isEmpty())
        repo.mediaPart("m1", "p1")!!.let {
            assertEquals(full.path, it.localPath)
            assertEquals(thumb.path, it.thumbPath)
            assertTrue(it.stickerChecked)
            assertTrue(it.fullSizeRequested)
        }

        // The full size arrives under a new id: reported, the old file dropped, the thumbnail kept.
        val upgraded = repo.upsertMessage(
            message("m1", parts = listOf(mediaPart("p1", mediaId = "M2", thumbId = "T1", key = key)))
        )
        assertEquals(listOf("M2"), upgraded.newMediaParts.map { it.mediaId })
        repo.mediaPart("m1", "p1")!!.let {
            assertEquals("", it.localPath)
            assertEquals(thumb.path, it.thumbPath)
            assertFalse(it.stickerChecked)
            assertFalse(it.fullSizeRequested)
        }
        assertFalse("orphaned media file deleted", full.exists())
        assertTrue(thumb.exists())
    }

    @Test
    fun thumbnailOnlyPartIsReportedAndTextPartsAreNotMedia() {
        val result = repo.upsertMessage(
            message(
                "m1",
                text = "look",
                parts = listOf(
                    PartDto("t", kind = "text", text = "look"),
                    mediaPart("p1", mediaId = "", thumbId = "T1", needsFullSize = true),
                    mediaPart("p2", mediaId = "", thumbId = ""),
                ),
            )
        )
        assertEquals(listOf("p1"), result.newMediaParts.map { it.partId })
        assertTrue(result.newMediaParts[0].needsFullSize)
        assertEquals(listOf("p1", "p2"), repo.media("m1").map { it.partId })
    }

    @Test
    fun deletingAMessageDeletesItsMediaFiles() {
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "M1"))))
        val file = MediaCache.fileFor(context, "M1", "png").apply { writeText("x") }
        repo.setMediaLocalPath("m1", "p1", file.path, thumbnail = false)
        repo.applyDeletion("m1")
        assertFalse(file.exists())
        assertTrue(repo.media("m1").isEmpty())
    }

    @Test
    fun aDownloadThatFinishesAfterItsMessageWasDeletedLeavesNoFile() {
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "late-M1", thumbId = "late-T1"))))
        // The user deletes the message while the picture and its thumbnail are being fetched.
        repo.applyDeletion("m1")
        val full = MediaCache.fileFor(context, "late-M1", "heic").apply { writeText("decrypted") }
        val thumb = MediaCache.fileFor(context, "late-T1", "jpg").apply { writeText("decrypted") }

        assertFalse(repo.setMediaLocalPath("m1", "p1", "late-M1", full.path, thumbnail = false))
        assertFalse(repo.setMediaLocalPath("m1", "p1", "late-T1", thumb.path, thumbnail = true))
        assertFalse(full.exists())
        assertFalse(thumb.exists())

        // The same without a cache key.
        val again = MediaCache.fileFor(context, "late-M1", "heic").apply { writeText("decrypted") }
        assertFalse(repo.setMediaLocalPath("m1", "p1", again.path, thumbnail = false))
        assertFalse(again.exists())
    }

    @Test
    fun aDownloadOfMediaThePartNoLongerHasIsNotRecorded() {
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "old-M", thumbId = "T"))))
        // The full size arrives under a new id while the old one is still downloading.
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "new-M", thumbId = "T"))))
        val stale = MediaCache.fileFor(context, "old-M", "heic").apply { writeText("old") }
        assertFalse(repo.setMediaLocalPath("m1", "p1", "old-M", stale.path, thumbnail = false))
        assertEquals("", repo.mediaPart("m1", "p1")!!.localPath)
        assertFalse(stale.exists())

        val current = MediaCache.fileFor(context, "new-M", "heic").apply { writeText("new") }
        assertTrue(repo.setMediaLocalPath("m1", "p1", "new-M", current.path, thumbnail = false))
        val thumb = MediaCache.fileFor(context, "T", "jpg").apply { writeText("thumb") }
        assertTrue(repo.setMediaLocalPath("m1", "p1", "T", thumb.path, thumbnail = true))
        repo.mediaPart("m1", "p1")!!.let {
            assertEquals(current.path, it.localPath)
            assertEquals(thumb.path, it.thumbPath)
        }
        // A thumbnail id is not a full-size id.
        assertFalse(repo.setMediaLocalPath("m1", "p1", "T", thumb.path, thumbnail = false))
        assertEquals(current.path, repo.mediaPart("m1", "p1")!!.localPath)
        assertTrue("still the part's thumbnail", thumb.exists())
    }

    @Test
    fun aLateFileAnotherPartStillUsesIsKept() {
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "shared-M"))))
        repo.upsertMessage(message("m2", parts = listOf(mediaPart("p1", mediaId = "shared-M"))))
        val file = MediaCache.fileFor(context, "shared-M", "png").apply { writeText("x") }
        assertTrue(repo.setMediaLocalPath("m1", "p1", "shared-M", file.path, thumbnail = false))
        repo.applyDeletion("m2")
        assertFalse(repo.setMediaLocalPath("m2", "p1", "shared-M", file.path, thumbnail = false))
        assertTrue(file.exists())
        assertEquals(file.path, repo.mediaPart("m1", "p1")!!.localPath)
    }

    @Test
    fun inlineMediaIsRecordedOnlyWhileThePartStillHasIt() {
        val inline = PartDto("p1", kind = "media", mime = "image/heic", inlineData = byteArrayOf(1, 2, 3))
        repo.upsertMessage(message("m1", parts = listOf(inline)))
        val key = MediaCache.inlineKey("m1", "p1")
        val file = MediaCache.fileFor(context, key, "heic").apply { writeText("inline") }
        assertTrue(repo.setMediaLocalPath("m1", "p1", key, file.path, thumbnail = false))
        assertEquals(file.path, repo.mediaPart("m1", "p1")!!.localPath)

        // The part now points to a downloadable file instead: the inline copy is not recorded again.
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "M-from-inline"))))
        assertFalse(file.exists())
        val late = MediaCache.fileFor(context, key, "heic").apply { writeText("inline") }
        assertFalse(repo.setMediaLocalPath("m1", "p1", key, late.path, thumbnail = false))
        assertFalse(late.exists())
    }

    @Test
    fun aNewFileForAPartDeletesTheOneItReplaces() {
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "M-ext"))))
        val first = MediaCache.fileFor(context, "M-ext", "bin").apply { writeText("x") }
        assertTrue(repo.setMediaLocalPath("m1", "p1", "M-ext", first.path, thumbnail = false))
        val second = MediaCache.fileFor(context, "M-ext", "heic").apply { writeText("x") }
        assertTrue(repo.setMediaLocalPath("m1", "p1", "M-ext", second.path, thumbnail = false))
        assertFalse(first.exists())
        assertTrue(second.exists())
        // Recording the same file again keeps it.
        assertTrue(repo.setMediaLocalPath("m1", "p1", "M-ext", second.path, thumbnail = false))
        assertTrue(second.exists())
    }

    @Test
    fun writeAtomicallyLeavesNoPartialFile() {
        val file = MediaCache.fileFor(context, "atomic-M", "heic")
        MediaCache.writeAtomically(file, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertTrue(partFiles(file.parentFile!!).isEmpty())

        // The rename fails (a non-empty directory is in the way): the partial file goes too.
        val blocked = MediaCache.fileFor(context, "atomic-blocked", "heic").apply { mkdirs() }
        File(blocked, "inside").writeText("x")
        try {
            MediaCache.writeAtomically(blocked, byteArrayOf(1))
            fail("expected an IOException")
        } catch (e: IOException) {
            // expected
        }
        assertTrue(partFiles(blocked.parentFile!!).isEmpty())
        blocked.deleteRecursively()
    }

    @Test
    fun writersOfTheSameMediaDoNotShareAPartialFile() {
        // Two downloads of the same media at once (the sticker check and the
        // conversation screen). Another writer is still writing its partial
        // file, under the name a shared temporary file would have had.
        val file = MediaCache.fileFor(context, "atomic-twice", "heic")
        val shared = File(file.parentFile, file.name + MediaCache.PART_SUFFIX)
        FileOutputStream(shared).use { other ->
            MediaCache.writeAtomically(file, byteArrayOf(1, 2, 3, 4))
            other.write(byteArrayOf(9, 9)) // the other writer carries on
        }
        assertArrayEquals("the finished file is whole and stays so", byteArrayOf(1, 2, 3, 4), file.readBytes())
        shared.delete()
        assertTrue(partFiles(file.parentFile!!).isEmpty())
    }

    private fun partFiles(dir: File) = dir.listFiles().orEmpty().filter { it.name.endsWith(MediaCache.PART_SUFFIX) }

    @Test
    fun stalePartialFilesAreDeletedOnTheFirstWrite() {
        val dir = MediaCache.mediaDir(context).apply { mkdirs() }
        val stale = File(dir, "abc.heic.part").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - MediaCache.STALE_PART_MS - 60_000)
        }
        val inProgress = File(dir, "def.heic.part").apply { writeText("x") }
        val oldMedia = File(dir, "abc.heic").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - MediaCache.STALE_PART_MS - 60_000)
        }

        repo.updateHealth(status = "connected")

        assertFalse(stale.exists())
        assertTrue("a write in progress is left alone", inProgress.exists())
        assertTrue("only partial files", oldMedia.exists())
        listOf(inProgress, oldMedia).forEach { it.delete() }
    }

    // --- search -----------------------------------------------------------------

    @Test
    fun searchMatchesWordsPrefixesCaseAndAccents() {
        repo.upsertConversation(conversation(CONV, name = "Book club"))
        repo.upsertMessage(message("m1", text = "Meet at the Café tomorrow", ts = 1_000))
        repo.upsertMessage(message("m2", text = "Tomorrow is fine", ts = 2_000))

        assertEquals(listOf("m2", "m1"), repo.search("tomorrow").map { it.messageId })
        assertEquals(listOf("m2", "m1"), repo.search("tomor").map { it.messageId })
        assertEquals(listOf("m1"), repo.search("cafe").map { it.messageId })
        assertEquals(listOf("m1"), repo.search("meet tomor").map { it.messageId })
        assertTrue(repo.search("meet fine").isEmpty())

        val hit = repo.search("café").single()
        assertEquals(CONV, hit.conversationId)
        assertEquals("Book club", hit.conversationName)
        assertEquals(1_000L, hit.timestampMs)
        assertTrue(hit.snippet, hit.snippet.contains("Café"))
    }

    @Test
    fun searchTreatsSyntaxCharactersAsText() {
        repo.upsertMessage(message("m1", text = "Price: \$5 (or less) - \"quoted\" AND NOT this*"))
        val inputs = listOf(
            "\"quoted", "(or", "AND", "NOT", "this*", "price:", "\"", "*", "-", ")(", "NEAR/2 less",
            "text_body:price", "'", "a OR", "   ", "",
        )
        for (input in inputs) repo.search(input) // none of these may throw
        assertEquals(listOf("m1"), repo.search("\"quoted").map { it.messageId })
        assertEquals(listOf("m1"), repo.search("(or less)").map { it.messageId })
        assertEquals(listOf("m1"), repo.search("AND").map { it.messageId })
        assertEquals(listOf("m1"), repo.search("price:").map { it.messageId })
        assertTrue(repo.search("*").isEmpty())
        assertTrue(repo.search("\"").isEmpty())
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun searchSurvivesHostileInput() {
        repo.upsertMessage(message("m1", text = "hello world, see you at 5"))
        val inputs = listOf(
            "hel\u0000lo", "hello\u0000", "\u0000", "wor\u0001ld", "^hello", "hello ^wor",
            (1..5_000).joinToString(" ") { "hello" }, "😀", "hello 😀", "→ world", "€ 5",
        )
        for (input in inputs) repo.search(input) // none of these may throw
        assertEquals(listOf("m1"), repo.search("hello 😀").map { it.messageId })
        assertEquals(listOf("m1"), repo.search("→ wor").map { it.messageId })
        assertEquals(listOf("m1"), repo.search("^world").map { it.messageId })
        assertEquals(listOf("m1"), repo.search((1..5_000).joinToString(" ") { "hello" }).map { it.messageId })
        assertTrue(repo.search("😀").isEmpty())
    }

    @Test
    fun ftsQueryQuotesEveryWord() {
        assertEquals("\"hello\" \"wor*\"", ArchiveRepository.ftsQuery("hello wor"))
        assertEquals("\"say\" \"hi*\"", ArchiveRepository.ftsQuery("say \"hi\"*-"))
        assertEquals("\"(or\" \"less*\"", ArchiveRepository.ftsQuery("(or less)"))
        assertNull(ArchiveRepository.ftsQuery(" * \" - "))
    }

    // --- conversations ----------------------------------------------------------

    @Test
    fun conversationsArePinnedFirstThenNewest() {
        repo.upsertConversations(
            listOf(
                conversation("a", ts = 100),
                conversation("b", ts = 300),
                conversation("c", ts = 200, pinned = true),
                conversation("d", ts = 400, folder = "archive"),
            )
        )
        assertEquals(listOf("c", "b", "a"), repo.conversations().map { it.id })
        assertEquals(listOf("d"), repo.conversations(Folder.ARCHIVE).map { it.id })
        assertEquals(setOf("a", "b", "c", "d"), repo.conversations(null).map { it.id }.toSet())
        assertEquals(listOf("c"), repo.conversations(limit = 1).map { it.id })
    }

    @Test
    fun participantsAreReplaced() {
        repo.upsertConversation(
            conversation(CONV, participants = listOf(ParticipantDto("1", name = "Me", isMe = true), ParticipantDto("2", name = "Sam")))
        )
        repo.upsertConversation(conversation(CONV, participants = listOf(ParticipantDto("3", name = "Kim"))))
        assertEquals(listOf("3"), repo.participants(CONV).map { it.id })
        assertEquals("Kim", repo.participants(CONV).single().name)
    }

    @Test
    fun foldersReadStateAndMute() {
        repo.upsertConversation(conversation(CONV, unread = true))
        assertEquals(1, repo.unreadCount())
        repo.markConversationRead(CONV)
        assertEquals(0, repo.unreadCount())

        repo.setConversationFolder(CONV, Folder.ARCHIVE)
        assertTrue(repo.conversations(Folder.INBOX).isEmpty())
        assertEquals(Folder.ARCHIVE, repo.conversation(CONV)!!.folderEnum)

        repo.setConversationMuted(CONV, true)
        // The phone never reports mute, so its "false" does not undo the local choice.
        repo.upsertConversation(conversation(CONV, folder = "archive"))
        assertTrue(repo.conversation(CONV)!!.muted)
        repo.setConversationMuted(CONV, false)
        assertFalse(repo.conversation(CONV)!!.muted)
    }

    @Test
    fun conversationWithoutTimestampKeepsItsLatestMessage() {
        repo.upsertMessage(message("m1", text = "kept", ts = 1_000))
        repo.upsertConversation(conversation(CONV, name = "Named", ts = 0))
        val c = repo.conversation(CONV)!!
        assertEquals("Named", c.name)
        assertEquals("kept", c.snippet)
        assertEquals(1_000L, c.lastMessageTs)
    }

    @Test
    fun aConversationThePhoneDeletedMovesToTheDeletedFolderWithEverythingInIt() {
        repo.upsertConversation(conversation(CONV, name = "Old"))
        repo.upsertMessage(message("m1", text = "findme", parts = listOf(mediaPart("p1", mediaId = "M1"))))
        repo.setSyncState(SyncStateRow(CONV, cursorId = "m1", cursorTs = 1))
        repo.enqueueOutbox(OutboxRow("t9", CONV, textBody = "x", createdAt = 1))
        val file = MediaCache.fileFor(context, "M1", "jpg").apply { writeText("x") }
        repo.setMediaLocalPath("m1", "p1", file.path, thumbnail = false)

        repo.upsertConversation(conversation(CONV, folder = "deleted"))

        val kept = repo.conversation(CONV)!!
        assertEquals(Folder.DELETED, kept.folderEnum)
        assertEquals("Old", kept.name)
        assertTrue("not in the inbox", repo.conversations(Folder.INBOX).isEmpty())
        assertEquals(listOf(CONV), repo.conversations(Folder.DELETED).map { it.id })
        assertNotNull(repo.message("m1"))
        assertEquals(1, repo.search("findme").size)
        assertTrue(file.exists())
        // One the archive never had is not created for that.
        repo.upsertConversation(conversation("never-seen", folder = "deleted"))
        assertNull(repo.conversation("never-seen"))

        // Restored here: back in the inbox. Only from the deleted folder.
        assertTrue(repo.restoreDeleted(CONV))
        assertEquals(Folder.INBOX, repo.conversation(CONV)!!.folderEnum)
        assertFalse(repo.restoreDeleted(CONV))

        // Purged: gone for good, with everything in it.
        repo.upsertConversation(conversation(CONV, folder = "deleted"))
        repo.upsertConversation(conversation("other", folder = "inbox"))
        assertEquals("only from the deleted folder", 0, repo.purgeDeleted("other"))
        assertEquals(1, repo.purgeDeleted(null))
        assertNotNull(repo.conversation("other"))

        assertNull(repo.conversation(CONV))
        assertNull(repo.message("m1"))
        assertTrue(repo.media("m1").isEmpty())
        assertTrue(repo.search("findme").isEmpty())
        assertNull(repo.syncState(CONV))
        assertNull(repo.outbox("t9"))
        assertFalse(file.exists())
    }

    @Test
    fun deleteConversationLocal() {
        repo.upsertMessage(message("m1", text = "bye"))
        repo.deleteConversationLocal(CONV)
        assertNull(repo.conversation(CONV))
        assertTrue(repo.messages(CONV).isEmpty())
        assertTrue(repo.search("bye").isEmpty())
    }

    // --- media paths ----------------------------------------------------------

    @Test
    fun mediaPathsAreIndexedAlsoAfterAnUpgradeFromVersion1() {
        val fresh = indexes(db.readableDatabase)
        assertTrue(fresh.containsAll(listOf("idx_media_local_path", "idx_media_thumb_path")))

        // A version 1 archive (no such indexes) gets them on upgrade, keeping its rows.
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "M1"))))
        val raw = db.writableDatabase
        raw.execSQL("DROP INDEX idx_media_local_path")
        raw.execSQL("DROP INDEX idx_media_thumb_path")
        db.onUpgrade(raw, 1, ArchiveDb.VERSION)
        assertTrue(indexes(raw).containsAll(listOf("idx_media_local_path", "idx_media_thumb_path")))
        assertNotNull(repo.mediaPart("m1", "p1"))
        assertEquals(2, ArchiveDb.VERSION)
        // The used-file check goes through them, not a scan of the table.
        val plan = raw.rawQuery(
            "EXPLAIN QUERY PLAN SELECT 1 FROM media WHERE local_path = ? OR thumb_path = ? LIMIT 1", arrayOf("a", "a"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.columnCount - 1)) } }.joinToString(" | ")
        assertTrue(plan, plan.contains("idx_media_local_path") && plan.contains("idx_media_thumb_path"))
    }

    @Test
    fun aDownloadLandingOnAFileBeingCleanedUpKeepsIt() {
        // m1's part had media M1 on disk; m2 shows the same media M1 (the
        // same file). m1's part gets other media, so M1's file may be unused:
        // the cleanup checks it after its write. Meanwhile a download of M1
        // for m2 writes that same path and records it.
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "M1"))))
        repo.upsertMessage(message("m2", parts = listOf(mediaPart("p1", mediaId = "M1"))))
        val file = MediaCache.fileFor(context, "M1", "jpg").apply { writeText("old") }
        assertTrue(repo.setMediaLocalPath("m1", "p1", "M1", file.path, thumbnail = false))

        val downloading = java.util.concurrent.CountDownLatch(1)
        val download = Thread {
            repo.withMediaFileLock {
                downloading.countDown()
                // The cleanup's write commits meanwhile and waits for this.
                Thread.sleep(300)
                MediaCache.writeAtomically(file, "new".toByteArray())
                assertTrue(repo.setMediaLocalPath("m2", "p1", "M1", file.path, thumbnail = false))
            }
        }.apply { start() }
        assertTrue(downloading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", mediaId = "M9"))))
        download.join(5_000)

        assertTrue("the new download is kept", file.exists())
        assertEquals("new", file.readText())
        assertEquals(file.path, repo.mediaPart("m2", "p1")!!.localPath)

        // Once nothing uses it, it does go.
        repo.upsertMessage(message("m2", parts = listOf(mediaPart("p1", mediaId = "M8"))))
        assertFalse(file.exists())
    }

    private fun indexes(database: android.database.sqlite.SQLiteDatabase): List<String> =
        database.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    // --- outbox, health, sync state, contacts -------------------------------------

    @Test
    fun outboxLifecycle() {
        repo.enqueueOutbox(OutboxRow("k2", CONV, textBody = "second", createdAt = 2))
        repo.enqueueOutbox(OutboxRow("k1", CONV, textBody = "first", mediaLocalPath = "/p", mediaMime = "image/png", createdAt = 1))
        assertEquals(listOf("k1", "k2"), repo.pendingOutbox().map { it.idempotencyKey })
        assertEquals("image/png", repo.outbox("k1")!!.mediaMime)

        repo.updateOutbox("k1", OutboxStatus.SENDING, attemptsDelta = 1)
        assertEquals("interrupted sends are still pending", 2, repo.pendingOutbox().size)
        repo.updateOutbox("k1", OutboxStatus.FAILED, attemptsDelta = 1, error = "not_connected")
        repo.outbox("k1")!!.let {
            assertEquals(OutboxStatus.FAILED, it.status)
            assertEquals(2, it.attempts)
            assertEquals("not_connected", it.lastError)
        }
        assertEquals(listOf("k2"), repo.pendingOutbox().map { it.idempotencyKey })

        repo.deleteOutbox("k2")
        assertNull(repo.outbox("k2"))
        repo.updateOutbox("missing", OutboxStatus.SENT) // no row, no error
    }

    @Test
    fun healthKeepsFieldsThatAreNotUpdated() {
        assertEquals(HealthRow(), repo.health())
        repo.updateHealth(status = "connected", lastEventTs = 5, account = "a@example.com")
        repo.updateHealth(lastError = "phone_not_responding")
        val health = repo.health()
        assertEquals("connected", health.status)
        assertEquals(5L, health.lastEventTs)
        assertEquals("a@example.com", health.account)
        assertEquals("phone_not_responding", health.lastError)
        assertEquals(0L, health.lastTokenRefresh)
    }

    @Test
    fun syncStateRoundTrip() {
        assertNull(repo.syncState(CONV))
        repo.setSyncState(SyncStateRow(CONV))
        assertNull(repo.syncState(CONV)!!.cursor)
        repo.setSyncState(SyncStateRow(CONV, cursorId = "m9", cursorTs = 900, historyComplete = true, lastBackfillAt = 77))
        val state = repo.syncState(CONV)!!
        assertEquals("m9", state.cursor!!.lastItemId)
        assertEquals(900L, state.cursor!!.lastItemTimestampMs)
        assertTrue(state.historyComplete)
        assertEquals(77L, state.lastBackfillAt)
        assertNotNull("a sync state needs its conversation", repo.conversation(CONV))
    }

    @Test
    fun contactsAreUpsertedAndFiltered() {
        repo.upsertContacts(
            listOf(
                ContactDto("p1", name = "Alice Ng", number = "+15550001", formattedNumber = "(555) 0001"),
                ContactDto("p2", name = "Bob", number = "+15550002"),
                ContactDto("", name = "No id", number = "+15550003", contactId = "c3"),
                ContactDto("", name = "Nothing"),
            )
        )
        assertEquals(listOf("Alice Ng", "Bob", "No id"), repo.contacts().map { it.displayName })
        assertEquals(listOf("p1"), repo.contacts("ali").map { it.participantId })
        assertEquals(listOf("p2"), repo.contacts("0002").map { it.participantId })
        assertTrue("LIKE wildcards are escaped", repo.contacts("%").isEmpty())

        repo.upsertContacts(listOf(ContactDto("p1", name = "Alice N", number = "+15550001")))
        assertEquals(3, repo.contacts().size)
        assertEquals("Alice N", repo.contacts("alice").single().displayName)
    }

    @Test
    fun clearAllWipesTablesAndFiles() {
        repo.upsertConversation(conversation(CONV))
        repo.upsertMessage(message("m1", text = "wipe me"))
        repo.enqueueOutbox(OutboxRow("k", CONV, createdAt = 1))
        repo.updateHealth(status = "connected")
        repo.upsertContacts(listOf(ContactDto("p1", name = "A")))
        val media = MediaCache.fileFor(context, "M1", "jpg").apply { writeText("x") }
        val outbox = MediaCache.outboxFile(context, "k").apply { writeText("x") }

        repo.clearAll()

        assertTrue(repo.conversations(null).isEmpty())
        assertNull(repo.message("m1"))
        assertTrue(repo.search("wipe").isEmpty())
        assertTrue(repo.pendingOutbox().isEmpty())
        assertEquals(HealthRow(), repo.health())
        assertTrue(repo.contacts().isEmpty())
        assertFalse(media.exists())
        assertFalse(outbox.exists())
        assertFalse(MediaCache.mediaDir(context).exists())
    }

    @Test
    fun mediaCacheNamesAreHashes() {
        val file = MediaCache.fileFor(context, "some/media:id", "heic")
        assertEquals(MediaCache.mediaDir(context), file.parentFile)
        assertTrue(file.name, file.name.matches(Regex("[0-9a-f]{32}\\.heic")))
        assertEquals(file, MediaCache.fileFor(context, "some/media:id", "heic"))
    }

    // --- fixtures ---------------------------------------------------------------

    private fun message(
        id: String,
        conversationId: String = CONV,
        text: String = "",
        ts: Long = 1_000,
        status: String = "INCOMING_COMPLETE",
        direction: String = "in",
        fromMe: Boolean = false,
        tmpId: String = "",
        isOld: Boolean = false,
        parts: List<PartDto> = emptyList(),
        reactions: List<ReactionDto> = emptyList(),
    ) = MessageDto(
        id = id,
        conversationId = conversationId,
        tmpId = tmpId,
        senderId = if (fromMe) "1" else "2",
        senderName = if (fromMe) "" else "Sam",
        fromMe = fromMe,
        timestampMs = ts,
        direction = direction,
        status = status,
        transport = "RCS",
        text = text,
        parts = parts,
        reactions = reactions,
        isOld = isOld,
    )

    private fun mediaPart(
        partId: String,
        mediaId: String,
        thumbId: String = "",
        key: ByteArray = ByteArray(32),
        needsFullSize: Boolean = false,
    ) = PartDto(
        partId = partId,
        kind = "media",
        mediaId = mediaId,
        key = key,
        thumbMediaId = thumbId,
        thumbKey = if (thumbId.isEmpty()) ByteArray(0) else ByteArray(32) { 7 },
        mime = "image/heic",
        size = 1234,
        width = 300,
        height = 300,
        needsFullSize = needsFullSize,
    )

    private fun conversation(
        id: String,
        name: String = "",
        ts: Long = 0,
        pinned: Boolean = false,
        unread: Boolean = false,
        folder: String = "inbox",
        participants: List<ParticipantDto> = emptyList(),
    ) = ConversationDto(
        id = id,
        name = name,
        type = "RCS",
        folder = folder,
        unread = unread,
        pinned = pinned,
        lastMessageTs = ts,
        latestMessageId = if (ts > 0) "latest-$id" else "",
        snippet = if (ts > 0) "snippet $id" else "",
        participants = participants,
    )

    private companion object {
        const val DB_NAME = "archive-test.db"
        const val CONV = "c1"
    }
}
