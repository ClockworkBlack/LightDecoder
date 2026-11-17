import Foundation

/**
 Turns what the server sends (a /sync answer, or a page of older messages)
 into the app's chat state: conversations, their messages with replies,
 reactions, edits and removals, who is typing, who has read what, and
 invitations. Pure: the same input gives the same state, so it is tested
 without a server.
 */
public enum SyncReducer {

    /// How many messages of a conversation are kept in memory.
    static let timelineLimit = 600

    /// Applies one /sync answer. Returns the messages from other people that are news (not the first load).
    @discardableResult
    public static func apply(_ sync: JSONValue, to state: inout ChatState, me: String) -> [IncomingMessage] {
        let firstLoad = state.nextBatch == nil
        var news: [IncomingMessage] = []

        for event in sync.at("account_data", "events")?.array ?? [] where event["type"]?.string == "m.direct" {
            var direct = Set<String>()
            for (_, rooms) in event["content"]?.object ?? [:] {
                for room in rooms.array ?? [] { if let id = room.string { direct.insert(id) } }
            }
            state.directRoomIds = direct
        }

        for (roomId, joined) in sync.at("rooms", "join")?.object ?? [:] {
            var room = state.rooms[roomId] ?? ChatRoom(id: roomId)
            state.invites[roomId] = nil

            if let heroes = joined.at("summary", "m.heroes")?.array { room.heroes = heroes.compactMap(\.string) }
            for event in joined.at("state", "events")?.array ?? [] { applyState(event, to: &room) }

            let timeline = joined["timeline"]
            let known = !room.timeline.isEmpty || room.prevBatch != nil
            if timeline?["limited"]?.bool == true, known {
                // Messages between what is held and what arrives were skipped: what is held
                // would have a hole in it, so only what is still being sent is kept.
                room.timeline = room.timeline.filter(\.isLocal)
                room.prevBatch = timeline?["prev_batch"]?.string
            } else if !known {
                room.prevBatch = timeline?["prev_batch"]?.string
            }
            for event in timeline?["events"]?.array ?? [] {
                if let message = applyTimeline(event, to: &room, me: me), !firstLoad, message.sender != me {
                    news.append(IncomingMessage(
                        roomId: roomId, roomName: room.displayName(me: me), senderName: room.name(of: message.sender),
                        preview: MatrixText.preview(message), eventId: message.id
                    ))
                }
            }
            for event in joined.at("ephemeral", "events")?.array ?? [] { applyEphemeral(event, to: &room, me: me) }
            if let count = joined.at("unread_notifications", "notification_count")?.int { room.unreadCount = count }
            if room.timeline.count > timelineLimit {
                room.timeline.removeFirst(room.timeline.count - timelineLimit)
                // History beyond what is kept is no longer contiguous with the saved position.
                room.prevBatch = nil
            }
            room.isDirect = state.directRoomIds.contains(roomId) || room.isDirect
            state.rooms[roomId] = room
        }

        for (roomId, invited) in sync.at("rooms", "invite")?.object ?? [:] where state.rooms[roomId] == nil {
            var invite = RoomInvite(id: roomId, name: nil, inviter: nil, inviterName: nil, isDirect: false)
            var names: [String: String] = [:]
            for event in invited.at("invite_state", "events")?.array ?? [] {
                let content = event["content"]
                switch event["type"]?.string {
                case "m.room.name": invite.name = content?["name"]?.string.flatMap { $0.isEmpty ? nil : $0 }
                case "m.room.member":
                    if let key = event["state_key"]?.string, let name = content?["displayname"]?.string { names[key] = name }
                    if event["state_key"]?.string == me, content?["membership"]?.string == "invite" {
                        invite.inviter = event["sender"]?.string
                        invite.isDirect = content?["is_direct"]?.bool ?? false
                    }
                default: break
                }
            }
            if let inviter = invite.inviter { invite.inviterName = names[inviter] ?? MatrixText.localpart(inviter) }
            state.invites[roomId] = invite
        }

        for (roomId, _) in sync.at("rooms", "leave")?.object ?? [:] {
            state.rooms[roomId] = nil
            state.invites[roomId] = nil
        }

        for roomId in state.directRoomIds { state.rooms[roomId]?.isDirect = true }
        if let next = sync["next_batch"]?.string { state.nextBatch = next }
        return news
    }

    /// Puts a page of older messages (newest first, as the server gives them) in front of a conversation.
    public static func prepend(older events: [JSONValue], end: String?, to room: inout ChatRoom, me: String) {
        var page = ChatRoom(id: room.id)
        page.members = room.members
        for event in events.reversed() {
            if event["state_key"] != nil { continue } // old state must not replace the current
            _ = applyTimeline(event, to: &page, me: me)
        }
        let have = Set(room.timeline.map(\.id))
        room.timeline.insert(contentsOf: page.timeline.filter { !have.contains($0.id) }, at: 0)
        // Reactions in the page to messages already held.
        for pending in page.pendingRelations { applyRelation(pending.event, to: &room) }
        room.prevBatch = events.isEmpty ? nil : end
    }

