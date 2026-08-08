@testable import CipherCore
import XCTest

final class SteganographyTests: XCTestCase {
    private let width = 128
    private let height = 128

    private func photo(seed: UInt64 = 7) -> [UInt8] {
        var state = seed
        func next() -> UInt8 {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return UInt8((state >> 33) & 0xFF)
        }
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        for y in 0 ..< height {
            for x in 0 ..< width {
                let base = (y * width + x) * 4
                if x < width / 2 {
                    pixels[base] = 120
                    pixels[base + 1] = 130
                    pixels[base + 2] = 140
                } else {
                    pixels[base] = next()
                    pixels[base + 1] = next()
                    pixels[base + 2] = next()
                }
                pixels[base + 3] = 255
            }
        }
        return pixels
    }

    func testHideRevealRoundTrip() throws {
        let cover = photo()
        let message = Data("совершенно секретное сообщение 🕵️".utf8)
        let stego = try ImageStego.hide(message, password: "correct horse", rgba: cover,
                                        width: width, height: height)
        let out = try ImageStego.reveal(rgba: stego, width: width, height: height,
                                        password: "correct horse")
        XCTAssertEqual(out, message)
    }

    func testCandidateSetSurvivesEmbedding() throws {
        let cover = photo()
        let stego = try ImageStego.hide(Data(randomBytes(200)), password: "pw", rgba: cover,
                                        width: width, height: height)
        let before = try ImageStego.candidates(rgba: cover, width: width, height: height)
        let after = try ImageStego.candidates(rgba: stego, width: width, height: height)
        XCTAssertEqual(before, after)
        XCTAssertGreaterThan(before.count, ImageStego.saltBits + ImageStego.lengthBits)
    }

    func testOnlyBlueChannelChangesAndOnlyByOne() throws {
        let cover = photo()
        let stego = try ImageStego.hide(Data("hello".utf8), password: "pw", rgba: cover,
                                        width: width, height: height)
        var changed = 0
        for i in 0 ..< cover.count where cover[i] != stego[i] {
            changed += 1
            XCTAssertEqual(i % 4, 2)
            XCTAssertEqual(abs(Int(cover[i]) - Int(stego[i])), 1)
        }
        XCTAssertGreaterThan(changed, 0)
    }

    func testFlatRegionIsNeverTouched() throws {
        let cover = photo()
        let stego = try ImageStego.hide(Data(randomBytes(120)), password: "pw", rgba: cover,
                                        width: width, height: height)
        for y in 0 ..< height {
            for x in 0 ..< width / 2 {
                let base = (y * width + x) * 4
                XCTAssertEqual(cover[base + 2], stego[base + 2])
            }
        }
    }

    func testWrongPasswordFails() throws {
        let cover = photo()
        let stego = try ImageStego.hide(Data("секрет".utf8), password: "right", rgba: cover,
                                        width: width, height: height)
        XCTAssertThrowsError(try ImageStego.reveal(rgba: stego, width: width, height: height,
                                                   password: "wrong")) { error in
            XCTAssertEqual(error as? CipherError, .decryptionFailed)
        }
    }

    func testCleanPhotoYieldsNoMessage() {
        let cover = photo(seed: 99)
        XCTAssertThrowsError(try ImageStego.reveal(rgba: cover, width: width, height: height,
                                                   password: "pw")) { error in
            XCTAssertEqual(error as? CipherError, .decryptionFailed)
        }
    }

    func testNoPlaintextMarkerInLowBits() throws {
        let cover = photo()
        let stego = try ImageStego.hide(Data("hello".utf8), password: "pw", rgba: cover,
                                        width: width, height: height)
        var packed = [UInt8]()
        var acc: UInt8 = 0
        var n = 0
        for i in stride(from: 2, to: stego.count, by: 4) {
            acc = (acc << 1) | (stego[i] & 1)
            n += 1
            if n % 8 == 0 { packed.append(acc); acc = 0 }
        }
        let marker = Array("KXS1".utf8)
        var found = false
        for i in 0 ... max(0, packed.count - marker.count) where Array(packed[i ..< min(i + 4, packed.count)]) == marker {
            found = true
        }
        XCTAssertFalse(found)
    }

    func testTwoRunsProduceDifferentCarriers() throws {
        let cover = photo()
        let message = Data("одно и то же".utf8)
        let a = try ImageStego.hide(message, password: "pw", rgba: cover, width: width, height: height)
        let b = try ImageStego.hide(message, password: "pw", rgba: cover, width: width, height: height)
        XCTAssertNotEqual(a, b)
        XCTAssertEqual(try ImageStego.reveal(rgba: a, width: width, height: height, password: "pw"), message)
        XCTAssertEqual(try ImageStego.reveal(rgba: b, width: width, height: height, password: "pw"), message)
    }

    func testCapacityIsEnforced() throws {
        let cover = photo()
        let capacity = ImageStego.capacity(rgba: cover, width: width, height: height)
        XCTAssertGreaterThan(capacity, 0)
        XCTAssertThrowsError(try ImageStego.hide(Data(randomBytes(capacity + 64)), password: "pw",
                                                 rgba: cover, width: width, height: height)) { error in
            XCTAssertEqual(error as? CipherError, .stegoCapacityExceeded)
        }
    }

    func testNearCapacityRoundTrip() throws {
        let cover = photo()
        let capacity = ImageStego.capacity(rgba: cover, width: width, height: height)
        XCTAssertGreaterThan(capacity, 0)
        let message = Data(randomBytes(capacity))
        let stego = try ImageStego.hide(message, password: "pw", rgba: cover,
                                        width: width, height: height)
        XCTAssertEqual(try ImageStego.reveal(rgba: stego, width: width, height: height,
                                             password: "pw"), message)
        let before = try ImageStego.candidates(rgba: cover, width: width, height: height)
        let after = try ImageStego.candidates(rgba: stego, width: width, height: height)
        XCTAssertEqual(before, after)
    }

    func testCarrierSizeNeverDependsOnMessageLength() throws {
        let cover = photo()
        for message in ["да", String(repeating: "заметно более длинный текст ", count: 30)] {
            let stego = try ImageStego.hide(Data(message.utf8), password: "pw", rgba: cover,
                                            width: width, height: height)
            XCTAssertEqual(stego.count, cover.count)
        }
    }

    func testWholeCapacityStaysUsable() throws {
        let cover = photo()
        let capacity = ImageStego.capacity(rgba: cover, width: width, height: height)
        let message = Data(randomBytes(capacity))
        let stego = try ImageStego.hide(message, password: "pw", rgba: cover, width: width, height: height)
        XCTAssertEqual(try ImageStego.reveal(rgba: stego, width: width, height: height, password: "pw"), message)
    }

    func testRejectsMalformedBuffer() {
        XCTAssertThrowsError(try ImageStego.hide(Data("x".utf8), password: "pw",
                                                 rgba: [UInt8](repeating: 0, count: 10),
                                                 width: width, height: height)) { error in
            XCTAssertEqual(error as? CipherError, .invalidInput)
        }
    }
}
