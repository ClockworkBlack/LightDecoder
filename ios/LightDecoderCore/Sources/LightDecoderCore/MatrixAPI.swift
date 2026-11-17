import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct HTTPRequest: Sendable {
    public var method: String
    public var url: URL
    public var headers: [String: String]
    public var body: Data?
    public var timeout: TimeInterval

    public init(method: String, url: URL, headers: [String: String] = [:], body: Data? = nil, timeout: TimeInterval = 30) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.timeout = timeout
    }
}

public struct HTTPResponse: Sendable {
    public var status: Int
    public var body: Data

    public init(status: Int, body: Data) {
        self.status = status
        self.body = body
    }
}

/// How requests leave the app; replaced by a fake in tests.
public protocol HTTPTransport: Sendable {
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}

public final class URLSessionTransport: HTTPTransport, @unchecked Sendable {
    private let session: URLSession

    public init(session: URLSession = URLSession(configuration: .ephemeral)) {
        self.session = session
    }

    public func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        var r = URLRequest(url: request.url, timeoutInterval: request.timeout)
        r.httpMethod = request.method
        for (k, v) in request.headers { r.setValue(v, forHTTPHeaderField: k) }
        r.httpBody = request.body
        return try await withCheckedThrowingContinuation { continuation in
            let task = session.dataTask(with: r) { data, response, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let http = response as? HTTPURLResponse {
                    continuation.resume(returning: HTTPResponse(status: http.statusCode, body: data ?? Data()))
                } else {
                    continuation.resume(throwing: MatrixError(status: 0, errcode: "NO_RESPONSE", message: "No response"))
                }
            }
            task.resume()
        }
    }
}

/// A server's refusal: its HTTP status, Matrix error code ("M_FORBIDDEN"...) and message.
public struct MatrixError: Error, Equatable, Sendable {
    public var status: Int
    public var errcode: String
    public var message: String

    public init(status: Int, errcode: String, message: String) {
        self.status = status
        self.errcode = errcode
        self.message = message
    }

    /// The account's sign-in is no longer valid: the person has to sign in again.
    public var isSignedOut: Bool { errcode == "M_UNKNOWN_TOKEN" || errcode == "M_MISSING_TOKEN" }
}

/**
 The Matrix client-server API, as much of it as the app uses: signing in,
 the sync loop, sending and removing events, media, receipts, typing and
 conversations. Messages are not end-to-end encrypted by this client: they
 travel over HTTPS and the server can read them.
 */
public final class MatrixAPI: Sendable {
    public let homeserver: URL
    public let accessToken: String?
    private let transport: HTTPTransport

    public init(homeserver: URL, accessToken: String? = nil, transport: HTTPTransport = URLSessionTransport()) {
        self.homeserver = homeserver
        self.accessToken = accessToken
        self.transport = transport
    }

    public convenience init(session: MatrixSession, transport: HTTPTransport = URLSessionTransport()) {
        self.init(homeserver: session.homeserver, accessToken: session.accessToken, transport: transport)
    }

    // MARK: - plumbing

    private static let pathAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()

