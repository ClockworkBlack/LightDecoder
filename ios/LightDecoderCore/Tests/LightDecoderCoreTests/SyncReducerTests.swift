import XCTest
@testable import LightDecoderCore

/// What the server sends becomes conversations: messages, replies, reactions, edits, removals, typing, receipts, invites.
final class SyncReducerTests: XCTestCase {

    private let me = "@me:matrix.org"
    private let anna = "@anna:matrix.org"
    private let room = "!r:matrix.org"

    private func event(_ id: String, _ type: String = "m.room.message", sender: String? = nil, ts: Int = 1000,
                       content: JSONValue, extra: [String: JSONValue] = [:]) -> JSONValue {
        var o: [String: JSONValue] = [
            "event_id": .string(id), "type": .string(type), "sender": .string(sender ?? anna),
            "origin_server_ts": .number(Double(ts)), "content": content,
        ]
        o.merge(extra) { $1 }
        return .object(o)
    }

    private func text(_ id: String, _ body: String, sender: String? = nil, ts: Int = 1000, replyTo: String? = nil) -> JSONValue {
        event(id, sender: sender, ts: ts, content: MatrixContent.text(body, replyTo: replyTo))
    }

    private func member(_ user: String, name: String, membership: String = "join") -> JSONValue {
        ["type": "m.room.member", "state_key": .string(user), "sender": .string(user), "event_id": .string("$m\(user)"),
         "content": ["membership": .string(membership), "displayname": .string(name)]]
    }

    private func sync(next: String, timeline: [JSONValue] = [], state: [JSONValue] = [], ephemeral: [JSONValue] = [],
                      limited: Bool = false, prev: String? = nil, unread: Int? = nil) -> JSONValue {
        var tl: [String: JSONValue] = ["events": .array(timeline), "limited": .bool(limited)]
        if let prev { tl["prev_batch"] = .string(prev) }
        var joined: [String: JSONValue] = [
            "timeline": .object(tl), "state": ["events": .array(state)], "ephemeral": ["events": .array(ephemeral)],
        ]
        if let unread { joined["unread_notifications"] = ["notification_count": .number(Double(unread))] }
        return ["next_batch": .string(next), "rooms": ["join": .object([room: .object(joined)])]]
    }

    /// A conversation with Anna, already loaded once (so later messages are news).
    private func loaded() -> ChatState {
        var state = ChatState()
        SyncReducer.apply(sync(next: "s1", state: [member(me, name: "Me"), member(anna, name: "Anna")], prev: "p0"), to: &state, me: me)
        return state
    }

    func testMessagesArriveInOrderAndOnlyNewOnesAreNews() {
        var state = ChatState()
        let first = SyncReducer.apply(
            sync(next: "s1", timeline: [text("$1", "hello")], state: [member(anna, name: "Anna")], prev: "p0", unread: 1), to: &state, me: me
        )
        XCTAssertTrue(first.isEmpty, "the first load is history, not news")
        XCTAssertEqual(state.nextBatch, "s1")
        XCTAssertEqual(state.rooms[room]?.unreadCount, 1)
        XCTAssertEqual(state.rooms[room]?.prevBatch, "p0")

        let news = SyncReducer.apply(sync(next: "s2", timeline: [text("$2", "you there?", ts: 2000), text("$3", "mine", sender: me, ts: 3000)]), to: &state, me: me)
        XCTAssertEqual(news.map(\.eventId), ["$2"], "one's own messages are not news")
        XCTAssertEqual(news.first?.senderName, "Anna")
        XCTAssertEqual(news.first?.preview, "you there?")
        XCTAssertEqual(news.first?.roomName, "Anna")
        XCTAssertEqual(state.rooms[room]?.timeline.map(\.id), ["$1", "$2", "$3"])
        // The same event again changes nothing.
        SyncReducer.apply(sync(next: "s3", timeline: [text("$2", "you there?", ts: 2000)]), to: &state, me: me)
        XCTAssertEqual(state.rooms[room]?.timeline.count, 3)
    }

