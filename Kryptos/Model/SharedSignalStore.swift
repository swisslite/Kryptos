import Foundation
import CryptoKit
import LibSignalClient

enum SignalPaths {
    static func meta(_ id: UUID) -> URL { AppGroup.container.appendingPathComponent("signal-meta-\(id.uuidString).enc") }
    static func store(_ id: UUID) -> URL { AppGroup.container.appendingPathComponent("signal-store-\(id.uuidString).enc") }
    static func lock(_ id: UUID) -> URL { AppGroup.container.appendingPathComponent("signal-store-\(id.uuidString).lock") }

    static func legacyFileKey(_ id: UUID) -> URL { AppGroup.container.appendingPathComponent("signal-filekey-\(id.uuidString).bin") }
    static func legacyIdentity(_ id: UUID) -> URL { AppGroup.container.appendingPathComponent("signal-identity-\(id.uuidString).enc") }

    static func purgeLegacyMirror(_ id: UUID) {
        for url in [legacyFileKey(id), legacyIdentity(id)] { try? FileManager.default.removeItem(at: url) }
    }
}

enum KeyboardSelection {
    private static let profileKey = "kb.profile"
    private static func contactKey(_ id: UUID) -> String { "kb.contact.\(id.uuidString)" }

    static func profileID() -> UUID? {
        guard let d = SharedStore.read(profileKey), let s = String(data: d, encoding: .utf8) else { return nil }
        return UUID(uuidString: s)
    }

    static func rememberProfile(_ id: UUID) {
        SharedStore.write(profileKey, Data(id.uuidString.utf8))
    }

    static func forgetProfile(_ id: UUID) {
        guard profileID() == id else { return }
        SharedStore.delete(profileKey)
    }

    static func contactFingerprint(profileID: UUID) -> String? {
        guard let d = SharedStore.read(contactKey(profileID)) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func rememberContact(_ fingerprint: String, profileID: UUID) {
        SharedStore.write(contactKey(profileID), Data(fingerprint.utf8))
    }

    static func forgetContact(profileID: UUID) {
        SharedStore.delete(contactKey(profileID))
    }
}

final class SharedSignalStore {
    let profile: Profile
    let myFingerprint: String
    let contacts: [Contact]

    private let identity: IdentityKeyPair
    private let registrationId: UInt32
    private let cryptKey: SymmetricKey
    private let storeKey: String
    private let metaKey: String
    private let lock = NSRecursiveLock()

    static func index() -> ProfilesIndex? {
        guard let d = SharedStore.read("index") else { return nil }
        return try? JSONDecoder().decode(ProfilesIndex.self, from: d)
    }

    static func profiles() -> [Profile] { index()?.profiles ?? [] }

    convenience init?() {
        guard let idx = SharedSignalStore.index(),
              let p = idx.profiles.first(where: { $0.id == idx.currentID }) ?? idx.profiles.first else { return nil }
        self.init(profile: p)
    }

    init?(profile: Profile) {
        self.profile = profile
        let id = profile.id

        guard let kd = SharedStore.read("filekey.\(id.uuidString)"), kd.count == 32,
              let idData = SharedStore.read("identity.\(id.uuidString)"),
              let identity = try? IdentityKeyPair(bytes: idData) else { return nil }
        let key = SymmetricKey(data: kd)
        self.identity = identity
        self.cryptKey = key
        self.storeKey = "store.\(id.uuidString)"
        self.metaKey = "meta.\(id.uuidString)"
        myFingerprint = SignalFormat.hex(identity.identityKey.serialize())

        guard let enc = SharedStore.read(metaKey),
              let box = try? AES.GCM.SealedBox(combined: enc),
              let dec = try? AES.GCM.open(box, using: key),
              let meta = try? JSONDecoder().decode(Meta.self, from: dec) else { return nil }
        contacts = meta.contacts
        registrationId = meta.registrationId
    }

    private func freshStore() -> PersistentSignalStore {
        PersistentSignalStore(identity: identity, registrationId: registrationId, storageKey: storeKey, cryptKey: cryptKey)
    }

    func encrypt(_ text: String, to fingerprint: String) throws -> String {
        try withLock {
            let cipher = try withConflictRetry { store in
                try SignalWire.encrypt(text, toFingerprint: fingerprint, myFingerprint: myFingerprint,
                                       store: store, stego: ChatStego.resolvedLanguage(), smart: ChatStego.resolvedSmart(),
                                       pad: PrivacyConfig.lengthPadding)
            }
            OwnCipherMarker.mark(cipher)
            appendMessage(text, mine: true, to: fingerprint)
            return cipher
        }
    }

    func decrypt(_ armored: String, from fingerprint: String) throws -> String {
        try withLock {
            let text = try withConflictRetry { store in
                try SignalWire.decrypt(armored, fromFingerprint: fingerprint, myFingerprint: myFingerprint, store: store)
            }
            appendMessage(text, mine: false, to: fingerprint, decryptedFrom: armored)
            return text
        }
    }

    private func withConflictRetry<T>(_ body: (PersistentSignalStore) throws -> T) throws -> T {
        let store = freshStore()
        do { return try body(store) }
        catch where store.hadStaleConflict { return try body(freshStore()) }
    }

    private func appendMessage(_ text: String, mine: Bool, to fingerprint: String, decryptedFrom armored: String? = nil) {
        guard let enc = SharedStore.read(metaKey),
              let box = try? AES.GCM.SealedBox(combined: enc),
              let dec = try? AES.GCM.open(box, using: cryptKey),
              var meta = try? JSONDecoder().decode(Meta.self, from: dec) else { return }
        meta.messages[fingerprint, default: []].append(ChatMessage(text: text, mine: mine))
        if let armored { meta.rememberDecrypt(armored: armored, fingerprint: fingerprint, text: text) }
        guard let json = try? JSONEncoder().encode(meta),
              let sealed = try? AES.GCM.seal(json, using: cryptKey),
              let combined = sealed.combined else { return }
        SharedStore.write(metaKey, combined)
    }

    private func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }
        return try body()
    }

    func cachedDecrypt(_ armored: String) -> (contact: Contact, text: String)? {
        guard let enc = SharedStore.read(metaKey),
              let box = try? AES.GCM.SealedBox(combined: enc),
              let dec = try? AES.GCM.open(box, using: cryptKey),
              let meta = try? JSONDecoder().decode(Meta.self, from: dec),
              let hit = meta.cachedDecrypt(for: armored) else { return nil }
        let contact = contacts.first { $0.fingerprint == hit.fingerprint }
            ?? Contact(fingerprint: hit.fingerprint, displayName: String(hit.fingerprint.prefix(8)))
        return (contact, hit.text)
    }

    func decryptFromAnyContact(_ armored: String) -> (contact: Contact, text: String)? {
        if let hit = cachedDecrypt(armored) { return hit }
        for contact in contacts {
            if let text = try? decrypt(armored, from: contact.fingerprint) { return (contact, text) }
        }
        return nil
    }
}