    // MARK: - state

    static func applyState(_ event: JSONValue, to room: inout ChatRoom) {
        let content = event["content"]
        switch event["type"]?.string {
        case "m.room.name":
            room.name = content?["name"]?.string.flatMap { $0.isEmpty ? nil : $0 }
        case "m.room.avatar":
            room.avatarMxc = content?["url"]?.string
        case "m.room.encryption":
            room.isEncrypted = true
        case "m.room.member":
            guard let user = event["state_key"]?.string else { return }
            room.members[user] = RoomMember(
                displayName: content?["displayname"]?.string.flatMap { $0.isEmpty ? nil : $0 },
                avatarMxc: content?["avatar_url"]?.string,
                membership: content?["membership"]?.string ?? "leave"
            )
        default: break
        }
    }

    // MARK: - timeline

    /// Applies one timeline event. Returns the message if it added a new one to show.
    static func applyTimeline(_ event: JSONValue, to room: inout ChatRoom, me: String) -> ChatMessage? {
        if event["state_key"] != nil {
            applyState(event, to: &room)
            return nil
        }
        guard let type = event["type"]?.string, let id = event["event_id"]?.string else { return nil }
        let content = event["content"] ?? .object([:])
        switch type {
        case "m.room.redaction":
            if let target = event["redacts"]?.string ?? content["redacts"]?.string { redact(target, in: &room) }
            return nil
        case "m.reaction":
            applyRelation(event, to: &room)
            return nil
        case "m.room.message", "m.sticker", "m.room.encrypted":
            if content.at("m.relates_to", "rel_type")?.string == "m.replace" {
                applyRelation(event, to: &room)
                return nil
            }
            // Redacted on the server already: its content is gone.
            let wasRedacted = event.at("unsigned", "redacted_because") != nil
            var message = wasRedacted
                ? ChatMessage(id: id, sender: event["sender"]?.string ?? "", timestampMs: Int64(event["origin_server_ts"]?.double ?? 0), kind: .redacted, body: "")
                : message(from: event, id: id, type: type, content: content)
            if room.timeline.contains(where: { $0.id == id }) { return nil }
            // The server's copy of something sent from here: it takes the local copy's place.
            if let txn = event.at("unsigned", "transaction_id")?.string,
               let index = room.timeline.firstIndex(where: { $0.transactionId == txn && $0.isLocal }) {
                message.transactionId = txn
                room.timeline[index] = message
            } else {
                room.timeline.append(message)
            }
            // Reactions and edits that came first.
            let waiting = room.pendingRelations.filter { $0.targetId == id }
            if !waiting.isEmpty {
                room.pendingRelations.removeAll { $0.targetId == id }
                for pending in waiting { applyRelation(pending.event, to: &room) }
            }
            return room.timeline.first { $0.id == id }
        default:
            return nil
        }
    }

    static func message(from event: JSONValue, id: String, type: String, content: JSONValue) -> ChatMessage {
        let sender = event["sender"]?.string ?? ""
        let ts = Int64(event["origin_server_ts"]?.double ?? 0)
        let replyTo = content.at("m.relates_to", "m.in_reply_to", "event_id")?.string
        var body = content["body"]?.string ?? ""
        if replyTo != nil { body = MatrixText.withoutReplyFallback(body) }

        func media() -> MediaInfo? {
            guard let mxc = content["url"]?.string, MatrixText.mxcParts(mxc) != nil else { return nil }
            let info = content["info"]
            return MediaInfo(
                mxc: mxc, thumbnailMxc: info?["thumbnail_url"]?.string, mimeType: info?["mimetype"]?.string,
                width: info?["w"]?.int, height: info?["h"]?.int, size: info?["size"]?.int
            )
        }

        var kind: MessageKind
        var attachment: MediaInfo?
        if type == "m.room.encrypted" {
            kind = .encrypted
            body = ""
        } else if type == "m.sticker" {
            attachment = media()
            kind = attachment == nil ? .unsupported : .sticker
        } else {
            switch content["msgtype"]?.string {
            case "m.text": kind = .text
            case "m.emote": kind = .emote
            case "m.notice": kind = .notice
            case "m.image": attachment = media(); kind = attachment == nil ? .unsupported : .image
            case "m.video": attachment = media(); kind = .video
            case "m.audio": attachment = media(); kind = .audio
            case "m.file": attachment = media(); kind = .file
            default: kind = .unsupported
            }
        }
        return ChatMessage(id: id, sender: sender, timestampMs: ts, kind: kind, body: body, media: attachment, replyTo: replyTo)
    }

