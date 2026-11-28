package com.lightdecoder.imessage.direct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IconChangeMessage
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * A group's name, members and photo changed from here (a chat's details) through the iMessage
 * library, as an iPhone does: Apple first, then here with a row of the chat's own; Apple's group
 * versions followed; a group photo from Apple shown; and a chat's own "Send Read Receipts".
 */
@RunWith(AndroidJUnit4::class)
class DirectGroupsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val alice = "tel:+15551234567"
    private val carol = "tel:+15553334444"
    private val dave = "tel:+15556667777"
    private val mallory = "tel:+15558889999"
    private val id = "imsg:group:G-1"
    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        GroupPhotos.dir(context).deleteRecursively()
        for (file in listOf("imessage_group_versions", "imessage_read_receipts", "imessage_group_names")) {
            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
        }
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
    }

    @After
    fun tearDown() {
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
        GroupPhotos.dir(context).deleteRecursively()
    }

    // --- set-up ------------------------------------------------------------------------------------

    private fun reached(handle: String): JSONObject = JSONObject().put(
        "conversation",
        ConversationDto(
            id = "imsg:$handle", type = DirectArchive.TRANSPORT, defaultOutgoingId = me,
            participants = listOf(ParticipantDto(id = handle, number = Handles.display(handle)), ParticipantDto(id = me, isMe = true)),
            otherParticipantIds = listOf(handle),
        ).toJson(),
    )

    /** A link whose requests Apple answers as it would: [onIMessage] are on iMessage; [answer] answers in its place where it does. */
    private fun link(onIMessage: Set<String> = setOf(alice, bob, carol, dave), answer: (Int, JSONObject) -> JSONObject? = { _, _ -> null }) =
        DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            answer(what, request) ?: when (what) {
                DirectWire.REACH -> Handles.normalize(request.getString("recipient"))!!.let { if (it in onIMessage) reached(it) else JSONObject() }
                DirectWire.RENAME, DirectWire.MEMBERS, DirectWire.GROUP_PHOTO -> DirectWire.guid("GG-${asked.size}")
                DirectWire.SEND -> DirectWire.sent("S-${asked.size}", System.currentTimeMillis())
                else -> DirectWire.ok()
            }
        }

    private fun says(text: String) = PushChatMessage.Message(
        NormalMessage(
            listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
            null, null, null, MessageType.IMessage, null, null, null, false, null, null,
        ),
    )

    private fun wire(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    private fun event(guid: String, from: String, m: PushChatMessage, participants: List<String>, groupGuid: String? = "G-1", ts: Long = 1_700_000_000_000L) =
        DirectArchive.map(
            MessageInst(guid, from, ConversationData(participants, null, groupGuid, null), m, ts.toULong(), null, false, false, null),
            setOf(me), Handles::display, DirectTexts(context),
        ).let(::wire)

    /** The group as Bob's first message in it makes it: the user, Bob, Alice and Carol. */
    private fun group(l: DirectLink, members: List<String> = listOf(me, bob, alice, carol)) = l.apply(event("GA1", bob, says("hi all"), members))

    private fun members() = repo.participants(id).filter { !it.isMe }.map { it.number }.toSet()

    private fun lastRequest(what: Int): JSONObject = synchronized(asked) { asked.last { it.first == what }.second }

    private fun latestRow() = repo.messages(id).last()

    private fun jpeg(width: Int, height: Int, color: Int = Color.MAGENTA): ByteArray {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    // --- renamed ------------------------------------------------------------------------------------

    @Test
    fun aGroupIsRenamedThroughAppleThenHere() {
        val l = link()
        group(l)
        repo.markConversationRead(id)
        runBlocking { l.renameGroup(id, "  Ski trip ") }
        val r = DirectWire.RenameRequest.fromJson(lastRequest(DirectWire.RENAME))
        assertEquals("Ski trip", r.name)
        assertEquals("G-1", r.target.groupGuid)
        assertEquals(setOf(bob, alice, carol), r.target.participants.toSet())
        assertEquals(me, r.target.from)
        // Here too: its name, what is sent to it, and the row of the chat's own, by the GUID Apple knows it by.
        assertEquals("Ski trip", repo.conversation(id)!!.name)
        assertEquals("Ski trip", l.targetOf(id)!!.groupName)
        assertEquals("Ski trip", l.groupName(id))
        val row = latestRow()
        assertEquals("You named the conversation “Ski trip”.", row.text)
        assertEquals("system", row.direction)
        assertTrue(row.id, row.id.startsWith("imsg:GG-"))
        assertFalse("the user's own change makes nothing unread", repo.conversation(id)!!.unread)
        assertEquals(setOf("+15559876543", "+15551234567", "+15553334444"), members())
        // Taken off: shown by its members again, and sent with none.
        runBlocking { l.renameGroup(id, "") }
        assertEquals("", DirectWire.RenameRequest.fromJson(lastRequest(DirectWire.RENAME)).name)
        assertEquals("You removed the name from the conversation.", latestRow().text)
        assertNull(l.targetOf(id)!!.groupName)
        assertEquals(setOf("+15559876543", "+15551234567", "+15553334444"), repo.conversation(id)!!.name.split(", ").toSet())
    }

    @Test
    fun aRenameAppleRefusesChangesNothingHere() {
        val l = link { what, _ -> if (what == DirectWire.RENAME) DirectWire.error("Apple refused the change: no") else null }
        group(l)
        val before = repo.messages(id).size
        try {
            runBlocking { l.renameGroup(id, "Pwned") }
            fail("refused, yet renamed")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Apple refused the change: no", e.message)
        }
        assertFalse(repo.conversation(id)!!.name.contains("Pwned"))
        assertEquals(before, repo.messages(id).size)
        // Not a group (a chat with one person), or not through the library: nothing to rename.
        l.apply(event("B1", bob, says("hi"), listOf(me, bob), groupGuid = null))
        try {
            runBlocking { l.renameGroup("imsg:$bob", "x") }
            fail("renamed a chat with one person")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_group_not_a_group), e.message)
        }
        assertEquals(DirectLink.GroupRules.NONE, l.groupRules("imsg:$bob"))
    }

    // --- members ------------------------------------------------------------------------------------

    @Test
    fun peopleAreAddedOnlyOnceAppleSaysTheyAreOnIMessageAsTheGroupsNextVersion() {
        val l = link(onIMessage = setOf(dave))
        group(l, listOf(me, bob, alice))
        // Bob added Carol, at the group's version 4: what follows from here goes as 5.
        l.apply(event("GA2", bob, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(me, bob, alice, carol), 4UL)), listOf(me, bob, alice), ts = 1_700_000_001_000L))
        assertEquals(4L, GroupVersions(context).of(id))
        // Someone not on iMessage (or something that is no address): nobody is added, and the error names them.
        for ((typed, said) in listOf("+1 555 777 8888" to "+15557778888", "nobody" to "nobody")) {
            try {
                runBlocking { l.addMembers(id, listOf("(555) 666-7777", typed)) }
                fail("added someone not on iMessage")
            } catch (e: DirectEngine.ImessageError) {
                assertEquals("$said is not on iMessage, so they can't be added.", e.message)
            }
        }
        assertTrue(asked.none { it.first == DirectWire.MEMBERS })
        assertEquals(setOf("+15559876543", "+15551234567", "+15553334444"), members())
        // Someone already in it, or the user: nothing to add.
        try {
            runBlocking { l.addMembers(id, listOf("+15559876543", "+15550000001")) }
            fail("added someone already in it")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_group_already_in), e.message)
        }
        // Dave is on iMessage: asked about first, then added.
        asked.clear()
        runBlocking { l.addMembers(id, listOf("(555) 666-7777")) }
        assertEquals(listOf(DirectWire.REACH, DirectWire.MEMBERS), asked.map { it.first })
        val m = DirectWire.MembersRequest.fromJson(lastRequest(DirectWire.MEMBERS))
        assertEquals(setOf(bob, alice, carol, dave, me), m.participants.toSet())
        assertEquals(5L, m.groupVersion)
        assertEquals(setOf("+15559876543", "+15551234567", "+15553334444", "+15556667777"), members())
        assertEquals("You added +15556667777.", latestRow().text)
        assertEquals(5L, GroupVersions(context).of(id))
        assertFalse(repo.conversation(id)!!.readOnly)
        assertTrue(l.canSend(id))
    }

    @Test
    fun aGroupMadeHereCountsItsChangesFromTheStartAndOneNeverSeenChangingGoesWellAhead() {
        val l = link()
        // A group others made, known here only by its messages (as every group is after the app was installed
        // again): how often it changed is not known. A change as of a version it is past already would be left
        // out by the iPhones in it, so the first one from here goes well ahead, and the next ones follow it.
        group(l, listOf(me, bob, alice))
        assertNull(GroupVersions(context).of(id))
        runBlocking { l.addMembers(id, listOf("(555) 666-7777")) }
        assertEquals(GroupVersions.UNKNOWN_FIRST, DirectWire.MembersRequest.fromJson(lastRequest(DirectWire.MEMBERS)).groupVersion)
        assertEquals(GroupVersions.UNKNOWN_FIRST, GroupVersions(context).of(id))
        runBlocking { l.addMembers(id, listOf("+15553334444")) }
        assertEquals(GroupVersions.UNKNOWN_FIRST + 1, DirectWire.MembersRequest.fromJson(lastRequest(DirectWire.MEMBERS)).groupVersion)
        // Within what a change from someone else may be ahead by: this app on another phone takes it.
        assertTrue(GroupVersions.plausible(GroupVersions.UNKNOWN_FIRST))
        // A group started from here: nothing has changed in it, so its first change is the first.
        val made = runBlocking { l.startGroup(listOf("+15559876543", "+15551234567"), "Ski trip") }
        assertNull("no version seen yet", GroupVersions(context).of(made))
        assertEquals(0L, GroupVersions(context).next(made))
        runBlocking { l.addMembers(made, listOf("(555) 666-7777")) }
        assertEquals(0L, DirectWire.MembersRequest.fromJson(lastRequest(DirectWire.MEMBERS)).groupVersion)
        assertEquals(1L, GroupVersions(context).next(made))
        // Once Apple's events say a version, a change from here follows that one, made here or not.
        val versions = GroupVersions(context)
        versions.noteNew("imsg:group:OTHER")
        versions.note("imsg:group:OTHER", 7)
        assertEquals(8L, versions.next("imsg:group:OTHER"))
        versions.noteNew("imsg:group:OTHER")
        assertEquals("already known: not new again", 8L, versions.next("imsg:group:OTHER"))
        versions.forget("imsg:group:OTHER")
        assertEquals(GroupVersions.UNKNOWN_FIRST, versions.next("imsg:group:OTHER"))
    }

    @Test
    fun aMemberIsRemovedOnlyFromAGroupOfFourOrMore() {
        val l = link()
        group(l)
        assertEquals(DirectLink.GroupRules(manageable = true, canRemove = true, canLeave = true), l.groupRules(id))
        runBlocking { l.removeMember(id, "+1 (555) 333-4444") }
        val m = DirectWire.MembersRequest.fromJson(lastRequest(DirectWire.MEMBERS))
        assertEquals(setOf(bob, alice, me), m.participants.toSet())
        // A group this phone never saw change: its version is not known, so the change goes well past where it may be.
        assertEquals(GroupVersions.UNKNOWN_FIRST, m.groupVersion)
        assertEquals("You removed +15553334444.", latestRow().text)
        assertEquals(setOf("+15559876543", "+15551234567"), members())
        // Down to two others: as on an iPhone, nobody can be removed and the user cannot leave.
        assertEquals(DirectLink.GroupRules(manageable = true, canRemove = false, canLeave = false), l.groupRules(id))
        asked.clear()
        for (attempt in listOf<suspend () -> Unit>({ l.removeMember(id, "+15559876543") }, { l.leaveGroup(id) })) {
            try {
                runBlocking { attempt() }
                fail("changed a group of three")
            } catch (e: DirectEngine.ImessageError) {
                assertEquals(context.getString(com.lightdecoder.R.string.imessage_group_too_small), e.message)
            }
        }
        assertTrue(asked.isEmpty())
        // Someone not in it is nobody to remove.
        group(l, listOf(me, bob, alice, carol, dave))
        try {
            runBlocking { l.removeMember(id, "+15558889999") }
            fail("removed someone not in it")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_group_not_member), e.message)
        }
    }

    @Test
    fun leavingMakesTheGroupReadOnlyAndNothingGoesToItAnyMore() {
        val l = link()
        group(l)
        runBlocking { l.leaveGroup(id) }
        // Everyone but the user.
        val m = DirectWire.MembersRequest.fromJson(lastRequest(DirectWire.MEMBERS))
        assertEquals(setOf(bob, alice, carol), m.participants.toSet())
        assertEquals("You left the conversation.", latestRow().text)
        assertTrue(repo.conversation(id)!!.readOnly)
        assertTrue(l.isLeft(id))
        assertNull(l.targetOf(id))
        assertFalse(l.canSend(id))
        assertEquals(DirectLink.GroupRules.NONE, l.groupRules(id))
        // The others are still its members here; the user is still "me" in it.
        assertEquals(setOf("+15559876543", "+15551234567", "+15553334444"), members())
        // Nothing goes to it any more.
        asked.clear()
        try {
            IMessageManager.get(context).sendText(id, "still here?", null)
            fail("sent to a group the user left")
        } catch (e: RelayException) {
            // As expected.
        }
        try {
            runBlocking { l.renameGroup(id, "x") }
            fail("renamed a group the user left")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_not_in_group), e.message)
        }
        assertTrue(asked.none { it.first == DirectWire.SEND || it.first == DirectWire.RENAME })
    }

    @Test
    fun aMembersChangeAppleDidNotConfirmChangesNothingHere() {
        val l = link { what, _ -> if (what == DirectWire.MEMBERS) DirectWire.failure(DirectEngine.Unconfirmed("Apple did not confirm the change within 45 seconds.")) else null }
        group(l)
        try {
            runBlocking { l.leaveGroup(id) }
            fail("not confirmed, yet left")
        } catch (e: DirectEngine.Unconfirmed) {
            assertTrue(e.message!!.contains("did not confirm"))
        }
        assertFalse(repo.conversation(id)!!.readOnly)
        assertTrue(l.canSend(id))
    }

    // --- photo ----------------------------------------------------------------------------------------

    @Test
    fun aGroupPhotoSetHereGoesToAppleAsASquareThenShows() {
        var shape: Pair<Int, Int>? = null
        val l = link { what, request ->
            if (what == DirectWire.GROUP_PHOTO) {
                // While Apple is asked, the square picture is where the other process may read it.
                val r = DirectWire.GroupPhotoRequest.fromJson(request)
                r.path?.let { path ->
                    assertTrue(GroupPhotos.isOurs(context, File(path)))
                    val b = BitmapFactory.decodeFile(path)!!
                    shape = b.width to b.height
                }
            }
            null
        }
        group(l)
        val source = File(context.cacheDir, "picked.jpg").apply { writeBytes(jpeg(600, 300)) }
        runBlocking { l.setGroupPhoto(id, Uri.fromFile(source)) }
        assertEquals(GroupPhotoImages.SIDE to GroupPhotoImages.SIDE, shape)
        val r = DirectWire.GroupPhotoRequest.fromJson(lastRequest(DirectWire.GROUP_PHOTO))
        assertEquals("G-1", r.target.groupGuid)
        assertEquals(GroupVersions.UNKNOWN_FIRST, r.groupVersion)
        val shown = GroupPhotos(context).of(id)
        assertNotNull(shown.file)
        assertEquals(GroupVersions.UNKNOWN_FIRST, shown.version)
        assertEquals("You changed the group photo.", latestRow().text)
        // Taken off: none sent, the next version, no picture here.
        runBlocking { l.setGroupPhoto(id, null) }
        val removed = DirectWire.GroupPhotoRequest.fromJson(lastRequest(DirectWire.GROUP_PHOTO))
        assertNull(removed.path)
        assertEquals(GroupVersions.UNKNOWN_FIRST + 1, removed.groupVersion)
        assertNull(GroupPhotos(context).pictureOf(id))
        assertEquals("You removed the group photo.", latestRow().text)
        // Something that is no picture is not sent at all.
        asked.clear()
        val junk = File(context.cacheDir, "junk.jpg").apply { writeText("not a picture") }
        try {
            runBlocking { l.setGroupPhoto(id, Uri.fromFile(junk)) }
            fail("sent something that is no picture")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_group_photo_unreadable), e.message)
        }
        assertTrue(asked.none { it.first == DirectWire.GROUP_PHOTO })
    }

    @Test
    fun aGroupPhotoAppleRefusedLeavesNothingBehind() {
        val l = link { what, _ -> if (what == DirectWire.GROUP_PHOTO) DirectWire.error("Apple refused the change: no") else null }
        group(l)
        val source = File(context.cacheDir, "picked.jpg").apply { writeBytes(jpeg(300, 300)) }
        try {
            runBlocking { l.setGroupPhoto(id, Uri.fromFile(source)) }
            fail("refused, yet set")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Apple refused the change: no", e.message)
        }
        assertNull(GroupPhotos(context).pictureOf(id))
        assertTrue(GroupPhotos.dir(context).listFiles().orEmpty().none { it.name.endsWith(".jpg") })
    }

    private val photoFile = MmcsFile(byteArrayOf(1), "o", "u", byteArrayOf(2), 1_000UL)

    /** [from]'s change of the group's photo to the picture at [path] (null: taken off), at the group's [version], as it crosses from the other process. */
    private fun photoEvent(guid: String, from: String, version: ULong, path: String?, participants: List<String> = listOf(me, bob, alice, carol)) =
        DirectArchive.groupPhoto(
            MessageInst(
                guid, from, ConversationData(participants, null, "G-1", null),
                PushChatMessage.IconChange(IconChangeMessage(if (path != null) photoFile else null, version)), 1_700_000_005_000UL, null, false, false, null,
            ),
            setOf(me), Handles::display, DirectTexts(context), path,
        ).let(::wire)

    private fun fetched(): File = GroupPhotos.incomingFile(context).apply { writeBytes(jpeg(570, 570, Color.CYAN)) }

    @Test
    fun aGroupPhotoFromAMemberShowsAndOneFromAnyoneElseOrAnOlderOneDoesNot() {
        val l = link()
        group(l)
        val first = fetched()
        l.apply(photoEvent("PH1", bob, 3UL, first.path))
        val shown = GroupPhotos(context).pictureOf(id)!!
        assertFalse("taken over", first.exists())
        assertEquals("+15559876543 changed the group photo.", repo.message("imsg:PH1")!!.text)
        // A change from here goes after it.
        assertEquals(4L, GroupVersions(context).next(id))
        // From someone not in the group: dropped, its picture gone.
        val theirs = fetched()
        l.apply(photoEvent("PH2", mallory, 9UL, theirs.path, listOf(me, bob, alice, carol, mallory)))
        assertFalse(theirs.exists())
        assertNull(repo.message("imsg:PH2"))
        assertEquals(shown, GroupPhotos(context).pictureOf(id))
        assertEquals(4L, GroupVersions(context).next(id))
        // Older than the photo shown (or the same again): nothing new, no row.
        val older = fetched()
        l.apply(photoEvent("PH3", alice, 2UL, older.path))
        assertFalse(older.exists())
        assertNull(repo.message("imsg:PH3"))
        assertEquals(shown, GroupPhotos(context).pictureOf(id))
        // A picture that is not where group photos are kept is not taken.
        val elsewhere = File(context.cacheDir, "elsewhere.jpg").apply { writeBytes(jpeg(10, 10)) }
        l.apply(photoEvent("PH4", alice, 7UL, elsewhere.path))
        assertTrue(elsewhere.exists())
        assertNull(repo.message("imsg:PH4"))
        // Taken off by Carol.
        l.apply(photoEvent("PH5", carol, 5UL, null))
        assertNull(GroupPhotos(context).pictureOf(id))
        assertEquals("+15553334444 removed the group photo.", repo.message("imsg:PH5")!!.text)
        // A group not stored here has nothing to show it in.
        val stray = fetched()
        l.apply(
            DirectArchive.groupPhoto(
                MessageInst(
                    "PH6", bob, ConversationData(listOf(me, bob, alice), null, "G-OTHER", null),
                    PushChatMessage.IconChange(IconChangeMessage(photoFile, 1UL)), 1_700_000_005_000UL, null, false, false, null,
                ),
                setOf(me), Handles::display, DirectTexts(context), stray.path,
            ).let(::wire),
        )
        assertFalse(stray.exists())
        assertNull(repo.conversation("imsg:group:G-OTHER"))
    }

    @Test
    fun aGroupsVersionIsTakenOnlyFromAChangeOneOfItsMembersMade() {
        val l = link()
        group(l)
        l.apply(event("GV1", mallory, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(me, mallory), 90UL)), listOf(me, bob, alice, carol)))
        assertNull(GroupVersions(context).of(id))
        l.apply(event("GV2", carol, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(me, bob, alice, carol, dave), 6UL)), listOf(me, bob, alice, carol)))
        assertEquals(6L, GroupVersions(context).of(id))
        assertEquals(7L, GroupVersions(context).next(id))
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** [from]'s change of the group's photo, at the group's [version], as the ":imessage" process now hands it over: where the photo is, nothing fetched. */
    private fun fileEvent(guid: String, from: String, version: ULong, participants: List<String> = listOf(me, bob, alice, carol), groupGuid: String = "G-1") =
        DirectArchive.map(
            MessageInst(
                guid, from, ConversationData(participants, null, groupGuid, null),
                PushChatMessage.IconChange(IconChangeMessage(photoFile, version)), 1_700_000_005_000UL, null, false, false, null,
            ),
            setOf(me), Handles::display, DirectTexts(context), { "<plist>photo</plist>" },
        ).let(::wire)

    @Test
    fun aGroupPhotoFromAppleIsFetchedOnlyOnceItsChangeIsTakenThenShows() {
        val downloads = Collections.synchronizedList(ArrayList<DirectWire.DownloadRequest>())
        val l = link { what, request ->
            if (what != DirectWire.DOWNLOAD) return@link null
            val r = DirectWire.DownloadRequest.fromJson(request)
            downloads += r
            File(r.path).apply { parentFile?.mkdirs() }.writeBytes(jpeg(800, 400))
            DirectWire.downloaded(File(r.path).length())
        }
        group(l)
        // From someone not in the group, or for a group not stored here: nothing is fetched.
        l.apply(fileEvent("PF1", mallory, 3UL, listOf(me, bob, alice, carol, mallory)))
        l.apply(fileEvent("PF2", bob, 3UL, groupGuid = "G-OTHER"))
        Thread.sleep(200)
        assertTrue(downloads.isEmpty())
        assertNull(repo.message("imsg:PF1"))
        // From a member: its row at once, the photo once it came.
        l.apply(fileEvent("PF3", bob, 4UL))
        assertEquals("+15559876543 changed the group photo.", repo.message("imsg:PF3")!!.text)
        waitFor("the photo") { GroupPhotos(context).pictureOf(id) != null }
        val r = downloads.single()
        assertEquals("<plist>photo</plist>", r.descriptor)
        assertEquals("never more than a group photo", GroupPhotos.MAX_DOWNLOAD_BYTES, r.maxBytes)
        assertEquals("one nobody asked for: Apple's servers only, in its time", DirectLink.GROUP_PHOTO_FETCH_MS, r.automaticMs)
        assertTrue("fetched where the other process may write", DirectPaths.isAllowed(r.path, DirectPaths.forApp(context)))
        assertFalse("and not left there", File(r.path).exists())
        val shown = BitmapFactory.decodeFile(GroupPhotos(context).pictureOf(id)!!.path)!!
        assertEquals(GroupPhotoImages.SIDE to GroupPhotoImages.SIDE, shown.width to shown.height)
        assertEquals(4L, GroupPhotos(context).of(id).version)
        assertEquals("nothing left but the photo and its index", 2, GroupPhotos.dir(context).list()!!.size)
        // The same change again (Apple's journal after a restart): nothing new to fetch.
        l.apply(fileEvent("PF3", bob, 4UL))
        Thread.sleep(200)
        assertEquals(1, downloads.size)
    }

    @Test
    fun aGroupPhotoThatDoesNotComeOrIsNoPictureLeavesThePhotoAsItWas() {
        var bytes: ByteArray? = null
        val l = link { what, request ->
            if (what != DirectWire.DOWNLOAD) return@link null
            val path = DirectWire.DownloadRequest.fromJson(request).path
            bytes?.let { File(path).writeBytes(it) } ?: return@link DirectWire.error("not on Apple's file servers")
            DirectWire.downloaded(File(path).length())
        }
        group(l)
        l.apply(photoEvent("PN1", bob, 2UL, fetched().path))
        val before = GroupPhotos(context).pictureOf(id)!!
        // Refused (not on Apple's servers, too large, not in time): the row, the photo as it was.
        l.apply(fileEvent("PN2", bob, 3UL))
        // Something that is no picture: the same.
        bytes = "not a picture".toByteArray()
        l.apply(fileEvent("PN3", bob, 4UL))
        Thread.sleep(500)
        assertEquals(before, GroupPhotos(context).pictureOf(id))
        assertEquals("+15559876543 changed the group photo.", repo.message("imsg:PN3")!!.text)
        assertEquals("the photo and its index only", 2, GroupPhotos.dir(context).list()!!.size)
    }

    @Test
    fun aGroupVersionFarAheadOfTheGroupsIsNotTakenAndTheUsersOwnChangeAlwaysShows() {
        val l = link()
        group(l)
        // Far ahead of the group's (it would hold every later photo back): not taken, no row.
        for (v in listOf(ULong.MAX_VALUE, 1UL shl 40, (1UL shl 32), 1_001UL)) {
            val ahead = fetched()
            l.apply(photoEvent("PV$v", bob, v, ahead.path))
            assertFalse(ahead.exists())
            assertNull(repo.message("imsg:PV$v"))
        }
        assertNull(GroupPhotos(context).pictureOf(id))
        assertNull(GroupVersions(context).of(id))
        // Within reach of what is known: taken.
        l.apply(photoEvent("PV1", bob, 1_000UL, fetched().path))
        assertNotNull(GroupPhotos(context).pictureOf(id))
        assertEquals(1_000L, GroupVersions(context).of(id))
        l.apply(photoEvent("PV2", alice, 2_000UL, fetched().path))
        assertEquals(2_000L, GroupPhotos(context).of(id).version)
        // A member's change of members as of a version far ahead: the change is taken, its version is not.
        l.apply(event("PV3", carol, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(me, bob, alice, carol, dave), ULong.MAX_VALUE)), listOf(me, bob, alice, carol)))
        assertTrue(members().contains("+15556667777"))
        assertEquals(2_000L, GroupVersions(context).of(id))
        assertEquals(2_001L, GroupVersions(context).next(id))
        // A photo kept as of a version far ahead (from before such were refused): the user's own change,
        // Apple having confirmed it, shows all the same.
        GroupPhotos(context).apply(id, fetched(), Long.MAX_VALUE)
        GroupVersions(context).note(id, Long.MAX_VALUE)
        val source = File(context.cacheDir, "picked-green.jpg").apply { writeBytes(jpeg(300, 300, Color.GREEN)) }
        runBlocking { l.setGroupPhoto(id, Uri.fromFile(source)) }
        assertEquals("You changed the group photo.", latestRow().text)
        val mine = BitmapFactory.decodeFile(GroupPhotos(context).pictureOf(id)!!.path)!!
        val px = mine.getPixel(mine.width / 2, mine.height / 2)
        assertTrue("the user's own photo", Color.green(px) > 180 && Color.red(px) < 90 && Color.blue(px) < 90)
        // Taken off by the user: gone here too.
        runBlocking { l.setGroupPhoto(id, null) }
        assertNull(GroupPhotos(context).pictureOf(id))
        assertEquals("You removed the group photo.", latestRow().text)
    }

    @Test
    fun aGroupPhotoChangeNamingWhatIsKeptThereAlreadyTakesNothing() {
        val l = link()
        group(l)
        l.apply(photoEvent("PK1", bob, 3UL, fetched().path))
        val kept = GroupPhotos(context).pictureOf(id)!!
        val index = File(GroupPhotos.dir(context), "index.json")
        assertTrue(index.isFile)
        // The index, a group's photo kept there, a picture the user is sending: none is a new picture that came.
        val outgoing = GroupPhotos.incomingFile(context, GroupPhotos.OUTGOING).apply { writeBytes(jpeg(10, 10)) }
        for ((n, path) in listOf(index.path, kept.path, outgoing.path).withIndex()) {
            l.apply(photoEvent("PK${n + 2}", alice, (4 + n).toULong(), path))
            assertNull(repo.message("imsg:PK${n + 2}"))
        }
        assertTrue(index.isFile)
        assertTrue(kept.isFile)
        assertTrue(outgoing.isFile)
        assertEquals(kept, GroupPhotos(context).pictureOf(id))
        assertEquals(3L, GroupPhotos(context).of(id).version)
    }

    @Test
    fun aDeletedChatTakesItsGroupPhotoVersionAndOwnReadReceiptsWithIt() {
        val l = link()
        group(l)
        l.apply(photoEvent("PD1", bob, 3UL, fetched().path))
        val picture = GroupPhotos(context).pictureOf(id)!!
        l.setSendsReadReceipts(id, true)
        assertEquals(3L, GroupVersions(context).of(id))
        runBlocking { IMessageManager.get(context).deleteConversation(id) }
        assertNull(repo.conversation(id))
        assertFalse(picture.exists())
        assertEquals(GroupPhotos.Photo(null, 0, known = false), GroupPhotos(context).of(id))
        assertNull(GroupVersions(context).of(id))
        assertNull(ReadReceiptChoices(context).of(id))
    }

    // --- a chat's own read receipts ------------------------------------------------------------------

    @Test
    fun aChatsOwnReadReceiptsChoiceGoesWithEachReadReceipt() {
        val l = link()
        val chat = "imsg:$bob"
        fun readFrom(guid: String): DirectWire.ReadRequest {
            l.apply(event(guid, bob, says("hi"), listOf(me, bob), groupGuid = null, ts = System.currentTimeMillis()))
            runBlocking { l.markRead(chat) }
            val read = DirectWire.ReadRequest.fromJson(lastRequest(DirectWire.READ))
            assertEquals(guid, read.guid)
            return read
        }
        // As the iMessage screen's setting says until the chat has its own choice.
        assertFalse(l.sendsReadReceipts(chat))
        assertNull(readFrom("R1").receipts)
        l.setSendsReadReceipts(chat, true)
        assertTrue(l.sendsReadReceipts(chat))
        assertEquals(true, readFrom("R2").receipts)
        l.setSendsReadReceipts(chat, false)
        assertEquals(false, readFrom("R3").receipts)
        // Kept: a new link (a restart) has it.
        DirectLink.resetForTesting()
        val again = link()
        assertFalse(again.sendsReadReceipts(chat))
        // Back to following the setting.
        again.setSendsReadReceipts(chat, null)
        assertNull(ReadReceiptChoices(context).of(chat))
    }

    companion object {
        private const val DB = "direct-groups-test.db"
    }
}
