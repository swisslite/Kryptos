@testable import CipherCore
import XCTest

final class Argon2Tests: XCTestCase {
    private func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private func check(_ memoryKiB: UInt32, _ iterations: UInt32, _ lanes: UInt32,
                       _ password: String, _ salt: String, _ expected: String) throws {
        let out = try Argon2id.hash(password: Data(password.utf8), salt: Data(salt.utf8),
                                    memoryKiB: memoryKiB, iterations: iterations,
                                    lanes: lanes, length: 32)
        XCTAssertEqual(hex(out), expected, "m=\(memoryKiB) t=\(iterations) p=\(lanes) pw=\(password) salt=\(salt)")
    }

    func testOfficialArgon2idVectors() throws {
        try check(65536, 2, 1, "password", "somesalt",
                  "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7")
        try check(256, 2, 1, "password", "somesalt",
                  "9dfeb910e80bad0311fee20f9c0e2b12c17987b4cac90c2ef54d5b3021c68bfe")
        try check(256, 2, 2, "password", "somesalt",
                  "6d093c501fd5999645e0ea3bf620d7b8be7fd2db59c20d9fff9539da2bf57037")
        try check(65536, 1, 1, "password", "somesalt",
                  "f6a5adc1ba723dddef9b5ac1d464e180fcd9dffc9d1cbf76cca2fed795d9ca98")
        try check(65536, 4, 1, "password", "somesalt",
                  "9025d48e68ef7395cca9079da4c4ec3affb3c8911fe4f86d1a2520856f63172c")
        try check(65536, 2, 1, "differentpassword", "somesalt",
                  "0b84d652cf6b0c4beaef0dfe278ba6a80df6696281d7e0d2891b817d8c458fde")
        try check(65536, 2, 1, "password", "diffsalt",
                  "bdf32b05ccc42eb15d58fd19b1f856b113da1e9a5874fdcc544308565aa8141c")
    }

    func testProfileParametersArePinned() {
        XCTAssertEqual(Argon2id.profileVersion, 1)
        XCTAssertEqual(Argon2id.memoryKiB, 65536)
        XCTAssertEqual(Argon2id.iterations, 3)
        XCTAssertEqual(Argon2id.lanes, 1)
        XCTAssertEqual(Argon2id.minSaltLength, 16)
    }

    func testProfileDerivationIsStable() throws {
        let salt = Data((0 ..< 16).map { UInt8($0) })
        let out = try Argon2id.derive(password: Data("correct horse".utf8), salt: salt, length: 44)
        XCTAssertEqual(hex(out),
                       "f4ae3395bb837ce60b20533d083efeb43f18009e1baf05f438d8190eeae2177d2b4ab708772d978b9d130a22")
    }

    func testDeriveRejectsShortSalt() {
        XCTAssertThrowsError(try Argon2id.derive(password: Data("pw".utf8),
                                                 salt: randomBytes(15), length: 44)) { error in
            XCTAssertEqual(error as? CipherError, .invalidInput)
        }
    }

    func testEmptyPasswordDerivesDistinctKey() throws {
        let salt = randomBytes(16)
        let a = try Argon2id.derive(password: Data(), salt: salt, length: 32)
        let b = try Argon2id.derive(password: Data("x".utf8), salt: salt, length: 32)
        XCTAssertNotEqual(a, b)
        XCTAssertEqual(a.count, 32)
    }

    func testTokenCarriesSaltThenVersion() throws {
        let blob = try PasswordCipher.encrypt(Data("hi".utf8), password: "pw")
        XCTAssertEqual(blob.count > PasswordCipher.saltLength, true)
        XCTAssertEqual(blob[blob.startIndex + PasswordCipher.saltLength], Argon2id.profileVersion)
    }

    func testRejectsUnknownProfileVersion() throws {
        var blob = [UInt8](try PasswordCipher.encrypt(Data("hi".utf8), password: "pw"))
        blob[PasswordCipher.saltLength] = 0x7F
        XCTAssertThrowsError(try PasswordCipher.decrypt(Data(blob), password: "pw")) { error in
            XCTAssertEqual(error as? CipherError, .malformed)
        }
    }

    func testTamperedVersionFailsAuthentication() throws {
        var blob = [UInt8](try PasswordCipher.encrypt(Data("hi".utf8), password: "pw"))
        blob[0] ^= 0xFF
        XCTAssertThrowsError(try PasswordCipher.decrypt(Data(blob), password: "pw")) { error in
            XCTAssertEqual(error as? CipherError, .decryptionFailed)
        }
    }

    func testSaltIsFreshPerMessage() throws {
        let a = try PasswordCipher.encrypt(Data("same".utf8), password: "pw")
        let b = try PasswordCipher.encrypt(Data("same".utf8), password: "pw")
        XCTAssertNotEqual(a.prefix(PasswordCipher.saltLength), b.prefix(PasswordCipher.saltLength))
        XCTAssertNotEqual(a, b)
    }
}
