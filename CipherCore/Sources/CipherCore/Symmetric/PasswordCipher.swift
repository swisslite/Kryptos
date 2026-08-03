import Foundation
import CryptoKit

public enum PasswordCipher {
    public static let saltLength = 16
    static let tagLength = 16
    static let keyLength = 32
    static let nonceLength = 12
    static let derivedLength = 44

    static func split(_ derived: [UInt8]) throws -> (SymmetricKey, AES.GCM.Nonce) {
        guard derived.count >= keyLength + nonceLength else { throw CipherError.invalidInput }
        let key = SymmetricKey(data: Data(derived[0 ..< keyLength]))
        let nonce = try AES.GCM.Nonce(data: Data(derived[keyLength ..< keyLength + nonceLength]))
        return (key, nonce)
    }

    static func sealBody(_ plaintext: Data, key: SymmetricKey, nonce: AES.GCM.Nonce,
                         version: UInt8, pad: Bool) throws -> Data {
        let compressed = Deflate.compress(plaintext)
        let deflate = compressed != nil
        let content = deflate ? compressed! : plaintext
        let framed = pad ? Padding.frame(content) : content
        var body = Data([(deflate ? 0x01 : 0x00) | (pad ? 0x02 : 0x00)])
        body.append(framed)
        let sealed = try AES.GCM.seal(body, using: key, nonce: nonce, authenticating: Data([version]))
        return sealed.ciphertext + sealed.tag
    }

    static func openBody(_ sealed: Data, key: SymmetricKey, nonce: AES.GCM.Nonce,
                         version: UInt8) throws -> Data {
        guard sealed.count >= tagLength else { throw CipherError.decryptionFailed }
        let ct = sealed.prefix(sealed.count - tagLength)
        let tag = sealed.suffix(tagLength)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(ct), tag: Data(tag))
        let body: Data
        do {
            body = try AES.GCM.open(box, using: key, authenticating: Data([version]))
        } catch {
            throw CipherError.decryptionFailed
        }
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

    public static func encrypt(_ plaintext: Data, password: String, pad: Bool = false) throws -> Data {
        let salt = randomBytes(saltLength)
        let version = Argon2id.profileVersion
        var derived = try Argon2id.derive(password: Data(password.utf8), salt: salt, length: derivedLength)
        defer { Argon2id.zero(&derived) }
        let (key, nonce) = try split(derived)
        var out = salt
        out.append(version)
        out.append(try sealBody(plaintext, key: key, nonce: nonce, version: version, pad: pad))
        return out
    }

    public static func decrypt(_ data: Data, password: String) throws -> Data {
        guard data.count >= saltLength + 1 + tagLength else { throw CipherError.malformed }
        let salt = Data(data.prefix(saltLength))
        let version = data[data.startIndex + saltLength]
        guard version == Argon2id.profileVersion else { throw CipherError.malformed }
        let sealed = Data(data.suffix(from: data.startIndex + saltLength + 1))
        var derived = try Argon2id.derive(password: Data(password.utf8), salt: salt, length: derivedLength)
        defer { Argon2id.zero(&derived) }
        let (key, nonce) = try split(derived)
        return try openBody(sealed, key: key, nonce: nonce, version: version)
    }
}
