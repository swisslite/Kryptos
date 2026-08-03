import Foundation
import CipherCore

struct KeyArchive: Codable {
    static let magic = "keys"
    static let version = 1
    static let minPasswordLength = 8

    var kryptos: String
    var v: Int
    var created: Int64
    var profiles: [ArchivedProfile]
    var pgpIdentities: [ArchivedPgpIdentity]
    var pgpRecipients: [ArchivedPgpRecipient]

    struct ArchivedProfile: Codable {
        var id: String
        var name: String
        var identity: String
        var registrationId: Int64
        var signedPreKeyId: Int64
        var signedPreKeyPub: String
        var signedPreKeySig: String
        var kyberPreKeyId: Int64
        var kyberPreKeyPub: String
        var kyberPreKeySig: String
        var prekeyCreatedAt: Int64?
        var nextSignedPreKeyId: Int64
        var nextKyberPreKeyId: Int64
        var nextOneTimePreKeyId: Int64
        var oneTimePreKeyIds: [Int64]
        var retired: [ArchivedRetired]
        var autoDelete: [String: Double]
        var contacts: [ArchivedContact]
        var preKeys: [String: String]
        var signedPreKeys: [String: String]
        var kyberPreKeys: [String: String]
        var sessions: [String: String]
        var identities: [String: String]
    }

    struct ArchivedRetired: Codable {
        var signedPreKeyId: Int64
        var kyberPreKeyId: Int64
        var retiredAt: Int64
    }

    struct ArchivedContact: Codable {
        var fingerprint: String
        var displayName: String
    }

    struct ArchivedPgpIdentity: Codable {
        var id: String
        var name: String
        var email: String
        var fingerprint: String
        var algo: String
        var created: Int64
        var publicKey: String
        var secret: String
    }

    struct ArchivedPgpRecipient: Codable {
        var name: String
        var publicKey: String
        var fingerprint: String
    }

    var isEmpty: Bool { profiles.isEmpty && pgpIdentities.isEmpty && pgpRecipients.isEmpty }

    var contactCount: Int { profiles.reduce(0) { $0 + $1.contacts.count } }
}

enum KeyArchiveError: LocalizedError {
    case passwordTooShort
    case unreadable
    case nothingToExport
    case writeFailed

    var errorDescription: String? {
        switch self {
        case .passwordTooShort:
            return String(localized: "Use at least 8 characters for the backup password.")
        case .unreadable:
            return String(localized: "Could not read this backup — wrong password, or the file is not a Kryptos key backup.")
        case .nothingToExport:
            return String(localized: "There are no keys to export yet.")
        case .writeFailed:
            return String(localized: "Could not create the backup file.")
        }
    }
}

extension KeyArchive {
    static func make(profiles: [ArchivedProfile],
                     pgpIdentities: [ArchivedPgpIdentity],
                     pgpRecipients: [ArchivedPgpRecipient]) -> KeyArchive {
        KeyArchive(kryptos: magic, v: version,
                   created: Int64(Date().timeIntervalSince1970 * 1000),
                   profiles: profiles, pgpIdentities: pgpIdentities, pgpRecipients: pgpRecipients)
    }

    func sealed(password: String) throws -> String {
        guard password.count >= KeyArchive.minPasswordLength else { throw KeyArchiveError.passwordTooShort }
        guard !isEmpty else { throw KeyArchiveError.nothingToExport }
        var plain = [UInt8](try JSONEncoder().encode(self))
        defer { Argon2id.zero(&plain) }
        let raw = try PasswordCipher.encrypt(Data(plain), password: password)
        return WireFormat.token(raw)
    }

    static func opened(_ text: String, password: String) throws -> KeyArchive {
        guard let raw = WireFormat.tokenBytes(text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let plain = try? PasswordCipher.decrypt(raw, password: password),
              let archive = try? JSONDecoder().decode(KeyArchive.self, from: plain),
              archive.kryptos == magic, archive.v == version else { throw KeyArchiveError.unreadable }
        guard !archive.isEmpty else { throw KeyArchiveError.unreadable }
        return archive
    }
}
