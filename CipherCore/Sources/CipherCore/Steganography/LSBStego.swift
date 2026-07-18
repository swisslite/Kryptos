import Foundation

public enum LSBStego {
    static let magic: [UInt8] = [0x4B, 0x58, 0x53, 0x31]
    private static let headerByteCount = 8

    public static func capacity(pixelCount: Int) -> Int {
        max(0, (pixelCount * 3) / 8 - headerByteCount)
    }

    public static func embed(payload: Data, intoRGBA pixels: [UInt8]) throws -> [UInt8] {
        guard pixels.count % 4 == 0 else { throw CipherError.invalidInput }
        let pixelCount = pixels.count / 4

        var stream = Data(magic)
        let len = UInt32(payload.count)
        stream.append(UInt8((len >> 24) & 0xFF))
        stream.append(UInt8((len >> 16) & 0xFF))
        stream.append(UInt8((len >> 8) & 0xFF))
        stream.append(UInt8(len & 0xFF))
        stream.append(payload)

        let totalBits = stream.count * 8
        let capacityBits = pixelCount * 3
        guard totalBits <= capacityBits else { throw CipherError.stegoCapacityExceeded }

        var out = pixels
        var bitIndex = 0
        outer: for p in 0 ..< pixelCount {
            for channel in 0 ..< 3 {
                if bitIndex >= totalBits { break outer }
                let byte = stream[stream.startIndex + bitIndex / 8]
                let bit = (byte >> (7 - (bitIndex % 8))) & 1
                let idx = p * 4 + channel
                out[idx] = (out[idx] & 0xFE) | bit
                bitIndex += 1
            }
        }
        return out
    }

    public static func extract(fromRGBA pixels: [UInt8]) throws -> Data {
        guard pixels.count % 4 == 0 else { throw CipherError.invalidInput }
        let channelCount = (pixels.count / 4) * 3

        func bit(at i: Int) -> UInt8 {
            let pixel = i / 3
            let channel = i % 3
            return pixels[pixel * 4 + channel] & 1
        }

        func readBytes(fromBit offset: Int, count: Int) throws -> [UInt8] {
            guard count >= 0, offset + count * 8 <= channelCount else { throw CipherError.noHiddenData }
            var result = [UInt8](repeating: 0, count: count)
            for j in 0 ..< count * 8 {
                let b = bit(at: offset + j)
                result[j / 8] |= (b << (7 - (j % 8)))
            }
            return result
        }

        let header = try readBytes(fromBit: 0, count: headerByteCount)
        guard Array(header.prefix(4)) == magic else { throw CipherError.noHiddenData }
        let length = (Int(header[4]) << 24) | (Int(header[5]) << 16) | (Int(header[6]) << 8) | Int(header[7])
        let payload = try readBytes(fromBit: headerByteCount * 8, count: length)
        return Data(payload)
    }
}
