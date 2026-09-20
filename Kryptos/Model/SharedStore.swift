import Foundation
import CryptoKit
import Security

enum KeychainProbe {
    private static let account = "kryptos.teamid.probe"
    private static let lock = NSLock()
    private nonisolated(unsafe) static var cached: String?

    static var probeAccount: String { account }

    static func defaultAccessGroup() -> String? {
        lock.lock()
        if let cached {
            lock.unlock()
            return cached
        }
        lock.unlock()
        let resolved = resolve()
        lock.lock()
        if let resolved { cached = resolved }
        lock.unlock()
        return resolved
    }

    static func teamPrefix() -> String? {
        guard let group = defaultAccessGroup(), let team = group.split(separator: ".").first else { return nil }
        return String(team)
    }

    static func forget() {
        lock.lock()
        cached = nil
        lock.unlock()
    }

    private static func resolve() -> String? {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrAccount as String: account,
                                   kSecAttrService as String: account]
        var query = base
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        var status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            SecItemAdd(base as CFDictionary, nil)
            status = SecItemCopyMatching(query as CFDictionary, &result)
        }
        guard status == errSecSuccess, let attrs = result as? [String: Any] else { return nil }
        return attrs[kSecAttrAccessGroup as String] as? String
    }
}

/// Advisory lock shared by the app and the keyboard. Best effort: when the lock cannot be taken
/// within the timeout the work runs anyway, exactly as it did before the lock existed.
enum SharedLock {
    static func withLock<T>(_ name: String, timeout: TimeInterval = 2, _ body: () throws -> T) rethrows -> T {
        let url = AppGroup.container.appendingPathComponent("kryptos-\(name).lock")
        let descriptor = open(url.path, O_CREAT | O_RDWR, 0o600)
        guard descriptor >= 0 else { return try body() }
        var held = false
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if flock(descriptor, LOCK_EX | LOCK_NB) == 0 {
                held = true
                break
            }
            if Date() >= deadline { break }
            usleep(5_000)
        }
        defer {
            if held { flock(descriptor, LOCK_UN) }
            close(descriptor)
        }
        return try body()
    }
}

/// Small blobs that describe what the person typed or who they write to. They are sealed with
/// AES-GCM under a key kept in the store as key material, the same way chat history is handled,
/// instead of sitting in the container as readable JSON.
enum SecureBlob {
    private static let keyName = "blobkey"
    private static let version: UInt8 = 1
    private static let lock = NSLock()
    private nonisolated(unsafe) static var cachedKey: SymmetricKey?

    static func readStrict(_ name: String) -> SharedStore.ReadResult {
        switch SharedStore.readStrict(name) {
        case .absent:
            return .absent
        case .unavailable:
            return .unavailable
        case .found(let raw):
            guard raw.first == version else { return .found(raw) }
            guard let key = key(create: false),
                  let box = try? AES.GCM.SealedBox(combined: raw.dropFirst()),
                  let plain = try? AES.GCM.open(box, using: key) else { return .unavailable }
            return .found(plain)
        }
    }

    static func read(_ name: String) -> Data? {
        guard case .found(let data) = readStrict(name) else { return nil }
        return data
    }

    @discardableResult
    static func write(_ name: String, _ data: Data) -> Bool {
        guard let key = key(create: true),
              let box = try? AES.GCM.seal(data, using: key),
              let combined = box.combined else { return false }
        return SharedStore.write(name, Data([version]) + combined)
    }

    /// False when the blob is still in the plain form written by a build that did not seal it.
    static func isSealed(_ name: String) -> Bool {
        guard case .found(let raw) = SharedStore.readStrict(name) else { return true }
        return raw.first == version
    }

    static func forgetKey() {
        lock.lock()
        cachedKey = nil
        lock.unlock()
    }

