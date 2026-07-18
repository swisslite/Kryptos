import Foundation
import Security

enum SharedStore {
    enum Backend: Equatable { case keychain(group: String); case appGroupFile; case localFile }
    enum ReadResult { case found(Data); case absent; case unavailable }

    static let backend: Backend = {
        if let group = sharedKeychainGroup, keychainUsable(group: group) { return .keychain(group: group) }
        if AppGroup.isShared { return .appGroupFile }
        return .localFile
    }()

    static func read(_ name: String) -> Data? {
        switch backend {
        case .keychain(let group): return kcLoad(name, group: group)
        case .appGroupFile:        return try? Data(contentsOf: fileURL(name, base: AppGroup.container))
        case .localFile:           return try? Data(contentsOf: fileURL(name, base: localBase))
        }
    }

    static func readStrict(_ name: String) -> ReadResult {
        switch backend {
        case .keychain(let group):
            return kcLoadStrict(name, group: group)
        case .appGroupFile:
            return fileReadStrict(fileURL(name, base: AppGroup.container))
        case .localFile:
            return fileReadStrict(fileURL(name, base: localBase))
        }
    }

    private static func fileReadStrict(_ url: URL) -> ReadResult {
        guard FileManager.default.fileExists(atPath: url.path) else { return .absent }
        guard let data = try? Data(contentsOf: url) else { return .unavailable }
        return .found(data)
    }

    @discardableResult
    static func write(_ name: String, _ data: Data) -> Bool {
        switch backend {
        case .keychain(let group): return kcSave(data, name: name, group: group)
        case .appGroupFile:        return (try? data.write(to: fileURL(name, base: AppGroup.container),
                                                            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil
        case .localFile:           return (try? data.write(to: fileURL(name, base: localBase),
                                                            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil
        }
    }

    static func delete(_ name: String) {
        switch backend {
        case .keychain(let group): kcDelete(name, group: group)
        case .appGroupFile:        try? FileManager.default.removeItem(at: fileURL(name, base: AppGroup.container))
        case .localFile:           try? FileManager.default.removeItem(at: fileURL(name, base: localBase))
        }
    }

    static func eraseAll() {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: kcService]
        SecItemDelete(q as CFDictionary)
        if let group = sharedKeychainGroup {
            q[kSecAttrAccessGroup as String] = group
            SecItemDelete(q as CFDictionary)
        }
        let fm = FileManager.default
        for base in [AppGroup.container, localBase] {
            guard let files = try? fm.contentsOfDirectory(at: base, includingPropertiesForKeys: nil) else { continue }
            for f in files where f.lastPathComponent.hasPrefix("kryptos-") { try? fm.removeItem(at: f) }
        }
    }

    static let sharedKeychainGroup: String? = {
        guard let def = defaultAccessGroup() else { return nil }
        if let team = def.split(separator: ".").first {
            let wildcard = "\(team).*"
            if keychainUsable(group: wildcard) { return wildcard }
        }
        return def
    }()

    private static func defaultAccessGroup() -> String? {
        let account = "kryptos.teamid.probe"
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrAccount as String: account,
                                   kSecAttrService as String: account]
        var q = base
        q[kSecReturnAttributes as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        var status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { SecItemAdd(base as CFDictionary, nil); status = SecItemCopyMatching(q as CFDictionary, &result) }
        guard status == errSecSuccess, let attrs = result as? [String: Any] else { return nil }
        return attrs[kSecAttrAccessGroup as String] as? String
    }

    private static func keychainUsable(group: String) -> Bool {
        let probe = "kryptos.kc.probe"
        kcDelete(probe, group: group)
        guard kcSave(Data([1]), name: probe, group: group) else { return false }
        let ok = kcLoad(probe, group: group) != nil
        kcDelete(probe, group: group)
        return ok
    }

    private static let kcService = "com.kryptos.shared"

    private static func kcBase(_ name: String, group: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: kcService,
         kSecAttrAccount as String: name,
         kSecAttrAccessGroup as String: group]
    }

    @discardableResult
    private static func kcSave(_ data: Data, name: String, group: String) -> Bool {
        var item = kcBase(name, group: group)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        if status == errSecSuccess { return true }
        guard status == errSecDuplicateItem else { return false }
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        return SecItemUpdate(kcBase(name, group: group) as CFDictionary, update as CFDictionary) == errSecSuccess
    }

    private static func kcLoad(_ name: String, group: String) -> Data? {
        var q = kcBase(name, group: group)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private static func kcLoadStrict(_ name: String, group: String) -> ReadResult {
        var q = kcBase(name, group: group)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { return .unavailable }
            return .found(data)
        case errSecItemNotFound:
            return .absent
        default:
            return .unavailable
        }
    }

    private static func kcDelete(_ name: String, group: String) {
        SecItemDelete(kcBase(name, group: group) as CFDictionary)
    }

    private static let localBase: URL = {
        (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
    }()

    private static func fileURL(_ name: String, base: URL) -> URL {
        base.appendingPathComponent("kryptos-\(name).blob")
    }
}
