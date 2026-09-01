import Foundation
import CipherCore

enum LockCodeResult: Sendable { case ok, tooShort, duplicate, failed }

struct LockCode: Sendable {
    static let minLength = 4
    private static let saltLength = 16
    private static let hashLength = 32
    private static let blobLength = saltLength + hashLength

    enum Presence: Sendable { case set, absent, unavailable }
    enum Check: Sendable { case matched, rejected, unavailable }

    let storeKey: String

    var presence: Presence {
        switch SharedStore.readStrict(storeKey) {
        case .found(let data): return data.count == Self.blobLength ? .set : .absent
        case .absent: return .absent
        case .unavailable: return .unavailable
        }
    }

    func clear() { SharedStore.delete(storeKey) }

    func check(_ code: String) -> Check {
        guard code.count >= Self.minLength else { return .rejected }
        let stored: Data?
        switch SharedStore.readStrict(storeKey) {
        case .found(let data): stored = data.count == Self.blobLength ? data : nil
        case .absent: stored = nil
        case .unavailable: return .unavailable
        }
        let bytes = stored.map { [UInt8]($0) } ?? [UInt8](repeating: 0, count: Self.blobLength)
        guard var digest = try? Argon2id.derive(password: code,
                                                salt: Data(bytes[0 ..< Self.saltLength]),
                                                length: Self.hashLength) else { return .rejected }
        defer { Argon2id.zero(&digest) }
        let equal = Self.constantTimeEqual(digest, Array(bytes[Self.saltLength ..< Self.blobLength]))
        return stored != nil && equal ? .matched : .rejected
    }

    func store(_ code: String, mustDifferFrom other: LockCode) -> LockCodeResult {
        guard code.count >= Self.minLength else { return .tooShort }
        switch other.check(code) {
        case .matched: return .duplicate
        case .unavailable: return .failed
        case .rejected: break
        }
        let salt = randomBytes(Self.saltLength)
        guard var digest = try? Argon2id.derive(password: code, salt: salt, length: Self.hashLength) else {
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

enum LockThrottle {
    private static let storeKey = "lock.failures"
    private static let cap = 1 << 20
    private static let freeAttempts = 4
    private static let maxDelay = 30

    static var failures: Int {
        guard let data = SharedStore.read(storeKey), data.count == 4 else { return 0 }
        let value = data.withUnsafeBytes { UInt32(bigEndian: $0.loadUnaligned(as: UInt32.self)) }
        return Int(min(value, UInt32(cap)))
    }

    static func delaySeconds(failures: Int) -> Int {
        let over = failures - freeAttempts
        guard over > 0 else { return 0 }
        return min(maxDelay, 1 << min(over - 1, 5))
    }

    static var pendingDelay: Int { delaySeconds(failures: failures) }

    static func recordFailure() {
        let next = UInt32(min(failures + 1, cap)).bigEndian
        SharedStore.write(storeKey, withUnsafeBytes(of: next) { Data($0) })
    }

    static func reset() { SharedStore.delete(storeKey) }
}

enum LockCodes {
    static let app = LockCode(storeKey: "appcode")
    static let panic = LockCode(storeKey: "panic")

    enum Outcome: Sendable { case rejected, unlocked, wiped, unavailable }

    struct State: Sendable {
        let app: LockCode.Presence
        let panic: LockCode.Presence
        var readable: Bool { app != .unavailable && panic != .unavailable }
    }

    static func classify(_ code: String) -> Outcome {
        guard code.count >= LockCode.minLength else { return .rejected }
        let panicResult = panic.check(code)
        let appResult = app.check(code)
        if panicResult == .matched { return .wiped }
        if appResult == .matched { return .unlocked }
        if panicResult == .unavailable || appResult == .unavailable { return .unavailable }
        return .rejected
    }

    static func classifyOffMain(_ code: String) async -> Outcome {
        let delay = LockThrottle.pendingDelay
        if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
        let outcome = await Task.detached(priority: .userInitiated) { classify(code) }.value
        if outcome == .rejected { LockThrottle.recordFailure() }
        return outcome
    }

    static func stateOffMain() async -> State {
        await Task.detached(priority: .userInitiated) {
            State(app: app.presence, panic: panic.presence)
        }.value
    }

    static func storeOffMain(_ code: String, into target: LockCode, other: LockCode) async -> LockCodeResult {
        await Task.detached(priority: .userInitiated) { target.store(code, mustDifferFrom: other) }.value
    }
}
