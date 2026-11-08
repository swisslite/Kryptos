import Foundation
import CryptoKit
import XCTest
@testable import CipherCore

/// Swift mirror of android/core/src/test/.../CoreRoundTripTest.kt.
final class CoreRoundTripTests: XCTestCase {

    // MARK: - padding

    func testPaddingRoundTripAndPowerOfTwoSizing() {
        for size in [0, 1, 17, 59, 60, 61, 200, 1000] {
            let content = Data((0 ..< size).map { UInt8(($0 * 31) & 0xFF) })
            let framed = Padding.frame(content)
            XCTAssertEqual(framed.count, Padding.target(4 + size))
            XCTAssertEqual(Padding.unframe(framed), content)
        }
        XCTAssertEqual(Padding.target(4 + 60), 64)
        XCTAssertEqual(Padding.target(4 + 61), 128)
    }

    func testPaddingRejectsCorruptLength() {
        let framed = Padding.frame(Data("secret".utf8))
        var tampered = framed
        tampered[tampered.startIndex + 3] = 0x7F
        XCTAssertNil(Padding.unframe(tampered))
        XCTAssertNil(Padding.unframe(Data(framed.prefix(3))))
    }

    // MARK: - deflate

    func testDeflateBodyRoundTripsForCompressibleAndShortText() {
        let longText = String(repeating: "Kryptos encrypts before the channel sees it. ", count: 200)
        for text in [longText, "hi", ""] {
            let body = Deflate.body(text)
            XCTAssertEqual(Deflate.text(body.bytes, deflated: body.deflated), text)
        }
    }

    func testDeflateTextFallsBackToTheOtherReading() {
        let plain = Data("plain words that were not compressed".utf8)
        XCTAssertEqual(Deflate.text(plain, deflated: true), "plain words that were not compressed")
    }

    func testDeflateRejectsNonUTF8Payload() {
        XCTAssertNil(Deflate.text(Data([0xFF, 0xFE, 0x00]), deflated: false))
    }

    // MARK: - shared-password mode

    func testPasswordCipherRoundTrip() throws {
        let plaintext = Data(String(repeating: "shared password mode / 共享密码", count: 20).utf8)
        for pad in [false, true] {
            let sealed = try PasswordCipher.encrypt(plaintext, password: "correct horse battery staple", pad: pad)
            XCTAssertEqual(try PasswordCipher.decrypt(sealed, password: "correct horse battery staple"), plaintext)
        }
    }

    func testPasswordCipherRejectsWrongPasswordAndTampering() throws {
        let sealed = try PasswordCipher.encrypt(Data("sensitive".utf8), password: "right password")
        XCTAssertThrowsError(try PasswordCipher.decrypt(sealed, password: "wrong password"))
        var tampered = sealed
        let last = tampered.index(before: tampered.endIndex)
        tampered[last] = tampered[last] ^ 0x01
        XCTAssertThrowsError(try PasswordCipher.decrypt(tampered, password: "right password"))
    }

    func testPasswordCipherFlagByteIsInsideTheGCMTag() throws {
        let key = SymmetricKey(data: Data(repeating: 5, count: 32))
        let nonce = try AES.GCM.Nonce(data: Data(repeating: 7, count: 12))
        let sealed = try PasswordCipher.sealBody(
            Data(String(repeating: "x", count: 500).utf8),
            key: key, nonce: nonce, version: 1, pad: true
        )
        var tampered = sealed
        tampered[tampered.startIndex] ^= 0x01
        XCTAssertThrowsError(try PasswordCipher.openBody(tampered, key: key, nonce: nonce, version: 1))
        XCTAssertThrowsError(try PasswordCipher.openBody(sealed, key: key, nonce: nonce, version: 2),
                             "the profile version is AEAD-associated data")
    }

    func testArgon2idMatchesTheCrossLanguageVector() throws {
        let salt = Data((0 ..< 16).map { UInt8(($0 * 17 + 1) & 0xFF) })
        let derived = try Argon2id.derive(password: "cross-language-vector", salt: salt, length: 44)
        XCTAssertEqual(derived.count, 44)
        XCTAssertEqual(derived.map { String(format: "%02x", $0) }.joined(), Self.crossLanguageArgon2Hex)
    }

    // MARK: - steganography

    func testTextStegoRoundTrip() throws {
        let payload = Data((0 ..< 64).map { UInt8(($0 * 29) & 0xFF) })
        let cover = try XCTUnwrap(TextStego.encode(payload, language: .english))
        XCTAssertEqual(TextStego.decode(cover), payload)
    }

    func testLetterStegoRoundTrip() throws {
        let payload = Data((0 ..< 48).map { UInt8(($0 * 11) & 0xFF) })
        let cover = try XCTUnwrap(LetterStego.encode(payload, language: .english))
        XCTAssertEqual(LetterStego.decode(cover), payload)
    }

    func testSmartTextStegoRoundTrip() throws {
        let payload = Data((0 ..< 48).map { UInt8(($0 * 13) & 0xFF) })
        let cover = try XCTUnwrap(SmartTextStego.encode(payload, language: .english))
        XCTAssertEqual(SmartTextStego.decode(cover), payload)
    }

    func testStegoWordlistsArePackagedWithTheCoreModule() {
        for language in StegoLanguage.allCases {
            XCTAssertGreaterThanOrEqual(language.words.count, 4096, "\(language.rawValue) wordlist missing")
        }
    }

    // "cross-language-vector", salt = (i*17+1), 44 bytes, Argon2id 64 MiB / t=3 / p=1.
    // Produced by CoreRoundTripTest.kt and asserted verbatim there.
    static let crossLanguageArgon2Hex =
        "474ec627d0e164299eaa2828f74bf405866735fa8fb586e83abdeb48088851b17364075eac5ad2c55df150ef"
}
