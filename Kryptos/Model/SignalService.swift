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
    @Published private(set) var myFingerprint = ""
    @Published private(set) var mySafetyNumber = ""
    @Published private(set) var isLoaded = false

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

    private func load(profileID id: UUID) {
        isLoaded = false
        contacts = []
        messages = [:]

        let key: SymmetricKey
        var keyIsNew = false
        switch SharedStore.readStrict(StoreKey.fileKey(id)) {
        case .found(let data):
            key = SymmetricKey(data: data)
        case .unavailable:
            return
        case .absent:
            if let legacy = Keychain.load(account: StoreKey.legacyFileKey(id)) {
                key = SymmetricKey(data: legacy)
            } else {
                key = SymmetricKey(size: .bits256)
            }
            keyIsNew = true
        }

        let id_: IdentityKeyPair
        var identityIsNew = false
        switch SharedStore.readStrict(StoreKey.identity(id)) {
        case .found(let data):
            guard let restored = try? IdentityKeyPair(bytes: data) else { return }
            id_ = restored
        case .unavailable:
            return
        case .absent:
            if let legacy = Keychain.load(account: StoreKey.legacyIdentity(id)), let restored = try? IdentityKeyPair(bytes: legacy) {
                id_ = restored
            } else {
                id_ = IdentityKeyPair.generate()
            }
            identityIsNew = true
        }

        let loadedMeta: Meta?
        switch SharedStore.readStrict(StoreKey.meta(id)) {
        case .found(let enc):
            guard let box = try? AES.GCM.SealedBox(combined: enc),
                  let dec = try? AES.GCM.open(box, using: key),
                  let m = try? JSONDecoder().decode(Meta.self, from: dec) else { return }
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

        withStoreLock {
            if let m = loadedMeta {
                meta = m
            } else {
                meta = provisionInitial(registrationId: regId)
            }
            contacts = meta.contacts
            messages = meta.messages
            maintainPreKeys()
            saveMeta()
        }
        isLoaded = true
        purgeExpiredMessages()
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
        meta.contacts = contacts
        meta.messages = messages
        guard let json = try? JSONEncoder().encode(meta),
              let box = try? AES.GCM.seal(json, using: cryptKey),
              let combined = box.combined else { return }
        SharedStore.write(metaStorageKey, combined)
    }

    func myKeyString() -> String {
        guard ensureLoaded() else { return "" }
        return withStoreLock {
            reloadStoreFromDisk()
            let opk = nextOneTimePreKeyForBundle()
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
                try processPreKeyBundle(bundle, for: addr, ourAddress: myAddr, sessionStore: store, identityStore: store, context: ctx)
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

    func removeContact(_ contact: Contact) {
        guard isLoaded else { return }
        withStoreLock {
            reloadStoreFromDisk()
            store.removeSessionAndIdentity(forName: contact.fingerprint)
            reloadStoreFromDisk()
        }
        contacts.removeAll { $0.fingerprint == contact.fingerprint }
        messages[contact.fingerprint] = nil
        meta.autoDelete?.removeValue(forKey: contact.fingerprint)
        meta.purgeDecryptCache(fingerprint: contact.fingerprint)
        saveMeta()
    }

    func reloadCurrentFromDisk() {
        guard ensureLoaded() else { return }
        withStoreLock {
            guard let enc = SharedStore.read(metaStorageKey),
                  let box = try? AES.GCM.SealedBox(combined: enc),
                  let dec = try? AES.GCM.open(box, using: cryptKey),
                  let m = try? JSONDecoder().decode(Meta.self, from: dec) else { return }
            meta = m
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
        let armored = try withStoreLock {
            try withConflictRetry {
                try SignalWire.encrypt(text, toFingerprint: contact.fingerprint, myFingerprint: myFingerprint,
                                       store: store, stego: ChatStego.resolvedLanguage(), smart: ChatStego.resolvedSmart(),
                                       pad: PrivacyConfig.lengthPadding)
            }
        }
        OwnCipherMarker.mark(armored)
        append(ChatMessage(text: text, mine: true), to: contact.fingerprint)
        return armored
    }

    func decrypt(_ armored: String, from contact: Contact) throws -> String {
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
                    try SignalWire.decrypt(armored, fromFingerprint: contact.fingerprint, myFingerprint: myFingerprint, store: store)
                }
            }
            meta.rememberDecrypt(armored: armored, fingerprint: contact.fingerprint, text: text)
            append(ChatMessage(text: text, mine: false), to: contact.fingerprint)
            return text
        } catch {
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
        guard let hit = meta?.cachedDecrypt(for: armored) else { return nil }
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
        guard let s = meta?.autoDelete?[fingerprint], s > 0 else { return nil }
        return s
    }

    func setAutoDelete(_ seconds: TimeInterval?, for contact: Contact) {
        guard isLoaded else { return }
        var map = meta.autoDelete ?? [:]
        if let seconds, seconds > 0 { map[contact.fingerprint] = seconds } else { map.removeValue(forKey: contact.fingerprint) }
        meta.autoDelete = map
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

    func wipeContactsAndChats() {
        guard isLoaded else { return }
        withStoreLock {
            reloadStoreFromDisk()
            store.removeAllSessionsAndPeerIdentities()
            reloadStoreFromDisk()
            contacts = []
            messages = [:]
            meta.autoDelete = nil
            meta.purgeDecryptCache()
            saveMeta()
        }
    }

    func profileIsolationSelfTestError() -> String? {
        let savedProfiles = profiles
        let savedCurrent = currentID
        func cleanup() {
            for p in profiles where p.name.hasPrefix("__isotest__") { wipeStorage(for: p.id) }
            profiles = savedProfiles
            currentID = savedCurrent
            persistIndex()
            load(profileID: savedCurrent)
        }
        let a = createProfile(name: "__isotest__A")
        contacts = [Contact(fingerprint: "00aa00aa", displayName: "IsoAlice")]
        saveMeta()
        let b = createProfile(name: "__isotest__B")
        if !contacts.isEmpty { cleanup(); return "B-had-\(contacts.count)-contacts" }
        switchTo(a.id)
        let ok = contacts.count == 1 && contacts.first?.displayName == "IsoAlice"
        _ = b
        cleanup()
        return ok ? nil : "A-lost-its-contact"
    }

    static func contactDeletionSelfTestError() -> String? {
        let ctx = NullContext()
        let key = SymmetricKey(size: .bits256)
        let storeKey = "selftest.del.\(UUID().uuidString)"
        defer { SharedStore.delete(storeKey) }
        func makeBundle() throws -> (PreKeyBundle, String) {
            let idk = IdentityKeyPair.generate()
            let reg = UInt32.random(in: 1 ... 0x3FFF)
            let peer = InMemorySignalProtocolStore(identity: idk, registrationId: reg)
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            let signed = PrivateKey.generate()
            let signedSig = idk.privateKey.generateSignature(message: signed.publicKey.serialize())
            try peer.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: now, privateKey: signed, signature: signedSig), id: 1, context: ctx)
            let kyber = KEMKeyPair.generate()
            let kyberSig = idk.privateKey.generateSignature(message: kyber.publicKey.serialize())
            try peer.storeKyberPreKey(KyberPreKeyRecord(id: 1, timestamp: now, keyPair: kyber, signature: kyberSig), id: 1, context: ctx)
            let bundle = try PreKeyBundle(registrationId: reg, deviceId: 1,
                                         signedPrekeyId: 1, signedPrekey: signed.publicKey, signedPrekeySignature: signedSig,
                                         identity: idk.identityKey,
                                         kyberPrekeyId: 1, kyberPrekey: kyber.publicKey, kyberPrekeySignature: kyberSig)
            return (bundle, SignalFormat.hex(idk.identityKey.serialize()))
        }
        do {
            let me = PersistentSignalStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 0x3FFF), storageKey: storeKey, cryptKey: key)
            let myAddr = try ProtocolAddress(name: "me", deviceId: 1)
            let (bBundle, bName) = try makeBundle()
            let (cBundle, cName) = try makeBundle()
            let bAddr = try ProtocolAddress(name: bName, deviceId: 1)
            let cAddr = try ProtocolAddress(name: cName, deviceId: 1)
            try processPreKeyBundle(bBundle, for: bAddr, ourAddress: myAddr, sessionStore: me, identityStore: me, context: ctx)
            try processPreKeyBundle(cBundle, for: cAddr, ourAddress: myAddr, sessionStore: me, identityStore: me, context: ctx)
            guard try me.loadSession(for: bAddr, context: ctx) != nil, try me.loadSession(for: cAddr, context: ctx) != nil else { return "setup" }
            me.removeSessionAndIdentity(forName: bName)
            let reloaded = PersistentSignalStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 0x3FFF), storageKey: storeKey, cryptKey: key)
            if (try reloaded.loadSession(for: bAddr, context: ctx)) != nil { return "deleted-session-remains" }
            if (try reloaded.loadSession(for: cAddr, context: ctx)) == nil { return "other-session-lost" }
            return nil
        } catch { return "\(error)" }
    }

    static func selfTestError() -> String? { SignalWire.selfTestError() }
    static func fullWireTestError() -> String? { SignalWire.fullWireTestError() }
    static func stegoWireTestError() -> String? { SignalWire.stegoWireTestError() }

    static func provisioningSelfTestError() -> String? {
        let ctx = NullContext()
        let key = SymmetricKey(size: .bits256)
        let bobKey = "selftest.bob.\(UUID().uuidString)"
        let aliceKey = "selftest.alice.\(UUID().uuidString)"
        defer { SharedStore.delete(bobKey); SharedStore.delete(aliceKey) }
        do {
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            let bobIdentity = IdentityKeyPair.generate()
            let bob = PersistentSignalStore(identity: bobIdentity, registrationId: UInt32.random(in: 1 ... 0x3FFF), storageKey: bobKey, cryptKey: key)
            let bobReg = try bob.localRegistrationId(context: ctx)
            let signed = PrivateKey.generate()
            let signedSig = bobIdentity.privateKey.generateSignature(message: signed.publicKey.serialize())
            try bob.storeSignedPreKey(SignedPreKeyRecord(id: 10, timestamp: now, privateKey: signed, signature: signedSig), id: 10, context: ctx)
            let kyber = KEMKeyPair.generate()
            let kyberSig = bobIdentity.privateKey.generateSignature(message: kyber.publicKey.serialize())
            try bob.storeKyberPreKey(KyberPreKeyRecord(id: 20, timestamp: now, keyPair: kyber, signature: kyberSig), id: 20, context: ctx)
            let otpId: UInt32 = 77
            let otp = PrivateKey.generate()
            try bob.storePreKey(PreKeyRecord(id: otpId, privateKey: otp), id: otpId, context: ctx)

            let bundle = try PreKeyBundle(registrationId: bobReg, deviceId: 1,
                                          prekeyId: otpId, prekey: otp.publicKey,
                                          signedPrekeyId: 10, signedPrekey: signed.publicKey, signedPrekeySignature: signedSig,
                                          identity: bobIdentity.identityKey,
                                          kyberPrekeyId: 20, kyberPrekey: kyber.publicKey, kyberPrekeySignature: kyberSig)
            let alice = PersistentSignalStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 0x3FFF), storageKey: aliceKey, cryptKey: key)
            let bobAddr = try ProtocolAddress(name: "bob", deviceId: 1)
            let aliceAddr = try ProtocolAddress(name: "alice", deviceId: 1)
            try processPreKeyBundle(bundle, for: bobAddr, ourAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)
            let ct = try signalEncrypt(message: Array("fs".utf8), for: bobAddr, localAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)
            guard ct.messageType == .preKey else { return "type" }
            let dec = try signalDecryptPreKey(message: PreKeySignalMessage(bytes: ct.serialize()), from: aliceAddr, localAddress: bobAddr,
                                              sessionStore: bob, identityStore: bob, preKeyStore: bob, signedPreKeyStore: bob, kyberPreKeyStore: bob, context: ctx)
            guard String(decoding: dec, as: UTF8.self) == "fs" else { return "decrypt" }

            if (try? bob.loadPreKey(id: otpId, context: ctx)) != nil { return "otp-not-consumed" }
            let bobReloaded = PersistentSignalStore(identity: bobIdentity, registrationId: bobReg, storageKey: bobKey, cryptKey: key)
            if (try? bobReloaded.loadPreKey(id: otpId, context: ctx)) != nil { return "otp-consumption-not-persisted" }

            bob.removeSignedPreKey(id: 10)
            if (try? bob.loadSignedPreKey(id: 10, context: ctx)) != nil { return "signed-not-deleted" }

            let lock = NSRecursiveLock()
            func guarded(_ v: Int) -> Int { lock.lock(); defer { lock.unlock() }; return v }
            lock.lock(); let sum = guarded(1) + guarded(2); lock.unlock()
            guard sum == 3 else { return "lock" }
            return nil
        } catch { return "\(error)" }
    }
}
