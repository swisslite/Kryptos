import Foundation

public enum KeyText {
    public static let prefix = "KRYPTOS-KEY:"
    static let maxBlobChars = 16 * 1024

    static func isBase64Char(_ c: Character) -> Bool {
        c.isASCII && (c.isLetter || c.isNumber || c == "+" || c == "/" || c == "=")
    }

    public static func blobs(in raw: String) -> [Data] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let range = trimmed.range(of: prefix) else { return [] }
        let rest = trimmed[range.upperBound...].prefix(maxBlobChars)
        let direct = String(rest.prefix { !$0.isWhitespace })
        let joined = String(rest.prefix { $0.isWhitespace || isBase64Char($0) }.filter { !$0.isWhitespace })
        var out: [Data] = []
        if let d = Data(base64Encoded: direct) { out.append(d) }
        if joined.count > direct.count, let d = Data(base64Encoded: joined) { out.append(d) }
        return out
    }
}