    private static func key(create: Bool) -> SymmetricKey? {
        lock.lock()
        let known = cachedKey
        lock.unlock()
        if let known { return known }

        let resolved: SymmetricKey?
        switch SharedStore.readStrict(keyName) {
        case .found(let raw) where raw.count == 32:
            resolved = SymmetricKey(data: raw)
        case .unavailable:
            return nil
        case .found, .absent:
            guard create else { return nil }
            resolved = SharedLock.withLock(keyName) { () -> SymmetricKey? in
                if case .found(let raw) = SharedStore.readStrict(keyName), raw.count == 32 {
                    return SymmetricKey(data: raw)
                }
                let fresh = SymmetricKey(size: .bits256)
                guard SharedStore.write(keyName, fresh.withUnsafeBytes { Data($0) }) else { return nil }
                return fresh
            }
        }
        guard let resolved else { return nil }
        lock.lock()
        cachedKey = resolved
        lock.unlock()
        return resolved
    }
}

enum SharedStore {
    enum Backend: Equatable { case keychain(group: String); case appGroupFile; case localFile }
    enum ReadResult { case found(Data); case absent; case unavailable }

    private static let backendLock = NSLock()
    private nonisolated(unsafe) static var resolvedBackend: Backend?

    static var backend: Backend {
        backendLock.lock()
        defer { backendLock.unlock() }
        if let resolved = resolvedBackend { return resolved }
        let fresh = resolveBackend()
        resolvedBackend = fresh
        return fresh
    }

    static var isShared: Bool {
        switch backend {
        case .keychain(let group): return group.hasSuffix(sharedGroupSuffix)
        case .appGroupFile: return true
        case .localFile: return false
        }
    }

    static func revalidateBackend() {
        backendLock.lock()
        defer { backendLock.unlock() }
        if let resolved = resolvedBackend, case .keychain = resolved { return }
        let fresh = resolveBackend()
        guard case .keychain(let group) = fresh else { return }
        adoptFallbackFiles(group: group)
        resolvedBackend = fresh
    }

    private static let profilesIndexKey = "index"

    private static func fileKeyName(_ url: URL) -> String? {
        let name = url.lastPathComponent
        guard name.hasPrefix(filePrefix), name.hasSuffix(fileSuffix) else { return nil }
        let key = name.dropFirst(filePrefix.count).dropLast(fileSuffix.count)
        return key.isEmpty ? nil : String(key)
    }

    private static func adoptFallbackFiles(group: String) {
        guard case .absent = kcLoadStrict(profilesIndexKey, group: group) else { return }
        let fm = FileManager.default
        var moved: [URL] = []
        var index: Data?
        var indexFile: URL?
        var complete = true
        for base in [AppGroup.container, localBase] {
            guard let files = try? fm.contentsOfDirectory(at: base, includingPropertiesForKeys: nil) else { continue }
            for url in files {
                guard let key = fileKeyName(url) else { continue }
                guard let data = try? Data(contentsOf: url) else { complete = false; continue }
                if key == profilesIndexKey {
                    index = data
                    indexFile = url
                    continue
                }
                switch kcLoadStrict(key, group: group) {
                case .absent: if kcSave(data, name: key, group: group) { moved.append(url) } else { complete = false }
                case .found: moved.append(url)
                case .unavailable: complete = false
                }
            }
        }
        guard complete else { return }
        if let index {
            guard kcSave(index, name: profilesIndexKey, group: group) else { return }
            if let indexFile { moved.append(indexFile) }
        }
        for url in moved { try? fm.removeItem(at: url) }
    }

