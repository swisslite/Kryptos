import Foundation

public enum Padding {
    static let floor = 64
    static let cap = 1 << 20

    public static func target(_ n: Int) -> Int {
        if n <= floor { return floor }
        if n > cap { return ((n + cap - 1) / cap) * cap }
        var p = floor
        while p < n { p <<= 1 }
        return p
    }

    public static func frame(_ content: Data) -> Data {
        let total = target(4 + content.count)
        let padLen = total - 4 - content.count
        let n = UInt32(truncatingIfNeeded: content.count)
        var out = Data(capacity: total)
        out.append(UInt8((n >> 24) & 0xFF))
        out.append(UInt8((n >> 16) & 0xFF))
        out.append(UInt8((n >> 8) & 0xFF))
        out.append(UInt8(n & 0xFF))
        out.append(content)
        if padLen > 0 { out.append(randomBytes(padLen)) }
        return out
    }

    public static func unframe(_ framed: Data) -> Data? {
        guard framed.count >= 4 else { return nil }
        let base = framed.startIndex
        let n = (Int(framed[base]) << 24) | (Int(framed[base + 1]) << 16)
            | (Int(framed[base + 2]) << 8) | Int(framed[base + 3])
        guard n >= 0, n <= framed.count - 4 else { return nil }
        return Data(framed[(base + 4) ..< (base + 4 + n)])
    }
}
