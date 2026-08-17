import Foundation
import CryptoKit

struct KeyStream {
    private let key: SymmetricKey
    private let label: Data
    private var block = [UInt8]()
    private var offset = 0
    private var counter: UInt32 = 0

    init(key: [UInt8], label: String) {
        self.key = SymmetricKey(data: key)
        self.label = Data(label.utf8)
    }

    private mutating func refill() {
        var input = label
        input.append(UInt8((counter >> 24) & 0xFF))
        input.append(UInt8((counter >> 16) & 0xFF))
        input.append(UInt8((counter >> 8) & 0xFF))
        input.append(UInt8(counter & 0xFF))
        block = Array(HMAC<SHA256>.authenticationCode(for: input, using: key))
        offset = 0
        counter &+= 1
    }

    mutating func byte() -> UInt8 {
        if offset >= block.count { refill() }
        defer { offset += 1 }
        return block[offset]
    }

    mutating func uint32() -> UInt32 {
        var v: UInt32 = 0
        for _ in 0 ..< 4 { v = (v << 8) | UInt32(byte()) }
        return v
    }

    mutating func index(_ modulus: Int) -> Int {
        guard modulus > 1 else { return 0 }
        let m = Int64(modulus)
        let bound = ((Int64(1) << 32) / m) * m
        var v = Int64(uint32())
        while v >= bound { v = Int64(uint32()) }
        return Int(v % m)
    }
}

public enum ImageStego {
    public static let containerVersion: UInt8 = 2
    static let saltBits = PasswordCipher.saltLength * 8
    static let lengthBits = 32
    static let selectionPercent = 25
    static let placementKeyLength = 32
    static let measureCap = 255

    static func candidates(rgba: [UInt8], width: Int, height: Int) throws -> [Int32] {
        guard width > 2, height > 2, rgba.count == width * height * 4 else { throw CipherError.invalidInput }
        let count = width * height
        var measures = [UInt8](repeating: 0, count: count)
        var histogram = [Int](repeating: 0, count: measureCap + 1)
        var interior = 0
        rgba.withUnsafeBufferPointer { px in
            measures.withUnsafeMutableBufferPointer { out in
                for y in 1 ..< height - 1 {
                    for x in 1 ..< width - 1 {
                        let p = y * width + x
                        let base = p * 4
                        var m = 0
                        for c in 0 ..< 2 {
                            let v = Int(px[base + c])
                            m += abs(v - Int(px[(p - 1) * 4 + c]))
                            m += abs(v - Int(px[(p + 1) * 4 + c]))
                            m += abs(v - Int(px[(p - width) * 4 + c]))
                            m += abs(v - Int(px[(p + width) * 4 + c]))
                        }
                        if m > measureCap { m = measureCap }
                        out[p] = UInt8(m)
                        histogram[m] += 1
                        interior += 1
                    }
                }
            }
        }
        let limit = max(1, (interior * selectionPercent) / 100)
        var threshold = measureCap
        var running = histogram[measureCap]
        var t = measureCap - 1
        while t >= 1 {
            if running + histogram[t] > limit { break }
            running += histogram[t]
            threshold = t
            t -= 1
        }
        guard running > 0 else { return [] }
        var list = [Int32]()
        list.reserveCapacity(running)
        for y in 1 ..< height - 1 {
            for x in 1 ..< width - 1 {
                let p = y * width + x
                if Int(measures[p]) >= threshold { list.append(Int32(p * 4 + 2)) }
            }
        }
        return list
    }

    static func publicStream(width: Int, height: Int, total: Int, label: String) -> KeyStream {
        var seed = Data("kryptos/stego/v2/pub".utf8)
        for value in [width, height, total] {
            let v = UInt32(truncatingIfNeeded: value)
            seed.append(UInt8((v >> 24) & 0xFF))
            seed.append(UInt8((v >> 16) & 0xFF))
            seed.append(UInt8((v >> 8) & 0xFF))
            seed.append(UInt8(v & 0xFF))
        }
        return KeyStream(key: Array(SHA256.hash(data: seed)), label: label)
    }

    static func place(count: Int, total: Int, used: inout [Bool], stream: inout KeyStream) throws -> [Int] {
        guard count > 0, total > 0, count <= total else { throw CipherError.stegoCapacityExceeded }
        var slots = [Int]()
        slots.reserveCapacity(count)
        var cursor = 0
        for i in 0 ..< count {
            let start = Int((Int64(i) * Int64(total)) / Int64(count))
            var end = Int((Int64(i + 1) * Int64(total)) / Int64(count))
            if end <= start { end = start + 1 }
            if end > total { end = total }
            let span = end - start
            var idx = start + stream.index(span)
            var probes = 0
            while used[idx] && probes < span {
                idx = start + ((idx - start + 1) % span)
                probes += 1
            }
            if used[idx] {
                while cursor < total && used[cursor] { cursor += 1 }
                guard cursor < total else { throw CipherError.stegoCapacityExceeded }
                idx = cursor
            }
            used[idx] = true
            slots.append(idx)
        }
        return slots
    }

    static func apply(_ pixels: inout [UInt8], index: Int, bit: UInt8, stream: inout KeyStream) {
        let v = pixels[index]
        if (v & 1) == bit { return }
        if v == 0 { pixels[index] = 1; return }
        if v == 255 { pixels[index] = 254; return }
        pixels[index] = (stream.byte() & 1) == 0 ? v &- 1 : v &+ 1
    }

