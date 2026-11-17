import LightDecoderCore
import PhotosUI
import SwiftUI
import UIKit

/// One conversation: its messages with replies and reactions, and the box to write in.
struct ThreadView: View {
    let roomId: String
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var stickers: StickerLibrary
    @State private var draft = ""
    @State private var replyTo: ChatMessage?
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var showStickers = false
    @State private var viewing: ChatMessage?
    @State private var loadingOlder = false
    @State private var notice: String?
    @FocusState private var writing: Bool

    private var room: ChatRoom? { model.state.rooms[roomId] }

    var body: some View {
        VStack(spacing: 0) {
            if let room {
                messages(room)
                if !room.typing.isEmpty {
                    Text(typingText(room)).font(.footnote).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal).padding(.top, 4)
                }
                if room.isEncrypted {
                    Text("This conversation is end-to-end encrypted by another app. LightDecoder can't read or write encrypted messages yet: start the chat from LightDecoder instead.")
                        .font(.footnote).foregroundStyle(.secondary).padding(.horizontal).padding(.top, 6)
                }
                composer
            } else {
                ContentUnavailableView("This conversation is gone", systemImage: "bubble.left")
            }
        }
        .navigationTitle(room?.displayName(me: model.me) ?? "Chat")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            model.openRoomId = roomId
            model.markOpenRoomRead()
        }
        .onDisappear {
            if model.openRoomId == roomId { model.openRoomId = nil }
            model.typing(roomId: roomId, isTyping: false)
        }
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            pickedPhoto = nil
            Task { await sendPhoto(item) }
        }
        .sheet(isPresented: $showStickers) {
            StickerPicker { sticker in
                showStickers = false
                send(sticker: sticker)
            }
        }
        .fullScreenCover(item: $viewing) { message in PictureViewer(message: message) }
        .alert("LightDecoder", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(notice ?? "")
        }
    }

    // MARK: - messages

    private func messages(_ room: ChatRoom) -> some View {
        ScrollViewReader { proxy in
            List {
                if room.prevBatch != nil {
                    Button(loadingOlder ? "Loading…" : "Load earlier messages") {
                        loadingOlder = true
                        Task {
                            await model.loadOlder(roomId: roomId)
                            loadingOlder = false
                        }
                    }
                    .disabled(loadingOlder)
                    .frame(maxWidth: .infinity)
                    .listRowSeparator(.hidden)
                }
                ForEach(room.timeline) { message in
                    MessageRow(
                        message: message, room: room, me: model.me,
                        isLastOfMine: message.id == room.timeline.last(where: { $0.sender == model.me })?.id,
                        onReply: { replyTo = message; writing = true },
                        onQuoteTap: { id in withAnimation { proxy.scrollTo(id, anchor: .center) } },
                        onPicture: { viewing = message },
                        onNotice: { notice = $0 }
                    )
                    .id(message.id)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 2, leading: 10, bottom: 2, trailing: 10))
                    // Swipe a message to the right to answer it.
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        if !message.isLocal, message.kind != .redacted {
                            Button {
                                replyTo = message
                                writing = true
                            } label: {
                                Label("Reply", systemImage: "arrowshape.turn.up.left")
                            }
                            .tint(.accentColor)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollDismissesKeyboard(.interactively)
            .onAppear { scrollToEnd(room, proxy, animated: false) }
            .onChange(of: room.timeline.last?.id) { _, _ in
                scrollToEnd(room, proxy, animated: true)
                model.markOpenRoomRead()
            }
        }
    }

    private func scrollToEnd(_ room: ChatRoom, _ proxy: ScrollViewProxy, animated: Bool) {
        guard let last = room.timeline.last?.id else { return }
        if animated {
            withAnimation { proxy.scrollTo(last, anchor: .bottom) }
        } else {
            proxy.scrollTo(last, anchor: .bottom)
        }
    }

    private func typingText(_ room: ChatRoom) -> String {
        let names = room.typingNames
        return names.count == 1 ? "\(names[0]) is typing…" : "\(names.joined(separator: ", ")) are typing…"
    }

    // MARK: - writing

    private var composer: some View {
        VStack(spacing: 0) {
            Divider()
            if let replyTo, let room {
                HStack {
                    Rectangle().fill(Color.accentColor).frame(width: 3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Replying to \(replyTo.sender == model.me ? "yourself" : room.name(of: replyTo.sender))")
                            .font(.caption.bold()).foregroundStyle(Color.accentColor)
                        Text(MatrixText.preview(replyTo)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Button { self.replyTo = nil } label: { Image(systemName: "xmark.circle.fill") }
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Cancel reply")
                }
                .frame(height: 36)
                .padding(.horizontal).padding(.top, 6)
            }
            HStack(alignment: .bottom, spacing: 10) {
                PhotosPicker(selection: $pickedPhoto, matching: .images) {
                    Image(systemName: "photo").font(.title3)
                }
                .accessibilityLabel("Send a picture")
                Button { showStickers = true } label: { Image(systemName: "face.smiling").font(.title3) }
                    .accessibilityLabel("Send a sticker")
                TextField("Message", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.roundedBorder)
                    .focused($writing)
                    .onChange(of: draft) { _, text in model.typing(roomId: roomId, isTyping: !text.isEmpty) }
                Button {
                    model.sendText(draft, roomId: roomId, replyTo: replyTo?.id)
                    draft = ""
                    replyTo = nil
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title)
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityLabel("Send")
            }
            .padding(.horizontal).padding(.vertical, 8)
        }
        .background(.bar)
    }

    private func sendPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
            notice = "That picture could not be read."
            return
        }
        // A reasonable size for a chat: at most 2048 across, as a JPEG.
        let fitted = StickerEngine.fitted(image, maxSide: 2048)
        guard let jpeg = fitted.jpegData(compressionQuality: 0.85) else { return }
        let size = CGSize(width: fitted.size.width * fitted.scale, height: fitted.size.height * fitted.scale)
        model.sendImage(jpeg, mimeType: "image/jpeg", size: size, roomId: roomId, replyTo: replyTo?.id, asSticker: false)
        replyTo = nil
    }

    private func send(sticker: Sticker) {
        guard let data = try? Data(contentsOf: sticker.url), let image = UIImage(data: data) else { return }
        let size = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        model.sendImage(data, mimeType: "image/png", size: size, roomId: roomId, replyTo: replyTo?.id, asSticker: true)
        replyTo = nil
    }
}

