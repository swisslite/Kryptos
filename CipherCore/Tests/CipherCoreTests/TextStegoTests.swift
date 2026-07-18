import XCTest
@testable import CipherCore

final class TextStegoTests: XCTestCase {
    func testRoundTripEnglish() throws {
        for n in [0, 1, 2, 3, 16, 33, 200, 1500] {
            let payload = randomBytes(n)
            let text = TextStego.encode(payload, language: .english)
            let back = try XCTUnwrap(TextStego.decode(text), "decode failed for n=\(n)")
            XCTAssertEqual(back, payload, "mismatch for n=\(n)")
        }
    }

    func testRoundTripRussian() throws {
        for n in [1, 5, 64, 777] {
            let payload = randomBytes(n)
            let text = TextStego.encode(payload, language: .russian)
            XCTAssertTrue(text.contains(" "))
            let back = try XCTUnwrap(TextStego.decode(text))
            XCTAssertEqual(back, payload)
        }
    }

    func testDecodeIsWhitespaceAndCaseTolerant() throws {
        let payload = randomBytes(40)
        let text = TextStego.encode(payload, language: .english)
        let mangled = "  " + text.uppercased().replacingOccurrences(of: " ", with: "\n  ") + "  "
        XCTAssertEqual(TextStego.decode(mangled), payload)
    }

    func testOrdinaryProseIsNotDecoded() {
        XCTAssertNil(TextStego.decode("Hello, how are you doing today?"))
        XCTAssertNil(TextStego.decode("Привет, как твои дела сегодня?"))
        XCTAssertNil(TextStego.decode(""))
    }

    func testOutputLooksLikeWords() {
        let text = TextStego.encode(randomBytes(30), language: .english)
        XCTAssertTrue(text.hasSuffix(".") || text.hasSuffix("?") || text.hasSuffix("!"))
        XCTAssertEqual(text.first, text.first?.uppercased().first)
    }

    func testCorruptionIsRejected() throws {
        let payload = Data((0 ..< 64).map { UInt8($0 * 3 & 0xFF) })
        let text = TextStego.encode(payload, language: .english, seed: 7)
        var tokens = text.split(separator: " ").map(String.init)
        tokens.removeLast()
        tokens.removeLast()
        XCTAssertNil(TextStego.decode(tokens.joined(separator: " ")))
        var swapped = text.split(separator: " ").map(String.init)
        let clean: (String) -> String = { $0.lowercased().filter(\.isLetter) }
        if let k = (2 ..< swapped.count - 2).first(where: { clean(swapped[$0]) != clean(swapped[$0 + 1]) }) {
            swapped.swapAt(k, k + 1)
            XCTAssertNil(TextStego.decode(swapped.joined(separator: " ")))
        }
    }

    func testDecodeSurvivesScreenChrome() throws {
        let payload = Data((0 ..< 64).map { UInt8(($0 * 3) & 0xFF) })
        for lang in [StegoLanguage.english, .russian] {
            let words = TextStego.encode(payload, language: lang)
            XCTAssertEqual(TextStego.decode("Алексей: \(words) изменено 2:14 PM ✓✓"), payload)
            let smart = SmartTextStego.encode(payload, language: lang)
            XCTAssertEqual(SmartTextStego.decode("Michael: \(smart) edited изменено 14:52"), payload)
        }
    }

    func testMaskedHeaderVariesFirstWord() {
        let payload = Data(repeating: 0x42, count: 24)
        var firstWords = Set<String>()
        for seed in 0 ..< 64 {
            let text = TextStego.encode(payload, language: .english, seed: seed)
            firstWords.insert(String(text.split(separator: " ").first ?? ""))
        }
        XCTAssertGreaterThan(firstWords.count, 32)
    }

    func testEncodedTextIsCompact() {
        let payload = randomBytes(96)
        let text = TextStego.encode(payload, language: .english)
        XCTAssertLessThan(text.count, 380)
    }
}
