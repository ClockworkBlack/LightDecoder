import SwiftUI
import UIKit

struct Sticker: Identifiable, Hashable {
    let id: String
    let url: URL
}

/// The person's own stickers: PNG files in the app, each with the picture it was made from and how, for editing later.
@MainActor
final class StickerLibrary: ObservableObject {
    @Published private(set) var items: [Sticker] = []

    private let directory = AppFiles.directory("stickers")
    private var projects: URL { directory.appendingPathComponent("projects", isDirectory: true) }

    init() {
        reload()
    }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        items = files.filter { $0.pathExtension == "png" }
            .sorted { Self.modified($0) > Self.modified($1) }
            .map { Sticker(id: $0.deletingPathExtension().lastPathComponent, url: $0) }
    }

    private static func modified(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }

    func image(_ sticker: Sticker) -> UIImage? { UIImage(contentsOfFile: sticker.url.path) }

    /// Saves a made sticker with how it was made; [replacing] keeps the same sticker, else a new one is added.
    @discardableResult
    func save(_ image: UIImage, original: UIImage, options: StickerOptions, replacing: Sticker? = nil) -> Sticker? {
        guard let png = image.pngData() else { return nil }
        let id = replacing?.id ?? UUID().uuidString
        let url = directory.appendingPathComponent("\(id).png")
        do {
            try png.write(to: url, options: .atomic)
            let project = projects.appendingPathComponent(id, isDirectory: true)
            try FileManager.default.createDirectory(at: project, withIntermediateDirectories: true)
            try original.pngData()?.write(to: project.appendingPathComponent("original.png"), options: .atomic)
            try JSONEncoder().encode(options).write(to: project.appendingPathComponent("options.json"), options: .atomic)
        } catch {
            return nil
        }
        reload()
        return Sticker(id: id, url: url)
    }

    /// A picture or sticker someone sent, kept as one of one's own: as it is, with no outline added.
    func add(picture: UIImage) {
        let original = StickerEngine.fitted(picture, maxSide: 1024)
        var options = StickerOptions()
        options.outline = false
        save(StickerEngine.make(original, options: options, subjects: nil), original: original, options: options)
    }

    /// The picture a sticker was made from, and how.
    func project(for sticker: Sticker) -> (original: UIImage, options: StickerOptions)? {
        let project = projects.appendingPathComponent(sticker.id, isDirectory: true)
        guard let original = UIImage(contentsOfFile: project.appendingPathComponent("original.png").path) else { return nil }
        let options = (try? Data(contentsOf: project.appendingPathComponent("options.json")))
            .flatMap { try? JSONDecoder().decode(StickerOptions.self, from: $0) } ?? StickerOptions()
        return (original, options)
    }

    func delete(_ sticker: Sticker) {
        try? FileManager.default.removeItem(at: sticker.url)
        try? FileManager.default.removeItem(at: projects.appendingPathComponent(sticker.id, isDirectory: true))
        reload()
    }
}
