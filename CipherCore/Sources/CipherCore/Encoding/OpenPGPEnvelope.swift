import Foundation

public enum OpenPGPEnvelope {
    public struct Shape: Sendable, Equatable {
        public let encrypted: Bool
        public let sessionKeys: Int
        public let compressedLayers: Int
        public let truncated: Bool
    }

    private enum Tag {
        static let publicKeySessionKey: UInt8 = 1
        static let symmetricSessionKey: UInt8 = 3
        static let symmetricallyEncrypted: UInt8 = 9
        static let compressed: UInt8 = 8
        static let integrityProtected: UInt8 = 18
    }

    private struct Piece {
        let length: Int
        let partial: Bool
    }

    private static let maxPackets = 4096
    private static let maxLengthHeaders = 65536

    public static func isEncrypted(_ binary: Data) -> Bool { shape(of: binary).encrypted }

    public static func shape(of binary: Data) -> Shape {
        let bytes = [UInt8](binary)
        var index = 0
        var encrypted = false
        var sessionKeys = 0
        var compressedLayers = 0
        var truncated = false
        var packets = 0
        var headers = 0

        walk: while index < bytes.count, packets < maxPackets {
            let header = bytes[index]
            guard header & 0x80 != 0 else {
                truncated = true
                break walk
            }
            packets += 1
            index += 1
            let isNewFormat = header & 0x40 != 0
            let tag = isNewFormat ? header & 0x3F : (header >> 2) & 0x0F

            switch tag {
            case Tag.symmetricallyEncrypted, Tag.integrityProtected: encrypted = true
            case Tag.publicKeySessionKey, Tag.symmetricSessionKey: sessionKeys += 1
            case Tag.compressed: compressedLayers += 1
            default: break
            }

            while true {
                headers += 1
                guard headers <= maxLengthHeaders else {
                    truncated = true
                    break walk
                }
                let piece = isNewFormat
                    ? newFormatLength(bytes, &index)
                    : oldFormatLength(bytes, &index, type: header & 0x03)
                guard let piece, piece.length >= 0, bytes.count - index >= piece.length else {
                    truncated = true
                    break walk
                }
                index += piece.length
                if !piece.partial { break }
            }
        }

        return Shape(encrypted: encrypted, sessionKeys: sessionKeys,
                     compressedLayers: compressedLayers, truncated: truncated)
    }

    private static func oldFormatLength(_ bytes: [UInt8], _ index: inout Int, type: UInt8) -> Piece? {
        switch type {
        case 0:
            guard index < bytes.count else { return nil }
            let n = Int(bytes[index]); index += 1
            return Piece(length: n, partial: false)
        case 1:
            guard index + 1 < bytes.count else { return nil }
            let n = Int(bytes[index]) << 8 | Int(bytes[index + 1]); index += 2
            return Piece(length: n, partial: false)
        case 2:
            guard index + 3 < bytes.count else { return nil }
            let n = Int(bytes[index]) << 24 | Int(bytes[index + 1]) << 16
                | Int(bytes[index + 2]) << 8 | Int(bytes[index + 3])
            index += 4
            return Piece(length: n, partial: false)
        default:
            return Piece(length: bytes.count - index, partial: false)
        }
    }

    private static func newFormatLength(_ bytes: [UInt8], _ index: inout Int) -> Piece? {
        guard index < bytes.count else { return nil }
        let first = bytes[index]
        index += 1
        if first < 192 { return Piece(length: Int(first), partial: false) }
        if first < 224 {
            guard index < bytes.count else { return nil }
            let n = (Int(first) - 192) << 8 | Int(bytes[index]) + 192
            index += 1
            return Piece(length: n, partial: false)
        }
        if first == 255 {
            guard index + 3 < bytes.count else { return nil }
            let n = Int(bytes[index]) << 24 | Int(bytes[index + 1]) << 16
                | Int(bytes[index + 2]) << 8 | Int(bytes[index + 3])
            index += 4
            return Piece(length: n, partial: false)
        }
        return Piece(length: 1 << Int(first & 0x1F), partial: true)
    }
}
