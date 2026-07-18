@testable import CipherCore
import XCTest

final class SteganographyTests: XCTestCase {
    private func randomPixels(count: Int) -> [UInt8] {
        (0 ..< count * 4).map { _ in UInt8.random(in: 0 ... 255) }
    }

    func testEmbedExtractRoundTrip() throws {
        let pixels = randomPixels(count: 200 * 200)
        let payload = randomBytes(1_000)
        let stego = try LSBStego.embed(payload: payload, intoRGBA: pixels)
        XCTAssertEqual(try LSBStego.extract(fromRGBA: stego), payload)
    }

    func testEncryptThenHideThenRecover() throws {
        let secret = "координаты встречи: 55.75, 37.61"
        let blob = try PasswordCipher.encrypt(Data(secret.utf8), password: "meet-me")
        let pixels = randomPixels(count: 300 * 300)

        let stego = try LSBStego.embed(payload: blob, intoRGBA: pixels)
        let recovered = try LSBStego.extract(fromRGBA: stego)
        let plaintext = try PasswordCipher.decrypt(recovered, password: "meet-me")
        XCTAssertEqual(String(decoding: plaintext, as: UTF8.self), secret)
    }

    func testCapacityExceededThrows() {
        let pixels = randomPixels(count: 10)
        XCTAssertThrowsError(try LSBStego.embed(payload: randomBytes(500), intoRGBA: pixels)) { error in
            XCTAssertEqual(error as? CipherError, .stegoCapacityExceeded)
        }
    }

    func testExtractFromCleanImageReportsNoData() {
        let pixels = [UInt8](repeating: 200, count: 100 * 4)
        XCTAssertThrowsError(try LSBStego.extract(fromRGBA: pixels)) { error in
            XCTAssertEqual(error as? CipherError, .noHiddenData)
        }
    }

    func testEmbeddingIsVisuallyMinimal() throws {
        let pixels = randomPixels(count: 128 * 128)
        let stego = try LSBStego.embed(payload: randomBytes(500), intoRGBA: pixels)
        for i in 0 ..< pixels.count {
            XCTAssertLessThanOrEqual(abs(Int(pixels[i]) - Int(stego[i])), 1)
            if i % 4 == 3 { XCTAssertEqual(pixels[i], stego[i]) }
        }
    }
}
