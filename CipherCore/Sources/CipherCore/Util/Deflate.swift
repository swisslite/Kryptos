import Foundation
import Compression
import CZlib

public enum Deflate {
    public static let maxOutput = 8 * 1024 * 1024

    private static let chunkSize = 64 * 1024

    public struct Body: Sendable {
        public let bytes: Data
        public let deflated: Bool
    }

    /// Picks the body form whose framing flag cannot be misread when the flag bit flips in transit:
    /// reading the body the other way has to fail, so the fallback in `text` restores the original.
    public static func body(_ text: String) -> Body {
        let plain = Data(text.utf8)
        guard !plain.isEmpty, let packed = pack(plain) else { return Body(bytes: plain, deflated: false) }
        let packedReadsAsText = strictText(packed) != nil
        if packed.count < plain.count, !packedReadsAsText { return Body(bytes: packed, deflated: true) }
        guard decompress(plain).flatMap(strictText) != nil else { return Body(bytes: plain, deflated: false) }
        if !packedReadsAsText { return Body(bytes: packed, deflated: true) }
        return Body(bytes: syncFlush + packed, deflated: true)
    }

    /// Reads the body as the flag describes it and falls back to the other reading. Strict UTF-8,
    /// so damaged bytes fail instead of arriving as replacement characters.
    public static func text(_ data: Data, deflated: Bool) -> String? {
        let flagged = deflated ? decompress(data) : data
        if let direct = flagged.flatMap(strictText) { return direct }
        let other = deflated ? data : decompress(data)
        return other.flatMap(strictText)
    }

    public static func compress(_ data: Data) -> Data? {
        guard let packed = pack(data), packed.count < data.count else { return nil }
        return packed
    }

    /// Raw deflate through zlib, the same decoder the Android side uses, so a stream either ends
    /// cleanly on both platforms or is rejected on both. Apple's Compression framework cannot tell
    /// a truncated stream from a finished one, which made damaged input decode to whatever the
    /// output buffer held.
    public static func decompress(_ data: Data, limit: Int = maxOutput) -> Data? {
        guard !data.isEmpty, limit > 0 else { return nil }
        var stream = z_stream()
        guard inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
            return nil
        }
        defer { inflateEnd(&stream) }

        var input = [UInt8](data)
        var chunk = [UInt8](repeating: 0, count: chunkSize)
        var out = Data()
        var finished = false
        input.withUnsafeMutableBufferPointer { source in
            stream.next_in = source.baseAddress
            stream.avail_in = uInt(source.count)
            while !finished {
                var status: Int32 = Z_OK
                var produced = 0
                chunk.withUnsafeMutableBufferPointer { sink in
                    stream.next_out = sink.baseAddress
                    stream.avail_out = uInt(sink.count)
                    status = inflate(&stream, Z_NO_FLUSH)
                    produced = sink.count - Int(stream.avail_out)
                }
                guard status == Z_OK || status == Z_STREAM_END else { return }
                if produced > 0 {
                    out.append(contentsOf: chunk[0 ..< produced])
                    guard out.count <= limit else { return }
                }
                if status == Z_STREAM_END { finished = true; break }
                if produced == 0 && stream.avail_in == 0 { return }
            }
        }
        guard finished, out.count <= limit else { return nil }
        return out
    }

    /// An empty stored block in front of the stream. It inflates to nothing, keeps the rest of the
    /// stream byte-aligned, and its 0xFF bytes are never valid UTF-8, which is what makes the
    /// deflated body unreadable as plain text.
    private static let syncFlush = Data([0x00, 0x00, 0x00, 0xFF, 0xFF])

    private static func pack(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        let ceiling = data.count * 2 + 1024
        var capacity = data.count + 64
        while true {
            var out = Data(count: capacity)
            let written = out.withUnsafeMutableBytes { dst -> Int in
                data.withUnsafeBytes { src in
                    compression_encode_buffer(
                        dst.bindMemory(to: UInt8.self).baseAddress!, capacity,
                        src.bindMemory(to: UInt8.self).baseAddress!, data.count,
                        nil, COMPRESSION_ZLIB)
                }
            }
            if written > 0 {
                out.removeSubrange(written ..< out.count)
                return out
            }
            guard capacity < ceiling else { return nil }
            capacity = min(capacity * 2, ceiling)
        }
    }

    private static func strictText(_ data: Data) -> String? {
        String(data: data, encoding: .utf8)
    }
}
