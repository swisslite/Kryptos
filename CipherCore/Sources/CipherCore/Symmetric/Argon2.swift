import Foundation
import CArgon2

public enum Argon2id {
    public static let profileVersion: UInt8 = 1
    public static let memoryKiB: UInt32 = 65536
    public static let iterations: UInt32 = 3
    public static let lanes: UInt32 = 1
    public static let minSaltLength = 16

    public static func derive(password: Data, salt: Data, length: Int) throws -> [UInt8] {
        guard salt.count >= minSaltLength else { throw CipherError.invalidInput }
        return try hash(password: password, salt: salt, memoryKiB: memoryKiB,
                        iterations: iterations, lanes: lanes, length: length)
    }

    public static func hash(password: Data, salt: Data, memoryKiB: UInt32,
                            iterations: UInt32, lanes: UInt32, length: Int) throws -> [UInt8] {
        guard length > 0 else { throw CipherError.invalidInput }
        var out = [UInt8](repeating: 0, count: length)
        var pwd = [UInt8](password)
        let pwdLength = pwd.count
        if pwd.isEmpty { pwd = [0] }
        let status: Int32 = pwd.withUnsafeBufferPointer { p in
            salt.withUnsafeBytes { s in
                out.withUnsafeMutableBufferPointer { o in
                    argon2id_hash_raw(iterations, memoryKiB, lanes,
                                      p.baseAddress, pwdLength,
                                      s.baseAddress, salt.count,
                                      o.baseAddress, length)
                }
            }
        }
        zero(&pwd)
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
