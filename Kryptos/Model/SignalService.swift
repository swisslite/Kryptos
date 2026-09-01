import Foundation
import SwiftUI
import CryptoKit
import LibSignalClient
import CipherCore

@MainActor
final class SignalService: ObservableObject {
    @Published private(set) var profiles: [Profile] = []
    @Published private(set) var currentID = UUID()
    @Published private(set) var contacts: [Contact] = []
    @Published private(set) var messages: [String: [ChatMessage]] = [:]
    @Published private(set) var autoDelete: [String: TimeInterval] = [:]
    @Published private(set) var pinned: Set<String> = []
    @Published private(set) var myFingerprint = ""
    @Published private(set) var mySafetyNumber = ""
    @Published private(set) var isLoaded = false
    @Published private(set) var keyMaterialLost = false
    @Published private(set) var hasBooted = false

    private var identity: IdentityKeyPair!
    private var store: PersistentSignalStore!
    private var meta: Meta!
    private var cryptKey: SymmetricKey!
    private var metaStorageKey: String!
    private let storeLock = NSRecursiveLock()
    private let ctx = NullContext()

    private static let rotationInterval: TimeInterval = 2 * 24 * 3600
    private static let retention: TimeInterval = 30 * 24 * 3600

    var currentProfile: Profile? { profiles.first { $0.id == currentID } }

    private var indexUnavailable = false

    init() {}

    func start() {
        guard !hasBooted else { return }
        bootstrapFromIndex()
        hasBooted = true
    }

    private func bootstrapFromIndex() {
        guard var index = SignalService.loadIndexStrict() else {
            indexUnavailable = true
            isLoaded = false
            return
        }
        indexUnavailable = false
        if index.profiles.isEmpty {
            let p = Profile(id: UUID(), name: SignalService.defaultProfileName(1))
            index = ProfilesIndex(profiles: [p], currentID: p.id)
            SignalService.saveIndex(index)
        }
        profiles = index.profiles.map(SignalService.relocalizedDefaultName)
        let preferred = profiles.contains { $0.id == index.currentID } ? index.currentID : profiles[0].id
        var lost = false
        for id in [preferred] + profiles.map(\.id).filter({ $0 != preferred }) {
            switch load(profileID: id) {
            case .ok:
                if id == preferred { persistIndex() }
                return
            case .keyMaterialLost:
                lost = true
            case .unavailable:
                break
            }
        }
        resetLoadedState()
        currentID = preferred
        keyMaterialLost = lost
    }

    private static func defaultProfileName(_ n: Int) -> String { String(localized: "Profile \(n)") }

    private static func relocalizedDefaultName(_ p: Profile) -> Profile {
        for prefix in ["Profile ", "Профиль ", "Profil ", "个人资料 ", "نمایه "] where p.name.hasPrefix(prefix) {
            if let n = decimalSuffix(p.name.dropFirst(prefix.count)) {
                var q = p
                q.name = defaultProfileName(n)
                return q
            }
        }
        return p
    }

    private static func decimalSuffix(_ text: Substring) -> Int? {
        guard !text.isEmpty, text.count <= 18 else { return nil }
        var value = 0
        for char in text {
            guard let digit = char.wholeNumberValue, (0 ... 9).contains(digit) else { return nil }
            value = value * 10 + digit
        }
        return value
    }

    private static func loadIndexStrict() -> ProfilesIndex? {
        switch SharedStore.readStrict(StoreKey.index) {
        case .absent:
            return ProfilesIndex(profiles: [], currentID: UUID())
        case .unavailable:
            return nil
        case .found(let data):
            return try? JSONDecoder().decode(ProfilesIndex.self, from: data)
        }
    }

    private static func saveIndex(_ index: ProfilesIndex) {
        if let data = try? JSONEncoder().encode(index) { SharedStore.write(StoreKey.index, data) }
    }

    private func persistIndex() {
        guard !indexUnavailable else { return }
        SignalService.saveIndex(ProfilesIndex(profiles: profiles, currentID: currentID))
    }

    @discardableResult
    func switchTo(_ id: UUID) -> Bool {
        guard profiles.contains(where: { $0.id == id }) else { return false }
        if isLoaded, currentID == id { return true }
        guard load(profileID: id) == .ok else { return false }
        persistIndex()
        return true
    }

    @discardableResult
    func createProfile(name: String) -> Profile? {
        if indexUnavailable { bootstrapFromIndex() }
        guard !indexUnavailable else { return nil }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let profile = Profile(id: UUID(), name: trimmed.isEmpty ? SignalService.defaultProfileName(profiles.count + 1) : trimmed)
        profiles.append(profile)
        guard load(profileID: profile.id) == .ok else {
            profiles.removeAll { $0.id == profile.id }
            wipeStorage(for: profile.id)
            return nil
        }
        persistIndex()
        return profile
    }

    func renameProfile(_ id: UUID, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let idx = profiles.firstIndex(where: { $0.id == id }) else { return }
        guard profiles[idx].name != trimmed else { return }
        profiles[idx].name = trimmed
        persistIndex()
    }

    @discardableResult
    func deleteProfile(_ id: UUID) -> Bool {
        wipeStorage(for: id)
        KeyboardSelection.forgetProfile(id)
        profiles.removeAll { $0.id == id }
        if profiles.isEmpty {
            profiles = [Profile(id: UUID(), name: SignalService.defaultProfileName(1))]
        }
        let target = profiles.contains { $0.id == currentID } ? currentID : profiles[0].id
        for next in [target] + profiles.map(\.id).filter({ $0 != target }) {
            guard load(profileID: next) == .ok else { continue }
            persistIndex()
            return true
        }
        resetLoadedState()
        currentID = profiles[0].id
        persistIndex()
        return false
    }

    @discardableResult
    func regenerateCurrentIdentity() -> Bool {
        let id = currentID
        wipeStorage(for: id)
        guard load(profileID: id) == .ok else {
            resetLoadedState()
            currentID = id
            return false
        }
        return true
    }

