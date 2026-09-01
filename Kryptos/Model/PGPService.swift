import Foundation
import SwiftUI
import CipherCore
import ObjectivePGP

struct PGPIdentity: Codable, Identifiable, Hashable {
    var id: UUID
    var name: String
    var email: String
    var fingerprint: String
    var algo: String
    var createdAt: Date
    var publicKey: String = ""

    var userID: String {
        let n = name.trimmingCharacters(in: .whitespaces).isEmpty ? "Kryptos" : name
        let e = email.trimmingCharacters(in: .whitespaces)
        return e.isEmpty ? n : "\(n) <\(e)>"
    }
}

struct PGPRecipient: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var publicKey: String
    var fingerprint: String = ""
}

enum PGPAlgo: String, Codable, CaseIterable, Identifiable {
    case curve25519, rsa3072, rsa4096
    var id: String { rawValue }

    var token: String {
        switch self {
        case .curve25519: return "Curve25519"
        case .rsa3072: return "RSA 3072"
        case .rsa4096: return "RSA 4096"
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .curve25519: return "Curve25519 (fast, recommended)"
        case .rsa3072: return "RSA 3072 (compatible)"
        case .rsa4096: return "RSA 4096 (strongest)"
        }
    }

    private var legacyLabel: String {
        switch self {
        case .curve25519: return "Curve25519 (fast, recommended)"
        case .rsa3072: return "RSA 3072 (compatible)"
        case .rsa4096: return "RSA 4096 (strongest)"
        }
    }

    static func matching(label: String) -> PGPAlgo? {
        allCases.first { $0.token == label || $0.rawValue == label || $0.legacyLabel == label }
    }
}

enum PGPVerification: Sendable { case verified, unverified }

enum PGPError: LocalizedError {
    case notReady, badKey, badMessage, notEncrypted, tooLarge, generationFailed, storageUnavailable
    var errorDescription: String? {
        switch self {
        case .notReady: return String(localized: "No PGP key is selected.")
        case .badKey: return String(localized: "This is not a valid PGP public key.")
        case .badMessage: return String(localized: "No PGP message found.")
        case .notEncrypted: return String(localized: "This text is not an encrypted PGP message — it carries no encrypted layer.")
        case .tooLarge: return String(localized: "This message is too large to open.")
        case .generationFailed: return String(localized: "Could not generate the key.")
        case .storageUnavailable: return String(localized: "Secure storage is unavailable right now. Try again in a moment.")
        }
    }
}

private struct PGPIndex: Codable {
    var identities: [PGPIdentity]
    var currentID: UUID
}

@MainActor
final class PGPService: ObservableObject {
    @Published private(set) var identities: [PGPIdentity] = []
    @Published private(set) var currentID = UUID()
    @Published private(set) var recipients: [PGPRecipient] = [] {
        didSet { cachedRecipientKeys = nil }
    }
    @Published private(set) var myPublicKey = ""
    @Published private(set) var ready = false
    @Published private(set) var busy = false
    @Published private(set) var failure: String?

    private var currentKey: Key?

    private struct KeySet: @unchecked Sendable {
        let signing: Key
        let recipients: [Key]
    }

    nonisolated private static let cryptoQueue = DispatchQueue(label: "kryptos.pgp.crypto", qos: .userInitiated)

