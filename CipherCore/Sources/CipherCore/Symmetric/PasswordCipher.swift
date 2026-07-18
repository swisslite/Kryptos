import Foundation
import CryptoKit
import CommonCrypto

public enum PasswordCipher {
    public static let iterations = 210_000
    static let saltLength = 16

    public static func encrypt(_ plaintext: Data, password: String, pad: Bool = false) throws -> Data {
        let salt = randomBytes(saltLength)
        let (key, nonce) = try derive(password: password, salt: salt)
        let compressed = Deflate.compress(plaintext)
        let deflate = compressed != nil
        let content = deflate ? compressed! : plaintext
        let framed = pad ? Padding.frame(content) : content
        var body = Data([(deflate ? 0x01 : 0x00) | (pad ? 0x02 : 0x00)])
        body.append(framed)
        let sealed = try AES.GCM.seal(body, using: key, nonce: AES.GCM.Nonce(data: nonce))
        var out = salt
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return out
    }

    public static func decrypt(_ data: Data, password: String) throws -> Data {
        guard data.count >= saltLength + 16 else { throw CipherError.malformed }
        let salt = data.prefix(saltLength)
        let rest = data.suffix(from: data.startIndex + saltLength)
        let ct = rest.prefix(rest.count - 16)
        let tag = rest.suffix(16)
        let (key, nonce) = try derive(password: password, salt: Data(salt))
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: Data(ct), tag: Data(tag))
        let body: Data
        do { body = try AES.GCM.open(box, using: key) } catch { throw CipherError.decryptionFailed }
        guard let flag = body.first else { throw CipherError.decryptionFailed }
        var content = Data(body.suffix(from: body.startIndex + 1))
        if flag & 0x02 != 0 {
            guard let unframed = Padding.unframe(content) else { throw CipherError.decryptionFailed }
            content = unframed
        }
        if flag & 0x01 != 0 {
            guard let inflated = Deflate.decompress(content) else { throw CipherError.decryptionFailed }
            return inflated
        }
        return content
    }

    private static func derive(password: String, salt: Data) throws -> (SymmetricKey, Data) {
        var derived = [UInt8](repeating: 0, count: 44)
        let pwd = Data(password.utf8)
        let status = pwd.withUnsafeBytes { pwdRaw -> Int32 in
            salt.withUnsafeBytes { saltRaw -> Int32 in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pwdRaw.bindMemory(to: Int8.self).baseAddress, pwd.count,
                    saltRaw.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    &derived, derived.count
                )
            }
        }
        guard status == kCCSuccess else { throw CipherError.invalidInput }
        return (SymmetricKey(data: Data(derived[0 ..< 32])), Data(derived[32 ..< 44]))
    }
}
