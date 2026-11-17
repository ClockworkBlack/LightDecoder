import LightDecoderCore
import SwiftUI

/// The account's conversations, the most recently active first, with invitations on top.
struct ChatListView: View {
    @EnvironmentObject private var model: AppModel
    @State private var path: [String] = []
    @State private var showNewChat = false

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if let problem = model.syncProblem {
                    Label(problem, systemImage: "wifi.slash").font(.footnote).foregroundStyle(.secondary)
                }
                let invites = model.state.invites.values.sorted { $0.id < $1.id }
                if !invites.isEmpty {
                    Section("Invitations") {
                        ForEach(invites) { invite in InviteRow(invite: invite) }
                    }
                }
                Section {
                    ForEach(model.state.sortedRooms) { room in
                        NavigationLink(value: room.id) { RoomRow(room: room, me: model.me) }
                            .swipeActions {
                                Button("Leave", role: .destructive) { Task { await model.leave(roomId: room.id) } }
                            }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if model.state.rooms.isEmpty && model.state.invites.isEmpty {
                    if model.loadedOnce {
                        ContentUnavailableView(
                            "No conversations yet", systemImage: "bubble.left.and.bubble.right",
                            description: Text("Tap the pencil to start one. Your name is \(model.me).")
                        )
                    } else {
                        ProgressView("Loading your conversations…")
                    }
                }
            }
            .navigationTitle("Chats")
            .navigationDestination(for: String.self) { ThreadView(roomId: $0) }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showNewChat = true } label: { Image(systemName: "square.and.pencil") }
                        .accessibilityLabel("New chat")
                }
            }
            .sheet(isPresented: $showNewChat) {
                NewChatView { roomId in
                    showNewChat = false
                    path = [roomId]
                }
            }
        }
    }
}

private struct RoomRow: View {
    let room: ChatRoom
    let me: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            InitialsBadge(name: room.displayName(me: me))
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(room.displayName(me: me)).font(.headline).lineLimit(1)
                    Spacer()
                    if let last = room.timeline.last {
                        Text(Self.time(last.timestampMs)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                HStack {
                    Text(preview).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                    Spacer()
                    if room.unreadCount > 0 {
                        Text("\(room.unreadCount)")
                            .font(.caption2.bold())
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(Color.accentColor, in: Capsule())
                            .foregroundStyle(.white)
                            .accessibilityLabel("\(room.unreadCount) unread")
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var preview: String {
        if !room.typing.isEmpty { return "typing…" }
        guard let last = room.timeline.last else { return "No messages yet" }
        let text = MatrixText.preview(last)
        return last.sender == me ? "You: \(text)" : text
    }

    static func time(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        let formatter = DateFormatter()
        if Calendar.current.isDateInToday(date) {
            formatter.timeStyle = .short
            formatter.dateStyle = .none
        } else {
            formatter.timeStyle = .none
            formatter.dateStyle = .short
        }
        return formatter.string(from: date)
    }
}

private struct InviteRow: View {
    let invite: RoomInvite
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(invite.name ?? invite.inviterName ?? "A conversation").font(.headline)
            if let inviter = invite.inviter {
                Text("\(invite.inviterName ?? inviter) (\(inviter)) wants to chat").font(.subheadline).foregroundStyle(.secondary)
            }
            HStack {
                Button("Accept") { Task { await model.accept(invite) } }.buttonStyle(.borderedProminent)
                Button("Decline", role: .destructive) { Task { await model.decline(invite) } }.buttonStyle(.bordered)
            }
        }
        .padding(.vertical, 4)
    }
}

/// A round badge with a name's first letter, in a colour that stays the same for that name.
struct InitialsBadge: View {
    let name: String
    var size: CGFloat = 44

    var body: some View {
        let letter = name.trimmingCharacters(in: CharacterSet(charactersIn: "@ ")).prefix(1).uppercased()
        let hue = Double(abs(name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) % 360 })) / 360
        Text(letter.isEmpty ? "?" : letter)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Color(hue: hue, saturation: 0.55, brightness: 0.75), in: Circle())
            .accessibilityHidden(true)
    }
}

/// Starting a conversation with someone by their Matrix name.
struct NewChatView: View {
    let opened: (String) -> Void
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var busy = false
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("@name:matrix.org", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Their Matrix name")
                } footer: {
                    Text("A name without a server is looked for on \(model.myServer).")
                }
                Section("Your name, to give to friends") {
                    HStack {
                        Text(model.me).textSelection(.enabled)
                        Spacer()
                        ShareLink(item: model.me) { Image(systemName: "square.and.arrow.up") }
                    }
                }
                if let problem { Section { Text(problem).foregroundStyle(.red) } }
            }
            .navigationTitle("New chat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Start") { Task { await start() } }.disabled(busy || name.isEmpty)
                }
            }
        }
    }

    private func start() async {
        guard let userId = MatrixText.userId(from: name, defaultServer: model.myServer) else {
            problem = "That doesn't look like a Matrix name. It looks like @name:matrix.org."
            return
        }
        guard userId != model.me else {
            problem = "That is your own name."
            return
        }
        busy = true
        defer { busy = false }
        do {
            opened(try await model.startChat(with: userId))
        } catch {
            problem = (error as? MatrixError)?.message ?? "Could not start the conversation."
        }
    }
}