    private func wipeStorage(for id: UUID) {
        OwnCipherMarker.clear()
        for key in [StoreKey.identity(id), StoreKey.fileKey(id), StoreKey.meta(id), StoreKey.store(id)] {
            SharedStore.delete(key)
        }
        Keychain.delete(account: StoreKey.legacyIdentity(id))
        Keychain.delete(account: StoreKey.legacyFileKey(id))
        KeyboardSelection.forgetContact(profileID: id)
        for url in [SignalPaths.meta(id), SignalPaths.store(id), SignalPaths.lock(id)] {
            try? FileManager.default.removeItem(at: url)
        }
        SignalPaths.purgeLegacyMirror(id)
    }

    private enum StoreKey {
        static let index = "index"
        static func identity(_ id: UUID) -> String { "identity.\(id.uuidString)" }
        static func fileKey(_ id: UUID) -> String { "filekey.\(id.uuidString)" }
        static func meta(_ id: UUID) -> String { "meta.\(id.uuidString)" }
        static func store(_ id: UUID) -> String { "store.\(id.uuidString)" }
        static func legacyIdentity(_ id: UUID) -> String { "signal.identity.\(id.uuidString)" }
        static func legacyFileKey(_ id: UUID) -> String { "signal.filekey.\(id.uuidString)" }
    }

    private func resetLoadedState() {
        isLoaded = false
        keyMaterialLost = false
        lastMetaFingerprint = nil
        lastMetaDigest = nil
        contacts = []
        messages = [:]
        autoDelete = [:]
        pinned = []
        myFingerprint = ""
        mySafetyNumber = ""
        store = nil
        meta = nil
        identity = nil
        cryptKey = nil
        metaStorageKey = nil
    }

    private struct Loaded {
        let key: SymmetricKey
        let identity: IdentityKeyPair
        let fingerprint: String
        let safetyNumber: String
        let store: PersistentSignalStore
        let meta: Meta
        let metaStorageKey: String
        let metaDigest: Data?
    }

    private enum LoadResult {
        case ok
        case unavailable
        case keyMaterialLost
    }

    private func readProfile(_ id: UUID) -> (result: LoadResult, loaded: Loaded?) {
        let key: SymmetricKey
        var keyIsNew = false
        switch SharedStore.readStrict(StoreKey.fileKey(id)) {
        case .found(let data):
            key = SymmetricKey(data: data)
        case .unavailable:
            return (.unavailable, nil)
        case .absent:
            switch Keychain.loadStrict(account: StoreKey.legacyFileKey(id)) {
            case .found(let legacy):
                key = SymmetricKey(data: legacy)
            case .unavailable:
                return (.unavailable, nil)
            case .absent:
                key = SymmetricKey(size: .bits256)
            }
            keyIsNew = true
        }

        let identity: IdentityKeyPair
        var identityIsNew = false
        switch SharedStore.readStrict(StoreKey.identity(id)) {
        case .found(let data):
            guard let restored = try? IdentityKeyPair(bytes: data) else { return (.keyMaterialLost, nil) }
            identity = restored
        case .unavailable:
            return (.unavailable, nil)
        case .absent:
            switch Keychain.loadStrict(account: StoreKey.legacyIdentity(id)) {
            case .found(let legacy):
                guard let restored = try? IdentityKeyPair(bytes: legacy) else { return (.keyMaterialLost, nil) }
                identity = restored
            case .unavailable:
                return (.unavailable, nil)
            case .absent:
                identity = IdentityKeyPair.generate()
            }
            identityIsNew = true
        }

        var loadedMeta: Meta?
        var metaDigest: Data?
        switch SharedStore.readStrict(StoreKey.meta(id)) {
        case .found(let enc):
            guard let box = try? AES.GCM.SealedBox(combined: enc),
                  let dec = try? AES.GCM.open(box, using: key),
                  let m = try? JSONDecoder().decode(Meta.self, from: dec) else {
                return (.keyMaterialLost, nil)
            }
            loadedMeta = m
            metaDigest = Data(SHA256.hash(data: dec))
        case .unavailable:
            return (.unavailable, nil)
        case .absent:
            break
        }

        if keyIsNew, !SharedStore.write(StoreKey.fileKey(id), key.withUnsafeBytes { Data($0) }, keyMaterial: true) {
            return (.unavailable, nil)
        }
        if identityIsNew, !SharedStore.write(StoreKey.identity(id), identity.serialize(), keyMaterial: true) {
            return (.unavailable, nil)
        }
        SignalPaths.purgeLegacyMirror(id)

        let regId = loadedMeta?.registrationId ?? UInt32.random(in: 1 ... 0x3FFF)
        let store = PersistentSignalStore(identity: identity, registrationId: regId,
                                          storageKey: StoreKey.store(id), cryptKey: key)
        guard !store.loadFailed else { return (.unavailable, nil) }

        let meta: Meta
        do {
            meta = try store.batch { () -> Meta in
                var m = loadedMeta ?? provisionInitial(registrationId: regId, identity: identity, store: store)
                maintainPreKeys(&m, identity: identity, store: store)
                return m
            }
        } catch {
            return (.unavailable, nil)
        }

        let fingerprint = SignalFormat.hex(identity.identityKey.serialize())
        return (.ok, Loaded(key: key, identity: identity, fingerprint: fingerprint,
                            safetyNumber: SignalFormat.safetyNumber(fromHex: fingerprint),
                            store: store, meta: meta, metaStorageKey: StoreKey.meta(id),
                            metaDigest: metaDigest))
    }