    static func escape(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: pathAllowed) ?? s }

    func url(_ path: String, query: [(String, String)] = []) -> URL {
        var text = homeserver.absoluteString
        while text.hasSuffix("/") { text.removeLast() }
        text += path
        if !query.isEmpty {
            text += "?" + query.map { "\(Self.escape($0.0))=\(Self.escape($0.1))" }.joined(separator: "&")
        }
        return URL(string: text)!
    }

    private func authorized(_ headers: [String: String] = [:]) -> [String: String] {
        var h = headers
        if let accessToken { h["Authorization"] = "Bearer \(accessToken)" }
        return h
    }

    @discardableResult
    private func call(
        _ method: String, _ path: String, query: [(String, String)] = [], body: JSONValue? = nil, timeout: TimeInterval = 30
    ) async throws -> JSONValue {
        var headers = authorized()
        var data: Data?
        if let body {
            data = try body.data()
            headers["Content-Type"] = "application/json"
        }
        let response = try await transport.send(HTTPRequest(method: method, url: url(path, query: query), headers: headers, body: data, timeout: timeout))
        return try Self.json(response)
    }

    static func json(_ response: HTTPResponse) throws -> JSONValue {
        let parsed = (try? JSONValue.parse(response.body)) ?? .object([:])
        guard (200..<300).contains(response.status) else {
            throw MatrixError(
                status: response.status,
                errcode: parsed["errcode"]?.string ?? "HTTP_\(response.status)",
                message: parsed["error"]?.string ?? "The server answered \(response.status)"
            )
        }
        return parsed
    }

    // MARK: - finding the server, signing in

    /// The address of a server's client API, from its name ("matrix.org" -> https://matrix-client.matrix.org).
    public static func discover(serverName: String, transport: HTTPTransport = URLSessionTransport()) async throws -> URL {
        var name = serverName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        for prefix in ["https://", "http://"] where name.hasPrefix(prefix) { name.removeFirst(prefix.count) }
        while name.hasSuffix("/") { name.removeLast() }
        guard !name.isEmpty, !name.contains(" "), let fallback = URL(string: "https://\(name)") else {
            throw MatrixError(status: 0, errcode: "BAD_SERVER", message: "That is not a server name")
        }
        let wellKnown = fallback.appendingPathComponent(".well-known/matrix/client")
        if let response = try? await transport.send(HTTPRequest(method: "GET", url: wellKnown, timeout: 15)),
           response.status == 200,
           let base = (try? JSONValue.parse(response.body))?.at("m.homeserver", "base_url")?.string,
           let url = URL(string: base), url.scheme == "https" {
            return url
        }
        return fallback
    }

    /// The ways this server lets an account sign in ("m.login.password", "m.login.sso"...).
    public func loginFlows() async throws -> [String] {
        let answer = try await call("GET", "/_matrix/client/v3/login")
        return (answer["flows"]?.array ?? []).compactMap { $0["type"]?.string }
    }

    /// The server's own sign-in page. It ends by opening [redirect] with "loginToken" added, for [login(token:)].
    public func ssoRedirectURL(redirect: String) -> URL {
        url("/_matrix/client/v3/login/sso/redirect", query: [("redirectUrl", redirect)])
    }

    private func session(from answer: JSONValue) throws -> MatrixSession {
        guard let token = answer["access_token"]?.string, let user = answer["user_id"]?.string else {
            throw MatrixError(status: 200, errcode: "BAD_LOGIN_ANSWER", message: "The server's answer had no sign-in in it")
        }
        return MatrixSession(homeserver: homeserver, accessToken: token, userId: user, deviceId: answer["device_id"]?.string ?? "")
    }

    public func login(user: String, password: String, deviceName: String) async throws -> MatrixSession {
        let body: JSONValue = [
            "type": "m.login.password",
            "identifier": ["type": "m.id.user", "user": .string(user)],
            "password": .string(password),
            "initial_device_display_name": .string(deviceName),
        ]
        return try session(from: try await call("POST", "/_matrix/client/v3/login", body: body))
    }

    public func login(token: String, deviceName: String) async throws -> MatrixSession {
        let body: JSONValue = ["type": "m.login.token", "token": .string(token), "initial_device_display_name": .string(deviceName)]
        return try session(from: try await call("POST", "/_matrix/client/v3/login", body: body))
    }

    public func logout() async throws {
        try await call("POST", "/_matrix/client/v3/logout", body: .object([:]))
    }

    // MARK: - sync

    /// Lazy member loading and a modest timeline per room; presence is not used.
    static let syncFilter = #"{"presence":{"types":[]},"room":{"state":{"lazy_load_members":true},"timeline":{"limit":40}}}"#

    /// Waits up to [timeoutMs] for news after [since]; without [since], the account's state so far.
    public func sync(since: String?, timeoutMs: Int) async throws -> JSONValue {
        var query = [("filter", Self.syncFilter), ("timeout", String(timeoutMs))]
        if let since { query.append(("since", since)) }
        return try await call("GET", "/_matrix/client/v3/sync", query: query, timeout: TimeInterval(timeoutMs) / 1000 + 30)
    }

    // MARK: - events

    /// Sends an event; [transactionId] makes a repeat of the same send harmless. Returns the event's id.
    public func send(roomId: String, type: String, content: JSONValue, transactionId: String) async throws -> String {
        let path = "/_matrix/client/v3/rooms/\(Self.escape(roomId))/send/\(Self.escape(type))/\(Self.escape(transactionId))"
        let answer = try await call("PUT", path, body: content)
        guard let id = answer["event_id"]?.string else {
            throw MatrixError(status: 200, errcode: "NO_EVENT_ID", message: "The server did not say what it stored")
        }
        return id
    }

    /// Removes an event (a message of one's own, or a reaction taken back).
    public func redact(roomId: String, eventId: String, transactionId: String) async throws {
        let path = "/_matrix/client/v3/rooms/\(Self.escape(roomId))/redact/\(Self.escape(eventId))/\(Self.escape(transactionId))"
        try await call("PUT", path, body: .object([:]))
    }

    /// Older messages before [from] (a room's prevBatch), newest first, and where to continue.
    public func messages(roomId: String, from: String, limit: Int = 40) async throws -> (events: [JSONValue], end: String?) {
        let answer = try await call(
            "GET", "/_matrix/client/v3/rooms/\(Self.escape(roomId))/messages",
            query: [("from", from), ("dir", "b"), ("limit", String(limit))]
        )
        return (answer["chunk"]?.array ?? [], answer["end"]?.string)
    }

    // MARK: - receipts and typing

    /// Says the account has read up to [eventId]: the sender sees it as read, and the unread count clears.
    public func markRead(roomId: String, eventId: String, sendReceipt: Bool) async throws {
        var body: [String: JSONValue] = ["m.fully_read": .string(eventId)]
        // A private receipt clears this account's unread count without telling the others.
        body[sendReceipt ? "m.read" : "m.read.private"] = .string(eventId)
        try await call("POST", "/_matrix/client/v3/rooms/\(Self.escape(roomId))/read_markers", body: .object(body))
    }

    public func setTyping(roomId: String, userId: String, typing: Bool) async throws {
        var body: [String: JSONValue] = ["typing": .bool(typing)]
        if typing { body["timeout"] = 20_000 }
        try await call("PUT", "/_matrix/client/v3/rooms/\(Self.escape(roomId))/typing/\(Self.escape(userId))", body: .object(body))
    }

    // MARK: - conversations

    /// Starts a private conversation with [userId], not end-to-end encrypted (this app cannot read encrypted ones yet).
    public func createDirectRoom(inviting userId: String) async throws -> String {
        let body: JSONValue = ["invite": [.string(userId)], "is_direct": true, "preset": "trusted_private_chat"]
        let answer = try await call("POST", "/_matrix/client/v3/createRoom", body: body)
        guard let id = answer["room_id"]?.string else {
            throw MatrixError(status: 200, errcode: "NO_ROOM_ID", message: "The server did not say which conversation it made")
        }
        return id
    }

    /// Records [roomId] as the account's one-to-one conversation with [userId] (m.direct), for every app of the account.
    public func markDirect(myUserId: String, otherUserId: String, roomId: String) async throws {
        let path = "/_matrix/client/v3/user/\(Self.escape(myUserId))/account_data/m.direct"
        var map = (try? await call("GET", path))?.object ?? [:]
        var rooms = map[otherUserId]?.array ?? []
        if !rooms.contains(.string(roomId)) { rooms.append(.string(roomId)) }
        map[otherUserId] = .array(rooms)
        try await call("PUT", path, body: .object(map))
    }

    public func join(roomId: String) async throws {
        try await call("POST", "/_matrix/client/v3/join/\(Self.escape(roomId))", body: .object([:]))
    }

    public func leave(roomId: String) async throws {
        try await call("POST", "/_matrix/client/v3/rooms/\(Self.escape(roomId))/leave", body: .object([:]))
    }

    // MARK: - profile

    public func profile(userId: String) async throws -> (displayName: String?, avatarMxc: String?) {
        let answer = try await call("GET", "/_matrix/client/v3/profile/\(Self.escape(userId))")
        return (answer["displayname"]?.string, answer["avatar_url"]?.string)
    }

    public func setDisplayName(userId: String, name: String) async throws {
        try await call("PUT", "/_matrix/client/v3/profile/\(Self.escape(userId))/displayname", body: ["displayname": .string(name)])
    }

    // MARK: - media

    /// Stores a picture or file on the server; returns its "mxc://" address.
    public func upload(_ data: Data, mimeType: String, fileName: String) async throws -> String {
        let request = HTTPRequest(
            method: "POST", url: url("/_matrix/media/v3/upload", query: [("filename", fileName)]),
            headers: authorized(["Content-Type": mimeType]), body: data, timeout: 120
        )
        let answer = try Self.json(try await transport.send(request))
        guard let mxc = answer["content_uri"]?.string else {
            throw MatrixError(status: 200, errcode: "NO_CONTENT_URI", message: "The server did not say where it stored the file")
        }
        return mxc
    }

    /// The request that fetches media (or, with [thumbnail], a small version of a picture). Media needs the sign-in.
    public func mediaRequest(mxc: String, thumbnail: (width: Int, height: Int)? = nil) -> HTTPRequest? {
        guard let parts = MatrixText.mxcParts(mxc) else { return nil }
        let tail = "\(Self.escape(parts.server))/\(Self.escape(parts.mediaId))"
        let address: URL
        if let thumbnail {
            address = url(
                "/_matrix/client/v1/media/thumbnail/\(tail)",
                query: [("width", String(thumbnail.width)), ("height", String(thumbnail.height)), ("method", "scale")]
            )
        } else {
            address = url("/_matrix/client/v1/media/download/\(tail)")
        }
        return HTTPRequest(method: "GET", url: address, headers: authorized(), timeout: 60)
    }

    public func download(mxc: String, thumbnail: (width: Int, height: Int)? = nil) async throws -> Data {
        guard let request = mediaRequest(mxc: mxc, thumbnail: thumbnail) else {
            throw MatrixError(status: 0, errcode: "BAD_MXC", message: "Not a media address")
        }
        let response = try await transport.send(request)
        guard (200..<300).contains(response.status) else { _ = try Self.json(response); return Data() }
        return response.body
    }
}
