import Foundation

/// A signed-in Matrix account. Kept in the Keychain by the app.
public struct MatrixSession: Codable, Equatable, Sendable {
    public var homeserver: URL
    public var accessToken: String
    public var userId: String
    public var deviceId: String

    public init(homeserver: URL, accessToken: String, userId: String, deviceId: String) {
        self.homeserver = homeserver
        self.accessToken = accessToken
        self.userId = userId
        self.deviceId = deviceId
    }
}

/// What a message is, for how it is drawn.
public enum MessageKind: String, Codable, Sendable {
    case text, emote, notice, image, sticker, file, video, audio
    /// An end-to-end encrypted message. This app does not decrypt those yet.
    case encrypted
    /// Removed by its sender (or a moderator).
    case redacted
    case unsupported
}

public enum SendState: String, Codable, Sendable { case sent, sending, failed }

/// A picture's or file's details, as the sender gave them.
public struct MediaInfo: Codable, Equatable, Sendable {
    public var mxc: String
    public var thumbnailMxc: String?
    public var mimeType: String?
    public var width: Int?
    public var height: Int?
    public var size: Int?

    public init(mxc: String, thumbnailMxc: String? = nil, mimeType: String? = nil, width: Int? = nil, height: Int? = nil, size: Int? = nil) {
        self.mxc = mxc
        self.thumbnailMxc = thumbnailMxc
        self.mimeType = mimeType
        self.width = width
        self.height = height
        self.size = size
    }
}

public struct ChatMessage: Codable, Equatable, Identifiable, Sendable {
    /// The event id, or "~" + transaction id while the message is only local.
    public var id: String
    public var transactionId: String?
    public var sender: String
    public var timestampMs: Int64
    public var kind: MessageKind
    public var body: String
    public var media: MediaInfo?
    /// The event this one replies to.
    public var replyTo: String?
    /// Emoji -> who reacted with it -> the reaction's own event id (to take it back).
    public var reactions: [String: [String: String]]
    public var edited: Bool
    public var sendState: SendState
    /// A picture being sent, before it has a server address: its local file.
    public var localFile: String?

    public init(
        id: String, transactionId: String? = nil, sender: String, timestampMs: Int64, kind: MessageKind, body: String,
        media: MediaInfo? = nil, replyTo: String? = nil, reactions: [String: [String: String]] = [:], edited: Bool = false,
        sendState: SendState = .sent, localFile: String? = nil
    ) {
        self.id = id
        self.transactionId = transactionId
        self.sender = sender
        self.timestampMs = timestampMs
        self.kind = kind
        self.body = body
        self.media = media
        self.replyTo = replyTo
        self.reactions = reactions
        self.edited = edited
        self.sendState = sendState
        self.localFile = localFile
    }

    public var isLocal: Bool { id.hasPrefix("~") }
}

public struct RoomMember: Codable, Equatable, Sendable {
    public var displayName: String?
    public var avatarMxc: String?
    public var membership: String

    public init(displayName: String? = nil, avatarMxc: String? = nil, membership: String = "join") {
        self.displayName = displayName
        self.avatarMxc = avatarMxc
        self.membership = membership
    }
}

public struct ChatRoom: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    /// The room's own name, if it has one (a conversation between two people usually has none).
    public var name: String?
    public var avatarMxc: String?
    public var isDirect: Bool
    public var isEncrypted: Bool
    /// The people the server names the room after when it has no name of its own.
    public var heroes: [String]
    public var members: [String: RoomMember]
    /// Oldest first.
    public var timeline: [ChatMessage]
    /// Where older history continues on the server; nil when there is none (or not known).
    public var prevBatch: String?
    public var unreadCount: Int
    public var typing: [String]
    /// Who has read up to which event.
    public var readReceipts: [String: String]
    /// Reactions and edits that arrived before the message they belong to.
    public var pendingRelations: [PendingRelation]

    public init(id: String) {
        self.id = id
        self.name = nil
        self.avatarMxc = nil
        self.isDirect = false
        self.isEncrypted = false
        self.heroes = []
        self.members = [:]
        self.timeline = []
        self.prevBatch = nil
        self.unreadCount = 0
        self.typing = []
        self.readReceipts = [:]
        self.pendingRelations = []
    }

    public var lastActivityMs: Int64 { timeline.last?.timestampMs ?? 0 }
}

public struct PendingRelation: Codable, Equatable, Sendable {
    public var targetId: String
    public var event: JSONValue
}

/// A conversation someone has invited this account to.
public struct RoomInvite: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String?
    public var inviter: String?
    public var inviterName: String?
    public var isDirect: Bool
}

/// Everything the app knows of the account's conversations. Saved between runs.
public struct ChatState: Codable, Equatable, Sendable {
    public var rooms: [String: ChatRoom]
    public var invites: [String: RoomInvite]
    /// Where the next sync continues.
    public var nextBatch: String?
    /// Rooms marked as one-to-one conversations in the account (m.direct).
    public var directRoomIds: Set<String>

    public init() {
        rooms = [:]
        invites = [:]
        nextBatch = nil
        directRoomIds = []
    }

    /// Conversations, the most recently active first.
    public var sortedRooms: [ChatRoom] {
        rooms.values.sorted { ($0.lastActivityMs, $0.id) > ($1.lastActivityMs, $1.id) }
    }

    public var totalUnread: Int { rooms.values.reduce(0) { $0 + $1.unreadCount } }
}

/// A message that just arrived from someone else: what a notification says.
public struct IncomingMessage: Equatable, Sendable {
    public var roomId: String
    public var roomName: String
    public var senderName: String
    public var preview: String
    public var eventId: String
}