    nonisolated private static func onCryptoQueue<T: Sendable>(
        _ work: @escaping @Sendable () throws -> T
    ) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            cryptoQueue.async { continuation.resume(with: Result { try work() }) }
        }
    }

    private static let indexStoreKey = "pgp.index"
    private static let recipientsStoreKey = "pgp.recipients"
    private static let indexKey = "pgp.identities.index.v1"
    private static let recipientsKey = "pgp.recipients.v1"
    private static let legacySecret = "pgp.secretkey.v1"
    private static func secretAccount(_ id: UUID) -> String { "pgp.secret.\(id.uuidString)" }

    var currentIdentity: PGPIdentity? { identities.first { $0.id == currentID } }

    private var storeUnavailable = false

    init() {}

    private var booted = false

    func start() {
        retryBootstrapIfNeeded()
    }

    private func bootstrap() {
        booted = true
        storeUnavailable = false
        guard let loadedRecipients = Self.loadRecipientsStrict(),
              var index = Self.loadIndexStrict() else {
            storeUnavailable = true
            ready = false
            return
        }
        recipients = loadedRecipients

        if index.identities.isEmpty, let data = Keychain.load(account: Self.legacySecret),
           let key = try? ObjectivePGP.readKeys(from: data).first {
            let ident = PGPIdentity(id: UUID(), name: String(localized: "My key"), email: "", fingerprint: Self.fingerprint(of: key), algo: "imported", createdAt: Date(), publicKey: Self.exportPublicArmored(key))
            let secret = Self.exportSecret(key)
            if !secret.isEmpty, Keychain.save(secret, account: Self.secretAccount(ident.id)) {
                let migrated = PGPIndex(identities: [ident], currentID: ident.id)
                if Self.saveIndex(migrated) {
                    Keychain.delete(account: Self.legacySecret)
                    index = migrated
                } else {
                    Keychain.delete(account: Self.secretAccount(ident.id))
                }
            }
        }

        identities = index.identities
        currentID = index.identities.contains { $0.id == index.currentID } ? index.currentID : (index.identities.first?.id ?? UUID())

        if identities.isEmpty {
            generate(name: String(localized: "My key"), email: "", algo: .curve25519)
        } else {
            loadCurrent()
        }
    }

    private func retryBootstrapIfNeeded() {
        if !booted || storeUnavailable { bootstrap() }
    }

    func archivedIdentities() -> [KeyArchive.ArchivedPgpIdentity]? {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { return nil }
        var out: [KeyArchive.ArchivedPgpIdentity] = []
        for ident in identities {
            guard case .found(var secret) = Keychain.loadStrict(account: Self.secretAccount(ident.id)) else { return nil }
            defer { secret.resetBytes(in: secret.startIndex ..< secret.endIndex) }
            guard let armored = String(data: secret, encoding: .utf8), !armored.isEmpty else { return nil }
            out.append(KeyArchive.ArchivedPgpIdentity(
                id: ident.id.uuidString, name: ident.name, email: ident.email,
                fingerprint: ident.fingerprint, algo: ident.algo,
                created: Int64(ident.createdAt.timeIntervalSince1970 * 1000),
                publicKey: ident.publicKey, secret: armored))
        }
        return out
    }

    func archivedRecipients() -> [KeyArchive.ArchivedPgpRecipient] {
        retryBootstrapIfNeeded()
        return recipients.map {
            KeyArchive.ArchivedPgpRecipient(name: $0.name, publicKey: $0.publicKey, fingerprint: $0.fingerprint)
        }
    }

    @discardableResult
    func restore(identities list: [KeyArchive.ArchivedPgpIdentity],
                 recipients incoming: [KeyArchive.ArchivedPgpRecipient]) -> Bool {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { return false }

        var staged: [(identity: PGPIdentity, secret: Data)] = []
        var seen = Set<String>()
        for entry in list {
            guard seen.insert(entry.id).inserted,
                  let id = UUID(uuidString: entry.id), !entry.secret.isEmpty else { continue }
            let secret = Data(entry.secret.utf8)
            guard (try? ObjectivePGP.readKeys(from: secret))?.first != nil else { continue }
            staged.append((PGPIdentity(id: id, name: entry.name, email: entry.email,
                                       fingerprint: entry.fingerprint, algo: entry.algo,
                                       createdAt: Date(timeIntervalSince1970: Double(entry.created) / 1000),
                                       publicKey: entry.publicKey), secret))
        }
        guard !list.isEmpty else {
            recipients = incoming.map {
                PGPRecipient(name: $0.name, publicKey: $0.publicKey, fingerprint: $0.fingerprint)
            }
            saveRecipients()
            return true
        }
        guard !staged.isEmpty else { return false }

        var restored: [PGPIdentity] = []
        for item in staged where Keychain.save(item.secret, account: Self.secretAccount(item.identity.id)) {
            restored.append(item.identity)
        }
        guard !restored.isEmpty else { return false }

        let keep = Set(restored.map(\.id))
        for ident in identities where !keep.contains(ident.id) {
            Keychain.delete(account: Self.secretAccount(ident.id))
        }

        recipients = incoming.map {
            PGPRecipient(name: $0.name, publicKey: $0.publicKey, fingerprint: $0.fingerprint)
        }
        saveRecipients()

        identities = restored
        currentID = restored[0].id
        persistIndex()
        loadCurrent()
        return true
    }

    func resetAfterWipe() {
        currentKey = nil
        identities = []
        recipients = []
        myPublicKey = ""
        currentID = UUID()
        ready = false
        busy = false
        failure = nil
        storeUnavailable = false
        bootstrap()
    }

    private static func loadIndexStrict() -> PGPIndex? {
        switch SharedStore.readStrict(indexStoreKey) {
        case .unavailable:
            return nil
        case .found(let d):
            return try? JSONDecoder().decode(PGPIndex.self, from: d)
        case .absent:
            if let d = UserDefaults.standard.data(forKey: indexKey), let i = try? JSONDecoder().decode(PGPIndex.self, from: d) {
                saveIndex(i)
                UserDefaults.standard.removeObject(forKey: indexKey)
                return i
            }
            return PGPIndex(identities: [], currentID: UUID())
        }
    }
    @discardableResult
    private static func saveIndex(_ index: PGPIndex) -> Bool {
        guard let d = try? JSONEncoder().encode(index) else { return false }
        return SharedStore.write(indexStoreKey, d)
    }

    @discardableResult
    private func persistIndex() -> Bool {
        guard !storeUnavailable else { return false }
        guard Self.saveIndex(PGPIndex(identities: identities, currentID: currentID)) else {
            storeUnavailable = true
            ready = false
            return false
        }
        return true
    }

    private static func loadRecipientsStrict() -> [PGPRecipient]? {
        switch SharedStore.readStrict(recipientsStoreKey) {
        case .unavailable:
            return nil
        case .found(let data):
            return try? JSONDecoder().decode([PGPRecipient].self, from: data)
        case .absent:
            if let data = UserDefaults.standard.data(forKey: recipientsKey),
               let list = try? JSONDecoder().decode([PGPRecipient].self, from: data) {
                if let d = try? JSONEncoder().encode(list) { SharedStore.write(recipientsStoreKey, d) }
                UserDefaults.standard.removeObject(forKey: recipientsKey)
                return list
            }
            return []
        }
    }
    private func saveRecipients() {
        guard !storeUnavailable else { return }
        if let data = try? JSONEncoder().encode(recipients) { SharedStore.write(Self.recipientsStoreKey, data) }
    }

    private func loadCurrent() {
        let data: Data
        switch Keychain.loadStrict(account: Self.secretAccount(currentID)) {
        case .found(let stored):
            data = stored
        case .absent:
            currentKey = nil; myPublicKey = ""; ready = false
            return
        case .unavailable:
            storeUnavailable = true
            currentKey = nil; myPublicKey = ""; ready = false
            return
        }
        guard let key = try? ObjectivePGP.readKeys(from: data).first else {
            storeUnavailable = true
            currentKey = nil; myPublicKey = ""; ready = false
            return
        }
        currentKey = key
        myPublicKey = currentIdentity?.publicKey ?? ""
        if myPublicKey.isEmpty { myPublicKey = Self.exportPublicArmored(key) }
        ready = true
    }

    nonisolated private static func exportSecret(_ key: Key) -> Data {
        guard let secret = try? key.export(keyType: .secret) else { return Data() }
        return Data(Armor.armored(secret, as: .secretKey).utf8)
    }

    nonisolated private static func exportPublicArmored(_ key: Key) -> String {
        guard let pub = try? key.export(keyType: .public), !pub.isEmpty else { return "" }
        return Armor.armored(pub, as: .publicKey)
    }

    nonisolated private static func fingerprint(of key: Key) -> String {
        let raw = key.publicKey?.fingerprint.description ?? key.keyID.longIdentifier
        let hex = raw.replacingOccurrences(of: " ", with: "").uppercased()
        return stride(from: 0, to: hex.count, by: 4).map {
            let s = hex.index(hex.startIndex, offsetBy: $0)
            let e = hex.index(s, offsetBy: 4, limitedBy: hex.endIndex) ?? hex.endIndex
            return String(hex[s ..< e])
        }.joined(separator: " ")
    }

    nonisolated private static func generator(for algo: PGPAlgo) -> KeyGenerator {
        let aes256 = PGPSymmetricAlgorithm(rawValue: 9)!
        let sha256 = PGPHashAlgorithm(rawValue: 8)!
        switch algo {
        case .curve25519: return KeyGenerator(algorithm: .edDSA, keyBitsLength: 0, cipherAlgorithm: aes256, hashAlgorithm: sha256)
        case .rsa3072: return KeyGenerator(algorithm: .RSA, keyBitsLength: 3072, cipherAlgorithm: aes256, hashAlgorithm: sha256)
        case .rsa4096: return KeyGenerator(algorithm: .RSA, keyBitsLength: 4096, cipherAlgorithm: aes256, hashAlgorithm: sha256)
        }
    }

    func generate(name: String, email: String, algo: PGPAlgo) {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { return }
        busy = true
        failure = nil
        let userID = PGPIdentity(id: UUID(), name: name, email: email, fingerprint: "", algo: algo.rawValue, createdAt: Date()).userID
        Task.detached(priority: .userInitiated) {
            let key = Self.generator(for: algo).generate(for: userID, passphrase: nil)
            let secret = Self.exportSecret(key)
            let pub = Self.exportPublicArmored(key)
            let fp = Self.fingerprint(of: key)
            await MainActor.run {
                self.busy = false
                let ident = PGPIdentity(id: UUID(), name: name, email: email, fingerprint: fp, algo: algo.token, createdAt: Date(), publicKey: pub)
                guard !secret.isEmpty, Keychain.save(secret, account: Self.secretAccount(ident.id)) else {
                    self.failure = String(localized: "Could not save the key to the keychain.")
                    return
                }
                let previousIdentities = self.identities
                let previousCurrent = self.currentID
                self.identities.append(ident)
                self.currentID = ident.id
                guard self.persistIndex() else {
                    self.identities = previousIdentities
                    self.currentID = previousCurrent
                    Keychain.delete(account: Self.secretAccount(ident.id))
                    self.failure = String(localized: "Could not save the key to the keychain.")
                    return
                }
                self.loadCurrent()
            }
        }
    }

    func switchTo(_ id: UUID) {
        retryBootstrapIfNeeded()
        guard identities.contains(where: { $0.id == id }) else { return }
        currentID = id
        persistIndex()
        loadCurrent()
    }

    func regenerateCurrent(algo: PGPAlgo) {
        retryBootstrapIfNeeded()
        guard !storeUnavailable, let ident = currentIdentity else { return }
        busy = true
        failure = nil
        let userID = ident.userID
        let id = ident.id
        Task.detached(priority: .userInitiated) {
            let key = Self.generator(for: algo).generate(for: userID, passphrase: nil)
            let secret = Self.exportSecret(key)
            let pub = Self.exportPublicArmored(key)
            let fp = Self.fingerprint(of: key)
            await MainActor.run {
                self.busy = false
                guard !secret.isEmpty, Keychain.save(secret, account: Self.secretAccount(id)) else {
                    self.failure = String(localized: "Could not save the key to the keychain.")
                    return
                }
                if let idx = self.identities.firstIndex(where: { $0.id == id }) {
                    self.identities[idx].fingerprint = fp
                    self.identities[idx].algo = algo.token
                    self.identities[idx].createdAt = Date()
                    self.identities[idx].publicKey = pub
                }
                guard self.persistIndex() else {
                    self.failure = String(localized: "Could not save the key to the keychain.")
                    return
                }
                self.loadCurrent()
            }
        }
    }

    func deleteIdentity(_ id: UUID) {
        retryBootstrapIfNeeded()
        guard !storeUnavailable, identities.contains(where: { $0.id == id }) else { return }
        let previousIdentities = identities
        let previousCurrent = currentID
        let remaining = identities.filter { $0.id != id }
        identities = remaining
        if currentID == id { currentID = remaining.first?.id ?? UUID() }
        guard persistIndex() else {
            identities = previousIdentities
            currentID = previousCurrent
            return
        }
        Keychain.delete(account: Self.secretAccount(id))
        if remaining.isEmpty {
            generate(name: String(localized: "My key"), email: "", algo: .curve25519)
            return
        }
        loadCurrent()
    }

    func addRecipient(name: String, armoredKey: String) throws {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { throw PGPError.storageUnavailable }
        guard let keys = try? ObjectivePGP.readKeys(from: Data(armoredKey.utf8)), let key = keys.first else {
            throw PGPError.badKey
        }
        let fp = Self.fingerprint(of: key)
        if let idx = recipients.firstIndex(where: { $0.fingerprint == fp && !fp.isEmpty }) {
            recipients[idx].name = name.isEmpty ? recipients[idx].name : name
            recipients[idx].publicKey = armoredKey
        } else {
            recipients.append(PGPRecipient(name: name.isEmpty ? "Contact" : name, publicKey: armoredKey, fingerprint: fp))
        }
        saveRecipients()
    }

    func removeRecipient(_ recipient: PGPRecipient) {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { return }
        recipients.removeAll { $0.id == recipient.id }
        saveRecipients()
    }

    private var cachedRecipientKeys: [Key]?

    private func allRecipientKeys() -> [Key] {
        if let cachedRecipientKeys { return cachedRecipientKeys }
        let keys = recipients.flatMap { (try? ObjectivePGP.readKeys(from: Data($0.publicKey.utf8))) ?? [] }
        cachedRecipientKeys = keys
        return keys
    }

    func encrypt(_ text: String, to recipient: PGPRecipient) async throws -> String {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { throw PGPError.storageUnavailable }
        guard let currentKey else { throw PGPError.notReady }
        guard let recipientKeys = try? ObjectivePGP.readKeys(from: Data(recipient.publicKey.utf8)), !recipientKeys.isEmpty else {
            throw PGPError.badKey
        }
        let keys = KeySet(signing: currentKey, recipients: recipientKeys)
        return try await Self.onCryptoQueue { try Self.seal(text, keys: keys) }
    }

    nonisolated private static func seal(_ text: String, keys: KeySet) throws -> String {
        let encrypted = try ObjectivePGP.encrypt(Data(text.utf8), addSignature: true,
                                                 using: keys.recipients + [keys.signing], passphraseForKey: nil)
        return Armor.armored(encrypted, as: .message)
    }

    nonisolated private static let maxMessageBytes = 4 * 1024 * 1024
    nonisolated private static let maxPlaintextBytes = 8 * 1024 * 1024
    nonisolated private static let maxCompressedLayers = 1

    func decrypt(_ armored: String) async throws -> (text: String, verification: PGPVerification) {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { throw PGPError.storageUnavailable }
        guard let currentKey else { throw PGPError.notReady }
        guard armored.utf8.count <= Self.maxMessageBytes else { throw PGPError.tooLarge }
        let keys = KeySet(signing: currentKey, recipients: allRecipientKeys())
        return try await Self.onCryptoQueue { try Self.open(armored, keys: keys) }
    }

    nonisolated private static func open(_ armored: String, keys: KeySet) throws -> (text: String, verification: PGPVerification) {
        let binary: Data
        if let blocks = try? Armor.convertArmoredMessage2BinaryBlocks(whenNecessary: Data(armored.utf8)), let b = blocks.first {
            binary = b
        } else {
            binary = Data(armored.utf8)
        }
        let shape = OpenPGPEnvelope.shape(of: binary)
        guard shape.encrypted else { throw PGPError.notEncrypted }
        guard shape.compressedLayers <= maxCompressedLayers else { throw PGPError.tooLarge }
        let verifyKeys = [keys.signing] + keys.recipients
        var verifiedCode: Int32 = -1
        var decryptionError: NSError?
        let verifiedAttempt = try? ObjectivePGP.decrypt(binary, verified: &verifiedCode, certifyWithRootKey: false,
                                                        using: verifyKeys, passphraseForKey: nil,
                                                        decryptionError: &decryptionError)
        if let plain = verifiedAttempt, decryptionError == nil {
            guard plain.count <= maxPlaintextBytes else { throw PGPError.tooLarge }
            return (String(decoding: plain, as: UTF8.self), verifiedCode == 0 ? .verified : .unverified)
        }
        guard let plain = try? ObjectivePGP.decrypt(binary, andVerifySignature: false,
                                                    using: [keys.signing], passphraseForKey: nil) else {
            throw PGPError.badMessage
        }
        guard plain.count <= maxPlaintextBytes else { throw PGPError.tooLarge }
        return (String(decoding: plain, as: UTF8.self), verifiedCode == 0 ? .verified : .unverified)
    }

    static func eraseAllStorage() {
        for source in [SharedStore.read(indexStoreKey), UserDefaults.standard.data(forKey: indexKey)] {
            guard let d = source, let index = try? JSONDecoder().decode(PGPIndex.self, from: d) else { continue }
            for ident in index.identities { Keychain.delete(account: secretAccount(ident.id)) }
        }
        Keychain.delete(account: legacySecret)
        SharedStore.delete(indexStoreKey)
        SharedStore.delete(recipientsStoreKey)
        UserDefaults.standard.removeObject(forKey: indexKey)
        UserDefaults.standard.removeObject(forKey: recipientsKey)
    }

}
