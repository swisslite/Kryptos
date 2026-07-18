import Foundation
import CryptoKit
import CipherCore

struct Profile: Codable, Identifiable, Hashable {
    var id: UUID
    var name: String
}

struct Contact: Codable, Identifiable, Hashable {
    var fingerprint: String
    var displayName: String
    var id: String { fingerprint }

    var safetyNumber: String { SignalFormat.safetyNumber(fromHex: fingerprint) }
}

struct ChatMessage: Codable, Identifiable, Hashable {
    var id = UUID()
    var text: String
    var mine: Bool
    var date = Date()
}

enum SignalFormat {
    static func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    static func safetyNumber(fromHex fp: String) -> String {
        stride(from: 0, to: min(fp.count, 24), by: 4).map {
            let s = fp.index(fp.startIndex, offsetBy: $0)
            let e = fp.index(s, offsetBy: 4, limitedBy: fp.endIndex) ?? fp.endIndex
            return String(fp[s ..< e])
        }.joined(separator: " ").uppercased()
    }
}

struct BundlePayload: Codable {
    var registrationId: UInt32
    var deviceId: UInt32
    var identityKey: Data
    var signedPreKeyId: UInt32
    var signedPreKey: Data
    var signedPreKeySignature: Data
    var kyberPreKeyId: UInt32
    var kyberPreKey: Data
    var kyberPreKeySignature: Data
    var oneTimePreKeyId: UInt32?
    var oneTimePreKey: Data?
}

struct RetiredPreKeyGen: Codable {
    var signedPreKeyId: UInt32
    var kyberPreKeyId: UInt32
    var retiredAt: Date
}

struct CachedDecrypt: Codable {
    var fingerprint: String
    var text: String
    var date: Date
}

enum DecryptCacheKey {
    static func key(for armored: String) -> String {
        let payload = TextStego.decode(armored)
            ?? SmartTextStego.decode(armored)
            ?? WireFormat.tokenBytes(armored)
            ?? Data(armored.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        return SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
    }
}

enum OwnCipherMarker {
    private static let storeKey = "clip.own.hash"

    static func mark(_ text: String) {
        SharedStore.write(storeKey, Data(DecryptCacheKey.key(for: text).utf8))
    }

    static func matches(_ text: String) -> Bool {
        guard let d = SharedStore.read(storeKey), let h = String(data: d, encoding: .utf8) else { return false }
        return h == DecryptCacheKey.key(for: text)
    }
}

struct Meta: Codable {
    var registrationId: UInt32
    var signedPreKeyId: UInt32
    var signedPreKeyPub: Data
    var signedPreKeySig: Data
    var kyberPreKeyId: UInt32
    var kyberPreKeyPub: Data
    var kyberPreKeySig: Data
    var contacts: [Contact] = []
    var messages: [String: [ChatMessage]] = [:]

    var decryptCache: [String: CachedDecrypt]?

    var prekeyCreatedAt: Date?
    var retiredPreKeyGens: [RetiredPreKeyGen]?
    var nextSignedPreKeyId: UInt32?
    var nextKyberPreKeyId: UInt32?
    var nextOneTimePreKeyId: UInt32?
    var oneTimePreKeyIds: [UInt32]?

    var autoDelete: [String: Double]?

    mutating func rememberDecrypt(armored: String, fingerprint: String, text: String) {
        var cache = decryptCache ?? [:]
        cache[DecryptCacheKey.key(for: armored)] = CachedDecrypt(fingerprint: fingerprint, text: text, date: Date())
        let cap = 64
        if cache.count > cap {
            for (key, _) in cache.sorted(by: { $0.value.date < $1.value.date }).prefix(cache.count - cap) {
                cache.removeValue(forKey: key)
            }
        }
        decryptCache = cache
    }

    func cachedDecrypt(for armored: String) -> CachedDecrypt? {
        decryptCache?[DecryptCacheKey.key(for: armored)]
    }

    mutating func purgeDecryptCache(fingerprint: String? = nil, olderThan age: TimeInterval? = nil) {
        guard var cache = decryptCache, !cache.isEmpty else { return }
        let now = Date()
        cache = cache.filter { _, entry in
            if let fingerprint, entry.fingerprint != fingerprint { return true }
            if let age { return now.timeIntervalSince(entry.date) < age }
            return false
        }
        decryptCache = cache.isEmpty ? nil : cache
    }
}

enum AutoDeletePreset: CaseIterable, Identifiable {
    case off, s30, m5, h1, h8, d1, w1

    var id: String { title }

    var seconds: TimeInterval? {
        switch self {
        case .off: return nil
        case .s30: return 30
        case .m5: return 5 * 60
        case .h1: return 60 * 60
        case .h8: return 8 * 60 * 60
        case .d1: return 24 * 60 * 60
        case .w1: return 7 * 24 * 60 * 60
        }
    }

    var title: String {
        switch self {
        case .off: return String(localized: "Off")
        case .s30: return String(localized: "30 seconds")
        case .m5: return String(localized: "5 minutes")
        case .h1: return String(localized: "1 hour")
        case .h8: return String(localized: "8 hours")
        case .d1: return String(localized: "1 day")
        case .w1: return String(localized: "1 week")
        }
    }

    static func matching(_ seconds: TimeInterval?) -> AutoDeletePreset {
        guard let seconds, seconds > 0 else { return .off }
        return allCases.first { $0.seconds == seconds } ?? .off
    }
}

struct ProfilesIndex: Codable {
    var profiles: [Profile]
    var currentID: UUID
}

enum SignalServiceError: LocalizedError {
    case badKeyString
    case decryptedForOtherContact(String)
    case storageUnavailable

    var errorDescription: String? {
        switch self {
        case .badKeyString:
            return String(localized: "This is not a valid Kryptos key.")
        case .decryptedForOtherContact(let name):
            return String(localized: "This message is from another contact: \(name).")
        case .storageUnavailable:
            return String(localized: "Secure storage is unavailable right now. Try again in a moment.")
        }
    }
}
