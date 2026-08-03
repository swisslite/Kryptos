import XCTest
@testable import CipherCore

final class LetterStegoTests: XCTestCase {
    private let probe = Data([0x03, 0x02, 0xAB, 0xCD, 0xEF, 0x10, 0x22, 0x77, 0x91, 0x04, 0x5C, 0xBE])

    func testRoundTripAcrossSizes() {
        for language in StegoLanguage.allCases {
            for n in [1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 31, 64, 120, 400, 1900] {
                let payload = randomBytes(n)
                let text = LetterStego.encode(payload, language: language)
                XCTAssertEqual(LetterStego.decode(text), payload, "\(language) n=\(n)")
            }
        }
    }

    func testOutputIsLettersOnlyAndSingleRun() {
        for language in StegoLanguage.allCases {
            let text = LetterStego.encode(randomBytes(120), language: language)
            XCTAssertTrue(text.allSatisfy { $0.isLetter && $0.isLowercase })
            XCTAssertFalse(text.contains(" "))
            if language == .russian {
                XCTAssertFalse(text.contains("ё"))
            }
        }
    }

    func testShorterThanTheOtherModes() {
        var body = Data([0x03, 0x02])
        body.append(randomBytes(118))
        for language in StegoLanguage.allCases {
            let letters = LetterStego.encode(body, language: language).count
            XCTAssertLessThan(letters, TextStego.encode(body, language: language).count)
            XCTAssertLessThan(letters, SmartTextStego.encode(body, language: language).count)
        }
    }

    func testSameInputProducesDifferentOutput() {
        for language in StegoLanguage.allCases {
            let a = LetterStego.encode(probe, language: language)
            let b = LetterStego.encode(probe, language: language)
            XCTAssertNotEqual(a, b)
            XCTAssertEqual(LetterStego.decode(a), probe)
            XCTAssertEqual(LetterStego.decode(b), probe)
        }
    }

    func testDisjointFromTheOtherModes() {
        for language in StegoLanguage.allCases {
            let letters = LetterStego.encode(probe, language: language)
            XCTAssertNil(TextStego.decode(letters))
            XCTAssertNil(SmartTextStego.decode(letters))
            XCTAssertNil(LetterStego.decode(TextStego.encode(probe, language: language)))
            XCTAssertNil(LetterStego.decode(SmartTextStego.encode(probe, language: language)))
        }
    }

    func testSurvivesScreenChrome() {
        for language in StegoLanguage.allCases {
            let text = LetterStego.encode(probe, language: language)
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

    func testEnglishTextCollidesWithWireUnwrapButStillDecodes() {
        let pairKey = Data("alicebob".utf8)
        var collisions = 0
        for _ in 0 ..< 3000 {
            let payload = randomBytes(Int.random(in: 60 ... 260))
            let text = LetterStego.encode(payload, language: .english)
            if WireFormat.unwrap(text, pairKey: pairKey) != nil {
                collisions += 1
                XCTAssertEqual(LetterStego.decode(text), payload)
            }
        }
        XCTAssertGreaterThan(collisions, 0)
    }

    func testRussianTextNeverReachesTheWireUnwrap() {
        let pairKey = Data("alicebob".utf8)
        for _ in 0 ..< 500 {
            let payload = randomBytes(Int.random(in: 60 ... 260))
            let text = LetterStego.encode(payload, language: .russian)
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
}
