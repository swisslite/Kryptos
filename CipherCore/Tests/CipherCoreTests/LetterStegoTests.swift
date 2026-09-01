import XCTest
@testable import CipherCore

final class LetterStegoTests: XCTestCase {
    private let probe = Data([0x03, 0x02, 0xAB, 0xCD, 0xEF, 0x10, 0x22, 0x77, 0x91, 0x04, 0x5C, 0xBE])

    func testRoundTripAcrossSizes() throws {
        for language in StegoLanguage.allCases {
            for n in [1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 31, 64, 120, 400, 1900] {
                let payload = randomBytes(n)
                let text = try XCTUnwrap(LetterStego.encode(payload, language: language))
                XCTAssertEqual(LetterStego.decode(text), payload, "\(language) n=\(n)")
            }
        }
    }

    func testOutputIsLettersOnlyAndSingleRun() throws {
        for language in StegoLanguage.allCases {
            let text = try XCTUnwrap(LetterStego.encode(randomBytes(120), language: language))
            XCTAssertTrue(text.allSatisfy { $0.isLetter })
            XCTAssertFalse(text.contains { $0.isUppercase })
            XCTAssertFalse(text.contains(" "))
            if language == .russian {
                XCTAssertFalse(text.contains("ё"))
            }
        }
    }

    func testShorterThanTheOtherModes() throws {
        var body = Data([0x03, 0x02])
        body.append(randomBytes(118))
        for language in StegoLanguage.allCases {
            let letters = try XCTUnwrap(LetterStego.encode(body, language: language)).count
            let smart = try XCTUnwrap(SmartTextStego.encode(body, language: language)).count
            XCTAssertLessThan(letters, smart, "\(language)")
            guard !language.isHan else { continue }
            let words = try XCTUnwrap(TextStego.encode(body, language: language)).count
            XCTAssertLessThan(letters, words, "\(language)")
        }
    }

    func testSameInputProducesDifferentOutput() throws {
        for language in StegoLanguage.allCases {
            var seen = Set<String>()
            for _ in 0 ..< 24 {
                seen.insert(try XCTUnwrap(LetterStego.encode(probe, language: language)))
            }
            XCTAssertGreaterThan(seen.count, 1)
            for text in seen { XCTAssertEqual(LetterStego.decode(text), probe) }
        }
    }

    func testDisjointFromTheOtherModes() {
        for language in StegoLanguage.allCases {
            for seed in [0x00, 0x5C, 0xB3, 0xFF] {
                let letters = LetterStego.encode(probe, language: language, seed: seed)
                XCTAssertNil(TextStego.decode(letters), "\(language) seed=\(seed)")
                XCTAssertNil(SmartTextStego.decode(letters), "\(language) seed=\(seed)")
                XCTAssertNil(LetterStego.decode(TextStego.encode(probe, language: language, seed: seed)),
                             "\(language) seed=\(seed)")
                XCTAssertNil(LetterStego.decode(SmartTextStego.encode(probe, language: language, seed: seed)),
                             "\(language) seed=\(seed)")
            }
        }
    }

    func testSurvivesScreenChrome() throws {
        for language in StegoLanguage.allCases {
            let text = try XCTUnwrap(LetterStego.encode(probe, language: language))
            XCTAssertEqual(LetterStego.decode("Иван  14:52  \(text)  изменено"), probe)
            XCTAssertEqual(LetterStego.decode("\(text)\nDelivered"), probe)
            XCTAssertEqual(LetterStego.decode(text.uppercased()), probe)
        }
    }

    func testProseIsRejected() {
        for text in ["привет как дела сегодня вечером", "hello how are you doing today my friend",
                     "Съешь ещё этих мягких французских булок да выпей чаю",
                     "The quick brown fox jumps over the lazy dog"] {
            XCTAssertNil(LetterStego.decode(text))
        }
    }

    func testGarbageNeverTraps() {
        for _ in 0 ..< 2000 {
            let ru = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
            let en = "abcdefghijklmnopqrstuvwxyz"
            let source = Bool.random() ? Array(ru) : Array(en)
            let n = Int.random(in: 1 ... 120)
            let junk = String((0 ..< n).map { _ in source.randomElement()! })
            _ = LetterStego.decode(junk)
        }
    }

    func testEnglishTextCollidesWithWireUnwrapButStillDecodes() throws {
        let pairKey = Data("alicebob".utf8)
        var collisions = 0
        for _ in 0 ..< 3000 {
            let payload = randomBytes(Int.random(in: 60 ... 260))
            let text = try XCTUnwrap(LetterStego.encode(payload, language: .english))
            if WireFormat.unwrap(text, pairKey: pairKey) != nil {
                collisions += 1
                XCTAssertEqual(LetterStego.decode(text), payload)
            }
        }
        XCTAssertGreaterThan(collisions, 0)
    }

    func testRussianTextNeverReachesTheWireUnwrap() throws {
        let pairKey = Data("alicebob".utf8)
        for _ in 0 ..< 500 {
            let payload = randomBytes(Int.random(in: 60 ... 260))
            let text = try XCTUnwrap(LetterStego.encode(payload, language: .russian))
            XCTAssertNil(WireFormat.unwrap(text, pairKey: pairKey))
        }
    }

    func testCrossPlatformVectors() {
        XCTAssertEqual(LetterStego.encode(probe, language: .english, seed: 0x5C),
                       "hdfhmfeavxoptfqowalblltnbmbi")
        XCTAssertEqual(LetterStego.encode(probe, language: .russian, seed: 0xB3),
                       "цппцпыяхшчжмяъэавквыцвсчеп")
        XCTAssertEqual(LetterStego.decode("hdfhmfeavxoptfqowalblltnbmbi"), probe)
        XCTAssertEqual(LetterStego.decode("цппцпыяхшчжмяъэавквыцвсчеп"), probe)
    }

    func testEveryPayloadFindsACleanSeed() throws {
        for language in StegoLanguage.allCases {
            for n in [12, 60, 200, 800, 1900] {
                let text = try XCTUnwrap(LetterStego.encode(randomBytes(n), language: language),
                                         "\(language) n=\(n) found no clean seed")
                XCTAssertFalse(StegoSafety.containsBlocked(text))
            }
        }
    }
}
