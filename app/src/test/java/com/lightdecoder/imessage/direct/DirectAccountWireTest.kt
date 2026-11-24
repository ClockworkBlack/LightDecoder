package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ShareProfileMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import uniffi.rust_bridge.UpdateProfileMessage
import uniffi.rust_bridge.UpdateProfileSharingMessage

/**
 * What the Apple Account's features (B6) take from Apple's events ([DirectArchive]) and what crosses
 * between the processes for them ([DirectWire]: codes 27-30, the "profileOffer", "focus" and
 * "cloudBatch" kinds, a DOWNLOAD's iCloud record), all read as untrusted.
 */
@RunWith(AndroidJUnit4::class)
class DirectAccountWireTest {

    private val me = "tel:+15550000001"
    private val bob = "tel:+15550000002"
    private val carol = "mailto:carol@example.com"
    private val profile = ShareProfileMessage(byteArrayOf(1, 2, 3), "record-1", null)

    private fun inst(m: PushChatMessage, sender: String? = bob, participants: List<String> = listOf(me, bob), unverified: Boolean = false, guid: String? = null) =
        MessageInst("ID-1", sender, ConversationData(participants, null, guid, null), m, 1_700_000_005_000UL, null, false, unverified, null)

    private fun map(i: MessageInst) = DirectArchive.map(i, setOf(me), { it }, DirectArchiveTest.TestTexts)

    // --- DirectArchive ---------------------------------------------------------------------------

    @Test
    fun aShareProfileIsAnOfferOnlyVerifiedNotOwnAndInAChatWithThatOnePerson() {
        val u = map(inst(PushChatMessage.ShareProfile(profile))) as DirectArchive.Update.ProfileOffer
        assertEquals("imsg:$bob", u.conversationId)
        assertEquals(bob, u.sender)
        assertEquals(ProfileDescriptor(byteArrayOf(1, 2, 3), "record-1"), ProfileDescriptor.decode(u.descriptor))
        assertEquals(DirectArchive.Update.Ignore, map(inst(PushChatMessage.ShareProfile(profile), unverified = true)))
        assertEquals(DirectArchive.Update.Ignore, map(inst(PushChatMessage.ShareProfile(profile), sender = me)))
        assertEquals(DirectArchive.Update.Ignore, map(inst(PushChatMessage.ShareProfile(profile), participants = listOf(me, bob, carol), guid = "G-1")))
        assertEquals(DirectArchive.Update.Ignore, map(inst(PushChatMessage.ShareProfile(profile), participants = listOf(me, carol))))
        // A descriptor that is no record's.
        assertEquals(DirectArchive.Update.Ignore, map(inst(PushChatMessage.ShareProfile(ShareProfileMessage(ByteArray(0), "r", null)))))
    }

    @Test
    fun aNameAndPhotoWithAMessageIsOfferedTheSameWay() {
        val parts = listOf(IndexedMessagePart(MessagePart.Text("hi", TextFormat.Flags(TextFlags(false, false, false, false))), null, null))
        val m = PushChatMessage.Message(NormalMessage(parts, null, null, null, MessageType.IMessage, null, null, null, false, null, profile))
        assertEquals("imsg:$bob", DirectArchive.embeddedProfileOf(inst(m), setOf(me))!!.conversationId)
        assertNull(DirectArchive.embeddedProfileOf(inst(m, unverified = true), setOf(me)))
        assertNull(DirectArchive.embeddedProfileOf(inst(m, participants = listOf(me, bob, carol), guid = "G"), setOf(me)))
    }

    @Test
    fun updateProfileAndItsSharingAreNeverTakenNorIsNotifyAnyway() {
        val update = PushChatMessage.UpdateProfile(UpdateProfileMessage(profile, true))
        val sharing = PushChatMessage.UpdateProfileSharing(UpdateProfileSharingMessage(emptyList(), emptyList(), 1UL))
        for (m in listOf(update, sharing, PushChatMessage.NotifyAnyways)) {
            assertEquals(DirectArchive.Update.Ignore, map(inst(m)))
            assertEquals(DirectArchive.Update.Ignore, map(inst(m, sender = me)))
            assertEquals(DirectArchive.Update.Ignore, map(inst(m, unverified = true)))
        }
    }

