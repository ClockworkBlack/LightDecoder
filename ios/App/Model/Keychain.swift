import Foundation
import LightDecoderCore
import Security

/// The account's sign-in, kept in the iPhone's Keychain (not in backups of the app's files).
enum SessionVault {
    private static let service = "com.lightdecoder.ios.session"
    private static let account = "matrix"

    static func load() -> MatrixSession? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var found: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &found) == errSecSuccess, let data = found as? Data else { return nil }
        return try? JSONDecoder().decode(MatrixSession.self, from: data)
    }

    static func save(_ session: MatrixSession) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        clear()
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            // Readable after the first unlock, so a background check for messages can use it; never leaves this device.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(item as CFDictionary, nil)
    }

    static func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// Where the app keeps its files: conversations, stickers, cached pictures. Not included in backups.
enum AppFiles {
    static func directory(_ name: String, cache: Bool = false) -> URL {
        let base = FileManager.default.urls(for: cache ? .cachesDirectory : .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent(name, isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? dir.setResourceValues(values)
        }
        return dir
    }
}

/// The person's choices.
enum Prefs {
    private static let d = UserDefaults.standard

    static var notify: Bool {
        get { d.object(forKey: "notify") as? Bool ?? true }
        set { d.set(newValue, forKey: "notify") }
    }

    /// Whether others are told when this account has read their messages.
    static var sendReadReceipts: Bool {
        get { d.object(forKey: "sendReadReceipts") as? Bool ?? true }
        set { d.set(newValue, forKey: "sendReadReceipts") }
    }

    static var sendTyping: Bool {
        get { d.object(forKey: "sendTyping") as? Bool ?? true }
        set { d.set(newValue, forKey: "sendTyping") }
    }

    static var serverName: String {
        get { d.string(forKey: "serverName") ?? "matrix.org" }
        set { d.set(newValue, forKey: "serverName") }
    }
}