// MARK: - one message

private struct MessageRow: View {
    let message: ChatMessage
    let room: ChatRoom
    let me: String
    let isLastOfMine: Bool
    let onReply: () -> Void
    let onQuoteTap: (String) -> Void
    let onPicture: () -> Void
    let onNotice: (String) -> Void
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var stickers: StickerLibrary

    private var mine: Bool { message.sender == me }
    private static let quick = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    var body: some View {
        VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
            if !mine, !room.isDirect {
                Text(room.name(of: message.sender)).font(.caption).foregroundStyle(.secondary)
            }
            content
                .contextMenu { menu }
            if !message.reactions.isEmpty { reactions }
            status
        }
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
    }

    @ViewBuilder private var content: some View {
        switch message.kind {
        case .sticker:
            // A sticker stands on its own, without a bubble.
            VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
                quote
                MessageImage(message: message).frame(maxWidth: 170, maxHeight: 170)
            }
        case .image:
            VStack(alignment: .leading, spacing: 4) {
                quote
                MessageImage(message: message)
                    .frame(maxWidth: 240, maxHeight: 320)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .onTapGesture(perform: onPicture)
            }
        default:
            VStack(alignment: .leading, spacing: 4) {
                quote
                Text(text).italic(message.kind != .text).foregroundStyle(textColour)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(bubble, in: RoundedRectangle(cornerRadius: 18))
            .frame(maxWidth: 300, alignment: mine ? .trailing : .leading)
        }
    }

    private var text: String {
        switch message.kind {
        case .text: return message.body
        case .emote: return "\(room.name(of: message.sender)) \(message.body)"
        case .notice: return message.body
        case .encrypted: return "Encrypted message (LightDecoder can't read these yet)"
        case .redacted: return "Message removed"
        case .file, .video, .audio: return "\(MatrixText.preview(message)) (open it in another Matrix app)"
        default: return "A kind of message LightDecoder doesn't show yet"
        }
    }

    private var plain: Bool { message.kind == .text || message.kind == .emote }
    private var bubble: Color { mine && plain ? Color.accentColor : Color(.secondarySystemBackground) }
    private var textColour: Color { mine && plain ? .white : (plain ? .primary : .secondary) }

    /// The message this one answers, shown above it; a tap goes to it.
    @ViewBuilder private var quote: some View {
        if let id = message.replyTo {
            let original = room.message(id)
            HStack(spacing: 6) {
                Rectangle().fill(mine && plain ? Color.white.opacity(0.7) : Color.accentColor).frame(width: 3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(original.map { $0.sender == me ? "You" : room.name(of: $0.sender) } ?? "Earlier message")
                        .font(.caption.bold())
                    Text(original.map(MatrixText.preview) ?? "Not loaded").font(.caption).lineLimit(2)
                }
                .foregroundStyle(mine && plain && message.kind != .sticker && message.kind != .image ? Color.white.opacity(0.9) : Color.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
            .contentShape(Rectangle())
            .onTapGesture { if original != nil { onQuoteTap(id) } }
        }
    }

    /// Each emoji with how many people chose it; a tap adds or takes back one's own.
    private var reactions: some View {
        HStack(spacing: 4) {
            ForEach(message.reactions.keys.sorted(), id: \.self) { emoji in
                let people = message.reactions[emoji] ?? [:]
                Button {
                    model.toggleReaction(emoji, on: message, roomId: room.id)
                } label: {
                    Text(people.count > 1 ? "\(emoji) \(people.count)" : emoji)
                        .font(.footnote)
                        .padding(.horizontal, 7).padding(.vertical, 3)
                        .background(people[me] != nil ? Color.accentColor.opacity(0.25) : Color(.tertiarySystemBackground), in: Capsule())
                        .overlay(Capsule().stroke(people[me] != nil ? Color.accentColor : Color(.separator), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(emoji), \(people.count)")
            }
        }
    }

    @ViewBuilder private var status: some View {
        switch message.sendState {
        case .sending:
            Text("Sending…").font(.caption2).foregroundStyle(.secondary)
        case .failed:
            HStack(spacing: 10) {
                Text("Not sent").font(.caption2).foregroundStyle(.red)
                if message.kind == .text {
                    Button("Try again") { model.retry(message, roomId: room.id) }.font(.caption2).buttonStyle(.borderless)
                }
                Button("Discard") { model.discard(message, roomId: room.id) }.font(.caption2).buttonStyle(.borderless)
            }
        case .sent:
            HStack(spacing: 4) {
                Text(Self.time(message.timestampMs))
                if message.edited { Text("· edited") }
                // Under the newest of one's own: whether it has been read.
                if mine, isLastOfMine { Text(room.isRead(message.id, me: me) ? "· Read" : "· Sent") }
            }
            .font(.caption2).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var menu: some View {
        if !message.isLocal, message.kind != .redacted {
            ControlGroup {
                ForEach(Self.quick, id: \.self) { emoji in
                    Button(emoji) { model.toggleReaction(emoji, on: message, roomId: room.id) }
                }
            }
            Button(action: onReply) { Label("Reply", systemImage: "arrowshape.turn.up.left") }
            if message.kind == .text || message.kind == .emote || message.kind == .notice {
                Button { UIPasteboard.general.string = message.body } label: { Label("Copy", systemImage: "doc.on.doc") }
            }
            if message.kind == .image || message.kind == .sticker, let mxc = message.media?.mxc {
                Button {
                    Task {
                        guard let api = model.api, let data = await MediaStore.shared.data(mxc: mxc, api: api), let image = UIImage(data: data) else {
                            onNotice("This picture could not be added to your stickers.")
                            return
                        }
                        stickers.add(picture: image)
                        onNotice("Added to your stickers")
                    }
                } label: {
                    Label("Add to my stickers", systemImage: "face.smiling")
                }
            }
            if mine {
                Button(role: .destructive) { model.remove(message, roomId: room.id) } label: { Label("Remove for everyone", systemImage: "trash") }
            }
        }
    }

    static func time(_ ms: Int64) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        formatter.dateStyle = .none
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

/// A picture on its own, with pinch to zoom and a way to share it.
struct PictureViewer: View {
    let message: ChatMessage
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1
    @State private var settled: CGFloat = 1

    var body: some View {
        NavigationStack {
            MessageImage(message: message)
                .scaleEffect(scale)
                .gesture(
                    MagnificationGesture()
                        .onChanged { scale = max(1, min(settled * $0, 6)) }
                        .onEnded { _ in settled = scale }
                )
                .onTapGesture(count: 2) {
                    let target: CGFloat = scale > 1 ? 1 : 2.5
                    withAnimation { scale = target }
                    settled = target
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                }
                .toolbarBackground(.visible, for: .navigationBar)
        }
    }
}
