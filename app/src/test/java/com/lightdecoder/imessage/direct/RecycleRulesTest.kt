package com.lightdecoder.imessage.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.DeleteTarget
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MoveToRecycleBinMessage
import uniffi.rust_bridge.OperatedChat
import uniffi.rust_bridge.PermanentDeleteMessage
import uniffi.rust_bridge.PushChatMessage
import kotlin.random.Random

/** "Delete on All Devices": which recycle bin messages are taken, and what goes to the user's own devices. */
class RecycleRulesTest {

    private val me = "tel:+15550000001"
    private val bob = "tel:+15550000002"
    private val carol = "mailto:carol@example.com"
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun chat(vararg members: String, groupId: String = "", guid: String = "iMessage;-;x") = OperatedChat(members.toList(), groupId, guid, null, null)

    private fun bin(target: DeleteTarget, at: Long = now - 1_000) = PushChatMessage.MoveToRecycleBin(MoveToRecycleBinMessage(target, at.toULong()))

    private fun inst(sender: String?, m: PushChatMessage, unverified: Boolean = false) =
        MessageInst("X", sender, ConversationData(listOf(me), null, null, null), m, now.toULong(), null, false, unverified, null)

    private fun map(sender: String?, m: PushChatMessage, unverified: Boolean = false, on: Boolean = true) =
        DirectArchive.map(inst(sender, m, unverified), setOf(me), { it }, DirectArchiveTest.TestTexts, now = now, deleteSync = on)

    @Test
    fun theUsersOwnVerifiedDevicesRecycleRecoverAndPurge() {
        // A chat with one person, named by their address without its scheme (as OpenBubbles sends it).
        assertEquals(
            DirectArchive.Update.Recycle("imsg:$bob", null, emptyList(), now - 1_000),
            map(me, bin(DeleteTarget.Chat(chat("+15550000002")))),
        )
        assertEquals(DirectArchive.Update.Recover("imsg:$bob", null), map(me, PushChatMessage.RecoverChat(chat("+15550000002", "+15550000001"))))
        assertEquals(
            DirectArchive.Update.Purge("imsg:$bob", null, emptyList()),
            map(me, PushChatMessage.PermanentDelete(PermanentDeleteMessage(DeleteTarget.Chat(chat(bob)), false))),
        )
        // A group by its id.
        val group = "ABCDEF01-2345-6789-ABCD-EF0123456789"
        assertEquals(
            DirectArchive.Update.Recycle(DirectArchive.groupConversationId(group), group, emptyList(), now - 1_000),
            map(me, bin(DeleteTarget.Chat(chat("+15550000002", "carol@example.com", groupId = group)))),
        )
        // Messages: their archive ids.
        assertEquals(
            DirectArchive.Update.Recycle(null, null, listOf("imsg:M1", "imsg:M2"), now - 1_000),
            map(me, bin(DeleteTarget.Messages(listOf("M1", "M2", "M1")))),
        )
        assertEquals(
            DirectArchive.Update.Purge(null, null, listOf("imsg:M1")),
            map(me, PushChatMessage.PermanentDelete(PermanentDeleteMessage(DeleteTarget.Messages(listOf("M1")), false))),
        )
    }

    @Test
    fun anotherSenderAnUnverifiedOneOrTheSettingOffChangesNothing() {
        val all = listOf(
            bin(DeleteTarget.Chat(chat(bob))), bin(DeleteTarget.Messages(listOf("M1"))),
            PushChatMessage.RecoverChat(chat(bob)),
            PushChatMessage.PermanentDelete(PermanentDeleteMessage(DeleteTarget.Chat(chat(bob)), false)),
        )
        for (m in all) {
            assertEquals(m.toString(), DirectArchive.Update.Ignore, map(bob, m))
            assertEquals(m.toString(), DirectArchive.Update.Ignore, map(me, m, unverified = true))
            assertEquals(m.toString(), DirectArchive.Update.Ignore, map(bob, m, unverified = true))
            assertEquals(m.toString(), DirectArchive.Update.Ignore, map(me, m, on = false))
            assertEquals(m.toString(), DirectArchive.Update.Ignore, map(null, m))
        }
    }

