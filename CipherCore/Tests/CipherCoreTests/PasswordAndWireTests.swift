@testable import CipherCore
import XCTest

final class PasswordAndWireTests: XCTestCase {
    func testPasswordRoundTrip() throws {
        let blob = try PasswordCipher.encrypt(Data("hello world".utf8), password: "correct horse battery")
        let out = try PasswordCipher.decrypt(blob, password: "correct horse battery")
        XCTAssertEqual(out, Data("hello world".utf8))
    }

    func testWrongPasswordFails() throws {
        let blob = try PasswordCipher.encrypt(Data("hello".utf8), password: "alpha")
        XCTAssertThrowsError(try PasswordCipher.decrypt(blob, password: "beta")) { error in
            XCTAssertEqual(error as? CipherError, .decryptionFailed)
        }
    }

    func testFacadePasswordRoundTrip() throws {
        let armored = try Kryptos.encrypt(text: "секретное сообщение 🕵️", password: "pw")
        XCTAssertTrue(Kryptos.containsMessage(armored))
        XCTAssertFalse(armored.contains("BEGIN"))
        XCTAssertFalse(armored.contains("KX1:"))
        XCTAssertEqual(try Kryptos.decrypt(armored: armored, password: "pw"), "секретное сообщение 🕵️")
    }

    func testFacadeRejectsPlainText() {
        XCTAssertThrowsError(try Kryptos.decrypt(armored: "просто обычный текст", password: "pw")) { error in
            XCTAssertEqual(error as? CipherError, .notAKryptosMessage)
        }
        XCTAssertFalse(Kryptos.containsMessage("просто обычный текст"))
    }

    func testPasswordCompressesRepetitiveText() throws {
        let text = String(repeating: "одно и то же сообщение снова и снова ", count: 60)
        let armored = try Kryptos.encrypt(text: text, password: "pw")
        XCTAssertLessThan(armored.count, text.utf8.count)
        XCTAssertEqual(try Kryptos.decrypt(armored: armored, password: "pw"), text)
    }

    func testWireWrapUnwrapRoundTrip() {
        let pairKey = Data("abcd".utf8) + Data("efgh".utf8)
        for n in [1, 20, 33, 200, 2048] {
            for padded in [false, true] {
                let body = randomBytes(n)
                let token = WireFormat.wrap(body, type: 3, deflate: true, padded: padded, pairKey: pairKey)
                if n >= 33 { XCTAssertTrue(WireFormat.isToken(token)) }
                guard let (type, deflate, out) = WireFormat.unwrap(token, pairKey: pairKey) else {
                    return XCTFail("unwrap n=\(n) padded=\(padded)")
                }
                XCTAssertEqual(type, 3)
                XCTAssertTrue(deflate)
                XCTAssertEqual(out, body)
            }
        }
    }

    func testWirePaddedGivesExactBucketLength() {
        let pairKey = Data("pair".utf8)
        let t40 = WireFormat.wrap(randomBytes(40), type: 2, deflate: false, padded: true, pairKey: pairKey)
        let t55 = WireFormat.wrap(randomBytes(55), type: 2, deflate: false, padded: true, pairKey: pairKey)
        let t200 = WireFormat.wrap(randomBytes(200), type: 2, deflate: false, padded: true, pairKey: pairKey)
        XCTAssertEqual(t40.count, t55.count)
        XCTAssertGreaterThan(t200.count, t40.count)
        let u40 = WireFormat.wrap(randomBytes(40), type: 2, deflate: false, padded: false, pairKey: pairKey)
        let u55 = WireFormat.wrap(randomBytes(55), type: 2, deflate: false, padded: false, pairKey: pairKey)
        XCTAssertNotEqual(u40.count, u55.count)
    }

    func testWireUnwrapWrongPairKeyNeverRecoversBody() {
        let body = randomBytes(80)
        for padded in [false, true] {
            let token = WireFormat.wrap(body, type: 2, deflate: false, padded: padded, pairKey: Data("one".utf8))
            for i in 0 ..< 64 {
                if let u = WireFormat.unwrap(token, pairKey: Data("k\(i)".utf8)) {
                    XCTAssertNotEqual(u.body, body)
                }
            }
            XCTAssertEqual(WireFormat.unwrap(token, pairKey: Data("one".utf8))?.body, body)
        }
    }