    func testARepliesTargetIsKeptAndOldQuoteLinesAreDropped() {
        var state = loaded()
        let old = event("$2", content: [
            "msgtype": "m.text", "body": "> <@me:matrix.org> see you at 6\n\nok!",
            "m.relates_to": ["m.in_reply_to": ["event_id": "$1"]],
        ])
        SyncReducer.apply(sync(next: "s2", timeline: [text("$1", "see you at 6", sender: me), old, text("$3", "and this", replyTo: "$2")]), to: &state, me: me)
        let timeline = state.rooms[room]!.timeline
        XCTAssertEqual(timeline[1].replyTo, "$1")
        XCTAssertEqual(timeline[1].body, "ok!")
        XCTAssertEqual(timeline[2].replyTo, "$2")
        XCTAssertEqual(timeline[2].body, "and this")
    }

    func testReactionsAreAddedCountedAndTakenBack() {
        var state = loaded()
        func reaction(_ id: String, _ emoji: String, from: String, to: String = "$1") -> JSONValue {
            event(id, "m.reaction", sender: from, content: MatrixContent.reaction(to: to, emoji: emoji))
        }
        SyncReducer.apply(sync(next: "s2", timeline: [
            text("$1", "pizza?", sender: me), reaction("$r1", "👍", from: anna), reaction("$r2", "👍", from: me), reaction("$r3", "❤️", from: anna),
        ]), to: &state, me: me)
        var message = state.rooms[room]!.timeline[0]
        XCTAssertEqual(message.reactions["👍"]?.count, 2)
        XCTAssertEqual(message.reactions["❤️"], [anna: "$r3"])
        XCTAssertEqual(state.rooms[room]!.myReaction("👍", on: "$1", me: me), "$r2")
        XCTAssertEqual(state.rooms[room]!.timeline.count, 1, "a reaction is not a message")

        // Anna takes her thumbs-up back.
        SyncReducer.apply(sync(next: "s3", timeline: [event("$x", "m.room.redaction", content: [:], extra: ["redacts": "$r1"])]), to: &state, me: me)
        message = state.rooms[room]!.timeline[0]
        XCTAssertEqual(message.reactions["👍"], [me: "$r2"])
        // A reaction that arrives before its message waits for it.
        SyncReducer.apply(sync(next: "s4", timeline: [reaction("$r9", "😂", from: anna, to: "$late")]), to: &state, me: me)
        SyncReducer.apply(sync(next: "s5", timeline: [text("$late", "joke", sender: me)]), to: &state, me: me)
        XCTAssertEqual(state.rooms[room]!.message("$late")?.reactions["😂"], [anna: "$r9"])
        XCTAssertTrue(state.rooms[room]!.pendingRelations.isEmpty)
    }

    func testEditsAndRemovals() {
        var state = loaded()
        let edit = event("$e", content: [
            "msgtype": "m.text", "body": "* see you at 7",
            "m.new_content": ["msgtype": "m.text", "body": "see you at 7"],
            "m.relates_to": ["rel_type": "m.replace", "event_id": "$1"],
        ])
        let forged = event("$f", sender: me, content: [
            "msgtype": "m.text", "body": "* hacked", "m.new_content": ["msgtype": "m.text", "body": "hacked"],
            "m.relates_to": ["rel_type": "m.replace", "event_id": "$1"],
        ])
        SyncReducer.apply(sync(next: "s2", timeline: [text("$1", "see you at 6"), edit, forged, text("$2", "oops")]), to: &state, me: me)
        var timeline = state.rooms[room]!.timeline
        XCTAssertEqual(timeline.map(\.id), ["$1", "$2"], "an edit is not a message of its own")
        XCTAssertEqual(timeline[0].body, "see you at 7")
        XCTAssertTrue(timeline[0].edited)

        SyncReducer.apply(sync(next: "s3", timeline: [event("$x", "m.room.redaction", content: ["redacts": "$2"])]), to: &state, me: me)
        timeline = state.rooms[room]!.timeline
        XCTAssertEqual(timeline[1].kind, .redacted)
        XCTAssertEqual(timeline[1].body, "")
    }

