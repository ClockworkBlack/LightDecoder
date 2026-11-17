import CryptoKit
import Foundation
import LightDecoderCore
import SwiftUI
import UIKit

/// Pictures from the server, fetched with the account's sign-in and kept in the app's cache.
final class MediaStore: @unchecked Sendable {
    static let shared = MediaStore()

    private let memory = NSCache<NSString, UIImage>()
    private let directory = AppFiles.directory("media", cache: true)

    private func file(_ key: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(digest)
    }

    /// What was just uploaded from here is the same picture: no need to fetch it back.
    func remember(_ data: Data, for mxc: String) {
        try? data.write(to: file(mxc))
    }

    func data(mxc: String, api: MatrixAPI) async -> Data? {
        let path = file(mxc)
        if let cached = try? Data(contentsOf: path) { return cached }
        guard let fetched = try? await api.download(mxc: mxc), !fetched.isEmpty else { return nil }
        try? fetched.write(to: path)
        return fetched
    }

    func image(mxc: String, api: MatrixAPI) async -> UIImage? {
        if let hit = memory.object(forKey: mxc as NSString) { return hit }
        guard let data = await data(mxc: mxc, api: api), let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: mxc as NSString)
        return image
    }

    func clear() {
        memory.removeAllObjects()
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
}

/// A picture of a message: the local file while it is being sent, else the server's.
struct MessageImage: View {
    let message: ChatMessage
    @EnvironmentObject private var model: AppModel
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else if failed {
                Label("Couldn't load", systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.secondary).padding()
            } else {
                ProgressView().frame(width: 120, height: 120)
            }
        }
        .task(id: message.media?.mxc ?? message.localFile ?? message.id) { await load() }
    }

    private func load() async {
        if let path = message.localFile, message.media == nil, let local = UIImage(contentsOfFile: path) {
            image = local
            return
        }
        guard let mxc = message.media?.mxc, let api = model.api else {
            failed = message.localFile == nil
            return
        }
        if let loaded = await MediaStore.shared.image(mxc: mxc, api: api) {
            image = loaded
        } else {
            failed = true
        }
    }
}