    public static func capacity(rgba: [UInt8], width: Int, height: Int) -> Int {
        guard let list = try? candidates(rgba: rgba, width: width, height: height) else { return 0 }
        let bits = list.count - saltBits - lengthBits
        guard bits > 0 else { return 0 }
        return max(0, bits / 8 - PasswordCipher.tagLength - 1)
    }

    public static func hide(_ message: Data, password: String, rgba: [UInt8],
                            width: Int, height: Int) throws -> [UInt8] {
        var out = rgba
        try hideInto(&out, message: message, password: password, width: width, height: height)
        return out
    }

    public static func hideInto(_ rgba: inout [UInt8], message: Data, password: String,
                                width: Int, height: Int) throws {
        let list = try candidates(rgba: rgba, width: width, height: height)
        let total = list.count
        guard total > saltBits + lengthBits else { throw CipherError.stegoCapacityExceeded }

        let salt = randomBytes(PasswordCipher.saltLength)
        var derived = try Argon2id.derive(password: password, salt: salt,
                                          length: PasswordCipher.derivedLength + placementKeyLength)
        defer { Argon2id.zero(&derived) }
        let (key, nonce) = try PasswordCipher.split(derived)
        var placementKey = [UInt8](derived[PasswordCipher.derivedLength ..< derived.count])
        defer { Argon2id.zero(&placementKey) }
        let sealed = try PasswordCipher.sealBody(message, key: key, nonce: nonce,
                                                 version: containerVersion, pad: false)
        let payloadBits = sealed.count * 8
        guard saltBits + lengthBits + payloadBits <= total else { throw CipherError.stegoCapacityExceeded }

        var used = [Bool](repeating: false, count: total)
        var slotStream = publicStream(width: width, height: height, total: total, label: "slots")
        let saltSlots = try place(count: saltBits, total: total, used: &used, stream: &slotStream)
        var keyed = KeyStream(key: placementKey, label: "kryptos/stego/v2/slots")
        let lengthSlots = try place(count: lengthBits, total: total, used: &used, stream: &keyed)
        let payloadSlots = try place(count: payloadBits, total: total, used: &used, stream: &keyed)

        var mask = KeyStream(key: placementKey, label: "kryptos/stego/v2/mask")
        let maskedLength = UInt32(sealed.count) ^ mask.uint32()

        var publicFlip = publicStream(width: width, height: height, total: total, label: "flip")
        var flip = KeyStream(key: placementKey, label: "kryptos/stego/v2/flip")

        for i in 0 ..< saltBits {
            let bit = (salt[salt.startIndex + i / 8] >> (7 - UInt8(i % 8))) & 1
            apply(&rgba, index: Int(list[saltSlots[i]]), bit: bit, stream: &publicFlip)
        }
        for i in 0 ..< lengthBits {
            let bit = UInt8((maskedLength >> (31 - UInt32(i))) & 1)
            apply(&rgba, index: Int(list[lengthSlots[i]]), bit: bit, stream: &flip)
        }
        for i in 0 ..< payloadBits {
            let bit = (sealed[sealed.startIndex + i / 8] >> (7 - UInt8(i % 8))) & 1
            apply(&rgba, index: Int(list[payloadSlots[i]]), bit: bit, stream: &flip)
        }
    }

    public static func reveal(rgba: [UInt8], width: Int, height: Int, password: String) throws -> Data {
        let list = try candidates(rgba: rgba, width: width, height: height)
        let total = list.count
        guard total > saltBits + lengthBits else { throw CipherError.decryptionFailed }

        var used = [Bool](repeating: false, count: total)
        var slotStream = publicStream(width: width, height: height, total: total, label: "slots")
        let saltSlots = try place(count: saltBits, total: total, used: &used, stream: &slotStream)
        var salt = [UInt8](repeating: 0, count: PasswordCipher.saltLength)
        for i in 0 ..< saltBits {
            salt[i / 8] |= (rgba[Int(list[saltSlots[i]])] & 1) << (7 - UInt8(i % 8))
        }

        var derived = try Argon2id.derive(password: password, salt: Data(salt),
                                          length: PasswordCipher.derivedLength + placementKeyLength)
        defer { Argon2id.zero(&derived) }
        let (key, nonce) = try PasswordCipher.split(derived)
        var placementKey = [UInt8](derived[PasswordCipher.derivedLength ..< derived.count])
        defer { Argon2id.zero(&placementKey) }

        var keyed = KeyStream(key: placementKey, label: "kryptos/stego/v2/slots")
        let lengthSlots = try place(count: lengthBits, total: total, used: &used, stream: &keyed)
        var maskedLength: UInt32 = 0
        for i in 0 ..< lengthBits {
            maskedLength |= UInt32(rgba[Int(list[lengthSlots[i]])] & 1) << (31 - UInt32(i))
        }
        var mask = KeyStream(key: placementKey, label: "kryptos/stego/v2/mask")
        let length = Int(maskedLength ^ mask.uint32())
        guard length > PasswordCipher.tagLength,
              saltBits + lengthBits + length * 8 <= total else { throw CipherError.decryptionFailed }

        let payloadSlots = try place(count: length * 8, total: total, used: &used, stream: &keyed)
        var sealed = [UInt8](repeating: 0, count: length)
        for i in 0 ..< length * 8 {
            sealed[i / 8] |= (rgba[Int(list[payloadSlots[i]])] & 1) << (7 - UInt8(i % 8))
        }
        return try PasswordCipher.openBody(Data(sealed), key: key, nonce: nonce, version: containerVersion)
    }
}