    func testPicturesStickersAndEncryptedMessages() {
        var state = loaded()
        let media = MediaInfo(mxc: "mxc://matrix.org/abc", mimeType: "image/png", width: 512, height: 512, size: 1234)
        SyncReducer.apply(sync(next: "s2", timeline: [
            event("$1", content: MatrixContent.image(media, fileName: "cat.png")),
            event("$2", "m.sticker", content: MatrixContent.sticker(media, description: "A cat")),
            event("$3", "m.room.encrypted", content: ["algorithm": "m.megolm.v1.aes-sha2", "ciphertext": "x"]),
            event("$4", content: ["msgtype": "m.image", "body": "no address"]),
        ]), to: &state, me: me)
        let timeline = state.rooms[room]!.timeline
        XCTAssertEqual(timeline.map(\.kind), [.image, .sticker, .encrypted, .unsupported])
        XCTAssertEqual(timeline[0].media, media)
        XCTAssertEqual(timeline[1].media?.width, 512)
        XCTAssertEqual(MatrixText.preview(timeline[1]), "Sticker")
        XCTAssertEqual(MatrixText.preview(timeline[2]), "Encrypted message")
    }

    func testTheServersCopyTakesTheLocalCopysPlace() {
        var state = loaded()
        state.rooms[room]!.addLocal(ChatMessage(id: "~t1", transactionId: "t1", sender: me, timestampMs: 5000, kind: .text, body: "hi", sendState: .sending))
        state.rooms[room]!.addLocal(ChatMessage(id: "~t2", transactionId: "t2", sender: me, timestampMs: 6000, kind: .text, body: "again", sendState: .sending))
        // Sync brings the first back before the send call returns.
        SyncReducer.apply(sync(next: "s2", timeline: [
            event("$a", sender: me, ts: 5001, content: MatrixContent.text("hi"), extra: ["unsigned": ["transaction_id": "t1"]]),
        ]), to: &state, me: me)
        XCTAssertEqual(state.rooms[room]!.timeline.map(\.id), ["$a", "~t2"], "in place, not twice")
        state.rooms[room]!.confirmSent(transactionId: "t1", eventId: "$a")
        XCTAssertEqual(state.rooms[room]!.timeline.count, 2)
        // The second: the send call returns first.
        state.rooms[room]!.confirmSent(transactionId: "t2", eventId: "$b")
        XCTAssertEqual(state.rooms[room]!.timeline.map(\.id), ["$a", "$b"])
        XCTAssertEqual(state.rooms[room]!.timeline[1].sendState, .sent)
        SyncReducer.apply(sync(next: "s3", timeline: [
            event("$b", sender: me, ts: 6001, content: MatrixContent.text("again"), extra: ["unsigned": ["transaction_id": "t2"]]),
        ]), to: &state, me: me)
        XCTAssertEqual(state.rooms[room]!.timeline.count, 2)
        // A failed one is marked, and can be dropped.
        state.rooms[room]!.addLocal(ChatMessage(id: "~t3", transactionId: "t3", sender: me, timestampMs: 7000, kind: .text, body: "x", sendState: .sending))
        state.rooms[room]!.markFailed(transactionId: "t3")
        XCTAssertEqual(state.rooms[room]!.timeline.last?.sendState, .failed)
        state.rooms[room]!.removeLocal(transactionId: "t3")
        XCTAssertEqual(state.rooms[room]!.timeline.count, 2)
    }

    func testTypingAndReadReceipts() {
        var state = loaded()
        SyncReducer.apply(sync(next: "s2", timeline: [text("$1", "one", sender: me), text("$2", "two", sender: me), text("$3", "three", sender: me)],
                               ephemeral: [["type": "m.typing", "content": ["user_ids": [.string(anna), .string(me)]]]]), to: &state, me: me)
        XCTAssertEqual(state.rooms[room]!.typingNames, ["Anna"], "not oneself")
        XCTAssertFalse(state.rooms[room]!.isRead("$1", me: me))

        let receipt: JSONValue = ["type": "m.receipt", "content": ["$2": ["m.read": .object([anna: ["ts": 1], me: ["ts": 1]])]]]
        SyncReducer.apply(sync(next: "s3", ephemeral: [receipt, ["type": "m.typing", "content": ["user_ids": []]]]), to: &state, me: me)
        let r = state.rooms[room]!
        XCTAssertTrue(r.typing.isEmpty)
        XCTAssertTrue(r.isRead("$1", me: me), "read up to a later message")
        XCTAssertTrue(r.isRead("$2", me: me))
        XCTAssertFalse(r.isRead("$3", me: me))
    }