    @Test
    fun emptyOversizedOrMalformedListsAreNotTaken() {
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Messages(emptyList()))))
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Messages((1..501).map { "M$it" }))))
        assertTrue(map(me, bin(DeleteTarget.Messages((1..500).map { "M$it" }))) is DirectArchive.Update.Recycle)
        // One GUID too long, or not a GUID at all: none of them.
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Messages(listOf("M1", "x".repeat(65))))))
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Messages(listOf("M1", "../etc")))))
        // A chat of nobody but the user, or one whose member is no address.
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Chat(chat(me)))))
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Chat(chat("not an address")))))
        assertEquals(DirectArchive.Update.Ignore, map(me, PushChatMessage.RecoverChat(chat())))
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Chat(chat(*Array(65) { "+1555000%04d".format(it) })))))
        // A group id that is no id.
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Chat(chat(bob, carol, groupId = "a b")))))
    }

    @Test
    fun aGroupDownToOnePersonIsNeverTheirOneToOneChat() {
        val group = "ABCDEF01-2345-6789-ABCD-EF0123456789"
        assertEquals(
            DirectArchive.Update.Recycle(DirectArchive.groupConversationId(group), group, emptyList(), now - 1_000),
            map(me, bin(DeleteTarget.Chat(chat("+15550000002", groupId = group, guid = "iMessage;+;chat123456789012345678")))),
        )
        // No group guid to find it by: not taken.
        assertEquals(DirectArchive.Update.Ignore, map(me, bin(DeleteTarget.Chat(chat("+15550000002", guid = "iMessage;+;chat123456789012345678")))))
        assertEquals(
            DirectArchive.Update.Ignore,
            map(me, PushChatMessage.PermanentDelete(PermanentDeleteMessage(DeleteTarget.Chat(chat("+15550000002", groupId = "x;y", guid = "iMessage;+;chat1")), false))),
        )
    }

    @Test
    fun aOneToOneChatsGuidAsGroupIdStillFindsTheChat() {
        assertEquals(
            DirectArchive.Update.Recycle("imsg:$carol", null, emptyList(), now - 1_000),
            map(me, bin(DeleteTarget.Chat(chat("carol@example.com", groupId = "iMessage;-;carol@example.com", guid = "iMessage;-;carol@example.com")))),
        )
        assertEquals(
            DirectArchive.Update.Recover("imsg:$bob", null),
            map(me, PushChatMessage.RecoverChat(chat("+15550000002", groupId = "iMessage;-;+15550000002", guid = "iMessage;-;+15550000002"))),
        )
    }

    @Test
    fun aScheduledDeleteKeepsItsSendLaterPath() {
        val gone = PushChatMessage.PermanentDelete(PermanentDeleteMessage(DeleteTarget.Messages(listOf("S1")), true))
        assertEquals(DirectArchive.Update.Remove("imsg:S1", scheduledOnly = true), map(me, gone))
        assertEquals(DirectArchive.Update.Remove("imsg:S1", scheduledOnly = true), map(me, gone, on = false))
        assertNull(RecycleRules.incoming(gone, me, false, setOf(me), true, now))
    }

    @Test
    fun theTimeARecycleNamesIsClamped() {
        assertEquals(now, RecycleRules.clampDate(0UL, now))
        assertEquals(now - 5_000, RecycleRules.clampDate((now - 5_000).toULong(), now))
        // Long ago (or before 1970 in Apple's terms): a month less a day back at the most, so that
        // retention keeps a chat recycled elsewhere a day at least.
        assertEquals(now - RecycleRules.RETENTION_MS + RecycleRules.MIN_DELETED_MS, RecycleRules.clampDate(1UL, now))
        assertEquals(now - RecycleRules.RETENTION_MS + RecycleRules.MIN_DELETED_MS, RecycleRules.clampDate((now - RecycleRules.RETENTION_MS).toULong(), now))
        assertEquals(now - RecycleRules.RETENTION_MS + RecycleRules.MIN_DELETED_MS, RecycleRules.clampDeletedAt(1L, now))
        assertEquals(now, RecycleRules.clampDeletedAt(0L, now))
        // The future: a few minutes at the most.
        assertEquals(now + DirectArchive.MAX_FUTURE_MS, RecycleRules.clampDate(ULong.MAX_VALUE, now))
        val at = (map(me, bin(DeleteTarget.Chat(chat(bob)), at = now * 3)) as DirectArchive.Update.Recycle).atMs
        assertEquals(now + DirectArchive.MAX_FUTURE_MS, at)
    }

    @Test
    fun aPurgeIsHeldADayFromWhenTheChatMovedHereWhateverDateItsRecycleNamed() {
        val now = 1_800_000_000_000L
        val day = RecycleRules.MIN_DELETED_MS
        val backdated = RecycleRules.clampDeletedAt(1L, now)
        assertEquals(RecycleRules.PurgeNow.Delete, RecycleRules.purgeNow(backdated, now))
        assertTrue(RecycleRules.purgeNow(backdated, now, holdUntil = now + day) is RecycleRules.PurgeNow.Defer)
        assertTrue(RecycleRules.purgeNow(backdated, now + day - 1, holdUntil = now + day) is RecycleRules.PurgeNow.Defer)
        assertEquals(RecycleRules.PurgeNow.Delete, RecycleRules.purgeNow(backdated, now + day, holdUntil = now + day))
        // A chat deleted here an hour ago: its day ends a day after that, whatever a purge deferred it to.
        assertEquals(now - 3_600_000 + day, RecycleRules.holdUntil(now - 3_600_000, now, 0))
        assertEquals(now + day, RecycleRules.holdUntil(backdated, now, now + day))
        assertEquals(0L, RecycleRules.holdUntil(now - 2 * day, now, 0))
    }


    @Test
    fun aRemotePurgeWaitsForTheChatsFirstDayInRecentlyDeleted() {
        assertEquals(RecycleRules.PurgeNow.Defer(now - RecycleRules.RETENTION_MS + day), RecycleRules.purgeNow(now - 60_000, now))
        assertEquals(RecycleRules.PurgeNow.Delete, RecycleRules.purgeNow(now - day - 1, now))
        assertEquals(RecycleRules.PurgeNow.Delete, RecycleRules.purgeNow(0, now))
    }

    @Test
    fun onlyANewLaterMessageRevivesADeletedChat() {
        assertTrue(RecycleRules.revives(stored = false, tombstoned = false, system = false, timestampMs = now, deletedAt = now - 1))
        assertFalse(RecycleRules.revives(stored = true, tombstoned = false, system = false, timestampMs = now, deletedAt = now - 1))
        assertFalse(RecycleRules.revives(stored = false, tombstoned = true, system = false, timestampMs = now, deletedAt = now - 1))
        assertFalse(RecycleRules.revives(stored = false, tombstoned = false, system = true, timestampMs = now, deletedAt = now - 1))
        assertFalse(RecycleRules.revives(stored = false, tombstoned = false, system = false, timestampMs = now - 2, deletedAt = now - 1))
    }

    @Test
    fun daysLeftCountsDown() {
        assertEquals(30, RecycleRules.daysLeft(now, now))
        assertEquals(30, RecycleRules.daysLeft(0, now))
        assertEquals(29, RecycleRules.daysLeft(now - day, now))
        assertEquals(1, RecycleRules.daysLeft(now - 30 * day + 1, now))
        assertEquals(1, RecycleRules.daysLeft(now - 40 * day, now))
    }

    @Test
    fun handlesComeBackWithTheirScheme() {
        assertEquals("tel:+15550000002", RecycleRules.handleOf("+15550000002"))
        assertEquals("tel:+15550000002", RecycleRules.handleOf("tel:+15550000002"))
        assertEquals("mailto:carol@example.com", RecycleRules.handleOf("Carol@Example.com"))
        assertNull(RecycleRules.handleOf("5550000002"))
        assertNull(RecycleRules.handleOf(""))
    }

    // --- what goes to the user's own devices --------------------------------------------------------

    @Test
    fun aOneToOneChatGoesAsOpenBubblesNamesIt() {
        val target = DirectWire.Target(listOf(bob, me), null, null, me)
        val (to, message) = RecycleRules.outgoing(target, RecycleRules.OP_BIN, null, me, listOf(me), "CHAT-GUID", now)
        // Only to the user's own address.
        assertEquals(listOf(me), to.participants)
        assertNull(to.senderGuid)
        val m = message as PushChatMessage.MoveToRecycleBin
        assertEquals(now.toULong(), m.v1.recoverableDeleteDate)
        val op = (m.v1.target as DeleteTarget.Chat).v1
        assertEquals(listOf("+15550000002"), op.participants)
        assertEquals("CHAT-GUID", op.groupId)
        assertEquals("iMessage;-;+15550000002", op.guid)
        assertNull(op.wasReportedAsJunk)
        assertNull(op.deleteIncomingMessages)
    }

    @Test
    fun aGroupGoesByItsGuidWithARandomChatGuid() {
        val target = DirectWire.Target(listOf(bob, carol, me), "Friends", "GROUP-GUID", me)
        val (to, message) = RecycleRules.outgoing(target, RecycleRules.OP_RECOVER, null, me, listOf(me, "mailto:me@example.com"), null, now, Random(1))
        assertEquals(listOf(me), to.participants)
        val op = (message as PushChatMessage.RecoverChat).v1
        assertEquals(listOf("+15550000002", "carol@example.com"), op.participants)
        assertEquals("GROUP-GUID", op.groupId)
        assertTrue(op.guid, Regex("^iMessage;\\+;chat[0-9]{18}$").matches(op.guid))
        // A purge of the chat; messages to the recycle bin.
        val purge = RecycleRules.outgoing(target, RecycleRules.OP_PURGE, null, me, listOf(me), null, now).second as PushChatMessage.PermanentDelete
        assertFalse(purge.v1.isScheduled)
        assertTrue(purge.v1.target is DeleteTarget.Chat)
        val messages = RecycleRules.outgoing(target, RecycleRules.OP_BIN, listOf("M1"), me, listOf(me), null, now).second as PushChatMessage.MoveToRecycleBin
        assertEquals(DeleteTarget.Messages(listOf("M1")), messages.v1.target)
    }

    @Test
    fun whatCannotGoIsRefused() {
        val target = DirectWire.Target(listOf(bob, me), null, null, me)
        for ((op, guids) in listOf(RecycleRules.OP_RECOVER to listOf("M1"), "wipe" to null, RecycleRules.OP_BIN to emptyList<String>(), RecycleRules.OP_BIN to listOf("a/b"))) {
            try {
                RecycleRules.outgoing(target, op, guids, me, listOf(me), null, now)
                fail("$op $guids")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
