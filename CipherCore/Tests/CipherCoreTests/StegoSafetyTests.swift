import XCTest
@testable import CipherCore

final class StegoSafetyTests: XCTestCase {
    private func wordlist(_ language: StegoLanguage) -> [String] {
        switch language {
        case .english: return StegoWordlists.english
        case .russian: return StegoWordlists.russian
        case .german: return StegoWordlists.german
        case .chinese: return StegoWordlists.chinese
        }
    }

    private func grammar(_ language: StegoLanguage) -> SmartStegoData.Grammar {
        switch language {
        case .english: return SmartStegoData.english
        case .russian: return SmartStegoData.russian
        case .german: return SmartStegoData.german
        case .chinese: return SmartStegoData.chinese
        }
    }

    func testMatcherSemantics() {
        XCTAssertTrue(StegoSafety.blocks("пиздец"))
        XCTAssertTrue(StegoSafety.blocks("Бомбардировка"))
        XCTAssertTrue(StegoSafety.blocks("gays"))
        XCTAssertTrue(StegoSafety.blocks("Ficken"))
        XCTAssertTrue(StegoSafety.blocks("war"))
        XCTAssertTrue(StegoSafety.blocks("ass"))

        for innocent in ["тебе", "себя", "хлеб", "потребность", "требую", "небо", "требовать",
                         "class", "assist", "glass", "method", "couple", "arsenal", "skill",
                         "total", "gunner", "warm", "ward", "hasel", "kante"] {
            XCTAssertFalse(StegoSafety.blocks(innocent), "\(innocent) must not be blocked")
        }
    }

    func testWordListsCarryNothingBlocked() {
        for language in StegoLanguage.allCases {
            let words = wordlist(language)
            XCTAssertEqual(words.count, 4096)
            XCTAssertEqual(Set(words).count, 4096)
            for word in words {
                XCTAssertFalse(StegoSafety.blocks(word), "\(language): \(word)")
                XCTAssertFalse(StegoSafety.containsBlocked(word), "\(language): \(word)")
            }
        }
    }

    func testGrammarCarriesNothingBlocked() {
        for language in StegoLanguage.allCases {
            let g = grammar(language)
            var tokens = g.openers
            for slot in g.slots { tokens += slot }
            for row in g.structures { tokens += row.filter { !$0.hasPrefix("#") } }
            for token in tokens {
                XCTAssertFalse(StegoSafety.blocks(token), "\(language): \(token)")
                XCTAssertFalse(StegoSafety.containsBlocked(token), "\(language): \(token)")
            }
        }
    }

    func testGeneratedCoverTextIsCleanInEveryMode() throws {
        for language in StegoLanguage.allCases {
            for _ in 0 ..< 60 {
                let payload = randomBytes(Int.random(in: 12 ... 400))
                let words = try XCTUnwrap(TextStego.encode(payload, language: language))
                let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: language))
                let letters = try XCTUnwrap(LetterStego.encode(payload, language: language))
                for text in [words, smart, letters] {
                    XCTAssertFalse(StegoSafety.containsBlocked(text),
                                   "\(language) produced blocked text: \(text.prefix(120))")
                }
            }
        }
    }

    func testSeedSearchIsBounded() {
        XCTAssertEqual(StegoSafety.seedAllowance(for: 12), StegoSafety.seedAttempts)
        XCTAssertEqual(StegoSafety.seedAllowance(for: 1900), StegoSafety.seedAttempts)
        XCTAssertLessThan(StegoSafety.seedAllowance(for: 32767), 32)
        XCTAssertGreaterThanOrEqual(StegoSafety.seedAllowance(for: 1 << 20), StegoSafety.minSeedAttempts)
        for payload in [1, 12, 1900, 12000, 32767, 1 << 20] {
            let allowance = StegoSafety.seedAllowance(for: payload)
            XCTAssertLessThanOrEqual(allowance * payload,
                                     StegoSafety.seedBudgetBytes + payload * StegoSafety.minSeedAttempts)
        }
    }

    func testScannerMatchesAcrossEncodings() {
        XCTAssertTrue(StegoSafety.containsBlocked("ааабомбаааа"))
        XCTAssertTrue(StegoSafety.containsBlocked("xxxTERRORxxx"))
        XCTAssertFalse(StegoSafety.containsBlocked("тебе себя хлеб потребность требую"))
        XCTAssertFalse(StegoSafety.containsBlocked("class assist glass method couple"))
        XCTAssertFalse(StegoSafety.containsBlocked(""))
        XCTAssertFalse(StegoSafety.containsBlocked("аб"))
    }

    func testHandshakeSizedCoverTextIsClean() throws {
        for language in StegoLanguage.allCases {
            let payload = randomBytes(1900)
            let words = try XCTUnwrap(TextStego.encode(payload, language: language))
            let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: language))
            let letters = try XCTUnwrap(LetterStego.encode(payload, language: language))
            for text in [words, smart, letters] {
                XCTAssertFalse(StegoSafety.containsBlocked(text), "\(language)")
            }
        }
    }
}