    func testAGapDropsWhatIsHeldAndOlderPagesGoInFront() {
        var state = loaded()
        SyncReducer.apply(sync(next: "s2", timeline: [text("$5", "five", ts: 5000)]), to: &state, me: me)
        // Away for a while: the server skips ahead.
        SyncReducer.apply(sync(next: "s3", timeline: [text("$9", "nine", ts: 9000)], limited: true, prev: "p8"), to: &state, me: me)
        XCTAssertEqual(state.rooms[room]!.timeline.map(\.id), ["$9"])
        XCTAssertEqual(state.rooms[room]!.prevBatch, "p8")

        // The page before it, newest first, with a reaction to a message already held.
        var r = state.rooms[room]!
        SyncReducer.prepend(older: [
            event("$r", "m.reaction", content: MatrixContent.reaction(to: "$9", emoji: "👍")),
            text("$8", "eight", ts: 8000), text("$7", "seven", ts: 7000),
        ], end: "p6", to: &r, me: me)
        XCTAssertEqual(r.timeline.map(\.id), ["$7", "$8", "$9"])
        XCTAssertEqual(r.message("$9")?.reactions["👍"], [anna: "$r"])
        XCTAssertEqual(r.prevBatch, "p6")
        SyncReducer.prepend(older: [], end: "p6", to: &r, me: me)
        XCTAssertNil(r.prevBatch, "nothing older")
    }

    func testInvitesNamesAndLeaving() {
        var state = ChatState()
        let invited: JSONValue = ["next_batch": "s1", "rooms": ["invite": ["!new:matrix.org": ["invite_state": ["events": [
            member(anna, name: "Anna"),
            ["type": "m.room.member", "state_key": .string(me), "sender": .string(anna),
             "content": ["membership": "invite", "is_direct": true]],
        ]]]]]]
        SyncReducer.apply(invited, to: &state, me: me)
        let invite = state.invites["!new:matrix.org"]
        XCTAssertEqual(invite?.inviterName, "Anna")
        XCTAssertEqual(invite?.isDirect, true)

        // Accepted: it is a conversation, named after the other person, marked one-to-one by the account.
        let joined: JSONValue = [
            "next_batch": "s2",
            "account_data": ["events": [["type": "m.direct", "content": .object([anna: ["!new:matrix.org"]])]]],
            "rooms": ["join": ["!new:matrix.org": [
                "summary": ["m.heroes": [.string(anna)]],
                "state": ["events": [member(anna, name: "Anna"), member(me, name: "Me")]],
                "timeline": ["events": []],
            ]]],
        ]
        SyncReducer.apply(joined, to: &state, me: me)
        XCTAssertNil(state.invites["!new:matrix.org"])
        XCTAssertEqual(state.rooms["!new:matrix.org"]?.displayName(me: me), "Anna")
        XCTAssertEqual(state.rooms["!new:matrix.org"]?.isDirect, true)
        // A named room keeps its name; a person without a display name shows the first part of their id.
        var named = ChatRoom(id: "!g:x.org")
        named.name = "Family"
        XCTAssertEqual(named.displayName(me: me), "Family")
        XCTAssertEqual(ChatRoom(id: "!x:x.org").name(of: "@bob:example.org"), "bob")

        SyncReducer.apply(["next_batch": "s3", "rooms": ["leave": ["!new:matrix.org": [:]]]], to: &state, me: me)
        XCTAssertNil(state.rooms["!new:matrix.org"])
    }

    func testTheStateSurvivesSavingAndLoading() throws {
        var state = loaded()
        SyncReducer.apply(sync(next: "s2", timeline: [text("$1", "hello"), event("$r", "m.reaction", sender: me, content: MatrixContent.reaction(to: "$1", emoji: "👍"))]), to: &state, me: me)
        let saved = try JSONEncoder().encode(state)
        XCTAssertEqual(try JSONDecoder().decode(ChatState.self, from: saved), state)
        XCTAssertEqual(state.sortedRooms.first?.id, room)
    }
}
