import Foundation
import CryptoKit
import LibSignalClient
import CipherCore

enum SignalWire {
    private static let ctx = NullContext()

    static func pairKey(_ a: String, _ b: String) -> Data {
        Data((a <= b ? a + b : b + a).utf8)
    }

    static func encrypt(_ text: String, toFingerprint fp: String, myFingerprint: String,
                        store: PersistentSignalStore, stego: StegoLanguage? = nil, mode: StegoMode = .words,
                        pad: Bool = false) throws -> String {
        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)

        if let language = stego {
            let raw = Data(text.utf8)
            let compressed = Deflate.compress(raw)
            let deflate = compressed != nil
            let ct = try signalEncrypt(message: Array(deflate ? compressed! : raw), for: addr, localAddress: myAddr,
                                       sessionStore: store, identityStore: store, context: ctx)
            var payload = Data([0x03, (ct.messageType.rawValue & 0x0F) | (deflate ? 0x10 : 0)])
            payload.append(ct.serialize())
            if payload.count <= TextStego.maxPayloadBytes {
                switch mode {
                case .words: return TextStego.encode(payload, language: language)
                case .smart: return SmartTextStego.encode(payload, language: language)
                case .letters: return LetterStego.encode(payload, language: language)
                }
            }
            return try WireFormat.wrap(ct.serialize(), type: ct.messageType.rawValue, deflate: deflate, padded: pad,
                                       pairKey: pairKey(myFingerprint, fp))
        }

        let plaintext = Data(text.utf8)
        let compressed = Deflate.compress(plaintext)
        let deflate = compressed != nil
        let ct = try signalEncrypt(message: Array(deflate ? compressed! : plaintext), for: addr, localAddress: myAddr,
                                   sessionStore: store, identityStore: store, context: ctx)
        return try WireFormat.wrap(ct.serialize(), type: ct.messageType.rawValue, deflate: deflate, padded: pad,
                                   pairKey: pairKey(myFingerprint, fp))
    }

    static func decrypt(_ armored: String, fromFingerprint fp: String, myFingerprint: String, store: PersistentSignalStore) throws -> String {
        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)

        if let (type, deflate, body) = WireFormat.unwrap(armored, pairKey: pairKey(myFingerprint, fp)) {
            do {
                let plain = try signalDecryptBytes(type: type, body: body, addr: addr, myAddr: myAddr, store: store)
                let data = deflate ? (Deflate.decompress(plain) ?? Data()) : plain
                return String(decoding: data, as: UTF8.self)
            } catch {
                guard let payload = stegoPayload(armored) else { throw error }
                return try decryptStego(payload, addr: addr, myAddr: myAddr, store: store)
            }
        }

        guard let payload = stegoPayload(armored) else { throw CipherError.notAKryptosMessage }
        return try decryptStego(payload, addr: addr, myAddr: myAddr, store: store)
    }

    private static func stegoPayload(_ armored: String) -> Data? {
        TextStego.decode(armored) ?? SmartTextStego.decode(armored) ?? LetterStego.decode(armored)
    }

    private static func decryptStego(_ payload: Data, addr: ProtocolAddress, myAddr: ProtocolAddress,
                                     store: PersistentSignalStore) throws -> String {
        guard payload.count >= 2, payload.first == 0x03 else {
            throw CipherError.notAKryptosMessage
        }
        let flags = payload[payload.startIndex + 1]
        let plain = try signalDecryptBytes(type: flags & 0x0F,
                                           body: payload.subdata(in: (payload.startIndex + 2) ..< payload.endIndex),
                                           addr: addr, myAddr: myAddr, store: store)
        let data = (flags & 0x10) != 0 ? (Deflate.decompress(plain) ?? Data()) : plain
        return String(decoding: data, as: UTF8.self)
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

    static func engineCheckError() -> String? {
        do {
            let ctx = NullContext()
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            let bob = InMemorySignalProtocolStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 16380))
            let bobId = try bob.identityKeyPair(context: ctx)
            let bobReg = try bob.localRegistrationId(context: ctx)
            let spk = PrivateKey.generate()
            let spkSig = bobId.privateKey.generateSignature(message: spk.publicKey.serialize())
            try bob.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: now, privateKey: spk, signature: spkSig), id: 1, context: ctx)
            let ky = KEMKeyPair.generate()
            let kySig = bobId.privateKey.generateSignature(message: ky.publicKey.serialize())
            try bob.storeKyberPreKey(KyberPreKeyRecord(id: 1, timestamp: now, keyPair: ky, signature: kySig), id: 1, context: ctx)
            let bundle = try PreKeyBundle(registrationId: bobReg, deviceId: 1,
                                          signedPrekeyId: 1, signedPrekey: spk.publicKey, signedPrekeySignature: spkSig,
                                          identity: bobId.identityKey,
                                          kyberPrekeyId: 1, kyberPrekey: ky.publicKey, kyberPrekeySignature: kySig)
            let alice = InMemorySignalProtocolStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 16380))
            let bobAddr = try ProtocolAddress(name: "bob", deviceId: 1)
            let aliceAddr = try ProtocolAddress(name: "alice", deviceId: 1)
            try processPreKeyBundle(bundle, for: bobAddr, ourAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)
            let ct = try signalEncrypt(message: Array("ok".utf8), for: bobAddr, localAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)
            guard ct.messageType == .preKey else { return "type" }
            let dec = try signalDecryptPreKey(message: PreKeySignalMessage(bytes: ct.serialize()), from: aliceAddr, localAddress: bobAddr, sessionStore: bob, identityStore: bob, preKeyStore: bob, signedPreKeyStore: bob, kyberPreKeyStore: bob, context: ctx)
            return String(decoding: dec, as: UTF8.self) == "ok" ? nil : "mismatch"
        } catch { return "\(error)" }
    }

}
