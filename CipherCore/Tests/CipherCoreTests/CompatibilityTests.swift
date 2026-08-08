import XCTest
@testable import CipherCore

final class CompatibilityTests: XCTestCase {

    private let ciphertext = Data((0 ..< 80).map { UInt8($0) })

    func testStegoWireRoundTripsEveryKnownFlagCombination() {
        for type in [UInt8(2), UInt8(3)] {
            for deflate in [false, true] {
                for padded in [false, true] {
                    let payload = StegoWire.frame(ciphertext, type: type, deflate: deflate, padded: padded)
                    XCTAssertFalse(StegoWire.carriesUnknownFlags(payload))
                    guard let framed = StegoWire.unframe(payload) else {
                        return XCTFail("unframe failed for \(type)/\(deflate)/\(padded)")
                    }
                    XCTAssertEqual(framed.type, type)
                    XCTAssertEqual(framed.deflate, deflate)
                    XCTAssertEqual(framed.body, ciphertext)
                }
            }
        }
    }

    func testStegoWireRejectsUnknownFlagsInsteadOfMisparsing() {
        var payload = StegoWire.frame(ciphertext, type: 3, deflate: true, padded: false)
        payload[payload.startIndex + 1] |= 0x40
        XCTAssertTrue(StegoWire.carriesUnknownFlags(payload))
        XCTAssertNil(StegoWire.unframe(payload))
    }

    func testStegoWireStillAcceptsPayloadsFromOlderBuilds() {
        var legacy = Data([StegoWire.prefix, 3])
        legacy.append(ciphertext)
        XCTAssertFalse(StegoWire.carriesUnknownFlags(legacy))
        let framed = StegoWire.unframe(legacy)
        XCTAssertEqual(framed?.type, 3)
        XCTAssertEqual(framed?.deflate, false)
        XCTAssertEqual(framed?.body, ciphertext)
    }

    func testWireUnwrapRejectsUnknownHeaderBits() throws {
        let pairKey = Data("alicebob".utf8)
        let salt = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88])
        for padded in [false, true] {
            let token = try WireFormat.wrap(ciphertext, type: 3, deflate: false, padded: padded,
                                            pairKey: pairKey, salt: salt)
            let unwrapped = WireFormat.unwrap(token, pairKey: pairKey)
            XCTAssertEqual(unwrapped?.body, ciphertext)
        }
        let (key, iv) = WireFormat.derive(pairKey: pairKey, salt: salt)
        var plain = Data([0x43])
        plain.append(ciphertext)
        let masked = try XCTUnwrap(WireFormat.ctr(key: key, iv: iv, plain))
        let token = WireFormat.token(salt + masked)
        XCTAssertNil(WireFormat.unwrap(token, pairKey: pairKey))
    }

    private func keyText(_ body: String) -> String { KeyText.prefix + body }

    func testKeyTextReadsAPlainKey() {
        let blob = Data([0x01, 0xAB, 0xCD, 0xEF])
        let blobs = KeyText.blobs(in: keyText(blob.base64EncodedString()))
        XCTAssertEqual(blobs.first, blob)
    }

    func testKeyTextRecoversAKeyBrokenAcrossLines() {
        let blob = Data((0 ..< 600).map { UInt8(($0 * 37 + 11) & 0xFF) })
        let b64 = blob.base64EncodedString()
        let wrapped = stride(from: 0, to: b64.count, by: 76).map { offset -> String in
            let s = b64.index(b64.startIndex, offsetBy: offset)
            let e = b64.index(s, offsetBy: 76, limitedBy: b64.endIndex) ?? b64.endIndex
            return String(b64[s ..< e])
        }.joined(separator: "\n")
        XCTAssertTrue(wrapped.contains("\n"))
        XCTAssertTrue(KeyText.blobs(in: keyText(wrapped)).contains(blob))
    }

    func testATruncatedFirstLineNeverHidesTheRealKey() {
        let blob = Data((0 ..< 600).map { UInt8(($0 * 3) & 0xFF) })
        let b64 = blob.base64EncodedString()
        let chunked = stride(from: 0, to: b64.count, by: 20).map { offset -> String in
            let s = b64.index(b64.startIndex, offsetBy: offset)
            let e = b64.index(s, offsetBy: 20, limitedBy: b64.endIndex) ?? b64.endIndex
            return String(b64[s ..< e])
        }.joined(separator: "\n")
        let candidates = KeyText.blobs(in: keyText(chunked))
        XCTAssertGreaterThanOrEqual(candidates.count, 2)
        XCTAssertNotEqual(candidates.first, blob)
        XCTAssertEqual(candidates.last, blob)
    }

    func testKeyTextKeepsIgnoringTrailingProse() {
        let blob = Data((0 ..< 120).map { UInt8(($0 * 13 + 5) & 0xFF) })
        let text = keyText(blob.base64EncodedString()) + " — это мой ключ"
        XCTAssertEqual(KeyText.blobs(in: text).first, blob)
    }

    func testKeyTextRejectsTextWithoutAKey() {
        XCTAssertTrue(KeyText.blobs(in: "just an ordinary sentence").isEmpty)
        XCTAssertTrue(KeyText.blobs(in: keyText("!!!!")).isEmpty)
    }
}
