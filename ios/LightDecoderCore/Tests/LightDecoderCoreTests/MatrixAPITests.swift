import XCTest
@testable import LightDecoderCore

/// A server that records what it is asked and answers from a script.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [HTTPRequest] = []
    var answers: [(status: Int, body: String)] = []

    var requests: [HTTPRequest] { lock.lock(); defer { lock.unlock() }; return _requests }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        lock.lock()
        _requests.append(request)
        let answer = answers.isEmpty ? (200, "{}") : answers.removeFirst()
        lock.unlock()
        return HTTPResponse(status: answer.0, body: Data(answer.1.utf8))
    }
}

final class MatrixAPITests: XCTestCase {

    private let base = URL(string: "https://matrix-client.matrix.org")!

    private func api(_ transport: FakeTransport, token: String? = "tok") -> MatrixAPI {
        MatrixAPI(homeserver: base, accessToken: token, transport: transport)
    }

    private func body(_ request: HTTPRequest) throws -> JSONValue { try JSONValue.parse(request.body ?? Data()) }

    func testTheServerIsFoundFromItsName() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"m.homeserver":{"base_url":"https://matrix-client.matrix.org"}}"#)]
        let found = try await MatrixAPI.discover(serverName: " Matrix.org/ ", transport: t)
        XCTAssertEqual(found, base)
        XCTAssertEqual(t.requests[0].url.absoluteString, "https://matrix.org/.well-known/matrix/client")
        // No such file: the name itself is the address. An http address in it is not trusted.
        t.answers = [(404, "")]
        let plain = try await MatrixAPI.discover(serverName: "https://example.org", transport: t)
        XCTAssertEqual(plain.absoluteString, "https://example.org")
        t.answers = [(200, #"{"m.homeserver":{"base_url":"http://evil.example"}}"#)]
        let guarded = try await MatrixAPI.discover(serverName: "example.org", transport: t)
        XCTAssertEqual(guarded.absoluteString, "https://example.org")
    }

    func testSigningInWithAPasswordOrTheServersOwnPage() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"access_token":"abc","user_id":"@anna:matrix.org","device_id":"DEV"}"#)]
        let session = try await api(t, token: nil).login(user: "anna", password: "pw", deviceName: "LightDecoder (iPhone)")
        XCTAssertEqual(session, MatrixSession(homeserver: base, accessToken: "abc", userId: "@anna:matrix.org", deviceId: "DEV"))
        let sent = try body(t.requests[0])
        XCTAssertEqual(sent["type"], "m.login.password")
        XCTAssertEqual(sent.at("identifier", "user"), "anna")
        XCTAssertNil(t.requests[0].headers["Authorization"], "nothing to authorize with yet")

        let sso = api(t, token: nil).ssoRedirectURL(redirect: "lightdecoder://sso")
        XCTAssertEqual(sso.absoluteString, "https://matrix-client.matrix.org/_matrix/client/v3/login/sso/redirect?redirectUrl=lightdecoder%3A%2F%2Fsso")
        t.answers = [(200, #"{"access_token":"xyz","user_id":"@bob:matrix.org","device_id":"D2"}"#)]
        let viaToken = try await api(t, token: nil).login(token: "ticket", deviceName: "x")
        XCTAssertEqual(viaToken.userId, "@bob:matrix.org")
        XCTAssertEqual(try body(t.requests[1])["token"], "ticket")
    }

    func testARefusalCarriesTheServersReason() async {
        let t = FakeTransport()
        t.answers = [(403, #"{"errcode":"M_FORBIDDEN","error":"Invalid username or password"}"#), (401, #"{"errcode":"M_UNKNOWN_TOKEN","error":"gone"}"#)]
        do {
            _ = try await api(t, token: nil).login(user: "a", password: "b", deviceName: "x")
            XCTFail("should have been refused")
        } catch let error as MatrixError {
            XCTAssertEqual(error, MatrixError(status: 403, errcode: "M_FORBIDDEN", message: "Invalid username or password"))
            XCTAssertFalse(error.isSignedOut)
        } catch { XCTFail("\(error)") }
        do {
            _ = try await api(t).sync(since: "s", timeoutMs: 0)
            XCTFail("should have been refused")
        } catch let error as MatrixError {
            XCTAssertTrue(error.isSignedOut)
        } catch { XCTFail("\(error)") }
    }

    func testSendingAReplyAReactionAndTakingItBack() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"event_id":"$new"}"#), (200, #"{"event_id":"$react"}"#), (200, "{}")]
        let a = api(t)
        let id = try await a.send(roomId: "!r:matrix.org", type: "m.room.message", content: MatrixContent.text("ok!", replyTo: "$1"), transactionId: "t 1")
        XCTAssertEqual(id, "$new")
        let first = t.requests[0]
        XCTAssertEqual(first.method, "PUT")
        XCTAssertEqual(first.url.absoluteString, "https://matrix-client.matrix.org/_matrix/client/v3/rooms/%21r%3Amatrix.org/send/m.room.message/t%201")
        XCTAssertEqual(first.headers["Authorization"], "Bearer tok")
        XCTAssertEqual(try body(first).at("m.relates_to", "m.in_reply_to", "event_id"), "$1")
        XCTAssertEqual(try body(first)["body"], "ok!", "no quoted lines are added: the relation says what is answered")

        _ = try await a.send(roomId: "!r:matrix.org", type: "m.reaction", content: MatrixContent.reaction(to: "$new", emoji: "👍"), transactionId: "t2")
        XCTAssertEqual(try body(t.requests[1]).at("m.relates_to", "key"), "👍")
        XCTAssertEqual(try body(t.requests[1]).at("m.relates_to", "rel_type"), "m.annotation")

        try await a.redact(roomId: "!r:matrix.org", eventId: "$react", transactionId: "t3")
        XCTAssertTrue(t.requests[2].url.absoluteString.hasSuffix("/rooms/%21r%3Amatrix.org/redact/%24react/t3"))
    }

    func testPicturesAreUploadedAndFetchedWithTheSignIn() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"content_uri":"mxc://matrix.org/abc"}"#), (200, "PNGDATA")]
        let a = api(t)
        let mxc = try await a.upload(Data("img".utf8), mimeType: "image/png", fileName: "sticker 1.png")
        XCTAssertEqual(mxc, "mxc://matrix.org/abc")
        XCTAssertEqual(t.requests[0].headers["Content-Type"], "image/png")
        XCTAssertEqual(t.requests[0].url.absoluteString, "https://matrix-client.matrix.org/_matrix/media/v3/upload?filename=sticker%201.png")

        let data = try await a.download(mxc: mxc, thumbnail: (width: 320, height: 240))
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "PNGDATA")
        XCTAssertEqual(t.requests[1].url.absoluteString, "https://matrix-client.matrix.org/_matrix/client/v1/media/thumbnail/matrix.org/abc?width=320&height=240&method=scale")
        XCTAssertEqual(t.requests[1].headers["Authorization"], "Bearer tok")
        XCTAssertEqual(a.mediaRequest(mxc: mxc)?.url.absoluteString, "https://matrix-client.matrix.org/_matrix/client/v1/media/download/matrix.org/abc")
        XCTAssertNil(a.mediaRequest(mxc: "https://evil.example/x"), "only the server's own media addresses")
        XCTAssertNil(a.mediaRequest(mxc: "mxc://matrix.org/a/../b"))
    }

    func testReadingTypingAndStartingAConversation() async throws {
        let t = FakeTransport()
        let a = api(t)
        try await a.markRead(roomId: "!r:matrix.org", eventId: "$5", sendReceipt: true)
        XCTAssertEqual(try body(t.requests[0]), ["m.fully_read": "$5", "m.read": "$5"])
        try await a.markRead(roomId: "!r:matrix.org", eventId: "$5", sendReceipt: false)
        XCTAssertEqual(try body(t.requests[1]), ["m.fully_read": "$5", "m.read.private": "$5"], "read receipts off: the others are not told")

        try await a.setTyping(roomId: "!r:matrix.org", userId: "@me:matrix.org", typing: true)
        XCTAssertEqual(try body(t.requests[2])["typing"], true)

        t.answers = [(200, #"{"room_id":"!new:matrix.org"}"#), (200, #"{"@old:matrix.org":["!o:matrix.org"]}"#), (200, "{}")]
        let room = try await a.createDirectRoom(inviting: "@anna:matrix.org")
        XCTAssertEqual(room, "!new:matrix.org")
        let made = try body(t.requests[3])
        XCTAssertEqual(made["invite"], ["@anna:matrix.org"])
        XCTAssertEqual(made["is_direct"], true)
        XCTAssertNil(made["initial_state"], "no encryption is switched on: this app could not read it")
        try await a.markDirect(myUserId: "@me:matrix.org", otherUserId: "@anna:matrix.org", roomId: room)
        XCTAssertEqual(try body(t.requests[5]), ["@old:matrix.org": ["!o:matrix.org"], "@anna:matrix.org": ["!new:matrix.org"]])

        t.answers = [(200, #"{"chunk":[{"event_id":"$9"}],"end":"p5"}"#)]
        let page = try await a.messages(roomId: "!r:matrix.org", from: "p9")
        XCTAssertEqual(page.events.count, 1)
        XCTAssertEqual(page.end, "p5")
        XCTAssertTrue(t.requests[6].url.absoluteString.contains("dir=b"))
    }

    func testSyncAsksForNewsSinceTheLastOne() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"next_batch":"s2"}"#)]
        let answer = try await api(t).sync(since: "s1", timeoutMs: 30000)
        XCTAssertEqual(answer["next_batch"], "s2")
        let url = t.requests[0].url.absoluteString
        XCTAssertTrue(url.contains("since=s1") && url.contains("timeout=30000") && url.contains("filter="))
        XCTAssertGreaterThan(t.requests[0].timeout, 30, "the request outlasts the server's wait")
    }

    func testIdsAndText() {
        XCTAssertEqual(MatrixText.userId(from: " Anna ", defaultServer: "matrix.org"), "@anna:matrix.org")
        XCTAssertEqual(MatrixText.userId(from: "@bob:example.org", defaultServer: "matrix.org"), "@bob:example.org")
        XCTAssertNil(MatrixText.userId(from: "two words", defaultServer: "matrix.org"))
        XCTAssertNil(MatrixText.userId(from: "@nohost", defaultServer: "matrix.org"))
        XCTAssertEqual(MatrixText.localpart("@anna:matrix.org"), "anna")
        XCTAssertEqual(MatrixText.withoutReplyFallback("> <@a:b> one\n> two\n\nanswer\nmore"), "answer\nmore")
        XCTAssertEqual(MatrixText.withoutReplyFallback("no quote"), "no quote")
        XCTAssertEqual(MatrixText.mxcParts("mxc://matrix.org/abc")?.mediaId, "abc")
        XCTAssertNil(MatrixText.mxcParts("mxc://matrix.org/"))
    }
}

/// Against the real matrix.org (skipped when it cannot be reached): what the app relies on is there.
final class LiveServerTests: XCTestCase {
    func testMatrixOrgOffersTheSignInsTheAppUses() async throws {
        let transport = URLSessionTransport()
        guard let base = try? await MatrixAPI.discover(serverName: "matrix.org", transport: transport),
              let flows = try? await MatrixAPI(homeserver: base, transport: transport).loginFlows() else {
            throw XCTSkip("matrix.org is not reachable from here")
        }
        XCTAssertEqual(base.absoluteString, "https://matrix-client.matrix.org")
        XCTAssertTrue(flows.contains("m.login.sso"), "\(flows)")
        XCTAssertTrue(flows.contains("m.login.token"), "\(flows)")
    }
}