    private static func resolveBackend() -> Backend {
        if let group = sharedKeychainGroup(), keychainUsable(group: group) { return .keychain(group: group) }
        if AppGroup.isShared { return .appGroupFile }
        return .localFile
    }

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
        case .appGroupFile:        return writeFile(data, to: fileURL(name, base: AppGroup.container))
        case .localFile:           return writeFile(data, to: fileURL(name, base: localBase))
        }
    }

    static func writeFile(_ data: Data, to url: URL) -> Bool {
        guard (try? data.write(to: url, options: [.atomic, .completeFileProtection])) != nil else { return false }
        excludeFromBackup(url)
        return true
    }

    private static let storedPrefixes = ["kryptos-", "kcfallback-", "signal-"]

    private static func storedFiles() -> [URL] {
        let fm = FileManager.default
        var found: [URL] = []
        for base in [AppGroup.container, localBase] {
            guard let files = try? fm.contentsOfDirectory(at: base, includingPropertiesForKeys: nil) else { continue }
            found += files.filter { url in storedPrefixes.contains { url.lastPathComponent.hasPrefix($0) } }
        }
        return found
    }

    static func excludeStoredFilesFromBackup() {
        for url in storedFiles() { excludeFromBackup(url) }
    }

    /// Anything written before the app required an unlocked device still carries the weaker class,
    /// so raise both the keychain items and the files once at start.
    static func hardenStoredItems() {
        let update: [String: Any] = [kSecAttrAccessible as String: accessibility]
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: kcService]
        SecItemUpdate(q as CFDictionary, update as CFDictionary)
        if let group = sharedKeychainGroup() {
            q[kSecAttrAccessGroup as String] = group
            SecItemUpdate(q as CFDictionary, update as CFDictionary)
        }
        let fm = FileManager.default
        for url in storedFiles() {
            try? fm.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        }
    }

    static func excludeFromBackup(_ url: URL) {
        var target = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? target.setResourceValues(values)
    }

    static func delete(_ name: String) {
        switch backend {
        case .keychain(let group): kcDelete(name, group: group)
        case .appGroupFile:        try? FileManager.default.removeItem(at: fileURL(name, base: AppGroup.container))
        case .localFile:           try? FileManager.default.removeItem(at: fileURL(name, base: localBase))
        }
    }

    private static let obsoleteKeys = ["engine.check"]

    static func purgeObsolete() {
        for key in obsoleteKeys { delete(key) }
    }

    static func eraseAll() {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: kcService]
        SecItemDelete(q as CFDictionary)
        if let group = sharedKeychainGroup() {
            q[kSecAttrAccessGroup as String] = group
            SecItemDelete(q as CFDictionary)
        }
        let fm = FileManager.default
        let keyMaterial = ["kryptos-filekey.", "kryptos-identity."]
        for base in [AppGroup.container, localBase] {
            guard let files = try? fm.contentsOfDirectory(at: base, includingPropertiesForKeys: nil) else { continue }
            let ours = files.filter { $0.lastPathComponent.hasPrefix("kryptos-") }
            for f in ours where keyMaterial.contains(where: { f.lastPathComponent.hasPrefix($0) }) {
                try? fm.removeItem(at: f)
            }
            for f in ours { try? fm.removeItem(at: f) }
        }
    }

    private static let groupLock = NSLock()
    private nonisolated(unsafe) static var cachedKeychainGroup: String?

    static func sharedKeychainGroup() -> String? {
        groupLock.lock()
        if let cached = cachedKeychainGroup {
            groupLock.unlock()
            return cached
        }
        groupLock.unlock()
        guard let def = KeychainProbe.defaultAccessGroup() else { return nil }
        var resolved = def
        if let team = def.split(separator: ".").first,
           keychainUsable(group: "\(team)\(sharedGroupSuffix)") {
            resolved = "\(team)\(sharedGroupSuffix)"
        }
        groupLock.lock()
        cachedKeychainGroup = resolved
        groupLock.unlock()
        return resolved
    }

    private static func keychainUsable(group: String) -> Bool {
        let probe = "kryptos.kc.probe"
        kcDelete(probe, group: group)
        guard kcSave(Data([1]), name: probe, group: group) else { return false }
        let ok = kcLoad(probe, group: group) != nil
        kcDelete(probe, group: group)
        return ok
    }

    private static let sharedGroupSuffix = ".*"
    private static let kcService = "com.kryptos.shared"
    private static var accessibility: CFString { kSecAttrAccessibleWhenUnlockedThisDeviceOnly }

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
        item[kSecAttrAccessible as String] = accessibility
        let status = SecItemAdd(item as CFDictionary, nil)
        if status == errSecSuccess { return true }
        guard status == errSecDuplicateItem else { return false }
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: accessibility]
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

    private static let filePrefix = "kryptos-"
    private static let fileSuffix = ".blob"

    private static func fileURL(_ name: String, base: URL) -> URL {
        base.appendingPathComponent(filePrefix + name + fileSuffix)
    }
}
