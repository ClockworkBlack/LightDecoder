import BackgroundTasks
import Foundation
import LightDecoderCore
import SwiftUI
import UIKit
import UserNotifications

/**
 The app's state and what it does: the signed-in account, its conversations
 (kept current by a sync loop while the app is open), and sending.

 Messages are not end-to-end encrypted in this version: they travel over
 HTTPS and are stored on the Matrix server the account is on.
 */
@MainActor
final class AppModel: ObservableObject {
    static let refreshTaskId = "com.lightdecoder.ios.refresh"

    @Published private(set) var session: MatrixSession?
    @Published private(set) var state = ChatState()
    /// Why the conversations are not up to date, when they are not.
    @Published private(set) var syncProblem: String?
    @Published private(set) var loadedOnce = false
    /// The conversation on screen: its messages are not notified, and are marked read.
    @Published var openRoomId: String?

    private(set) var api: MatrixAPI?
    private var syncTask: Task<Void, Never>?
    private var saveTask: Task<Void, Never>?
    private var started = false
    private var active = true
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    var me: String { session?.userId ?? "" }

    // MARK: - start and stop

    func start() {
        guard !started else { return }
        started = true
        if let saved = SessionVault.load() {
            adopt(saved)
        }
    }

    private func adopt(_ new: MatrixSession) {
        session = new
        api = MatrixAPI(session: new)
        state = Self.loadState(for: new.userId)
        loadedOnce = state.nextBatch != nil
        startSync()
    }

    private func startSync() {
        syncTask?.cancel()
        syncTask = Task { [weak self] in await self?.syncLoop() }
    }

    private func syncLoop() async {
        var wait: UInt64 = 2
        while !Task.isCancelled {
            guard let api else { return }
            do {
                // The first load answers at once; after that the server holds the request until there is news.
                let answer = try await api.sync(since: state.nextBatch, timeoutMs: state.nextBatch == nil ? 0 : 30_000)
                if Task.isCancelled { return }
                let news = SyncReducer.apply(answer, to: &state, me: me)
                loadedOnce = true
                syncProblem = nil
                wait = 2
                scheduleSave()
                notify(news)
                markOpenRoomRead()
            } catch let error as MatrixError where error.isSignedOut {
                signedOutByServer()
                return
            } catch is CancellationError {
                return
            } catch {
                if Task.isCancelled { return }
                syncProblem = "Not connected. Trying again…"
                try? await Task.sleep(nanoseconds: wait * 1_000_000_000)
                wait = min(wait * 2, 30)
            }
        }
    }

    func becameActive() {
        active = true
        endBackgroundTask()
        UNUserNotificationCenter.current().setBadgeCount(0)
        if session != nil, syncTask == nil || syncTask?.isCancelled == true { startSync() }
    }

    /// iOS stops the app shortly after it leaves the screen: the loop gets a little time to finish, then rests.
    func wentToBackground() {
        active = false
        saveNow()
        guard session != nil else { return }
        scheduleRefresh()
        backgroundTask = UIApplication.shared.beginBackgroundTask { [weak self] in
            Task { @MainActor in self?.pauseSync() }
        }
    }

