import Foundation
import Compression

public enum Deflate {
    public static let maxOutput = 8 * 1024 * 1024

    public static func compress(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        let cap = data.count + 64
        var out = Data(count: cap)
        let written = out.withUnsafeMutableBytes { dst -> Int in
            data.withUnsafeBytes { src in
                compression_encode_buffer(
                    dst.bindMemory(to: UInt8.self).baseAddress!, cap,
                    src.bindMemory(to: UInt8.self).baseAddress!, data.count,
                    nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0, written < data.count else { return nil }
        out.removeSubrange(written ..< out.count)
        return out
    }

    public static func decompress(_ data: Data, limit: Int = maxOutput) -> Data? {
        guard !data.isEmpty, limit > 0 else { return nil }
        var capacity = min(max(data.count * 4, 256), limit + 1)
        while true {
            var out = Data(count: capacity)
            let written = out.withUnsafeMutableBytes { dst -> Int in
                data.withUnsafeBytes { src in
                    compression_decode_buffer(
                        dst.bindMemory(to: UInt8.self).baseAddress!, capacity,
                        src.bindMemory(to: UInt8.self).baseAddress!, data.count,
                        nil, COMPRESSION_ZLIB)
                }
            }
            if written == 0 { return nil }
            if written < capacity {
                guard written <= limit else { return nil }
                out.removeSubrange(written ..< out.count)
                return out
            }
            if capacity >= limit + 1 { return nil }
            capacity = min(capacity * 2, limit + 1)
        }
    }
}