    private func adopt(_ id: UUID, _ loaded: Loaded) {
        cryptKey = loaded.key
        identity = loaded.identity
        store = loaded.store
        meta = loaded.meta
        metaStorageKey = loaded.metaStorageKey
        lastMetaDigest = loaded.metaDigest
        lastMetaFingerprint = nil
        currentID = id
        myFingerprint = loaded.fingerprint
        mySafetyNumber = loaded.safetyNumber
        autoDelete = loaded.meta.autoDelete ?? [:]
        pinned = Set(loaded.meta.pinned ?? [])
        contacts = loaded.meta.contacts
        messages = loaded.meta.messages
        keyMaterialLost = false
        isLoaded = true
        withStoreLock { saveMeta() }
        purgeExpiredMessages()
    }

    @discardableResult
    private func load(profileID id: UUID) -> LoadResult {
        let read = withStoreLock { readProfile(id) }
        guard read.result == .ok, let loaded = read.loaded else { return read.result }
        adopt(id, loaded)
        return .ok
    }

    private static func b64(_ map: [String: Data]) -> [String: String] {
        map.mapValues { $0.base64EncodedString() }
    }

    private static func unb64(_ map: [String: String]) -> [String: Data] {
        var out = [String: Data](minimumCapacity: map.count)
        for (k, v) in map { if let d = Data(base64Encoded: v) { out[k] = d } }
        return out
    }

    func archivedProfiles() -> [KeyArchive.ArchivedProfile]? {
        if isLoaded { withStoreLock { saveMeta() } }
        var out: [KeyArchive.ArchivedProfile] = []
        for profile in profiles {
            guard case .found(let identityData) = SharedStore.readStrict(StoreKey.identity(profile.id)),
                  case .found(let keyData) = SharedStore.readStrict(StoreKey.fileKey(profile.id)),
                  keyData.count == 32 else { return nil }
            let key = SymmetricKey(data: keyData)
            guard case .found(let enc) = SharedStore.readStrict(StoreKey.meta(profile.id)),
                  let box = try? AES.GCM.SealedBox(combined: enc),
                  let dec = try? AES.GCM.open(box, using: key),
                  let m = try? JSONDecoder().decode(Meta.self, from: dec) else { return nil }
            let snap: PersistentSignalStore.Archive
            switch SharedStore.readStrict(StoreKey.store(profile.id)) {
            case .absent:
                snap = PersistentSignalStore.Archive()
            case .unavailable:
                return nil
            case .found:
                guard let read = PersistentSignalStore.exportArchive(storageKey: StoreKey.store(profile.id),
                                                                     cryptKey: key) else { return nil }
                snap = read
            }
            out.append(KeyArchive.ArchivedProfile(
                id: profile.id.uuidString,
                name: profile.name,
                identity: identityData.base64EncodedString(),
                registrationId: Int64(m.registrationId),
                signedPreKeyId: Int64(m.signedPreKeyId),
                signedPreKeyPub: m.signedPreKeyPub.base64EncodedString(),
                signedPreKeySig: m.signedPreKeySig.base64EncodedString(),
                kyberPreKeyId: Int64(m.kyberPreKeyId),
                kyberPreKeyPub: m.kyberPreKeyPub.base64EncodedString(),
                kyberPreKeySig: m.kyberPreKeySig.base64EncodedString(),
                prekeyCreatedAt: m.prekeyCreatedAt.map { Int64($0.timeIntervalSince1970 * 1000) },
                nextSignedPreKeyId: Int64(m.nextSignedPreKeyId ?? m.signedPreKeyId + 2),
                nextKyberPreKeyId: Int64(m.nextKyberPreKeyId ?? m.kyberPreKeyId + 2),
                nextOneTimePreKeyId: Int64(m.nextOneTimePreKeyId ?? 1),
                oneTimePreKeyIds: (m.oneTimePreKeyIds ?? []).map(Int64.init),
                retired: (m.retiredPreKeyGens ?? []).map {
                    KeyArchive.ArchivedRetired(signedPreKeyId: Int64($0.signedPreKeyId),
                                               kyberPreKeyId: Int64($0.kyberPreKeyId),
                                               retiredAt: Int64($0.retiredAt.timeIntervalSince1970 * 1000))
                },
                autoDelete: m.autoDelete ?? [:],
                pinned: m.pinned,
                usedPreKeys: m.usedPreKeys,
                contacts: m.contacts.map {
                    KeyArchive.ArchivedContact(fingerprint: $0.fingerprint, displayName: $0.displayName)
                },
                preKeys: SignalService.b64(snap.preKeys),
                signedPreKeys: SignalService.b64(snap.signedPreKeys),
                kyberPreKeys: SignalService.b64(snap.kyberPreKeys),
                sessions: SignalService.b64(snap.sessions),
                identities: SignalService.b64(snap.identities)))
        }
        return out
    }