    private func pauseSync() {
        syncTask?.cancel()
        syncTask = nil
        saveNow()
        endBackgroundTask()
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    private func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// One look for new messages while the app is closed (when iOS allows it), notifying what arrived.
    func backgroundRefresh() async {
        start()
        guard let api, session != nil, !active else { return }
        scheduleRefresh()
        if let answer = try? await api.sync(since: state.nextBatch, timeoutMs: 0) {
            let news = SyncReducer.apply(answer, to: &state, me: me)
            saveNow()
            notify(news)
        }
    }

    // MARK: - signing in and out

    func signIn(server: String, user: String, password: String) async throws {
        let base = try await MatrixAPI.discover(serverName: server)
        let new = try await MatrixAPI(homeserver: base).login(user: user, password: password, deviceName: Self.deviceName)
        finishSignIn(new, server: server)
    }

    /// The address of the server's own sign-in page (where an account can also be made).
    func signInPage(server: String) async throws -> URL {
        let base = try await MatrixAPI.discover(serverName: server)
        return MatrixAPI(homeserver: base).ssoRedirectURL(redirect: "lightdecoder://sso")
    }

    /// Finishes a sign-in made on the server's page: [callback] is the address it handed back.
    func signIn(server: String, callback: URL) async throws {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        guard let token = items.first(where: { $0.name == "loginToken" })?.value, !token.isEmpty else {
            throw MatrixError(status: 0, errcode: "NO_TOKEN", message: "The sign-in page did not finish")
        }
        let base = try await MatrixAPI.discover(serverName: server)
        let new = try await MatrixAPI(homeserver: base).login(token: token, deviceName: Self.deviceName)
        finishSignIn(new, server: server)
    }

    private func finishSignIn(_ new: MatrixSession, server: String) {
        SessionVault.save(new)
        Prefs.serverName = server
        adopt(new)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func signOut() async {
        let old = api
        clearAccount()
        try? await old?.logout()
    }

    private func signedOutByServer() {
        clearAccount()
    }

    private func clearAccount() {
        syncTask?.cancel()
        syncTask = nil
        if let user = session?.userId { try? FileManager.default.removeItem(at: Self.stateFile(for: user)) }
        SessionVault.clear()
        MediaStore.shared.clear()
        session = nil
        api = nil
        state = ChatState()
        loadedOnce = false
        openRoomId = nil
    }

    private static var deviceName: String { "LightDecoder (\(UIDevice.current.model))" }

    /// The server part of the account's id, for completing a name typed without one.
    var myServer: String { me.split(separator: ":", maxSplits: 1).dropFirst().first.map(String.init) ?? Prefs.serverName }

    // MARK: - saving

    private static func stateFile(for user: String) -> URL {
        let safe = user.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? String($0) : "_" }.joined()
        return AppFiles.directory("chat").appendingPathComponent("\(safe).json")
    }

    private static func loadState(for user: String) -> ChatState {
        guard let data = try? Data(contentsOf: stateFile(for: user)),
              let saved = try? JSONDecoder().decode(ChatState.self, from: data) else { return ChatState() }
        return saved
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled { self?.saveNow() }
        }
    }

