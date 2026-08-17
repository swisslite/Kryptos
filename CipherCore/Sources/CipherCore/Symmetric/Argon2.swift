import Foundation
import CArgon2

public enum Argon2id {
    public static let profileVersion: UInt8 = 1
    public static let memoryKiB: UInt32 = 65536
    public static let iterations: UInt32 = 3
    public static let lanes: UInt32 = 1
    public static let minSaltLength = 16

    public static func derive(password: Data, salt: Data, length: Int) throws -> [UInt8] {
        var bytes = [UInt8](password)
        defer { zero(&bytes) }
        return try derive(passwordBytes: bytes, salt: salt, length: length)
    }

    public static func derive(password: String, salt: Data, length: Int) throws -> [UInt8] {
        var bytes = [UInt8](password.utf8)
        defer { zero(&bytes) }
        return try derive(passwordBytes: bytes, salt: salt, length: length)
    }

    static func derive(passwordBytes: [UInt8], salt: Data, length: Int) throws -> [UInt8] {
        guard salt.count >= minSaltLength else { throw CipherError.invalidInput }
        return try hash(passwordBytes: passwordBytes, salt: salt, memoryKiB: memoryKiB,
                        iterations: iterations, lanes: lanes, length: length)
    }

    public static func hash(password: Data, salt: Data, memoryKiB: UInt32,
                            iterations: UInt32, lanes: UInt32, length: Int) throws -> [UInt8] {
        var bytes = [UInt8](password)
        defer { zero(&bytes) }
        return try hash(passwordBytes: bytes, salt: salt, memoryKiB: memoryKiB,
                        iterations: iterations, lanes: lanes, length: length)
    }

    static func hash(passwordBytes: [UInt8], salt: Data, memoryKiB: UInt32,
                     iterations: UInt32, lanes: UInt32, length: Int) throws -> [UInt8] {
        guard length > 0 else { throw CipherError.invalidInput }
        var out = [UInt8](repeating: 0, count: length)
        let pwdLength = passwordBytes.count
        var scratch = passwordBytes.isEmpty ? [0] : passwordBytes
        let status: Int32 = scratch.withUnsafeBufferPointer { p in
            salt.withUnsafeBytes { s in
                out.withUnsafeMutableBufferPointer { o in
                    argon2id_hash_raw(iterations, memoryKiB, lanes,
                                      p.baseAddress, pwdLength,
                                      s.baseAddress, salt.count,
                                      o.baseAddress, length)
                }
            }
        }
        if passwordBytes.isEmpty { zero(&scratch) }
        guard status == ARGON2_OK.rawValue else {
            zero(&out)
            throw CipherError.invalidInput
        }
        return out
    }

    public static func zero(_ bytes: inout [UInt8]) {
        bytes.withUnsafeMutableBufferPointer { buffer in
            guard let base = buffer.baseAddress else { return }
            memset_s(base, buffer.count, 0, buffer.count)
        }
    }
}