    func restoreProfiles(_ list: [KeyArchive.ArchivedProfile]) -> Bool {
        let previous = profiles
        var restored: [Profile] = []
        var seen = Set<String>()
        for entry in list {
            guard seen.insert(entry.id).inserted,
                  let id = UUID(uuidString: entry.id),
                  let identityData = Data(base64Encoded: entry.identity),
                  (try? IdentityKeyPair(bytes: identityData)) != nil,
                  let signedPub = Data(base64Encoded: entry.signedPreKeyPub),
                  let signedSig = Data(base64Encoded: entry.signedPreKeySig),
                  let kyberPub = Data(base64Encoded: entry.kyberPreKeyPub),
                  let kyberSig = Data(base64Encoded: entry.kyberPreKeySig) else { continue }

            var meta = Meta(registrationId: UInt32(truncatingIfNeeded: entry.registrationId),
                            signedPreKeyId: UInt32(truncatingIfNeeded: entry.signedPreKeyId),
                            signedPreKeyPub: signedPub, signedPreKeySig: signedSig,
                            kyberPreKeyId: UInt32(truncatingIfNeeded: entry.kyberPreKeyId),
                            kyberPreKeyPub: kyberPub, kyberPreKeySig: kyberSig)
            meta.contacts = entry.contacts.map { Contact(fingerprint: $0.fingerprint, displayName: $0.displayName) }
            meta.messages = [:]
            meta.decryptCache = nil
            meta.prekeyCreatedAt = entry.prekeyCreatedAt.map { Date(timeIntervalSince1970: Double($0) / 1000) }
            meta.retiredPreKeyGens = entry.retired.map {
                RetiredPreKeyGen(signedPreKeyId: UInt32(truncatingIfNeeded: $0.signedPreKeyId),
                                 kyberPreKeyId: UInt32(truncatingIfNeeded: $0.kyberPreKeyId),
                                 retiredAt: Date(timeIntervalSince1970: Double($0.retiredAt) / 1000))
            }
            meta.nextSignedPreKeyId = UInt32(truncatingIfNeeded: entry.nextSignedPreKeyId)
            meta.nextKyberPreKeyId = UInt32(truncatingIfNeeded: entry.nextKyberPreKeyId)
            meta.nextOneTimePreKeyId = UInt32(truncatingIfNeeded: entry.nextOneTimePreKeyId)
            meta.oneTimePreKeyIds = entry.oneTimePreKeyIds.map { UInt32(truncatingIfNeeded: $0) }
            meta.autoDelete = entry.autoDelete.isEmpty ? nil : entry.autoDelete
            let keptPins = (entry.pinned ?? []).filter { fp in entry.contacts.contains { $0.fingerprint == fp } }
            meta.pinned = keptPins.isEmpty ? nil : keptPins
            meta.usedPreKeys = entry.usedPreKeys

            let key = SymmetricKey(size: .bits256)
            guard let json = try? JSONEncoder().encode(meta),
                  let box = try? AES.GCM.seal(json, using: key),
                  let combined = box.combined,
                  SharedStore.write(StoreKey.fileKey(id), key.withUnsafeBytes { Data($0) }, keyMaterial: true),
                  SharedStore.write(StoreKey.identity(id), identityData, keyMaterial: true),
                  SharedStore.write(StoreKey.meta(id), combined) else { continue }

            let archive = PersistentSignalStore.Archive(
                preKeys: SignalService.unb64(entry.preKeys),
                signedPreKeys: SignalService.unb64(entry.signedPreKeys),
                kyberPreKeys: SignalService.unb64(entry.kyberPreKeys),
                sessions: SignalService.unb64(entry.sessions),
                identities: SignalService.unb64(entry.identities))
            PersistentSignalStore.writeArchive(archive, storageKey: StoreKey.store(id), cryptKey: key)
            restored.append(SignalService.relocalizedDefaultName(Profile(id: id, name: entry.name)))
        }

        guard !restored.isEmpty else { return false }
        let restoredIDs = Set(restored.map(\.id))
        for old in previous where !restoredIDs.contains(old.id) { wipeStorage(for: old.id) }
        KeyboardSelection.forgetProfile(currentID)
        profiles = restored
        indexUnavailable = false
        guard load(profileID: restored[0].id) == .ok else {
            resetLoadedState()
            currentID = restored[0].id
            persistIndex()
            return false
        }
        persistIndex()
        return true
    }

    func resetAfterWipe() {
        withStoreLock { resetLoadedState() }
        profiles = []
        indexUnavailable = false
        currentID = UUID()
        bootstrapFromIndex()
    }

    @discardableResult
    private func ensureLoaded() -> Bool {
        if !hasBooted { start(); return isLoaded }
        if indexUnavailable {
            bootstrapFromIndex()
            return isLoaded
        }
        if !isLoaded, load(profileID: currentID) != .ok { bootstrapFromIndex() }
        return isLoaded
    }

    private func provisionInitial(registrationId regId: UInt32, identity: IdentityKeyPair,
                                  store: PersistentSignalStore) -> Meta {
        let gen = generateSignedAndKyber(signedId: 1, kyberId: 2, identity: identity, store: store)
        var m = Meta(registrationId: regId,
                     signedPreKeyId: gen.signedId, signedPreKeyPub: gen.signedPub, signedPreKeySig: gen.signedSig,
                     kyberPreKeyId: gen.kyberId, kyberPreKeyPub: gen.kyberPub, kyberPreKeySig: gen.kyberSig)
        m.prekeyCreatedAt = Date()
        m.retiredPreKeyGens = []
        m.nextSignedPreKeyId = 3
        m.nextKyberPreKeyId = 4
        m.nextOneTimePreKeyId = 1
        m.oneTimePreKeyIds = []
        return m
    }

    private func maintainPreKeys(_ meta: inout Meta, identity: IdentityKeyPair, store: PersistentSignalStore) {
        if meta.prekeyCreatedAt == nil {
            meta.prekeyCreatedAt = Date()
            meta.retiredPreKeyGens = meta.retiredPreKeyGens ?? []
            let maxId = max(meta.signedPreKeyId, meta.kyberPreKeyId)
            meta.nextSignedPreKeyId = maxId + 1
            meta.nextKyberPreKeyId = maxId + 2
            meta.nextOneTimePreKeyId = 1
            meta.oneTimePreKeyIds = []
        }

        if let created = meta.prekeyCreatedAt, Date().timeIntervalSince(created) > SignalService.rotationInterval {
            rotateSignedAndKyber(&meta, identity: identity, store: store)
        }

        let cutoff = Date().addingTimeInterval(-SignalService.retention)
        var kept: [RetiredPreKeyGen] = []
        for g in meta.retiredPreKeyGens ?? [] {
            if g.retiredAt < cutoff {
                store.removeSignedPreKey(id: g.signedPreKeyId)
                store.removeKyberPreKey(id: g.kyberPreKeyId)
            } else {
                kept.append(g)
            }
        }
        meta.retiredPreKeyGens = kept
    }

