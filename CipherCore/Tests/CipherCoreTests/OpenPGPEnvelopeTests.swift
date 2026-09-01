import XCTest
@testable import CipherCore

final class OpenPGPEnvelopeTests: XCTestCase {

    private func oldFormat(tag: UInt8, body: [UInt8]) -> [UInt8] {
        [0x80 | (tag << 2)] + [UInt8(body.count)] + body
    }

    private func newFormat(tag: UInt8, body: [UInt8]) -> [UInt8] {
        [0xC0 | tag] + [UInt8(body.count)] + body
    }

    func testLiteralOnlyMessageIsNotEncrypted() {
        let literal = oldFormat(tag: 11, body: Array("hello".utf8))
        XCTAssertFalse(OpenPGPEnvelope.isEncrypted(Data(literal)))
    }

    func testCompressedLiteralIsNotEncrypted() {
        let compressed = newFormat(tag: 8, body: Array(repeating: 0x42, count: 12))
        let shape = OpenPGPEnvelope.shape(of: Data(compressed))
        XCTAssertFalse(shape.encrypted)
        XCTAssertEqual(shape.compressedLayers, 1)
    }

    func testSessionKeyPlusIntegrityProtectedIsEncrypted() {
        let bytes = newFormat(tag: 1, body: Array(repeating: 0x01, count: 20))
            + newFormat(tag: 18, body: Array(repeating: 0x02, count: 30))
        let shape = OpenPGPEnvelope.shape(of: Data(bytes))
        XCTAssertTrue(shape.encrypted)
        XCTAssertEqual(shape.sessionKeys, 1)
        XCTAssertFalse(shape.truncated)
    }

    func testLegacySymmetricallyEncryptedIsEncrypted() {
        let bytes = oldFormat(tag: 3, body: Array(repeating: 0x01, count: 10))
            + oldFormat(tag: 9, body: Array(repeating: 0x02, count: 10))
        XCTAssertTrue(OpenPGPEnvelope.isEncrypted(Data(bytes)))
    }

    func testGarbageIsNotEncrypted() {
        XCTAssertFalse(OpenPGPEnvelope.isEncrypted(Data([0x00, 0x01, 0x02, 0x03])))
        XCTAssertFalse(OpenPGPEnvelope.isEncrypted(Data()))
        XCTAssertFalse(OpenPGPEnvelope.isEncrypted(Data(repeating: 0x41, count: 512)))
    }

    func testTruncatedPacketIsReportedNotCrashing() {
        let bytes: [UInt8] = [0xC0 | 18, 0xFF, 0x00, 0x00, 0x10, 0x00]
        let shape = OpenPGPEnvelope.shape(of: Data(bytes))
        XCTAssertTrue(shape.truncated)
        XCTAssertTrue(shape.encrypted)
    }

    func testNestedCompressionIsCounted() {
        let bytes = newFormat(tag: 8, body: [1, 2, 3]) + newFormat(tag: 8, body: [4, 5, 6])
        XCTAssertEqual(OpenPGPEnvelope.shape(of: Data(bytes)).compressedLayers, 2)
    }

    func testWalkerTerminatesOnAdversarialInput() {
        var bytes: [UInt8] = []
        for _ in 0 ..< 20_000 { bytes.append(contentsOf: [0xC0 | 11, 0x00]) }
        let shape = OpenPGPEnvelope.shape(of: Data(bytes))
        XCTAssertFalse(shape.encrypted)
    }

    private func partialLength(tag: UInt8, body: [UInt8], chunk: Int) -> [UInt8] {
        var exponent = 0
        while (1 << exponent) < chunk { exponent += 1 }
        let step = 1 << exponent
        var out: [UInt8] = [0xC0 | tag]
        var i = 0
        while body.count - i > step {
            out.append(UInt8(224 + exponent))
            out += Array(body[i ..< i + step])
            i += step
        }
        let rest = body.count - i
        if rest < 192 {
            out.append(UInt8(rest))
        } else {
            let v = rest - 192
            out += [UInt8(192 + (v >> 8)), UInt8(v & 0xFF)]
        }
        out += Array(body[i...])
        return out
    }

    func testPartialBodyLengthEncryptedPacketIsRecognised() {
        let body = (0 ..< 3000).map { UInt8(($0 * 7 + 3) & 0xFF) }
        let bytes = newFormat(tag: 1, body: Array(repeating: 0x01, count: 20))
            + partialLength(tag: 18, body: body, chunk: 512)
        let shape = OpenPGPEnvelope.shape(of: Data(bytes))
        XCTAssertTrue(shape.encrypted)
        XCTAssertFalse(shape.truncated)
        XCTAssertEqual(shape.sessionKeys, 1)
    }

    func testPartialBodyLengthLiteralIsStillNotEncrypted() {
        let body = (0 ..< 3000).map { UInt8($0 & 0xFF) }
        let shape = OpenPGPEnvelope.shape(of: Data(partialLength(tag: 11, body: body, chunk: 512)))
        XCTAssertFalse(shape.encrypted)
        XCTAssertFalse(shape.truncated)
    }

    func testTrailingBytesDoNotHideTheEncryptedLayer() {
        let bytes = newFormat(tag: 18, body: [1, 2, 3, 4]) + [0x00]
        let shape = OpenPGPEnvelope.shape(of: Data(bytes))
        XCTAssertTrue(shape.encrypted)
        XCTAssertTrue(shape.truncated)
    }

    func testOneBytePartialChunksTerminate() {
        var bytes: [UInt8] = [0xC0 | 18]
        for _ in 0 ..< 200_000 { bytes += [224, 0x00] }
        bytes.append(0x00)
        let shape = OpenPGPEnvelope.shape(of: Data(bytes))
        XCTAssertTrue(shape.encrypted)
        XCTAssertTrue(shape.truncated)
    }

    func testTwoOctetLengthMatchesRFC4880() {
        for bodyLength in [192, 255, 256, 448, 512, 1000, 2000, 8383] {
            let v = bodyLength - 192
            let header: [UInt8] = [0xC0 | 18, UInt8(192 + (v >> 8)), UInt8(v & 0xFF)]
            let bytes = header + Array(repeating: 0x77, count: bodyLength)
            let shape = OpenPGPEnvelope.shape(of: Data(bytes))
            XCTAssertTrue(shape.encrypted, "body \(bodyLength)")
            XCTAssertFalse(shape.truncated, "body \(bodyLength)")
        }
    }
}
