import Foundation
import LibSignalClient
import CipherCore

enum SignalWire {
    private static let ctx = NullContext()

    static func pairSecret(store: PersistentSignalStore, myFingerprint: String, peerFingerprint: String) throws -> Data {
        guard let peerKey = SignalFormat.bytes(peerFingerprint), let peer = try? PublicKey(peerKey) else {
            throw CipherError.invalidInput
        }
        var agreement = try store.identityKeyPair(context: ctx).privateKey.keyAgreement(with: peer)
        defer { agreement.resetBytes(in: agreement.startIndex ..< agreement.endIndex) }
        return WireFormat.pairSecret(agreement: agreement, myFingerprint, peerFingerprint)
    }

    struct Sealed: Sendable {
        let ciphertext: Data
        let type: UInt8
        let deflate: Bool
        let pad: Bool
        let secret: Data
    }

    struct Cover: Sendable {
        let text: String
        let hidden: Bool
    }

    static func seal(_ text: String, toFingerprint fp: String, myFingerprint: String,
                     store: PersistentSignalStore, pad: Bool) throws -> Sealed {
        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)
        let secret = try pairSecret(store: store, myFingerprint: myFingerprint, peerFingerprint: fp)
        let body = Deflate.body(text)
        let ct = try signalEncrypt(message: Array(body.bytes), for: addr, localAddress: myAddr,
                                   sessionStore: store, identityStore: store, context: ctx)
        return Sealed(ciphertext: ct.serialize(), type: ct.messageType.rawValue, deflate: body.deflated,
                      pad: pad, secret: secret)
    }

    static func cover(_ sealed: Sealed, stego: StegoLanguage?, mode: StegoMode) throws -> Cover {
        if let language = stego {
            let padded = sealed.pad && WireFormat.fitsStego(ciphertext: sealed.ciphertext.count, padded: true)
            let payload = try WireFormat.seal(sealed.ciphertext, type: sealed.type,
                                              deflate: sealed.deflate, padded: padded, pairKey: sealed.secret)
            if payload.count <= TextStego.maxPayloadBytes {
                let cover: String?
                switch mode {
                case .words: cover = TextStego.encode(payload, language: language)
                case .smart: cover = SmartTextStego.encode(payload, language: language)
                case .letters: cover = LetterStego.encode(payload, language: language)
                }
                if let cover { return Cover(text: cover, hidden: true) }
            }
        }
        let token = try WireFormat.wrap(sealed.ciphertext, type: sealed.type, deflate: sealed.deflate,
                                        padded: sealed.pad, pairKey: sealed.secret)
        return Cover(text: token, hidden: false)
    }

    static func encrypt(_ text: String, toFingerprint fp: String, myFingerprint: String,
                        store: PersistentSignalStore, stego: StegoLanguage? = nil, mode: StegoMode = .words,
                        pad: Bool = false) throws -> String {
        let sealed = try seal(text, toFingerprint: fp, myFingerprint: myFingerprint, store: store, pad: pad)
        return try cover(sealed, stego: stego, mode: mode).text
    }

    static func decrypt(_ armored: String, fromFingerprint fp: String, myFingerprint: String,
                        store: PersistentSignalStore, stego precomputed: Data?? = nil) throws -> String {
        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)
        let secret = try pairSecret(store: store, myFingerprint: myFingerprint, peerFingerprint: fp)
        let hidden = { precomputed ?? stegoPayload(armored) }

        switch WireFormat.unwrap(armored, pairKey: secret) {
        case .message(let type, let deflate, let body):
            do {
                let plain = try signalDecryptBytes(type: type, body: body, addr: addr, myAddr: myAddr, store: store)
                return try inflate(plain, deflate: deflate)
            } catch {
                guard let payload = hidden() else { throw error }
                return try decryptStego(payload, secret: secret, addr: addr, myAddr: myAddr, store: store)
            }
        case .unsupported:
            throw CipherError.unsupportedFormat
        case .absent:
            guard let payload = hidden() else { throw CipherError.notAKryptosMessage }
            return try decryptStego(payload, secret: secret, addr: addr, myAddr: myAddr, store: store)
        }
    }

    static let maxStegoInputChars = 1_000_000

    static func stegoPayload(_ armored: String) -> Data? {
        guard armored.utf16.count <= maxStegoInputChars else { return nil }
        return TextStego.decode(armored) ?? SmartTextStego.decode(armored) ?? LetterStego.decode(armored)
    }

    private static func inflate(_ plain: Data, deflate: Bool) throws -> String {
        guard let text = Deflate.text(plain, deflated: deflate) else { throw CipherError.decryptionFailed }
        return text
    }

    private static func decryptStego(_ payload: Data, secret: Data, addr: ProtocolAddress, myAddr: ProtocolAddress,
                                     store: PersistentSignalStore) throws -> String {
        switch WireFormat.open(payload, pairKey: secret) {
        case .message(let type, let deflate, let body):
            let plain = try signalDecryptBytes(type: type, body: body, addr: addr, myAddr: myAddr, store: store)
            return try inflate(plain, deflate: deflate)
        case .unsupported:
            throw CipherError.unsupportedFormat
        case .absent:
            throw CipherError.notAKryptosMessage
        }
    }

    private static func signalDecryptBytes(type: UInt8, body: Data, addr: ProtocolAddress, myAddr: ProtocolAddress,
                                           store: PersistentSignalStore) throws -> Data {
        if type == CiphertextMessage.MessageType.preKey.rawValue {
            return try signalDecryptPreKey(message: PreKeySignalMessage(bytes: body),
                                           from: addr, localAddress: myAddr,
                                           sessionStore: store, identityStore: store,
                                           preKeyStore: store, signedPreKeyStore: store, kyberPreKeyStore: store, context: ctx)
        }
        return try signalDecrypt(message: SignalMessage(bytes: body), from: addr, to: myAddr,
                                 sessionStore: store, identityStore: store, context: ctx)
    }

}