    private func rotateSignedAndKyber(_ meta: inout Meta, identity: IdentityKeyPair,
                                      store: PersistentSignalStore) {
        var retired = meta.retiredPreKeyGens ?? []
        retired.append(RetiredPreKeyGen(signedPreKeyId: meta.signedPreKeyId, kyberPreKeyId: meta.kyberPreKeyId, retiredAt: Date()))
        meta.retiredPreKeyGens = retired

        let signedId = meta.nextSignedPreKeyId ?? (meta.signedPreKeyId + 2)
        let kyberId = meta.nextKyberPreKeyId ?? (meta.kyberPreKeyId + 2)
        let gen = generateSignedAndKyber(signedId: signedId, kyberId: kyberId, identity: identity, store: store)
        meta.signedPreKeyId = gen.signedId; meta.signedPreKeyPub = gen.signedPub; meta.signedPreKeySig = gen.signedSig
        meta.kyberPreKeyId = gen.kyberId; meta.kyberPreKeyPub = gen.kyberPub; meta.kyberPreKeySig = gen.kyberSig
        meta.prekeyCreatedAt = Date()
        meta.nextSignedPreKeyId = signedId + 2
        meta.nextKyberPreKeyId = kyberId + 2
    }

    private struct GenKeys {
        var signedId: UInt32, signedPub: Data, signedSig: Data
        var kyberId: UInt32, kyberPub: Data, kyberSig: Data
    }

    private func generateSignedAndKyber(signedId: UInt32, kyberId: UInt32, identity: IdentityKeyPair,
                                        store: PersistentSignalStore) -> GenKeys {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        let signed = PrivateKey.generate()
        let signedSig = identity.privateKey.generateSignature(message: signed.publicKey.serialize())
        try? store.storeSignedPreKey(SignedPreKeyRecord(id: signedId, timestamp: now, privateKey: signed, signature: signedSig), id: signedId, context: ctx)
        let kyber = KEMKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(message: kyber.publicKey.serialize())
        try? store.storeKyberPreKey(KyberPreKeyRecord(id: kyberId, timestamp: now, keyPair: kyber, signature: kyberSig), id: kyberId, context: ctx)
        return GenKeys(signedId: signedId, signedPub: signed.publicKey.serialize(), signedSig: signedSig,
                       kyberId: kyberId, kyberPub: kyber.publicKey.serialize(), kyberSig: kyberSig)
    }

    private static let oneTimePreKeyPoolLimit = 100

    private func nextOneTimePreKeyForBundle() -> (id: UInt32, pub: Data)? {
        var pool = (meta.oneTimePreKeyIds ?? []).filter { (try? store.loadPreKey(id: $0, context: ctx)) != nil }

        let id = meta.nextOneTimePreKeyId ?? 1
        let priv = PrivateKey.generate()
        guard (try? store.storePreKey(PreKeyRecord(id: id, privateKey: priv), id: id, context: ctx)) != nil else {
            meta.oneTimePreKeyIds = pool
            return nil
        }
        pool.append(id)
        var next = id &+ 1; if next == 0 { next = 1 }
        meta.nextOneTimePreKeyId = next

        if pool.count > SignalService.oneTimePreKeyPoolLimit {
            for old in pool.prefix(pool.count - SignalService.oneTimePreKeyPoolLimit) {
                try? store.removePreKey(id: old, context: ctx)
            }
            pool = Array(pool.suffix(SignalService.oneTimePreKeyPoolLimit))
        }
        meta.oneTimePreKeyIds = pool
        return (id, priv.publicKey.serialize())
    }

    private func saveMeta() {
        guard meta != nil, let cryptKey, let metaStorageKey else { return }
        meta.contacts = contacts
        meta.messages = messages
        guard let json = try? SignalService.metaEncoder.encode(meta) else { return }
        let plainDigest = Data(SHA256.hash(data: json))
        guard plainDigest != lastMetaDigest else { return }
        guard let box = try? AES.GCM.seal(json, using: cryptKey),
              let combined = box.combined else { return }
        guard SharedStore.write(metaStorageKey, combined) else { return }
        lastMetaDigest = plainDigest
        lastMetaFingerprint = Data(SHA256.hash(data: combined))
    }

    struct KeyShare {
        let payload: Data
        var text: String { KeyText.prefix + payload.base64EncodedString() }
    }

    func myKeyShare() -> KeyShare? {
        guard ensureLoaded() else { return nil }
        return withStoreLock {
            reloadStoreFromDisk()
            let opk = (try? store.batch { nextOneTimePreKeyForBundle() }) ?? nil
            saveMeta()
            let payload = BundlePayload(
                registrationId: meta.registrationId, deviceId: 1,
                identityKey: identity.identityKey.serialize(),
                signedPreKeyId: meta.signedPreKeyId, signedPreKey: meta.signedPreKeyPub, signedPreKeySignature: meta.signedPreKeySig,
                kyberPreKeyId: meta.kyberPreKeyId, kyberPreKey: meta.kyberPreKeyPub, kyberPreKeySignature: meta.kyberPreKeySig,
                oneTimePreKeyId: opk?.id, oneTimePreKey: opk?.pub
            )
            return KeyShare(payload: SignalService.encodeBundle(payload))
        }
    }

    private static let bundleFormatByte: UInt8 = 0x01

    private static func encodeBundle(_ p: BundlePayload) -> Data {
        var w = BinaryWriter()
        w.writeByte(bundleFormatByte)
        w.writeUInt32(p.registrationId)
        w.writeUInt32(p.deviceId)
        w.writeUInt32(p.signedPreKeyId)
        w.writeUInt32(p.kyberPreKeyId)
        w.writeVar(p.identityKey)
        w.writeVar(p.signedPreKey)
        w.writeVar(p.signedPreKeySignature)
        w.writeVar(p.kyberPreKey)
        w.writeVar(p.kyberPreKeySignature)
        if let id = p.oneTimePreKeyId, let otp = p.oneTimePreKey {
            w.writeByte(1); w.writeUInt32(id); w.writeVar(otp)
        } else {
            w.writeByte(0)
        }
        return w.data
    }

