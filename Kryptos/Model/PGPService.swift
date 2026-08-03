import Foundation
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
    var label: String {
        switch self {
        case .curve25519: return "Curve25519 (fast, recommended)"
        case .rsa3072: return "RSA 3072 (compatible)"
        case .rsa4096: return "RSA 4096 (strongest)"
        }
    }
}

enum PGPVerification { case verified, unverified }

enum PGPError: LocalizedError {
    case notReady, badKey, badMessage, generationFailed, storageUnavailable
    var errorDescription: String? {
        switch self {
        case .notReady: return String(localized: "No PGP key is selected.")
        case .badKey: return String(localized: "This is not a valid PGP public key.")
        case .badMessage: return String(localized: "No PGP message found.")
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
    @Published private(set) var recipients: [PGPRecipient] = []
    @Published private(set) var myPublicKey = ""
    @Published private(set) var ready = false
    @Published private(set) var busy = false

    private var currentKey: Key?

    private static let indexStoreKey = "pgp.index"
    private static let recipientsStoreKey = "pgp.recipients"
    private static let indexKey = "pgp.identities.index.v1"
    private static let recipientsKey = "pgp.recipients.v1"
    private static let legacySecret = "pgp.secretkey.v1"
    private static func secretAccount(_ id: UUID) -> String { "pgp.secret.\(id.uuidString)" }

    var currentIdentity: PGPIdentity? { identities.first { $0.id == currentID } }

    private var storeUnavailable = false

    init() {
        bootstrap()
    }

    private func bootstrap() {
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
            let ident = PGPIdentity(id: UUID(), name: "My key", email: "", fingerprint: Self.fingerprint(of: key), algo: "imported", createdAt: Date(), publicKey: Self.exportPublicArmored(key))
            Keychain.save(Self.exportSecret(key), account: Self.secretAccount(ident.id))
            Keychain.delete(account: Self.legacySecret)
            index = PGPIndex(identities: [ident], currentID: ident.id)
            Self.saveIndex(index)
        }

        identities = index.identities
        currentID = index.identities.contains { $0.id == index.currentID } ? index.currentID : (index.identities.first?.id ?? UUID())

        if identities.isEmpty {
            generate(name: "My key", email: "", algo: .curve25519)
        } else {
            loadCurrent()
        }
    }

    private func retryBootstrapIfNeeded() {
        if storeUnavailable { bootstrap() }
    }

    func archivedIdentities() -> [KeyArchive.ArchivedPgpIdentity]? {
        var out: [KeyArchive.ArchivedPgpIdentity] = []
        for ident in identities {
            guard case .found(let secret) = Keychain.loadStrict(account: Self.secretAccount(ident.id)),
                  let armored = String(data: secret, encoding: .utf8), !armored.isEmpty else { return nil }
            out.append(KeyArchive.ArchivedPgpIdentity(
                id: ident.id.uuidString, name: ident.name, email: ident.email,
                fingerprint: ident.fingerprint, algo: ident.algo,
                created: Int64(ident.createdAt.timeIntervalSince1970 * 1000),
                publicKey: ident.publicKey, secret: armored))
        }
        return out
    }

    func archivedRecipients() -> [KeyArchive.ArchivedPgpRecipient] {
        recipients.map {
            KeyArchive.ArchivedPgpRecipient(name: $0.name, publicKey: $0.publicKey, fingerprint: $0.fingerprint)
        }
    }

    @discardableResult
    func restore(identities list: [KeyArchive.ArchivedPgpIdentity],
                 recipients incoming: [KeyArchive.ArchivedPgpRecipient]) -> Bool {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { return false }
        for ident in identities { Keychain.delete(account: Self.secretAccount(ident.id)) }

        var restored: [PGPIdentity] = []
        var seen = Set<String>()
        for entry in list {
            guard seen.insert(entry.id).inserted,
                  let id = UUID(uuidString: entry.id), !entry.secret.isEmpty,
                  (try? ObjectivePGP.readKeys(from: Data(entry.secret.utf8)))?.first != nil,
                  Keychain.save(Data(entry.secret.utf8), account: Self.secretAccount(id)) else { continue }
            restored.append(PGPIdentity(id: id, name: entry.name, email: entry.email,
                                        fingerprint: entry.fingerprint, algo: entry.algo,
                                        createdAt: Date(timeIntervalSince1970: Double(entry.created) / 1000),
                                        publicKey: entry.publicKey))
        }

        recipients = incoming.map {
            PGPRecipient(name: $0.name, publicKey: $0.publicKey, fingerprint: $0.fingerprint)
        }
        saveRecipients()

        if restored.isEmpty {
            identities = []
            currentID = UUID()
            persistIndex()
            generate(name: "My key", email: "", algo: .curve25519)
            return false
        }
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
    private static func saveIndex(_ index: PGPIndex) {
        if let d = try? JSONEncoder().encode(index) { SharedStore.write(indexStoreKey, d) }
    }
    private func persistIndex() {
        guard !storeUnavailable else { return }
        Self.saveIndex(PGPIndex(identities: identities, currentID: currentID))
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
            currentKey = nil; myPublicKey = ""; ready = !identities.isEmpty
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
        let userID = PGPIdentity(id: UUID(), name: name, email: email, fingerprint: "", algo: algo.rawValue, createdAt: Date()).userID
        Task.detached(priority: .userInitiated) {
            let key = Self.generator(for: algo).generate(for: userID, passphrase: nil)
            let secret = Self.exportSecret(key)
            let pub = Self.exportPublicArmored(key)
            let fp = Self.fingerprint(of: key)
            await MainActor.run {
                let ident = PGPIdentity(id: UUID(), name: name, email: email, fingerprint: fp, algo: algo.label, createdAt: Date(), publicKey: pub)
                Keychain.save(secret, account: Self.secretAccount(ident.id))
                self.identities.append(ident)
                self.currentID = ident.id
                self.persistIndex()
                self.loadCurrent()
                self.busy = false
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
        let userID = ident.userID
        let id = ident.id
        Task.detached(priority: .userInitiated) {
            let key = Self.generator(for: algo).generate(for: userID, passphrase: nil)
            let secret = Self.exportSecret(key)
            let pub = Self.exportPublicArmored(key)
            let fp = Self.fingerprint(of: key)
            await MainActor.run {
                Keychain.save(secret, account: Self.secretAccount(id))
                if let idx = self.identities.firstIndex(where: { $0.id == id }) {
                    self.identities[idx].fingerprint = fp
                    self.identities[idx].algo = algo.label
                    self.identities[idx].createdAt = Date()
                    self.identities[idx].publicKey = pub
                }
                self.persistIndex()
                self.loadCurrent()
                self.busy = false
            }
        }
    }

    func deleteIdentity(_ id: UUID) {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { return }
        Keychain.delete(account: Self.secretAccount(id))
        identities.removeAll { $0.id == id }
        if identities.isEmpty {
            persistIndex()
            generate(name: "My key", email: "", algo: .curve25519)
            return
        }
        if currentID == id { currentID = identities[0].id }
        persistIndex()
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

    private func allRecipientKeys() -> [Key] {
        recipients.flatMap { (try? ObjectivePGP.readKeys(from: Data($0.publicKey.utf8))) ?? [] }
    }

    func encrypt(_ text: String, to recipient: PGPRecipient) throws -> String {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { throw PGPError.storageUnavailable }
        guard let currentKey else { throw PGPError.notReady }
        guard let recipientKeys = try? ObjectivePGP.readKeys(from: Data(recipient.publicKey.utf8)), !recipientKeys.isEmpty else {
            throw PGPError.badKey
        }
        let encrypted = try ObjectivePGP.encrypt(Data(text.utf8), addSignature: true,
                                                 using: recipientKeys + [currentKey], passphraseForKey: nil)
        return Armor.armored(encrypted, as: .message)
    }

    private static let maxMessageBytes = 8 * 1024 * 1024

    func decrypt(_ armored: String) throws -> (text: String, verification: PGPVerification) {
        retryBootstrapIfNeeded()
        guard !storeUnavailable else { throw PGPError.storageUnavailable }
        guard let currentKey else { throw PGPError.notReady }
        guard armored.utf8.count <= Self.maxMessageBytes else { throw PGPError.badMessage }
        let binary: Data
        if let blocks = try? Armor.convertArmoredMessage2BinaryBlocks(whenNecessary: Data(armored.utf8)), let b = blocks.first {
            binary = b
        } else {
            binary = Data(armored.utf8)
        }
        let verifyKeys = [currentKey] + allRecipientKeys()
        var verifiedCode: Int32 = -1
        var decryptionError: NSError?
        if let plain = try? ObjectivePGP.decrypt(binary, verified: &verifiedCode, certifyWithRootKey: false,
                                                 using: verifyKeys, passphraseForKey: nil,
                                                 decryptionError: &decryptionError),
           decryptionError == nil {
            return (String(decoding: plain, as: UTF8.self), verifiedCode == 0 ? .verified : .unverified)
        }
        guard let plain = try? ObjectivePGP.decrypt(binary, andVerifySignature: false, using: [currentKey], passphraseForKey: nil) else {
            throw PGPError.badMessage
        }
        let verified = Self.verificationCode(binary, keys: verifyKeys) == 0
        return (String(decoding: plain, as: UTF8.self), verified ? .verified : .unverified)
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

    nonisolated private static func verificationCode(_ data: Data, keys: [Key]) -> Int32 {
        var verified: Int32 = -1
        var decErr: NSError?
        _ = try? ObjectivePGP.decrypt(data, verified: &verified, certifyWithRootKey: false, using: keys, passphraseForKey: nil, decryptionError: &decErr)
        return verified
    }

}