    // --- DirectWire --------------------------------------------------------------------------------

    private val target = DirectWire.Target(listOf(bob), null, null, me, null)

    private fun back(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encodeUpdate(u).toString()))

    @Test
    fun theFeaturesCodesAreSpec2s() {
        assertEquals(27, DirectWire.NOTIFY_ANYWAY)
        assertEquals(28, DirectWire.PROFILE_FETCH)
        assertEquals(29, DirectWire.PROFILE_SHARE)
        assertEquals(30, DirectWire.FOCUS_SUBSCRIBE)
    }

    @Test
    fun notifyAnywayIsForAGuidInAChatWithOnePerson() {
        val r = DirectWire.NotifyAnywayRequest.fromJson(JSONObject(DirectWire.notifyAnyway(target, "ABC-1").toString()))
        assertEquals("ABC-1", r.guid)
        assertFails { DirectWire.NotifyAnywayRequest.fromJson(JSONObject(DirectWire.notifyAnyway(target, "../x").toString())) }
        assertFails { DirectWire.NotifyAnywayRequest.fromJson(JSONObject(DirectWire.notifyAnyway(target.copy(participants = listOf(bob, carol)), "ABC").toString())) }
        assertFails { DirectWire.NotifyAnywayRequest.fromJson(JSONObject(DirectWire.notifyAnyway(target.copy(groupGuid = "G"), "ABC").toString())) }
    }

    @Test
    fun aProfileFetchAndItsAnswerAreReadStrictly() {
        val d = ProfileDescriptor(byteArrayOf(5), "rec")
        val r = DirectWire.ProfileFetchRequest.fromJson(JSONObject(DirectWire.profileFetch(d.encode(), "/data/x/incoming-1").toString()))
        assertEquals(d, r.descriptor)
        assertFails { DirectWire.ProfileFetchRequest.fromJson(JSONObject(DirectWire.profileFetch("nope", "/x").toString())) }
        assertFails { DirectWire.ProfileFetchRequest.fromJson(JSONObject(DirectWire.profileFetch(d.encode(), "").toString())) }
        val reply = DirectWire.ProfileReply.fromJson(JSONObject(DirectWire.ProfileReply("B".repeat(1000), "B", "", true).toJson().toString()))!!
        assertEquals(com.lightdecoder.chat.SharedNames.MAX_RAW, reply.name.length)
        assertTrue(reply.image)
        assertNull(DirectWire.ProfileReply.fromJson(DirectWire.error("off")))
        assertFalse(DirectWire.ProfileReply.fromJson(JSONObject("""{"ok":true,"image":"true"}"""))!!.image)
    }

    @Test
    fun focusSubscribeTakesAddressesOnlyFiftyAtMost() {
        val o = JSONObject(DirectWire.focusSubscribe(listOf(bob, "junk", bob), listOf(carol)).toString())
        assertEquals(listOf(bob), DirectWire.focusHandlesOf(o))
        assertEquals(listOf(carol), DirectWire.focusInviteesOf(o))
        assertEquals(emptyList<String>(), DirectWire.focusInviteesOf(JSONObject().put("handles", JSONArray())))
        val many = JSONObject().put("handles", JSONArray((1..51).map { "tel:+1555000%04d".format(it) }))
        assertFails { DirectWire.focusHandlesOf(many) }
        assertEquals(50, DirectWire.focusSubscribe((1..60).map { "tel:+1555000%04d".format(it) }).getJSONArray("handles").length())
    }

    @Test
    fun theFeaturesKindsCrossAndAreReadAsUntrusted() {
        val d = ProfileDescriptor(byteArrayOf(1), "r").encode()
        assertEquals(DirectArchive.Update.ProfileOffer("imsg:$bob", bob, d), back(DirectArchive.Update.ProfileOffer("imsg:$bob", bob, d)))
        // A group, someone not an address, a descriptor that is none: nothing.
        assertEquals(DirectArchive.Update.Ignore, back(DirectArchive.Update.ProfileOffer("imsg:group:G", bob, d)))
        assertEquals(DirectArchive.Update.Ignore, back(DirectArchive.Update.ProfileOffer("imsg:$bob", "Bob", d)))
        assertEquals(DirectArchive.Update.Ignore, back(DirectArchive.Update.ProfileOffer("imsg:$bob", bob, "x")))
        assertEquals(DirectArchive.Update.Focus(bob, true, 5), back(DirectArchive.Update.Focus(bob, true, 5)))
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject("""{"kind":"focus","handle":"$bob","silenced":"yes","atMs":5}""")))
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject("""{"kind":"focus","handle":"Mom","silenced":true,"atMs":5}""")))
        val chat = ConversationDto(id = "imsg:$bob")
        val imported = MessageDto(id = "imsg:G-1", conversationId = "imsg:$bob", text = "hi", extra = IMessageExtra(imported = true).toJson())
        val live = imported.copy(id = "imsg:G-2", extra = "")
        val other = imported.copy(id = "12345")
        val b = back(DirectArchive.Update.CloudBatch(listOf(chat, ConversationDto(id = "999")), listOf(imported, live, other))) as DirectArchive.Update.CloudBatch
        assertEquals(listOf("imsg:$bob"), b.chats.map { it.id })
        assertEquals("only messages marked imported, only iMessages", listOf("imsg:G-1"), b.messages.map { it.id })
        assertEquals(DirectArchive.Update.Ignore, back(DirectArchive.Update.CloudBatch(emptyList(), listOf(live))))
    }

    @Test
    fun aSignOutSaysWhetherImportedMessagesGoToo() {
        val out = back(DirectArchive.Update.Account(AccountState.SIGNED_OUT, removeImported = true)) as DirectArchive.Update.Account
        assertTrue(out.removeImported)
        // Never while signed in.
        val stillIn = back(DirectArchive.Update.Account(AccountState(signedIn = true), removeImported = true)) as DirectArchive.Update.Account
        assertFalse(stillIn.removeImported)
    }

    @Test
    fun aDownloadMayNameAnIcloudRecordOnlyForOneTheUserAskedFor() {
        val r = DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("", "/p", cloudRecord = "rec_1-A").toString()))
        assertEquals("rec_1-A", r.cloudRecord)
        assertFails { DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("", "/p", cloudRecord = "../etc").toString())) }
        assertFails { DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("", "/p", automaticMs = 1000, cloudRecord = "rec").toString())) }
        assertNull(DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("d", "/p").toString())).cloudRecord)
        assertFalse(DirectWire.isCloudRecord("x".repeat(DirectWire.MAX_CLOUD_RECORD_CHARS + 1)))
    }

    @Test
    fun theFeaturesFlagsAreKeptOnlyWhileSignedIn() {
        val f = AccountFlag(FlagState.SIGNED_IN, 1, history = AccountFlag.HISTORY_ON, focusShare = true, focusIn = false, profileShare = true, window = CloudImport.WINDOW_ALL)
        assertEquals(f, AccountFlag.fromJson(f.toJson().toString()))
        // Signed out: none of it.
        assertEquals(AccountFlag(), AccountFlag.fromJson(AccountFlag(history = AccountFlag.HISTORY_ON, focusShare = true).toJson().toString()))
        assertEquals(AccountFlag.HISTORY_OFF, AccountFlag.fromJson("""{"state":"signedIn","since":1,"history":"everything"}""").history)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: org.json.JSONException) {
            return
        }
        throw AssertionError("not refused")
    }
}
