import Foundation
import Security

enum Keychain {
    private static let service = "com.kryptos.app"
    private static let accessGroup = AppGroup.identifier

    @discardableResult
    static func save(_ data: Data, account: String) -> Bool {
        if saveItem(data, account: account, group: accessGroup) { FileFallback.delete(account); return true }
        return FileFallback.save(data, account: account)
    }

    static func load(account: String) -> Data? {
        loadItem(account: account, group: accessGroup) ?? loadItem(account: account, group: nil) ?? FileFallback.load(account)
    }

    @discardableResult
    static func delete(account: String) -> Bool {
        let a = deleteItem(account: account, group: accessGroup)
        let b = deleteItem(account: account, group: nil)
        FileFallback.delete(account)
        return a || b
    }

    static func eraseAll() {
        for group in [accessGroup, nil] {
            var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service]
            if let group { q[kSecAttrAccessGroup as String] = group }
            SecItemDelete(q as CFDictionary)
        }
        FileFallback.deleteAll()
    }

    private enum FileFallback {
        private static func url(_ account: String) -> URL {
            AppGroup.container.appendingPathComponent("kcfallback-\(account).bin")
        }
        static func save(_ data: Data, account: String) -> Bool {
            (try? data.write(to: url(account), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil
        }
        static func load(_ account: String) -> Data? { try? Data(contentsOf: url(account)) }
        static func delete(_ account: String) { try? FileManager.default.removeItem(at: url(account)) }
        static func deleteAll() {
            let fm = FileManager.default
            guard let files = try? fm.contentsOfDirectory(at: AppGroup.container, includingPropertiesForKeys: nil) else { return }
            for f in files where f.lastPathComponent.hasPrefix("kcfallback-") { try? fm.removeItem(at: f) }
        }
    }

    private static func baseQuery(account: String, group: String?) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        if let group { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    private static func saveItem(_ data: Data, account: String, group: String?) -> Bool {
        var item = baseQuery(account: account, group: group)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        if status == errSecSuccess { return true }
        guard status == errSecDuplicateItem else { return false }
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        return SecItemUpdate(baseQuery(account: account, group: group) as CFDictionary, update as CFDictionary) == errSecSuccess
    }

    private static func loadItem(account: String, group: String?) -> Data? {
        var q = baseQuery(account: account, group: group)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private static func deleteItem(account: String, group: String?) -> Bool {
        SecItemDelete(baseQuery(account: account, group: group) as CFDictionary) == errSecSuccess
    }
}
