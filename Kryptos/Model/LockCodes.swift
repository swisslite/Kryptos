import Foundation
import CipherCore

enum LockCodeResult: Sendable { case ok, tooShort, duplicate, failed }

struct LockCode: Sendable {
    static let minLength = 4
    private static let saltLength = 16
    private static let hashLength = 32
    private static let blobLength = saltLength + hashLength

    let storeKey: String

    var isSet: Bool { SharedStore.read(storeKey)?.count == Self.blobLength }

    func clear() { SharedStore.delete(storeKey) }

    func matches(_ code: String) -> Bool {
        guard code.count >= Self.minLength else { return false }
        let stored = SharedStore.read(storeKey)
        let present = stored?.count == Self.blobLength
        let bytes = present ? [UInt8](stored!) : [UInt8](repeating: 0, count: Self.blobLength)
        guard var digest = try? Argon2id.derive(password: Data(code.utf8),
                                                salt: Data(bytes[0 ..< Self.saltLength]),
                                                length: Self.hashLength) else { return false }
        defer { Argon2id.zero(&digest) }
        let equal = Self.constantTimeEqual(digest, Array(bytes[Self.saltLength ..< Self.blobLength]))
        return present && equal
    }

    func store(_ code: String, mustDifferFrom other: LockCode) -> LockCodeResult {
        guard code.count >= Self.minLength else { return .tooShort }
        if other.matches(code) { return .duplicate }
        let salt = randomBytes(Self.saltLength)
        guard var digest = try? Argon2id.derive(password: Data(code.utf8), salt: salt, length: Self.hashLength) else {
            return .failed
        }
        defer { Argon2id.zero(&digest) }
        return SharedStore.write(storeKey, salt + Data(digest)) ? .ok : .failed
    }

    private static func constantTimeEqual(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0 ..< a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }
}

enum LockCodes {
    static let app = LockCode(storeKey: "appcode")
    static let panic = LockCode(storeKey: "panic")

    enum Outcome: Sendable { case rejected, unlocked, wiped }

    static func classify(_ code: String) -> Outcome {
        guard code.count >= LockCode.minLength else { return .rejected }
        let panicMatched = panic.matches(code)
        let appMatched = app.matches(code)
        if panicMatched { return .wiped }
        if appMatched { return .unlocked }
        return .rejected
    }

    static func classifyOffMain(_ code: String) async -> Outcome {
        await Task.detached(priority: .userInitiated) { classify(code) }.value
    }

    static func storeOffMain(_ code: String, into target: LockCode, other: LockCode) async -> LockCodeResult {
        await Task.detached(priority: .userInitiated) { target.store(code, mustDifferFrom: other) }.value
    }
}
