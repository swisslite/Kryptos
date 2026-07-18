import XCTest
@testable import CipherCore

final class SmartTextStegoTests: XCTestCase {
    private func isPowerOfTwo(_ n: Int) -> Bool { n > 0 && (n & (n - 1)) == 0 }

    private func lettersOnly(_ w: String) -> Bool {
        !w.isEmpty && w.unicodeScalars.allSatisfy { Character($0).isLetter }
    }

    func testGrammarDataInvariants() {
        for grammar in [SmartStegoData.english, SmartStegoData.russian] {
            XCTAssertTrue(isPowerOfTwo(grammar.openers.count))
            XCTAssertEqual(Set(grammar.openers).count, grammar.openers.count)
            for w in grammar.openers { XCTAssertTrue(lettersOnly(w) && w == w.lowercased()) }
            for slot in grammar.slots {
                XCTAssertTrue(isPowerOfTwo(slot.count), "slot size \(slot.count)")
                XCTAssertEqual(Set(slot).count, slot.count, "duplicate in slot")
                for w in slot { XCTAssertTrue(lettersOnly(w) && w == w.lowercased(), "bad word '\(w)'") }
            }
            XCTAssertFalse(grammar.structOf.isEmpty)
            for s in grammar.structOf { XCTAssertTrue(s >= 0 && s < grammar.structures.count) }
            XCTAssertEqual(grammar.structOf.count, grammar.openers.count)
            for structure in grammar.structures {
                for token in structure {
                    if token.hasPrefix("#") {
                        let idx = Int(token.dropFirst())
                        XCTAssertNotNil(idx)
                        XCTAssertTrue(idx! >= 0 && idx! < grammar.slots.count)
                    } else {
                        XCTAssertTrue(lettersOnly(token) && token == token.lowercased())
                    }
                }
            }
        }
    }

    func testRoundTripAllSizes() throws {
        for language in StegoLanguage.allCases {
            for n in [0, 1, 2, 3, 5, 16, 33, 64, 100, 127, 128, 200, 300, 512, 1000, 4096] {
                let payload = Data((0..<n).map { UInt8(($0 * 37 + 11) & 0xFF) })
                let text = SmartTextStego.encode(payload, language: language, seed: (n * 7) & 0xFF)
                let back = try XCTUnwrap(SmartTextStego.decode(text), "nil n=\(n) lang=\(language)")
                XCTAssertEqual(back, payload, "mismatch n=\(n) lang=\(language)")
                XCTAssertTrue(SmartTextStego.looksLikeStego(text))
            }
        }
    }

    func testRandomRoundTrip() throws {
        for language in StegoLanguage.allCases {
            for _ in 0..<200 {
                let n = Int.random(in: 0...400)
                let payload = randomBytes(n)
                let text = SmartTextStego.encode(payload, language: language)
                XCTAssertEqual(SmartTextStego.decode(text), payload, "n=\(n) lang=\(language)")
            }
        }
    }

    func testDisjointFromStandardStego() {
        for language in StegoLanguage.allCases {
            for n in [1, 8, 33, 64, 200, 512] {
                let payload = Data((0..<n).map { UInt8(($0 * 41 + 7) & 0xFF) })
                let text = SmartTextStego.encode(payload, language: language, seed: n & 0xFF)
                XCTAssertNil(TextStego.decode(text), "standard decoded smart text n=\(n)")
            }
        }
    }

    func testOrdinaryProseIsRejected() {
        XCTAssertNil(SmartTextStego.decode("just some ordinary words that mean nothing at all"))
        XCTAssertNil(SmartTextStego.decode("Привет, как твои дела сегодня вечером?"))
        XCTAssertNil(SmartTextStego.decode(""))
        XCTAssertNil(SmartTextStego.decode("Now then"))
    }

    func testDecodeIsWhitespaceAndCaseTolerant() throws {
        let payload = randomBytes(48)
        let text = SmartTextStego.encode(payload, language: .english, seed: 3)
        let mangled = "\n  " + text.uppercased().replacingOccurrences(of: " ", with: "\n") + "  \n"
        XCTAssertEqual(SmartTextStego.decode(mangled), payload)
    }

    func testOutputLooksLikeSentences() {
        for language in StegoLanguage.allCases {
            let text = SmartTextStego.encode(randomBytes(40), language: language)
            XCTAssertTrue(text.hasSuffix(".") || text.hasSuffix("!"))
            XCTAssertEqual(text.first, text.first?.uppercased().first)
        }
    }

    func testCorruptionIsRejected() throws {
        let payload = Data((0..<80).map { UInt8($0 * 3 & 0xFF) })
        let text = SmartTextStego.encode(payload, language: .english, seed: 11)
        var tokens = text.split(separator: " ").map(String.init)
        tokens.removeLast()
        XCTAssertNil(SmartTextStego.decode(tokens.joined(separator: " ")))
        var swapped = text.split(separator: " ").map(String.init)
        let clean: (String) -> String = { $0.lowercased().filter(\.isLetter) }
        if let k = (2..<swapped.count - 2).first(where: { clean(swapped[$0]) != clean(swapped[$0 + 1]) }) {
            swapped.swapAt(k, k + 1)
            XCTAssertNil(SmartTextStego.decode(swapped.joined(separator: " ")))
        }
    }

    func testFirstWordVariesAcrossSeeds() {
        let payload = Data(repeating: 0x42, count: 24)
        var firstWords = Set<String>()
        for seed in 0..<256 {
            let text = SmartTextStego.encode(payload, language: .english, seed: seed)
            firstWords.insert(String(text.split(separator: " ")[0]))
        }
        XCTAssertGreaterThanOrEqual(firstWords.count, 40, "got \(firstWords.count)")
    }

    func testCompactness() {
        let payload = randomBytes(96)
        for language in StegoLanguage.allCases {
            let text = SmartTextStego.encode(payload, language: language)
            XCTAssertLessThan(text.count, 1100, "lang=\(language) count=\(text.count)")
        }
    }

    func testCrossPlatformVectors() {
        let probe = Data([0x03, 0x02, 0xAB, 0xCD, 0xEF, 0x10, 0x22, 0x77, 0x91, 0x04, 0x5C, 0xBE])
        let en = SmartTextStego.encode(probe, language: .english, seed: 0x5C)
        let ru = SmartTextStego.encode(probe, language: .russian, seed: 0xB3)
        XCTAssertEqual(en,
            "Luckily, his agent clenched their thimble, so this teacher conveyed some platter. "
            + "Seemingly, one customer poked his strap gamely. "
            + "Naturally, one writer found the box.")
        XCTAssertEqual(ru,
            "Часто всякий архитектор распилил некий лоскут, а иной портной почистил твой зубец. "
            + "Честно, мой учитель подпилил тот противень, и любой слесарь заложил один ключ.")
        XCTAssertEqual(SmartTextStego.decode(en), probe)
        XCTAssertEqual(SmartTextStego.decode(ru), probe)
    }
}
