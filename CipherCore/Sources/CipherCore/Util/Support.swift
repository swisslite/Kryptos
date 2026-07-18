import Foundation

public enum CipherError: Error, Equatable {
    case malformed
    case notAKryptosMessage
    case decryptionFailed
    case stegoCapacityExceeded
    case noHiddenData
    case invalidInput
}

public func randomBytes(_ count: Int) -> Data {
    var generator = SystemRandomNumberGenerator()
    var bytes = [UInt8]()
    bytes.reserveCapacity(count)
    for _ in 0 ..< count { bytes.append(UInt8.random(in: UInt8.min ... UInt8.max, using: &generator)) }
    return Data(bytes)
}

public struct BinaryWriter {
    public private(set) var data = Data()
    public init() {}

    public mutating func writeByte(_ b: UInt8) { data.append(b) }

    public mutating func writeUInt32(_ v: UInt32) {
        data.append(UInt8((v >> 24) & 0xFF))
        data.append(UInt8((v >> 16) & 0xFF))
        data.append(UInt8((v >> 8) & 0xFF))
        data.append(UInt8(v & 0xFF))
    }

    public mutating func writeRaw(_ d: Data) { data.append(d) }

    public mutating func writeVar(_ d: Data) {
        writeUInt32(UInt32(d.count))
        data.append(d)
    }
}

public struct BinaryReader {
    private let bytes: [UInt8]
    private var index = 0

    public init(_ data: Data) { self.bytes = [UInt8](data) }

    public mutating func readByte() throws -> UInt8 {
        guard index < bytes.count else { throw CipherError.malformed }
        defer { index += 1 }
        return bytes[index]
    }

    public mutating func readUInt32() throws -> UInt32 {
        let b = try readRaw(4)
        let a = [UInt8](b)
        return (UInt32(a[0]) << 24) | (UInt32(a[1]) << 16) | (UInt32(a[2]) << 8) | UInt32(a[3])
    }

    public mutating func readRaw(_ n: Int) throws -> Data {
        guard n >= 0, index + n <= bytes.count else { throw CipherError.malformed }
        defer { index += n }
        return Data(bytes[index ..< index + n])
    }

    public mutating func readVar() throws -> Data {
        let n = try readUInt32()
        return try readRaw(Int(n))
    }
}