    func testWireTokenExtractedFromSurroundingText() {
        let token = WireFormat.wrap(randomBytes(64), type: 2, deflate: false, padded: false, pairKey: Data("p".utf8))
        XCTAssertEqual(WireFormat.extractToken("смотри: \(token) ответь"), token)
        XCTAssertNil(WireFormat.extractToken("just some ordinary words here"))
    }

    func testPaddingTargetBuckets() {
        XCTAssertEqual(Padding.target(0), 64)
        XCTAssertEqual(Padding.target(60), 64)
        XCTAssertEqual(Padding.target(64), 64)
        XCTAssertEqual(Padding.target(65), 128)
        XCTAssertEqual(Padding.target(128), 128)
        XCTAssertEqual(Padding.target(129), 256)
        XCTAssertEqual(Padding.target(1000), 1024)
        XCTAssertEqual(Padding.target(1 << 20), 1 << 20)
        XCTAssertEqual(Padding.target((1 << 20) + 1), 2 << 20)
    }

    func testPaddingFrameUnframeRoundTrip() {
        for n in [0, 1, 60, 61, 200, 5000] {
            let content = randomBytes(n)
            let framed = Padding.frame(content)
            XCTAssertEqual(framed.count, Padding.target(4 + n))
            XCTAssertEqual(Padding.unframe(framed), content)
        }
    }

    func testPaddingUnframeRejectsBadLength() {
        XCTAssertNil(Padding.unframe(Data([0x00, 0x00])))
        XCTAssertNil(Padding.unframe(Data([0xFF, 0xFF, 0xFF, 0xFF, 0x01])))
    }

    func testPasswordPaddedHidesLength() throws {
        let a = try Kryptos.encrypt(text: "да", password: "pw", pad: true)
        let b = try Kryptos.encrypt(text: "нет, совсем другой текст!", password: "pw", pad: true)
        XCTAssertEqual(a.count, b.count)
        XCTAssertEqual(try Kryptos.decrypt(armored: a, password: "pw"), "да")
        XCTAssertEqual(try Kryptos.decrypt(armored: b, password: "pw"), "нет, совсем другой текст!")
    }

    func testDeflateRoundTrip() {
        let data = Data(String(repeating: "ab", count: 500).utf8)
        guard let comp = Deflate.compress(data) else { return XCTFail("compress") }
        XCTAssertLessThan(comp.count, data.count)
        XCTAssertEqual(Deflate.decompress(comp), data)
    }

    func testDecompressesAndroidDeflate() {
        let hex = "f32eaa2c28c92f56284e4d2e2d4a55c84d2d2e4e4c4f55f01e151e7ac200"
        var bytes = Data()
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            bytes.append(UInt8(hex[i ..< j], radix: 16)!)
            i = j
        }
        let expected = Data(String(repeating: "Kryptos secure message ", count: 20).utf8)
        XCTAssertEqual(Deflate.decompress(bytes), expected)
    }

    func testDeflateExactLimitRoundTrip() {
        let data = Data(count: 64 * 1024)
        guard let comp = Deflate.compress(data) else { return XCTFail("compress") }
        XCTAssertEqual(Deflate.decompress(comp, limit: 64 * 1024)?.count, 64 * 1024)
        XCTAssertNil(Deflate.decompress(comp, limit: 64 * 1024 - 1))
    }

    func testDeflateHandlesGarbageWithoutCrash() {
        for _ in 0 ..< 50 {
            if let out = Deflate.decompress(randomBytes(200)) {
                XCTAssertLessThanOrEqual(out.count, Deflate.maxOutput)
            }
        }
        XCTAssertNil(Deflate.decompress(Data([0xFF, 0xFF, 0xFF, 0xFF])))
        XCTAssertNil(Deflate.decompress(Data()))
    }
}