    private func saveNow() {
        guard let user = session?.userId else { return }
        var copy = state
        // What is still being sent is not saved as if it had been.
        for id in copy.rooms.keys { copy.rooms[id]?.timeline.removeAll { $0.isLocal && $0.sendState == .sending } }
        if let data = try? JSONEncoder().encode(copy) {
            try? data.write(to: Self.stateFile(for: user), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    // MARK: - notifications

    private func notify(_ news: [IncomingMessage]) {
        guard Prefs.notify else { return }
        for message in news where !(active && openRoomId == message.roomId) {
            let content = UNMutableNotificationContent()
            content.title = message.roomName
            content.body = message.senderName == message.roomName ? message.preview : "\(message.senderName): \(message.preview)"
            content.sound = .default
            content.threadIdentifier = message.roomId
            content.userInfo = ["roomId": message.roomId]
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: message.eventId, content: content, trigger: nil))
        }
    }

    // MARK: - reading

    /// The open conversation's newest message is marked read (which is also what tells its sender).
    func markOpenRoomRead() {
        guard active, let roomId = openRoomId, let room = state.rooms[roomId], let api else { return }
        guard let last = room.timeline.last(where: { !$0.isLocal }) else { return }
        if room.unreadCount == 0, room.readReceipts[me] == last.id { return }
        state.rooms[roomId]?.unreadCount = 0
        state.rooms[roomId]?.readReceipts[me] = last.id
        Task { try? await api.markRead(roomId: roomId, eventId: last.id, sendReceipt: Prefs.sendReadReceipts) }
    }

    func loadOlder(roomId: String) async {
        guard let api, let from = state.rooms[roomId]?.prevBatch else { return }
        guard let page = try? await api.messages(roomId: roomId, from: from) else { return }
        guard var room = state.rooms[roomId], room.prevBatch == from else { return }
        SyncReducer.prepend(older: page.events, end: page.end, to: &room, me: me)
        state.rooms[roomId] = room
        scheduleSave()
    }

    private var lastTyping: [String: Date] = [:]

    func typing(roomId: String, isTyping: Bool) {
        guard Prefs.sendTyping, let api else { return }
        if isTyping, let last = lastTyping[roomId], Date().timeIntervalSince(last) < 8 { return }
        if !isTyping, lastTyping[roomId] == nil { return }
        lastTyping[roomId] = isTyping ? Date() : nil
        let user = me
        Task { try? await api.setTyping(roomId: roomId, userId: user, typing: isTyping) }
    }

    // MARK: - sending

    private func newTransactionId() -> String { "ld\(Int(Date().timeIntervalSince1970 * 1000))\(Int.random(in: 1000...9999))" }

    func sendText(_ text: String, roomId: String, replyTo: String?) {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        let txn = newTransactionId()
        let local = ChatMessage(
            id: "~\(txn)", transactionId: txn, sender: me, timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            kind: .text, body: body, replyTo: replyTo, sendState: .sending
        )
        state.rooms[roomId]?.addLocal(local)
        typing(roomId: roomId, isTyping: false)
        deliver(roomId: roomId, txn: txn, type: "m.room.message", content: MatrixContent.text(body, replyTo: replyTo))
    }

    /// Sends a picture, or (with [asSticker]) a sticker: shown at once, uploaded, then sent.
    func sendImage(_ data: Data, mimeType: String, size: CGSize, roomId: String, replyTo: String?, asSticker: Bool) {
        let txn = newTransactionId()
        let ext = mimeType == "image/png" ? "png" : "jpg"
        let file = AppFiles.directory("outgoing", cache: true).appendingPathComponent("\(txn).\(ext)")
        try? data.write(to: file)
        let local = ChatMessage(
            id: "~\(txn)", transactionId: txn, sender: me, timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            kind: asSticker ? .sticker : .image, body: asSticker ? "Sticker" : "picture.\(ext)", replyTo: replyTo,
            sendState: .sending, localFile: file.path
        )
        state.rooms[roomId]?.addLocal(local)
        guard let api else { return }
        Task {
            do {
                let mxc = try await api.upload(data, mimeType: mimeType, fileName: "\(txn).\(ext)")
                let media = MediaInfo(mxc: mxc, mimeType: mimeType, width: Int(size.width), height: Int(size.height), size: data.count)
                MediaStore.shared.remember(data, for: mxc)
                let content = asSticker
                    ? MatrixContent.sticker(media, description: "Sticker", replyTo: replyTo)
                    : MatrixContent.image(media, fileName: "picture.\(ext)", replyTo: replyTo)
                let id = try await api.send(roomId: roomId, type: asSticker ? "m.sticker" : "m.room.message", content: content, transactionId: txn)
                // The local copy gets the server's address, so it shows the same picture once confirmed.
                if let index = state.rooms[roomId]?.timeline.firstIndex(where: { $0.transactionId == txn && $0.isLocal }) {
                    state.rooms[roomId]?.timeline[index].media = media
                }
                state.rooms[roomId]?.confirmSent(transactionId: txn, eventId: id)
                scheduleSave()
            } catch {
                state.rooms[roomId]?.markFailed(transactionId: txn)
            }
        }
    }

    private func deliver(roomId: String, txn: String, type: String, content: JSONValue) {
        guard let api else { return }
        Task {
            do {
                let id = try await api.send(roomId: roomId, type: type, content: content, transactionId: txn)
                state.rooms[roomId]?.confirmSent(transactionId: txn, eventId: id)
                scheduleSave()
            } catch {
                state.rooms[roomId]?.markFailed(transactionId: txn)
            }
        }
    }

    /// Tries a failed text again (the same transaction, so it cannot arrive twice).
    func retry(_ message: ChatMessage, roomId: String) {
        guard let txn = message.transactionId, message.sendState == .failed, message.kind == .text else { return }
        if let index = state.rooms[roomId]?.timeline.firstIndex(where: { $0.id == message.id }) {
            state.rooms[roomId]?.timeline[index].sendState = .sending
        }
        deliver(roomId: roomId, txn: txn, type: "m.room.message", content: MatrixContent.text(message.body, replyTo: message.replyTo))
    }

    func discard(_ message: ChatMessage, roomId: String) {
        if let txn = message.transactionId { state.rooms[roomId]?.removeLocal(transactionId: txn) }
    }

    /// Adds the account's reaction, or takes it back if it is already there.
    func toggleReaction(_ emoji: String, on message: ChatMessage, roomId: String) {
        guard let api, !message.isLocal else { return }
        let user = me
        if var room = state.rooms[roomId], let mine = room.myReaction(emoji, on: message.id, me: user) {
            SyncReducer.redact(mine, in: &room)
            state.rooms[roomId] = room
            Task { try? await api.redact(roomId: roomId, eventId: mine, transactionId: newTransactionId()) }
        } else {
            let txn = newTransactionId()
            if let index = state.rooms[roomId]?.timeline.firstIndex(where: { $0.id == message.id }) {
                state.rooms[roomId]?.timeline[index].reactions[emoji, default: [:]][user] = "~\(txn)"
            }
            Task {
                let content = MatrixContent.reaction(to: message.id, emoji: emoji)
                if let id = try? await api.send(roomId: roomId, type: "m.reaction", content: content, transactionId: txn) {
                    if let index = state.rooms[roomId]?.timeline.firstIndex(where: { $0.id == message.id }),
                       state.rooms[roomId]?.timeline[index].reactions[emoji]?[user] == "~\(txn)" {
                        state.rooms[roomId]?.timeline[index].reactions[emoji]?[user] = id
                    }
                } else if let index = state.rooms[roomId]?.timeline.firstIndex(where: { $0.id == message.id }) {
                    state.rooms[roomId]?.timeline[index].reactions[emoji]?[user] = nil
                    if state.rooms[roomId]?.timeline[index].reactions[emoji]?.isEmpty == true {
                        state.rooms[roomId]?.timeline[index].reactions[emoji] = nil
                    }
                }
            }
        }
    }

    /// Removes one of the account's own messages for everyone.
    func remove(_ message: ChatMessage, roomId: String) {
        guard let api, !message.isLocal, message.sender == me, var room = state.rooms[roomId] else { return }
        SyncReducer.redact(message.id, in: &room)
        state.rooms[roomId] = room
        Task { try? await api.redact(roomId: roomId, eventId: message.id, transactionId: newTransactionId()) }
    }

    // MARK: - conversations

    /// Opens the conversation with [userId], starting one if there is none. Returns its id.
    func startChat(with userId: String) async throws -> String {
        guard let api else { throw MatrixError(status: 0, errcode: "SIGNED_OUT", message: "Not signed in") }
        if let existing = state.rooms.values.first(where: { room in
            room.isDirect && room.members[userId].map { $0.membership == "join" || $0.membership == "invite" } == true
        }) {
            return existing.id
        }
        let roomId = try await api.createDirectRoom(inviting: userId)
        try? await api.markDirect(myUserId: me, otherUserId: userId, roomId: roomId)
        if state.rooms[roomId] == nil {
            var room = ChatRoom(id: roomId)
            room.isDirect = true
            room.heroes = [userId]
            state.rooms[roomId] = room
        }
        state.directRoomIds.insert(roomId)
        return roomId
    }

    func accept(_ invite: RoomInvite) async {
        guard let api else { return }
        if (try? await api.join(roomId: invite.id)) != nil {
            if invite.isDirect, let inviter = invite.inviter {
                try? await api.markDirect(myUserId: me, otherUserId: inviter, roomId: invite.id)
            }
            state.invites[invite.id] = nil
        }
    }

    func decline(_ invite: RoomInvite) async {
        guard let api else { return }
        if (try? await api.leave(roomId: invite.id)) != nil { state.invites[invite.id] = nil }
    }

    func leave(roomId: String) async {
        guard let api else { return }
        if (try? await api.leave(roomId: roomId)) != nil { state.rooms[roomId] = nil }
    }

    func setDisplayName(_ name: String) async -> Bool {
        guard let api else { return false }
        return (try? await api.setDisplayName(userId: me, name: name)) != nil
    }

    func myDisplayName() async -> String? {
        guard let api else { return nil }
        return (try? await api.profile(userId: me))?.displayName
    }
}