    private static func decodeBundle(_ data: Data) throws -> BundlePayload {
        var r = BinaryReader(data)
        guard try r.readByte() == bundleFormatByte else { throw SignalServiceError.badKeyString }
        let reg = try r.readUInt32(), dev = try r.readUInt32()
        let spkId = try r.readUInt32(), kyId = try r.readUInt32()
        let ik = try r.readVar(), spk = try r.readVar(), spkSig = try r.readVar()
        let ky = try r.readVar(), kySig = try r.readVar()
        var otpId: UInt32?; var otp: Data?
        if try r.readByte() == 1 { otpId = try r.readUInt32(); otp = try r.readVar() }
        return BundlePayload(registrationId: reg, deviceId: dev, identityKey: ik,
                             signedPreKeyId: spkId, signedPreKey: spk, signedPreKeySignature: spkSig,
                             kyberPreKeyId: kyId, kyberPreKey: ky, kyberPreKeySignature: kySig,
                             oneTimePreKeyId: otpId, oneTimePreKey: otp)
    }

    private static func parseKeyPayload(_ blob: Data) -> BundlePayload? {
        if blob.first == bundleFormatByte { return try? decodeBundle(blob) }
        return try? JSONDecoder().decode(BundlePayload.self, from: blob)
    }

    private static func parseKeyText(_ raw: String) -> BundlePayload? {
        for blob in KeyText.blobs(in: raw) {
            if let peer = parseKeyPayload(blob) { return peer }
        }
        return nil
    }

    @discardableResult
    func addContact(fromKeyString raw: String, displayName: String) throws -> Contact {
        guard let peer = SignalService.parseKeyText(raw) else { throw SignalServiceError.badKeyString }
        return try addPeer(peer, displayName: displayName)
    }

    @discardableResult
    func addContact(scanned raw: Data, displayName: String) throws -> Contact {
        let legacy = String(data: raw, encoding: .isoLatin1) ?? String(decoding: raw, as: UTF8.self)
        let peer = SignalService.parseKeyPayload(raw) ?? SignalService.parseKeyText(legacy)
        guard let peer else { throw SignalServiceError.unreadableScan }
        return try addPeer(peer, displayName: displayName)
    }

    @discardableResult
    private func addPeer(_ peer: BundlePayload, displayName: String) throws -> Contact {
        guard ensureLoaded() else { throw SignalServiceError.storageUnavailable }
        let fp = SignalFormat.hex(peer.identityKey)
        guard fp != myFingerprint else { throw SignalServiceError.ownKey }

        let ik = try IdentityKey(bytes: peer.identityKey)
        let spk = try PublicKey(peer.signedPreKey)
        let kyber = try KEMPublicKey(peer.kyberPreKey)
        let oneTime: (id: UInt32, key: PublicKey, mark: String)?
        if let otpId = peer.oneTimePreKeyId, let otp = peer.oneTimePreKey, let otpKey = try? PublicKey(otp) {
            oneTime = (otpId, otpKey, PreKeyMark.of(identityKey: peer.identityKey, oneTimePreKey: otp))
        } else {
            oneTime = nil
        }
        let spent = oneTime != nil && (meta.usedPreKeys ?? []).contains(oneTime!.mark)

        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)
        if !spent || !hasUsableSession(with: fp) {
            let bundle: PreKeyBundle
            if let oneTime, !spent {
                meta.rememberUsedPreKey(oneTime.mark)
                saveMeta()
                bundle = try PreKeyBundle(
                    registrationId: peer.registrationId, deviceId: peer.deviceId,
                    prekeyId: oneTime.id, prekey: oneTime.key,
                    signedPrekeyId: peer.signedPreKeyId, signedPrekey: spk, signedPrekeySignature: peer.signedPreKeySignature,
                    identity: ik,
                    kyberPrekeyId: peer.kyberPreKeyId, kyberPrekey: kyber, kyberPrekeySignature: peer.kyberPreKeySignature)
            } else {
                bundle = try PreKeyBundle(
                    registrationId: peer.registrationId, deviceId: peer.deviceId,
                    signedPrekeyId: peer.signedPreKeyId, signedPrekey: spk, signedPrekeySignature: peer.signedPreKeySignature,
                    identity: ik,
                    kyberPrekeyId: peer.kyberPreKeyId, kyberPrekey: kyber, kyberPrekeySignature: peer.kyberPreKeySignature)
            }
            try withStoreLock {
                try withConflictRetry {
                    try store.batch {
                        try processPreKeyBundle(bundle, for: addr, ourAddress: myAddr, sessionStore: store, identityStore: store, context: ctx)
                    }
                }
            }
        }

