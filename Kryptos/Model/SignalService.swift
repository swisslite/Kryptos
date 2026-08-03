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
    @Published private(set) var myFingerprint = ""
    @Published private(set) var mySafetyNumber = ""
    @Published private(set) var isLoaded = false
    @Published private(set) var keyMaterialLost = false

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

    init() {
        bootstrapFromIndex()
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
        currentID = index.profiles.contains(where: { $0.id == index.currentID }) ? index.currentID : index.profiles[0].id
        load(profileID: currentID)
        persistIndex()
    }

    private static func defaultProfileName(_ n: Int) -> String { String(localized: "Profile \(n)") }

    private static func relocalizedDefaultName(_ p: Profile) -> Profile {
        for prefix in ["Profile ", "Профиль "] where p.name.hasPrefix(prefix) {
            if let n = Int(p.name.dropFirst(prefix.count)) {
                var q = p
                q.name = defaultProfileName(n)
                return q
            }
        }
        return p
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

    func switchTo(_ id: UUID) {
        guard profiles.contains(where: { $0.id == id }) else { return }
        currentID = id
        persistIndex()
        load(profileID: id)
    }

    @discardableResult
    func createProfile(name: String) -> Profile {
        if indexUnavailable { bootstrapFromIndex() }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let profile = Profile(id: UUID(), name: trimmed.isEmpty ? SignalService.defaultProfileName(profiles.count + 1) : trimmed)
        profiles.append(profile)
        currentID = profile.id
        persistIndex()
        load(profileID: profile.id)
        return profile
    }

    func renameCurrent(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let idx = profiles.firstIndex(where: { $0.id == currentID }) else { return }
        profiles[idx].name = trimmed
        persistIndex()
    }

    func deleteProfile(_ id: UUID) {
        wipeStorage(for: id)
        KeyboardSelection.forgetProfile(id)
        profiles.removeAll { $0.id == id }
        if profiles.isEmpty {
            let p = Profile(id: UUID(), name: SignalService.defaultProfileName(1))
            profiles = [p]
            currentID = p.id
        } else if currentID == id {
            currentID = profiles[0].id
        }
        persistIndex()
        load(profileID: currentID)
    }

    func regenerateCurrentIdentity() {
        wipeStorage(for: currentID)
        load(profileID: currentID)
    }

    private func wipeStorage(for id: UUID) {
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
        contacts = []
        messages = [:]
        autoDelete = [:]
        myFingerprint = ""
        mySafetyNumber = ""
        store = nil
        meta = nil
        identity = nil
        cryptKey = nil
        metaStorageKey = nil
    }

    private func load(profileID id: UUID) {
        resetLoadedState()

        let key: SymmetricKey
        var keyIsNew = false
        switch SharedStore.readStrict(StoreKey.fileKey(id)) {
        case .found(let data):
            key = SymmetricKey(data: data)
        case .unavailable:
            return
        case .absent:
            switch Keychain.loadStrict(account: StoreKey.legacyFileKey(id)) {
            case .found(let legacy):
                key = SymmetricKey(data: legacy)
            case .unavailable:
                return
            case .absent:
                key = SymmetricKey(size: .bits256)
            }
            keyIsNew = true
        }

        let id_: IdentityKeyPair
        var identityIsNew = false
        switch SharedStore.readStrict(StoreKey.identity(id)) {
        case .found(let data):
            guard let restored = try? IdentityKeyPair(bytes: data) else {
                keyMaterialLost = true
                return
            }
            id_ = restored
        case .unavailable:
            return
        case .absent:
            switch Keychain.loadStrict(account: StoreKey.legacyIdentity(id)) {
            case .found(let legacy):
                guard let restored = try? IdentityKeyPair(bytes: legacy) else {
                    keyMaterialLost = true
                    return
                }
                id_ = restored
            case .unavailable:
                return
            case .absent:
                id_ = IdentityKeyPair.generate()
            }
            identityIsNew = true
        }

        let loadedMeta: Meta?
        switch SharedStore.readStrict(StoreKey.meta(id)) {
        case .found(let enc):
            guard let box = try? AES.GCM.SealedBox(combined: enc),
                  let dec = try? AES.GCM.open(box, using: key),
                  let m = try? JSONDecoder().decode(Meta.self, from: dec) else {
                keyMaterialLost = true
                return
            }
            loadedMeta = m
        case .unavailable:
            return
        case .absent:
            loadedMeta = nil
        }

        cryptKey = key
        if keyIsNew { SharedStore.write(StoreKey.fileKey(id), key.withUnsafeBytes { Data($0) }) }
        if identityIsNew { SharedStore.write(StoreKey.identity(id), id_.serialize()) }
        identity = id_
        myFingerprint = SignalFormat.hex(id_.identityKey.serialize())
        mySafetyNumber = SignalFormat.safetyNumber(fromHex: myFingerprint)

        SignalPaths.purgeLegacyMirror(id)

        metaStorageKey = StoreKey.meta(id)
        let regId = loadedMeta?.registrationId ?? UInt32.random(in: 1 ... 0x3FFF)
        store = PersistentSignalStore(identity: id_, registrationId: regId, storageKey: StoreKey.store(id), cryptKey: key)
        guard !store.loadFailed else { return }

        var provisioned = true
        withStoreLock {
            do {
                try store.batch {
                    if let m = loadedMeta {
                        meta = m
                    } else {
                        meta = provisionInitial(registrationId: regId)
                    }
                    maintainPreKeys()
                }
            } catch {
                provisioned = false
                meta = nil
                return
            }
            autoDelete = meta.autoDelete ?? [:]
            contacts = meta.contacts
            messages = meta.messages
            saveMeta()
        }
        guard provisioned else { return }
        isLoaded = true
        purgeExpiredMessages()
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

            let key = SymmetricKey(size: .bits256)
            guard let json = try? JSONEncoder().encode(meta),
                  let box = try? AES.GCM.seal(json, using: key),
                  let combined = box.combined,
                  SharedStore.write(StoreKey.fileKey(id), key.withUnsafeBytes { Data($0) }),
                  SharedStore.write(StoreKey.identity(id), identityData),
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
        currentID = restored[0].id
        indexUnavailable = false
        persistIndex()
        load(profileID: currentID)
        return isLoaded
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
        if indexUnavailable {
            bootstrapFromIndex()
            if indexUnavailable { return false }
            return isLoaded
        }
        if !isLoaded { load(profileID: currentID) }
        return isLoaded
    }

    private func provisionInitial(registrationId regId: UInt32) -> Meta {
        let gen = generateSignedAndKyber(signedId: 1, kyberId: 2)
        var m = Meta(registrationId: regId,
                     signedPreKeyId: gen.signedId, signedPreKeyPub: gen.signedPub, signedPreKeySig: gen.signedSig,
                     kyberPreKeyId: gen.kyberId, kyberPreKeyPub: gen.kyberPub, kyberPreKeySig: gen.kyberSig)
        m.prekeyCreatedAt = Date()
        m.retiredPreKeyGens = []
        m.nextSignedPreKeyId = 3
        m.nextKyberPreKeyId = 4
        m.nextOneTimePreKeyId = 1
        m.oneTimePreKeyIds = []
        meta = m
        return meta
    }

    private func maintainPreKeys() {
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
            rotateSignedAndKyber()
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

    private func rotateSignedAndKyber() {
        var retired = meta.retiredPreKeyGens ?? []
        retired.append(RetiredPreKeyGen(signedPreKeyId: meta.signedPreKeyId, kyberPreKeyId: meta.kyberPreKeyId, retiredAt: Date()))
        meta.retiredPreKeyGens = retired

        let signedId = meta.nextSignedPreKeyId ?? (meta.signedPreKeyId + 2)
        let kyberId = meta.nextKyberPreKeyId ?? (meta.kyberPreKeyId + 2)
        let gen = generateSignedAndKyber(signedId: signedId, kyberId: kyberId)
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

    private func generateSignedAndKyber(signedId: UInt32, kyberId: UInt32) -> GenKeys {
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
        guard let json = try? JSONEncoder().encode(meta),
              let box = try? AES.GCM.seal(json, using: cryptKey),
              let combined = box.combined else { return }
        guard SharedStore.write(metaStorageKey, combined) else { return }
        lastMetaFingerprint = Data(SHA256.hash(data: combined))
    }

    func myKeyString() -> String {
        guard ensureLoaded() else { return "" }
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
            return "KRYPTOS-KEY:" + SignalService.encodeBundle(payload).base64EncodedString()
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

    @discardableResult
    func addContact(fromKeyString raw: String, displayName: String) throws -> Contact {
        guard ensureLoaded() else { throw SignalServiceError.storageUnavailable }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let range = trimmed.range(of: "KRYPTOS-KEY:") else { throw SignalServiceError.badKeyString }
        let b64 = String(trimmed[range.upperBound...]).prefix { !$0.isWhitespace }
        guard let blob = Data(base64Encoded: String(b64)) else { throw SignalServiceError.badKeyString }
        let peer: BundlePayload
        if blob.first == SignalService.bundleFormatByte {
            peer = try SignalService.decodeBundle(blob)
        } else if let j = try? JSONDecoder().decode(BundlePayload.self, from: blob) {
            peer = j
        } else {
            throw SignalServiceError.badKeyString
        }

        let fp = SignalFormat.hex(peer.identityKey)
        guard fp != myFingerprint else { throw SignalServiceError.badKeyString }

        let ik = try IdentityKey(bytes: peer.identityKey)
        let spk = try PublicKey(peer.signedPreKey)
        let kyber = try KEMPublicKey(peer.kyberPreKey)
        let bundle: PreKeyBundle
        if let otpId = peer.oneTimePreKeyId, let otp = peer.oneTimePreKey, let otpKey = try? PublicKey(otp) {
            bundle = try PreKeyBundle(
                registrationId: peer.registrationId, deviceId: peer.deviceId,
                prekeyId: otpId, prekey: otpKey,
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

        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)
        try withStoreLock {
            try withConflictRetry {
                try store.batch {
                    try processPreKeyBundle(bundle, for: addr, ourAddress: myAddr, sessionStore: store, identityStore: store, context: ctx)
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

    @discardableResult
    func removeContact(_ contact: Contact) -> Bool {
        guard isLoaded else { return false }
        let erased: Bool = withStoreLock {
            do {
                try withConflictRetry { try store.removeSessionAndIdentity(forName: contact.fingerprint) }
                reloadStoreFromDisk()
                return true
            } catch {
                reloadStoreFromDisk()
                return false
            }
        }
        guard erased else { return false }
        contacts.removeAll { $0.fingerprint == contact.fingerprint }
        messages[contact.fingerprint] = nil
        meta.autoDelete?.removeValue(forKey: contact.fingerprint)
        autoDelete = meta.autoDelete ?? [:]
        meta.purgeDecryptCache(fingerprint: contact.fingerprint)
        saveMeta()
        return true
    }

    private var lastMetaFingerprint: Data?

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
            meta = m
            autoDelete = meta.autoDelete ?? [:]
            contacts = m.contacts
            messages = m.messages
        }
        purgeExpiredMessages()
    }

    private func reloadStoreFromDisk() {
        store = PersistentSignalStore(identity: identity, registrationId: meta.registrationId, storageKey: StoreKey.store(currentID), cryptKey: cryptKey)
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

    func encrypt(_ text: String, to contact: Contact) throws -> String {
        guard ensureLoaded() else { throw SignalServiceError.storageUnavailable }
        let cover = ChatStego.resolvedCover()
        let pad = PrivacyConfig.lengthPadding
        let armored = try withStoreLock {
            try withConflictRetry {
                try store.batch {
                    try SignalWire.encrypt(text, toFingerprint: contact.fingerprint, myFingerprint: myFingerprint,
                                           store: store, stego: cover.language, mode: cover.mode, pad: pad)
                }
            }
        }
        OwnCipherMarker.mark(armored)
        append(ChatMessage(text: text, mine: true), to: contact.fingerprint)
        return armored
    }

    func decrypt(_ armored: String, from contact: Contact, refreshOnFailure: Bool = true) throws -> String {
        guard ensureLoaded() else { throw SignalServiceError.storageUnavailable }
        if let hit = cachedDecrypt(armored) {
            guard hit.contact.fingerprint == contact.fingerprint else {
                throw SignalServiceError.decryptedForOtherContact(hit.contact.displayName)
            }
            return hit.text
        }
        do {
            let text = try withStoreLock {
                try withConflictRetry {
                    try store.batch {
                        try SignalWire.decrypt(armored, fromFingerprint: contact.fingerprint, myFingerprint: myFingerprint, store: store)
                    }
                }
            }
            meta.rememberDecrypt(armored: armored, fingerprint: contact.fingerprint, text: text)
            append(ChatMessage(text: text, mine: false), to: contact.fingerprint)
            return text
        } catch {
            guard refreshOnFailure else { throw error }
            reloadCurrentFromDisk()
            if let hit = cachedDecrypt(armored) {
                guard hit.contact.fingerprint == contact.fingerprint else {
                    throw SignalServiceError.decryptedForOtherContact(hit.contact.displayName)
                }
                return hit.text
            }
            throw error
        }
    }

    func cachedDecrypt(_ armored: String) -> (contact: Contact, text: String)? {
        guard isLoaded, let hit = meta?.cachedDecrypt(for: armored) else { return nil }
        let contact = contacts.first { $0.fingerprint == hit.fingerprint }
            ?? Contact(fingerprint: hit.fingerprint, displayName: String(hit.fingerprint.prefix(8)))
        return (contact, hit.text)
    }

    private func append(_ message: ChatMessage, to fingerprint: String) {
        messages[fingerprint, default: []].append(message)
        purgeExpired(for: fingerprint)
        saveMeta()
    }

    func autoDeleteInterval(for fingerprint: String) -> TimeInterval? {
        guard let s = autoDelete[fingerprint], s > 0 else { return nil }
        return s
    }

    func setAutoDelete(_ seconds: TimeInterval?, for contact: Contact) {
        guard isLoaded else { return }
        var map = meta.autoDelete ?? [:]
        if let seconds, seconds > 0 { map[contact.fingerprint] = seconds } else { map.removeValue(forKey: contact.fingerprint) }
        meta.autoDelete = map
        autoDelete = map
        purgeExpired(for: contact.fingerprint)
        saveMeta()
    }

    private func purgeExpired(for fingerprint: String) {
        guard let secs = meta.autoDelete?[fingerprint], secs > 0 else { return }
        meta.purgeDecryptCache(fingerprint: fingerprint, olderThan: secs)
        guard var msgs = messages[fingerprint] else { return }
        let now = Date()
        let before = msgs.count
        msgs.removeAll { now.timeIntervalSince($0.date) >= secs }
        if msgs.count != before { messages[fingerprint] = msgs }
    }

    @discardableResult
    func purgeExpiredMessages() -> Bool {
        guard isLoaded, let map = meta?.autoDelete, !map.isEmpty else { return false }
        let now = Date()
        var changed = false
        let cacheBefore = meta.decryptCache?.count ?? 0
        for (fp, secs) in map where secs > 0 {
            meta.purgeDecryptCache(fingerprint: fp, olderThan: secs)
            guard var msgs = messages[fp], !msgs.isEmpty else { continue }
            let before = msgs.count
            msgs.removeAll { now.timeIntervalSince($0.date) >= secs }
            if msgs.count != before { messages[fp] = msgs; changed = true }
        }
        if (meta.decryptCache?.count ?? 0) != cacheBefore { changed = true }
        if changed { saveMeta() }
        return changed
    }

    func clearChat(_ contact: Contact) {
        guard isLoaded else { return }
        messages[contact.fingerprint] = nil
        meta.purgeDecryptCache(fingerprint: contact.fingerprint)
        saveMeta()
    }

    func wipeAllChats() {
        guard isLoaded else { return }
        messages = [:]
        meta.purgeDecryptCache()
        saveMeta()
    }

    @discardableResult
    func wipeContactsAndChats() -> Bool {
        guard isLoaded else { return false }
        return withStoreLock {
            do {
                try withConflictRetry { try store.removeAllSessionsAndPeerIdentities() }
            } catch {
                reloadStoreFromDisk()
                return false
            }
            reloadStoreFromDisk()
            contacts = []
            messages = [:]
            meta.autoDelete = nil
            autoDelete = [:]
            meta.purgeDecryptCache()
            saveMeta()
            return true
        }
    }


}
