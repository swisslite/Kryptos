import Foundation
import CryptoKit
import CommonCrypto

public enum WireFormat {
    public static let saltLength = 8
    static let info = Data("kryptos/wire/v2".utf8)
    static let minTokenBytes = 24
    static let minTokenChars = 32
    static let maxTokenChars = 2_000_000

    public static func wrap(_ body: Data, type: UInt8, deflate: Bool, padded: Bool, pairKey: Data) -> String {
        wrap(body, type: type, deflate: deflate, padded: padded, pairKey: pairKey, salt: randomBytes(saltLength))
    }

    public static func wrap(_ body: Data, type: UInt8, deflate: Bool, padded: Bool, pairKey: Data, salt: Data) -> String {
        var plain = Data([(type & 0x0F) | (deflate ? 0x10 : 0x00) | (padded ? 0x20 : 0x00)])
        plain.append(padded ? Padding.frame(body) : body)
        let (key, iv) = derive(pairKey: pairKey, salt: salt)
        var token = salt
        token.append(ctr(key: key, iv: iv, plain))
        return base64URLEncode(token)
    }

    public static func unwrap(_ text: String, pairKey: Data) -> (type: UInt8, deflate: Bool, body: Data)? {
        guard let raw = rawBytes(text), raw.count > saltLength else { return nil }
        let salt = raw.prefix(saltLength)
        let masked = raw.suffix(from: raw.startIndex + saltLength)
        let (key, iv) = derive(pairKey: pairKey, salt: Data(salt))
        let plain = ctr(key: key, iv: iv, Data(masked))
        guard let header = plain.first else { return nil }
        let type = header & 0x0F
        guard type == 2 || type == 3 else { return nil }
        let inner = Data(plain.suffix(from: plain.startIndex + 1))
        let body: Data
        if header & 0x20 != 0 {
            guard let unpadded = Padding.unframe(inner) else { return nil }
            body = unpadded
        } else {
            body = inner
        }
        return (type, header & 0x10 != 0, body)
    }

    public static func token(_ raw: Data) -> String { base64URLEncode(raw) }

    public static func tokenBytes(_ text: String) -> Data? { rawBytes(text) }

    static func rawBytes(_ text: String) -> Data? {
        guard let run = longestRun(text) else { return nil }
        return base64URLDecode(run)
    }

    public static func isToken(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.count >= minTokenChars, t.count <= maxTokenChars, t.allSatisfy(isBase64URLChar) else { return false }
        guard let d = base64URLDecode(t) else { return false }
        return d.count >= minTokenBytes
    }

    public static func extractToken(_ text: String) -> String? {
        guard let run = longestRun(text), run.count >= minTokenChars, run.count <= maxTokenChars,
              let d = base64URLDecode(run), d.count >= minTokenBytes else { return nil }
        return run
    }

    static func longestRun(_ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty, t.allSatisfy(isBase64URLChar) { return t }
        var best = "", current = ""
        func consider() { if current.count > best.count { best = current } }
        for c in text {
            if isBase64URLChar(c) { current.append(c) } else { consider(); current = "" }
        }
        consider()
        return best.isEmpty ? nil : best
    }

    static func derive(pairKey: Data, salt: Data) -> (Data, Data) {
        let okm = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: pairKey),
                                         salt: salt, info: info, outputByteCount: 48)
        let bytes = okm.withUnsafeBytes { Data($0) }
        return (bytes.prefix(32), bytes.suffix(16))
    }

    static func ctr(key: Data, iv: Data, _ input: Data) -> Data {
        guard !input.isEmpty else { return Data() }
        var cryptor: CCCryptorRef?
        let status = key.withUnsafeBytes { k in
            iv.withUnsafeBytes { i in
                CCCryptorCreateWithMode(CCOperation(kCCEncrypt), CCMode(kCCModeCTR), CCAlgorithm(kCCAlgorithmAES),
                                        CCPadding(ccNoPadding), i.baseAddress, k.baseAddress, key.count,
                                        nil, 0, 0, CCModeOptions(kCCModeOptionCTR_BE), &cryptor)
            }
        }
        guard status == kCCSuccess, let c = cryptor else { return input }
        defer { CCCryptorRelease(c) }
        var out = Data(count: input.count)
        let outCount = out.count
        var moved = 0
        _ = out.withUnsafeMutableBytes { o in
            input.withUnsafeBytes { i in
                CCCryptorUpdate(c, i.baseAddress, input.count, o.baseAddress, outCount, &moved)
            }
        }
        return out
    }

    static func isBase64URLChar(_ c: Character) -> Bool {
        c.isASCII && (c.isLetter || c.isNumber || c == "-" || c == "_")
    }

    static func base64URLEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func base64URLDecode(_ string: String) -> Data? {
        var s = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let pad = s.count % 4
        if pad > 0 { s += String(repeating: "=", count: 4 - pad) }
        return Data(base64Encoded: s)
    }
}