        let name = displayName.isEmpty ? String(fp.prefix(8)) : displayName
        if let idx = contacts.firstIndex(where: { $0.fingerprint == fp }) {
            contacts[idx].displayName = name
        } else {
            contacts.append(Contact(fingerprint: fp, displayName: name))
        }
        saveMeta()
        return Contact(fingerprint: fp, displayName: name)
    }

    func renameContact(_ contact: Contact, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isLoaded, !trimmed.isEmpty,
              let idx = contacts.firstIndex(where: { $0.fingerprint == contact.fingerprint }) else { return }
        guard contacts[idx].displayName != trimmed else { return }
        contacts[idx].displayName = trimmed
        saveMeta()
    }

    @discardableResult
    func removeContact(_ contact: Contact) -> Bool {
        guard isLoaded else { return false }
        let erased: Bool = withStoreLock {
            do {
                try withConflictRetry { try store.removeSessionAndIdentity(forName: contact.fingerprint) }
                rebuildStore()
                return true
            } catch {
                rebuildStore()
                return false
            }
        }
        guard erased else { return false }
        let dropped = (messages[contact.fingerprint] ?? []).map(\.text)
        contacts.removeAll { $0.fingerprint == contact.fingerprint }
        messages[contact.fingerprint] = nil
        meta.autoDelete?.removeValue(forKey: contact.fingerprint)
        autoDelete = meta.autoDelete ?? [:]
        meta.pinned?.removeAll { $0 == contact.fingerprint }
        if meta.pinned?.isEmpty == true { meta.pinned = nil }
        pinned = Set(meta.pinned ?? [])
        meta.purgeDecryptCache(fingerprint: contact.fingerprint)
        saveMeta()
        KeyboardSelection.forgetContact(contact.fingerprint, profileID: currentID)
        OwnCipherMarker.clear()
        DecryptPurgeMarker.bump()
        Clipboard.clearIfHolds(dropped)
        return true
    }

    private var lastMetaFingerprint: Data?
    private var lastMetaDigest: Data?

    private static let metaEncoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    func reloadCurrentFromDisk() {
        guard ensureLoaded() else { return }
        withStoreLock {
            guard let enc = SharedStore.read(metaStorageKey) else { return }
            let fingerprint = Data(SHA256.hash(data: enc))
            guard fingerprint != lastMetaFingerprint else { return }
            guard let box = try? AES.GCM.SealedBox(combined: enc),
                  let dec = try? AES.GCM.open(box, using: cryptKey),
                  let m = try? JSONDecoder().decode(Meta.self, from: dec) else { return }
            lastMetaFingerprint = fingerprint
            lastMetaDigest = Data(SHA256.hash(data: dec))
            meta = m
            autoDelete = meta.autoDelete ?? [:]
            pinned = Set(m.pinned ?? [])
            contacts = m.contacts
            messages = m.messages
        }
        purgeExpiredMessages()
    }

    private func rebuildStore() {
        store = PersistentSignalStore(identity: identity, registrationId: meta.registrationId,
                                      storageKey: StoreKey.store(currentID), cryptKey: cryptKey)
    }

    private func reloadStoreFromDisk() {
        let storageKey = StoreKey.store(currentID)
        let onDisk = PersistentSignalStore.currentDiskDigest(storageKey: storageKey)
        if let store, store.matchesDisk(onDisk) {
            store.clearStaleConflict()
            return
        }
        store = PersistentSignalStore(identity: identity, registrationId: meta.registrationId, storageKey: storageKey, cryptKey: cryptKey)
    }

    private func withStoreLock<T>(_ body: () throws -> T) rethrows -> T {
        storeLock.lock()
        defer { storeLock.unlock() }
        return try body()
    }

    private func withConflictRetry<T>(_ body: () throws -> T) throws -> T {
        reloadStoreFromDisk()
        do { return try body() }
        catch where store.hadStaleConflict {
            reloadStoreFromDisk()
            return try body()
        }
    }

    private func hasSession(with fingerprint: String) -> Bool {
        guard let store, let addr = try? ProtocolAddress(name: fingerprint, deviceId: 1) else { return false }
        return ((try? store.loadSession(for: addr, context: ctx)) ?? nil) != nil
    }

    private func hasUsableSession(with fingerprint: String) -> Bool {
        guard let store, let addr = try? ProtocolAddress(name: fingerprint, deviceId: 1),
              let record = (try? store.loadSession(for: addr, context: ctx)) ?? nil else { return false }
        return record.hasCurrentState(requirePqRatio: 0)
    }

    struct SentMessage: Sendable {
        let cipher: String
        let hidden: Bool
    }

    func encrypt(_ text: String, to contact: Contact) async throws -> SentMessage {
        guard ensureLoaded() else { throw SignalServiceError.storageUnavailable }
        let cover = ChatStego.resolvedCover()
        let pad = PrivacyConfig.lengthPadding
        let sealed = try withStoreLock {
            try withConflictRetry {
                guard hasSession(with: contact.fingerprint) else { throw SignalServiceError.sessionLost }
                return try store.batch {
                    try SignalWire.seal(text, toFingerprint: contact.fingerprint,
                                        myFingerprint: myFingerprint, store: store, pad: pad)
                }
            }
        }
        let language = cover.language
        let mode = cover.mode
        let produced = try await Task.detached(priority: .userInitiated) { () -> SignalWire.Cover in
            let cover = try SignalWire.cover(sealed, stego: language, mode: mode)
            OwnCipherMarker.mark(cover.text)
            return cover
        }.value
        TypingRollbackMarker.bump()
        append(ChatMessage(text: text, mine: true), to: contact.fingerprint)
        return SentMessage(cipher: produced.text, hidden: produced.hidden)
    }

    func decrypt(_ armored: String, from contact: Contact, refreshOnFailure: Bool = true,
                 stego: Data?? = nil, wireStego: Data?? = nil) throws -> String {
        guard ensureLoaded() else { throw SignalServiceError.storageUnavailable }
        let hidden = stego ?? DecryptCacheKey.stegoPayload(armored)
        let wire: Data?? = wireStego ?? (hidden == nil ? nil : .some(hidden))
        if let hit = cachedDecrypt(armored, stego: .some(hidden)) {
            guard hit.contact.fingerprint == contact.fingerprint else {
                throw SignalServiceError.decryptedForOtherContact(hit.contact.displayName)
            }
            return hit.text
        }
        do {
            let text = try withStoreLock {
                try withConflictRetry {
                    try store.batch {
                        try SignalWire.decrypt(armored, fromFingerprint: contact.fingerprint,
                                               myFingerprint: myFingerprint, store: store, stego: wire)
                    }
                }
            }
            append(ChatMessage(text: text, mine: false), to: contact.fingerprint) { [self] in
                meta.rememberDecrypt(armored: armored, fingerprint: contact.fingerprint,
                                     text: text, stego: .some(hidden))
            }
            return text
        } catch {
            guard refreshOnFailure else { throw error }
            reloadCurrentFromDisk()
            if let hit = cachedDecrypt(armored, stego: .some(hidden)) {
                guard hit.contact.fingerprint == contact.fingerprint else {
                    throw SignalServiceError.decryptedForOtherContact(hit.contact.displayName)
                }
                return hit.text
            }
            throw error
        }
    }

    func decryptFromAnyContact(_ armored: String, stego: Data?? = nil,
                               wireStego: Data?? = nil) -> (contact: Contact, text: String)? {
        guard ensureLoaded() else { return nil }
        let hidden = stego ?? DecryptCacheKey.stegoPayload(armored)
        if let hit = cachedDecrypt(armored, stego: .some(hidden)) { return hit }
        let wire: Data?? = wireStego ?? (hidden == nil ? nil : .some(hidden))
        let candidates = contacts
        let found: (contact: Contact, text: String)? = withStoreLock {
            reloadStoreFromDisk()
            for contact in candidates {
                do {
                    let text = try store.batch {
                        try SignalWire.decrypt(armored, fromFingerprint: contact.fingerprint,
                                               myFingerprint: myFingerprint, store: store, stego: wire)
                    }
                    return (contact, text)
                } catch {
                    if store.hadStaleConflict { reloadStoreFromDisk() }
                }
            }
            return nil
        }
        guard let found else { return nil }
        append(ChatMessage(text: found.text, mine: false), to: found.contact.fingerprint) { [self] in
            meta.rememberDecrypt(armored: armored, fingerprint: found.contact.fingerprint,
                                 text: found.text, stego: .some(hidden))
        }
        return found
    }

    func cachedDecrypt(_ armored: String, stego: Data?? = nil) -> (contact: Contact, text: String)? {
        guard isLoaded, let hit = meta?.cachedDecrypt(for: armored, stego: stego) else { return nil }
        let contact = contacts.first { $0.fingerprint == hit.fingerprint }
            ?? Contact(fingerprint: hit.fingerprint, displayName: String(hit.fingerprint.prefix(8)))
        return (contact, hit.text)
    }

    private func append(_ message: ChatMessage, to fingerprint: String,
                        remember: (() -> Void)? = nil) {
        reloadCurrentFromDisk()
        remember?()
        messages[fingerprint, default: []].append(message)
        if !purgeExpiredMessages() { saveMeta() }
    }

    func autoDeleteInterval(for fingerprint: String) -> TimeInterval? {
        guard let s = autoDelete[fingerprint], s > 0 else { return nil }
        return s
    }

    func setPinned(_ value: Bool, for contact: Contact) {
        guard isLoaded, meta != nil else { return }
        var list = meta.pinned ?? []
        if value {
            guard !list.contains(contact.fingerprint) else { return }
            list.append(contact.fingerprint)
        } else {
            let before = list.count
            list.removeAll { $0 == contact.fingerprint }
            guard list.count != before else { return }
        }
        meta.pinned = list.isEmpty ? nil : list
        pinned = Set(list)
        saveMeta()
    }

    func setAutoDelete(_ seconds: TimeInterval?, for contact: Contact) {
        guard isLoaded else { return }
        var map = meta.autoDelete ?? [:]
        if let seconds, seconds > 0 { map[contact.fingerprint] = seconds } else { map.removeValue(forKey: contact.fingerprint) }
        meta.autoDelete = map
        autoDelete = map
        purgeExpiredMessages()
        saveMeta()
    }

    @discardableResult
    func purgeExpiredMessages() -> Bool {
        guard isLoaded, meta != nil else { return false }
        let before = messages
        meta.messages = before
        guard meta.purgeExpired() else { return false }
        messages = meta.messages
        saveMeta()
        DecryptPurgeMarker.bump()
        Clipboard.clearIfHolds(SignalService.vanished(from: before, to: messages))
        return true
    }

    private static func vanished(from before: [String: [ChatMessage]],
                                 to after: [String: [ChatMessage]]) -> [String] {
        var gone: [String] = []
        for (fingerprint, list) in before {
            let kept = Set((after[fingerprint] ?? []).map(\.id))
            for message in list where !kept.contains(message.id) { gone.append(message.text) }
        }
        return gone
    }

    func deleteMessage(_ message: ChatMessage, from contact: Contact) {
        guard isLoaded, var list = messages[contact.fingerprint] else { return }
        let before = list.count
        list.removeAll { $0.id == message.id }
        guard list.count != before else { return }
        messages[contact.fingerprint] = list.isEmpty ? nil : list
        meta.purgeDecrypted(fingerprint: contact.fingerprint, text: message.text)
        saveMeta()
        DecryptPurgeMarker.bump()
        Clipboard.clearIfHolds([message.text])
    }

    func clearChat(_ contact: Contact) {
        guard isLoaded else { return }
        let erased = (messages[contact.fingerprint] ?? []).map(\.text)
        messages[contact.fingerprint] = nil
        meta.purgeDecryptCache(fingerprint: contact.fingerprint)
        saveMeta()
        DecryptPurgeMarker.bump()
        Clipboard.clearIfHolds(erased)
    }

    func wipeAllChats() {
        guard isLoaded else { return }
        let erased = messages.values.flatMap { $0 }.map(\.text)
        messages = [:]
        meta.purgeDecryptCache()
        saveMeta()
        OwnCipherMarker.clear()
        DecryptPurgeMarker.bump()
        Clipboard.clearIfHolds(erased)
    }

    @discardableResult
    func wipeContactsAndChats() -> Bool {
        guard isLoaded else { return false }
        return withStoreLock {
            do {
                try withConflictRetry { try store.removeAllSessionsAndPeerIdentities() }
            } catch {
                rebuildStore()
                return false
            }
            rebuildStore()
            OwnCipherMarker.clear()
            let dropped = messages.values.flatMap { $0 }.map(\.text)
            contacts = []
            messages = [:]
            meta.autoDelete = nil
            autoDelete = [:]
            meta.pinned = nil
            pinned = []
            meta.purgeDecryptCache()
            saveMeta()
            KeyboardSelection.forgetContact(profileID: currentID)
            DecryptPurgeMarker.bump()
            Clipboard.clearIfHolds(dropped)
            return true
        }
    }


}
