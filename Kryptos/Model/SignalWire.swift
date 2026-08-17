import Foundation
import LibSignalClient
import CipherCore

enum SignalWire {
    private static let ctx = NullContext()

    static func pairKey(_ a: String, _ b: String) -> Data {
        Data((a <= b ? a + b : b + a).utf8)
    }

    struct Sealed: Sendable {
        let ciphertext: Data
        let type: UInt8
        let deflate: Bool
        let pad: Bool
        let pairKey: Data
    }

    struct Cover: Sendable {
        let text: String
        let hidden: Bool
    }

    static func seal(_ text: String, toFingerprint fp: String, myFingerprint: String,
                     store: PersistentSignalStore, pad: Bool) throws -> Sealed {
        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)
        let raw = Data(text.utf8)
        let compressed = Deflate.compress(raw)
        let deflate = compressed != nil
        let ct = try signalEncrypt(message: Array(deflate ? compressed! : raw), for: addr, localAddress: myAddr,
                                   sessionStore: store, identityStore: store, context: ctx)
        return Sealed(ciphertext: ct.serialize(), type: ct.messageType.rawValue, deflate: deflate,
                      pad: pad, pairKey: pairKey(myFingerprint, fp))
    }

    static func cover(_ sealed: Sealed, stego: StegoLanguage?, mode: StegoMode) throws -> Cover {
        if let language = stego {
            let padded = sealed.pad && StegoWire.fits(ciphertext: sealed.ciphertext.count, padded: true)
            let payload = StegoWire.frame(sealed.ciphertext, type: sealed.type,
                                          deflate: sealed.deflate, padded: padded)
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
                                        padded: sealed.pad, pairKey: sealed.pairKey)
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
        let hidden = { precomputed ?? stegoPayload(armored) }

        if let (type, deflate, body) = WireFormat.unwrap(armored, pairKey: pairKey(myFingerprint, fp)) {
            do {
                let plain = try signalDecryptBytes(type: type, body: body, addr: addr, myAddr: myAddr, store: store)
                return try inflate(plain, deflate: deflate)
            } catch {
                guard let payload = hidden() else { throw error }
                return try decryptStego(payload, addr: addr, myAddr: myAddr, store: store)
            }
        }

        guard let payload = hidden() else { throw CipherError.notAKryptosMessage }
        return try decryptStego(payload, addr: addr, myAddr: myAddr, store: store)
    }

    static let maxStegoInputChars = 1_000_000

    static func stegoPayload(_ armored: String) -> Data? {
        guard armored.utf16.count <= maxStegoInputChars else { return nil }
        return TextStego.decode(armored) ?? SmartTextStego.decode(armored) ?? LetterStego.decode(armored)
    }

    private static func inflate(_ plain: Data, deflate: Bool) throws -> String {
        guard deflate else { return String(decoding: plain, as: UTF8.self) }
        guard let data = Deflate.decompress(plain) else { throw CipherError.decryptionFailed }
        return String(decoding: data, as: UTF8.self)
    }

    private static func decryptStego(_ payload: Data, addr: ProtocolAddress, myAddr: ProtocolAddress,
                                     store: PersistentSignalStore) throws -> String {
        guard let framed = StegoWire.unframe(payload) else {
            throw StegoWire.carriesUnknownFlags(payload) ? CipherError.unsupportedFormat : CipherError.notAKryptosMessage
        }
        let plain = try signalDecryptBytes(type: framed.type, body: framed.body,
                                           addr: addr, myAddr: myAddr, store: store)
        return try inflate(plain, deflate: framed.deflate)
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
