import Foundation
import LibSignalClient
import CipherCore

enum SignalWire {
    private static let ctx = NullContext()

    static func pairKey(_ a: String, _ b: String) -> Data {
        Data((a <= b ? a + b : b + a).utf8)
    }

    static func encrypt(_ text: String, toFingerprint fp: String, myFingerprint: String,
                        store: PersistentSignalStore, stego: StegoLanguage? = nil, smart: Bool = false,
                        pad: Bool = false) throws -> String {
        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)

        if let language = stego {
            let ct = try signalEncrypt(message: Array(text.utf8), for: addr, localAddress: myAddr,
                                       sessionStore: store, identityStore: store, context: ctx)
            var payload = Data([0x03, ct.messageType.rawValue])
            payload.append(ct.serialize())
            if payload.count <= TextStego.maxPayloadBytes {
                return smart ? SmartTextStego.encode(payload, language: language)
                             : TextStego.encode(payload, language: language)
            }
        }

        let plaintext = Data(text.utf8)
        let compressed = Deflate.compress(plaintext)
        let deflate = compressed != nil
        let ct = try signalEncrypt(message: Array(deflate ? compressed! : plaintext), for: addr, localAddress: myAddr,
                                   sessionStore: store, identityStore: store, context: ctx)
        return WireFormat.wrap(ct.serialize(), type: ct.messageType.rawValue, deflate: deflate, padded: pad,
                               pairKey: pairKey(myFingerprint, fp))
    }

    static func decrypt(_ armored: String, fromFingerprint fp: String, myFingerprint: String, store: PersistentSignalStore) throws -> String {
        let addr = try ProtocolAddress(name: fp, deviceId: 1)
        let myAddr = try ProtocolAddress(name: myFingerprint, deviceId: 1)

        if let (type, deflate, body) = WireFormat.unwrap(armored, pairKey: pairKey(myFingerprint, fp)) {
            let plain = try signalDecryptBytes(type: type, body: body, addr: addr, myAddr: myAddr, store: store)
            let data = deflate ? (Deflate.decompress(plain) ?? Data()) : plain
            return String(decoding: data, as: UTF8.self)
        }

        let payload: Data
        if let p = TextStego.decode(armored) {
            payload = p
        } else if let p = SmartTextStego.decode(armored) {
            payload = p
        } else {
            throw CipherError.notAKryptosMessage
        }
        guard payload.count >= 2, payload.first == 0x03 else {
            throw CipherError.notAKryptosMessage
        }
        let plain = try signalDecryptBytes(type: payload[payload.startIndex + 1],
                                           body: payload.subdata(in: (payload.startIndex + 2) ..< payload.endIndex),
                                           addr: addr, myAddr: myAddr, store: store)
        return String(decoding: plain, as: UTF8.self)
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

    static func selfTestError() -> String? {
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

    static func stegoWireTestError() -> String? {
        func fp(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
        func send(_ ct: CiphertextMessage, stego lang: StegoLanguage) -> String {
            var payload = Data([0x03, ct.messageType.rawValue]); payload.append(ct.serialize())
            return TextStego.encode(payload, language: lang)
        }
        func receive(_ s: String) -> (UInt8, Data)? {
            guard let payload = TextStego.decode(s), payload.count >= 2, payload.first == 0x03 else { return nil }
            return (payload[payload.startIndex + 1], payload.subdata(in: (payload.startIndex + 2) ..< payload.endIndex))
        }
        do {
            let ctx = NullContext()
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            let bob = InMemorySignalProtocolStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 0x3FFF))
            let bobId = try bob.identityKeyPair(context: ctx)
            let signed = PrivateKey.generate()
            let signedSig = bobId.privateKey.generateSignature(message: signed.publicKey.serialize())
            try bob.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: now, privateKey: signed, signature: signedSig), id: 1, context: ctx)
            let kyber = KEMKeyPair.generate()
            let kyberSig = bobId.privateKey.generateSignature(message: kyber.publicKey.serialize())
            try bob.storeKyberPreKey(KyberPreKeyRecord(id: 1, timestamp: now, keyPair: kyber, signature: kyberSig), id: 1, context: ctx)
            let bundle = try PreKeyBundle(registrationId: try bob.localRegistrationId(context: ctx), deviceId: 1,
                                          signedPrekeyId: 1, signedPrekey: signed.publicKey, signedPrekeySignature: signedSig,
                                          identity: bobId.identityKey,
                                          kyberPrekeyId: 1, kyberPrekey: kyber.publicKey, kyberPrekeySignature: kyberSig)
            let alice = InMemorySignalProtocolStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 0x3FFF))
            let bobAddr = try ProtocolAddress(name: fp(bobId.identityKey.serialize()), deviceId: 1)
            let aliceAddr = try ProtocolAddress(name: fp((try alice.identityKeyPair(context: ctx)).identityKey.serialize()), deviceId: 1)
            try processPreKeyBundle(bundle, for: bobAddr, ourAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)

            let hello = "первое сообщение рукопожатие"
            let m1 = send(try signalEncrypt(message: Array(hello.utf8), for: bobAddr, localAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx), stego: .russian)
            if !TextStego.looksLikeStego(m1) { return "handshake-should-be-stego" }
            guard let (t1, c1) = receive(m1), t1 == CiphertextMessage.MessageType.preKey.rawValue else { return "recv1" }
            let d1 = try signalDecryptPreKey(message: PreKeySignalMessage(bytes: c1), from: aliceAddr, localAddress: bobAddr, sessionStore: bob, identityStore: bob, preKeyStore: bob, signedPreKeyStore: bob, kyberPreKeyStore: bob, context: ctx)
            if String(decoding: d1, as: UTF8.self) != hello { return "A->B" }

            let m2 = send(try signalEncrypt(message: Array("ответ".utf8), for: aliceAddr, localAddress: bobAddr, sessionStore: bob, identityStore: bob, context: ctx), stego: .russian)
            if !TextStego.looksLikeStego(m2) { return "reply-should-be-stego" }
            guard let (_, c2) = receive(m2) else { return "recv2" }
            let d2 = try signalDecrypt(message: SignalMessage(bytes: c2), from: bobAddr, to: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)
            if String(decoding: d2, as: UTF8.self) != "ответ" { return "B->A" }

            let m3 = send(try signalEncrypt(message: Array("hi again".utf8), for: bobAddr, localAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx), stego: .english)
            if !TextStego.looksLikeStego(m3) { return "m3-should-be-stego" }
            guard let (_, c3) = receive(m3) else { return "recv3" }
            let d3 = try signalDecrypt(message: SignalMessage(bytes: c3), from: aliceAddr, to: bobAddr, sessionStore: bob, identityStore: bob, context: ctx)
            return String(decoding: d3, as: UTF8.self) == "hi again" ? nil : "A->B-2"
        } catch { return "\(error)" }
    }

    static func fullWireTestError() -> String? {
        func fp(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
        do {
            let ctx = NullContext()
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            let bob = InMemorySignalProtocolStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 0x3FFF))
            let bobId = try bob.identityKeyPair(context: ctx)
            let signed = PrivateKey.generate()
            let signedSig = bobId.privateKey.generateSignature(message: signed.publicKey.serialize())
            try bob.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: now, privateKey: signed, signature: signedSig), id: 1, context: ctx)
            let kyber = KEMKeyPair.generate()
            let kyberSig = bobId.privateKey.generateSignature(message: kyber.publicKey.serialize())
            try bob.storeKyberPreKey(KyberPreKeyRecord(id: 1, timestamp: now, keyPair: kyber, signature: kyberSig), id: 1, context: ctx)

            let payload = BundlePayload(registrationId: try bob.localRegistrationId(context: ctx), deviceId: 1,
                                        identityKey: bobId.identityKey.serialize(),
                                        signedPreKeyId: 1, signedPreKey: signed.publicKey.serialize(), signedPreKeySignature: signedSig,
                                        kyberPreKeyId: 1, kyberPreKey: kyber.publicKey.serialize(), kyberPreKeySignature: kyberSig)
            let keyString = "KRYPTOS-KEY:" + (try JSONEncoder().encode(payload)).base64EncodedString()
            guard let r = keyString.range(of: "KRYPTOS-KEY:"),
                  let json = Data(base64Encoded: String(keyString[r.upperBound...])) else { return "keystring" }
            let peer = try JSONDecoder().decode(BundlePayload.self, from: json)
            let bundle = try PreKeyBundle(registrationId: peer.registrationId, deviceId: peer.deviceId,
                                          signedPrekeyId: peer.signedPreKeyId, signedPrekey: PublicKey(peer.signedPreKey), signedPrekeySignature: peer.signedPreKeySignature,
                                          identity: IdentityKey(bytes: peer.identityKey),
                                          kyberPrekeyId: peer.kyberPreKeyId, kyberPrekey: KEMPublicKey(peer.kyberPreKey), kyberPrekeySignature: peer.kyberPreKeySignature)
            let alice = InMemorySignalProtocolStore(identity: .generate(), registrationId: UInt32.random(in: 1 ... 0x3FFF))
            let bobAddr = try ProtocolAddress(name: fp(peer.identityKey), deviceId: 1)
            let aliceAddr = try ProtocolAddress(name: fp((try alice.identityKeyPair(context: ctx)).identityKey.serialize()), deviceId: 1)
            try processPreKeyBundle(bundle, for: bobAddr, ourAddress: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)

            let pk = SignalWire.pairKey(aliceAddr.name, bobAddr.name)
            func send(_ text: String, from local: ProtocolAddress, to remote: ProtocolAddress, store: InMemorySignalProtocolStore, pad: Bool) throws -> String {
                let ptext = Data(text.utf8)
                let comp = Deflate.compress(ptext); let deflate = comp != nil
                let ct = try signalEncrypt(message: Array(deflate ? comp! : ptext), for: remote, localAddress: local, sessionStore: store, identityStore: store, context: ctx)
                return WireFormat.wrap(ct.serialize(), type: ct.messageType.rawValue, deflate: deflate, padded: pad, pairKey: pk)
            }
            func recv(_ token: String) -> (UInt8, Bool, Data)? { WireFormat.unwrap(token, pairKey: pk) }
            func finish(_ raw: Data, _ deflate: Bool) -> String {
                let data = deflate ? (Deflate.decompress(raw) ?? Data()) : raw
                return String(decoding: data, as: UTF8.self)
            }

            let longMessage = String(repeating: "привет мир ", count: 40)
            let a1 = try send(longMessage, from: aliceAddr, to: bobAddr, store: alice, pad: true)
            guard WireFormat.isToken(a1) else { return "a1-shape" }
            guard let (t1, defl1, c1) = recv(a1), t1 == CiphertextMessage.MessageType.preKey.rawValue else { return "unwrap1" }
            let raw1 = try signalDecryptPreKey(message: PreKeySignalMessage(bytes: c1), from: aliceAddr, localAddress: bobAddr, sessionStore: bob, identityStore: bob, preKeyStore: bob, signedPreKeyStore: bob, kyberPreKeyStore: bob, context: ctx)
            guard finish(raw1, defl1) == longMessage else { return "A->B" }
            let a2 = try send("ответ", from: bobAddr, to: aliceAddr, store: bob, pad: false)
            guard let (_, defl2, c2) = recv(a2) else { return "unwrap2" }
            let raw2 = try signalDecrypt(message: SignalMessage(bytes: c2), from: bobAddr, to: aliceAddr, sessionStore: alice, identityStore: alice, context: ctx)
            guard finish(raw2, defl2) == "ответ" else { return "B->A" }

            let m3 = try send("padded whisper проверка", from: aliceAddr, to: bobAddr, store: alice, pad: true)
            guard let (_, defl3, c3) = recv(m3) else { return "unwrap3" }
            let raw3 = try signalDecrypt(message: SignalMessage(bytes: c3), from: aliceAddr, to: bobAddr, sessionStore: bob, identityStore: bob, context: ctx)
            return finish(raw3, defl3) == "padded whisper проверка" ? nil : "A->B-2"
        } catch { return "\(error)" }
    }
}
