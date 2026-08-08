import Foundation

public enum StegoWire {
    public static let prefix: UInt8 = 0x03
    static let typeMask: UInt8 = 0x0F
    static let deflateFlag: UInt8 = 0x10
    static let paddedFlag: UInt8 = 0x20
    static let knownFlags: UInt8 = typeMask | deflateFlag | paddedFlag

    public static func carriesUnknownFlags(_ payload: Data) -> Bool {
        guard payload.count >= 2, payload.first == prefix else { return false }
        return payload[payload.startIndex + 1] & ~knownFlags != 0
    }

    public static func payloadSize(ciphertext: Int, padded: Bool) -> Int {
        2 + (padded ? Padding.target(4 + ciphertext) : ciphertext)
    }

    public static func fits(ciphertext: Int, padded: Bool) -> Bool {
        payloadSize(ciphertext: ciphertext, padded: padded) <= TextStego.maxPayloadBytes
    }

    public static func frame(_ ciphertext: Data, type: UInt8, deflate: Bool, padded: Bool) -> Data {
        var payload = Data([prefix, (type & typeMask) | (deflate ? deflateFlag : 0) | (padded ? paddedFlag : 0)])
        payload.append(padded ? Padding.frame(ciphertext) : ciphertext)
        return payload
    }

    public static func unframe(_ payload: Data) -> (type: UInt8, deflate: Bool, body: Data)? {
        guard payload.count >= 2, payload.first == prefix else { return nil }
        let flags = payload[payload.startIndex + 1]
        guard flags & ~knownFlags == 0 else { return nil }
        var body = payload.subdata(in: (payload.startIndex + 2) ..< payload.endIndex)
        if flags & paddedFlag != 0 {
            guard let unpadded = Padding.unframe(body) else { return nil }
            body = unpadded
        }
        return (flags & typeMask, flags & deflateFlag != 0, body)
    }
}