    /// A reaction or an edit: applied to its message, or kept until the message arrives.
    static func applyRelation(_ event: JSONValue, to room: inout ChatRoom) {
        let content = event["content"]
        guard let relation = content?["m.relates_to"], let target = relation["event_id"]?.string else { return }
        guard let index = room.timeline.firstIndex(where: { $0.id == target }) else {
            if !room.pendingRelations.contains(where: { $0.event["event_id"] == event["event_id"] }) {
                room.pendingRelations.append(PendingRelation(targetId: target, event: event))
                if room.pendingRelations.count > 200 { room.pendingRelations.removeFirst() }
            }
            return
        }
        switch relation["rel_type"]?.string {
        case "m.annotation":
            guard let key = relation["key"]?.string, let sender = event["sender"]?.string, let id = event["event_id"]?.string else { return }
            room.timeline[index].reactions[key, default: [:]][sender] = id
        case "m.replace":
            // Only the author's own edit counts.
            guard event["sender"]?.string == room.timeline[index].sender, let new = content?["m.new_content"] else { return }
            if let body = new["body"]?.string {
                room.timeline[index].body = body
                room.timeline[index].edited = true
            }
        default: break
        }
    }

    /// Removes a message's content, or takes a reaction back.
    static func redact(_ target: String, in room: inout ChatRoom) {
        if let index = room.timeline.firstIndex(where: { $0.id == target }) {
            room.timeline[index].kind = .redacted
            room.timeline[index].body = ""
            room.timeline[index].media = nil
            room.timeline[index].reactions = [:]
            return
        }
        for index in room.timeline.indices {
            for (emoji, people) in room.timeline[index].reactions {
                if let who = people.first(where: { $0.value == target })?.key {
                    room.timeline[index].reactions[emoji]?[who] = nil
                    if room.timeline[index].reactions[emoji]?.isEmpty == true { room.timeline[index].reactions[emoji] = nil }
                    return
                }
            }
        }
        room.pendingRelations.removeAll { $0.event["event_id"]?.string == target }
    }

    // MARK: - typing and receipts

    static func applyEphemeral(_ event: JSONValue, to room: inout ChatRoom, me: String) {
        switch event["type"]?.string {
        case "m.typing":
            room.typing = (event.at("content", "user_ids")?.array ?? []).compactMap(\.string).filter { $0 != me }
        case "m.receipt":
            for (eventId, kinds) in event["content"]?.object ?? [:] {
                for (user, _) in kinds["m.read"]?.object ?? [:] { room.readReceipts[user] = eventId }
            }
        default: break
        }
    }
}

// MARK: - what the screens ask of a conversation

extension ChatRoom {

    /// The name of a person in this conversation: their display name, else the first part of their id.
    public func name(of userId: String) -> String {
        members[userId]?.displayName ?? MatrixText.localpart(userId)
    }

    /// What the conversation is called: its own name, else the people in it other than [me].
    public func displayName(me: String) -> String {
        if let name, !name.isEmpty { return name }
        var others = heroes.filter { $0 != me }
        if others.isEmpty {
            others = members.filter { $0.key != me && ($0.value.membership == "join" || $0.value.membership == "invite") }.keys.sorted()
        }
        if others.isEmpty { return "Empty conversation" }
        return others.prefix(3).map { name(of: $0) }.joined(separator: ", ") + (others.count > 3 ? " and others" : "")
    }

    /// Whether someone other than [me] has read as far as message [id] (a receipt on it or on a later message).
    public func isRead(_ id: String, me: String) -> Bool {
        guard let index = timeline.firstIndex(where: { $0.id == id }) else { return false }
        for (user, eventId) in readReceipts where user != me {
            if let at = timeline.firstIndex(where: { $0.id == eventId }), at >= index { return true }
        }
        return false
    }

    /// The names of the people typing, for "Anna is typing…".
    public var typingNames: [String] { typing.map { name(of: $0) } }

    public func message(_ id: String) -> ChatMessage? { timeline.first { $0.id == id } }

    // MARK: sending from here

    /// Shows a message at once, before the server has it.
    public mutating func addLocal(_ message: ChatMessage) {
        timeline.append(message)
    }

    /// The server took the message: it has its real id now (unless sync already put the server's copy in).
    public mutating func confirmSent(transactionId: String, eventId: String) {
        guard let index = timeline.firstIndex(where: { $0.transactionId == transactionId && $0.isLocal }) else { return }
        if timeline.contains(where: { $0.id == eventId }) {
            timeline.remove(at: index)
        } else {
            timeline[index].id = eventId
            timeline[index].sendState = .sent
        }
    }

    public mutating func markFailed(transactionId: String) {
        guard let index = timeline.firstIndex(where: { $0.transactionId == transactionId && $0.isLocal }) else { return }
        timeline[index].sendState = .failed
    }

    public mutating func removeLocal(transactionId: String) {
        timeline.removeAll { $0.transactionId == transactionId && $0.isLocal }
    }

    /// The account's own reaction with [emoji] on message [id], if any: its event id, to take it back.
    public func myReaction(_ emoji: String, on id: String, me: String) -> String? {
        message(id)?.reactions[emoji]?[me]
    }
}
