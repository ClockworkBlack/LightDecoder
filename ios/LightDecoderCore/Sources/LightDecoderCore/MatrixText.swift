import Foundation

/// Small rules about Matrix ids, addresses and message text.
public enum MatrixText {

    /// "@name:server" -> "name".
    public static func localpart(_ userId: String) -> String {
        var s = Substring(userId)
        if s.hasPrefix("@") { s = s.dropFirst() }
        return String(s.split(separator: ":", maxSplits: 1).first ?? s)
    }

    /// Whether [text] is a full Matrix user id, "@name:server.tld".
    public static func isUserId(_ text: String) -> Bool {
        guard text.hasPrefix("@"), let colon = text.firstIndex(of: ":") else { return false }
        let name = text[text.index(after: text.startIndex)..<colon]
        let server = text[text.index(after: colon)...]
        return !name.isEmpty && !server.isEmpty && !text.contains(" ") && server.contains(".")
    }

    /// What someone typed for a person, made a full id: "anna" on matrix.org -> "@anna:matrix.org".
    public static func userId(from typed: String, defaultServer: String) -> String? {
        let t = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t.contains(" ") { return nil }
        if isUserId(t) { return t }
        if t.hasPrefix("@") || t.contains(":") { return nil }
        let full = "@\(t.lowercased()):\(defaultServer)"
        return isUserId(full) ? full : nil
    }

    /// "mxc://server/id" -> (server, id).
    public static func mxcParts(_ mxc: String) -> (server: String, mediaId: String)? {
        guard mxc.hasPrefix("mxc://") else { return nil }
        let rest = mxc.dropFirst("mxc://".count)
        let parts = rest.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty, !parts[1].contains("/") else { return nil }
        return (String(parts[0]), String(parts[1]))
    }

    /// A reply's text without the quoted lines older apps put in front of it ("> <@a:b> hello", then a blank line).
    public static func withoutReplyFallback(_ body: String) -> String {
        var lines = body.components(separatedBy: "\n")
        guard let first = lines.first, first.hasPrefix("> ") else { return body }
        while let line = lines.first, line.hasPrefix(">") { lines.removeFirst() }
        if let line = lines.first, line.isEmpty { lines.removeFirst() }
        return lines.joined(separator: "\n")
    }

    /// One line that stands for a message, in the conversation list, a quote or a notification.
    public static func preview(_ message: ChatMessage) -> String {
        switch message.kind {
        case .text, .notice: return firstLine(message.body)
        case .emote: return "* " + firstLine(message.body)
        case .image: return "Picture"
        case .sticker: return "Sticker"
        case .video: return "Video"
        case .audio: return "Audio"
        case .file: return message.body.isEmpty ? "File" : "File: " + firstLine(message.body)
        case .encrypted: return "Encrypted message"
        case .redacted: return "Message removed"
        case .unsupported: return "Message"
        }
    }

    private static func firstLine(_ text: String) -> String {
        let line = text.components(separatedBy: "\n").first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? ""
        return String(line.trimmingCharacters(in: .whitespaces).prefix(140))
    }
}

/// The content of events this app sends.
public enum MatrixContent {

    private static func relation(replyTo: String?) -> [String: JSONValue] {
        guard let replyTo else { return [:] }
        return ["m.relates_to": ["m.in_reply_to": ["event_id": .string(replyTo)]]]
    }

    public static func text(_ body: String, replyTo: String? = nil) -> JSONValue {
        var o: [String: JSONValue] = ["msgtype": "m.text", "body": .string(body)]
        o.merge(relation(replyTo: replyTo)) { $1 }
        return .object(o)
    }

    private static func info(_ media: MediaInfo) -> JSONValue {
        var o: [String: JSONValue] = [:]
        if let m = media.mimeType { o["mimetype"] = .string(m) }
        if let w = media.width { o["w"] = .number(Double(w)) }
        if let h = media.height { o["h"] = .number(Double(h)) }
        if let s = media.size { o["size"] = .number(Double(s)) }
        return .object(o)
    }

    public static func image(_ media: MediaInfo, fileName: String, replyTo: String? = nil) -> JSONValue {
        var o: [String: JSONValue] = ["msgtype": "m.image", "body": .string(fileName), "url": .string(media.mxc), "info": info(media)]
        o.merge(relation(replyTo: replyTo)) { $1 }
        return .object(o)
    }

    /// A sticker (event type m.sticker): shown without a bubble, at sticker size, by every Matrix app.
    public static func sticker(_ media: MediaInfo, description: String, replyTo: String? = nil) -> JSONValue {
        var o: [String: JSONValue] = ["body": .string(description), "url": .string(media.mxc), "info": info(media)]
        o.merge(relation(replyTo: replyTo)) { $1 }
        return .object(o)
    }

    public static func reaction(to eventId: String, emoji: String) -> JSONValue {
        ["m.relates_to": ["rel_type": "m.annotation", "event_id": .string(eventId), "key": .string(emoji)]]
    }
}
