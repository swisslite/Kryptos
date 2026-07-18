import Foundation
import CryptoKit
import LibSignalClient

final class PersistentSignalStore: InMemorySignalProtocolStore {
    enum PersistError: Error {
        case staleSnapshot
        case writeFailed
    }

    private let storageKey: String
    private let cryptKey: SymmetricKey

    private struct Snapshot: Codable {
        var generation: UInt64?
        var preKeys: [String: Data] = [:]
        var signedPreKeys: [String: Data] = [:]
        var kyberPreKeys: [String: Data] = [:]
        var sessions: [String: Data] = [:]
        var identities: [String: Data] = [:]
    }

    private struct GenerationProbe: Codable { var generation: UInt64? }

    private var snap = Snapshot()
    private var expectedGeneration: UInt64 = 0
    private(set) var hadStaleConflict = false
    private(set) var loadFailed = false

    private var revokedSignedPreKeyIds = Set<UInt32>()
    private var revokedKyberPreKeyIds = Set<UInt32>()

    init(identity: IdentityKeyPair, registrationId: UInt32, storageKey: String, cryptKey: SymmetricKey) {
        self.storageKey = storageKey
        self.cryptKey = cryptKey
        super.init(identity: identity, registrationId: registrationId)
        load()
    }

    private func addrKey(_ a: ProtocolAddress) -> String { "\(a.name)|\(a.deviceId)" }
    private func parseAddr(_ s: String) -> ProtocolAddress? {
        let parts = s.split(separator: "|")
        guard parts.count == 2, let d = UInt32(parts[1]) else { return nil }
        return try? ProtocolAddress(name: String(parts[0]), deviceId: d)
    }

    private func decryptedBlob() -> Data? {
        guard let enc = SharedStore.read(storageKey),
              let box = try? AES.GCM.SealedBox(combined: enc),
              let dec = try? AES.GCM.open(box, using: cryptKey) else { return nil }
        return dec
    }

    private func load() {
        switch SharedStore.readStrict(storageKey) {
        case .absent:
            return
        case .unavailable:
            loadFailed = true
            return
        case .found(let enc):
            guard let box = try? AES.GCM.SealedBox(combined: enc),
                  let dec = try? AES.GCM.open(box, using: cryptKey),
                  let s = try? JSONDecoder().decode(Snapshot.self, from: dec) else {
                loadFailed = true
                return
            }
            restore(s)
        }
    }

    private func restore(_ s: Snapshot) {
        snap = s
        expectedGeneration = s.generation ?? 0
        let ctx = NullContext()
        for (k, v) in s.preKeys { if let id = UInt32(k), let r = try? PreKeyRecord(bytes: v) { try? super.storePreKey(r, id: id, context: ctx) } }
        for (k, v) in s.signedPreKeys { if let id = UInt32(k), let r = try? SignedPreKeyRecord(bytes: v) { try? super.storeSignedPreKey(r, id: id, context: ctx) } }
        for (k, v) in s.kyberPreKeys { if let id = UInt32(k), let r = try? KyberPreKeyRecord(bytes: v) { try? super.storeKyberPreKey(r, id: id, context: ctx) } }
        for (k, v) in s.sessions { if let a = parseAddr(k), let r = try? SessionRecord(bytes: v) { try? super.storeSession(r, for: a, context: ctx) } }
        for (k, v) in s.identities { if let a = parseAddr(k), let ik = try? IdentityKey(bytes: v) { _ = try? super.saveIdentity(ik, for: a, context: ctx) } }
    }

    private func persist() throws {
        guard !loadFailed else { throw PersistError.staleSnapshot }
        let diskGeneration: UInt64 = {
            guard let dec = decryptedBlob() else { return 0 }
            return (try? JSONDecoder().decode(GenerationProbe.self, from: dec))?.generation ?? 0
        }()
        guard diskGeneration == expectedGeneration else {
            hadStaleConflict = true
            throw PersistError.staleSnapshot
        }
        let next = expectedGeneration &+ 1
        snap.generation = next
        guard let json = try? JSONEncoder().encode(snap),
              let box = try? AES.GCM.seal(json, using: cryptKey),
              let combined = box.combined,
              SharedStore.write(storageKey, combined) else {
            throw PersistError.writeFailed
        }
        expectedGeneration = next
    }

    override func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        try super.storePreKey(record, id: id, context: context)
        snap.preKeys[String(id)] = record.serialize(); try persist()
    }

    override func removePreKey(id: UInt32, context: StoreContext) throws {
        try super.removePreKey(id: id, context: context)
        snap.preKeys[String(id)] = nil; try persist()
    }

    override func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try super.storeSignedPreKey(record, id: id, context: context)
        snap.signedPreKeys[String(id)] = record.serialize(); try persist()
    }

    override func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try super.storeKyberPreKey(record, id: id, context: context)
        snap.kyberPreKeys[String(id)] = record.serialize(); try persist()
    }

    override func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        try super.storeSession(record, for: address, context: context)
        snap.sessions[addrKey(address)] = record.serialize(); try persist()
    }

    override func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> IdentityChange {
        let change = try super.saveIdentity(identity, for: address, context: context)
        snap.identities[addrKey(address)] = identity.serialize(); try persist()
        return change
    }

    override func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        if revokedSignedPreKeyIds.contains(id) { throw SignalError.invalidKeyIdentifier("signed prekey deleted") }
        return try super.loadSignedPreKey(id: id, context: context)
    }

    override func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        if revokedKyberPreKeyIds.contains(id) { throw SignalError.invalidKeyIdentifier("kyber prekey deleted") }
        return try super.loadKyberPreKey(id: id, context: context)
    }

    func removeSignedPreKey(id: UInt32) {
        revokedSignedPreKeyIds.insert(id)
        if snap.signedPreKeys.removeValue(forKey: String(id)) != nil { try? persist() }
    }

    func removeKyberPreKey(id: UInt32) {
        revokedKyberPreKeyIds.insert(id)
        if snap.kyberPreKeys.removeValue(forKey: String(id)) != nil { try? persist() }
    }

    func removeAllSessionsAndPeerIdentities() {
        snap.sessions = [:]
        snap.identities = [:]
        try? persist()
    }

    func removeSessionAndIdentity(forName name: String) {
        let prefix = name + "|"
        snap.sessions = snap.sessions.filter { !$0.key.hasPrefix(prefix) }
        snap.identities = snap.identities.filter { !$0.key.hasPrefix(prefix) }
        try? persist()
    }
}
