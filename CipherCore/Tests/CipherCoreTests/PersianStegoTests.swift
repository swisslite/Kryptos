import XCTest
@testable import CipherCore

final class PersianStegoTests: XCTestCase {
    private let probe = Data([0x03, 0x02, 0xAB, 0xCD, 0xEF, 0x10, 0x22, 0x77, 0x91, 0x04, 0x5C, 0xBE])
    private let letters = Set("ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی")

    func testRoundTripAcrossSizes() throws {
        for n in [0, 1, 2, 3, 5, 16, 33, 64, 127, 128, 200, 512, 1500] {
            let payload = randomBytes(n)
            let words = try XCTUnwrap(TextStego.encode(payload, language: .persian), "words n=\(n)")
            XCTAssertEqual(TextStego.decode(words), payload, "words n=\(n)")
            let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: .persian), "smart n=\(n)")
            XCTAssertEqual(SmartTextStego.decode(smart), payload, "smart n=\(n)")
            guard n > 0 else { continue }
            let run = try XCTUnwrap(LetterStego.encode(payload, language: .persian), "letters n=\(n)")
            XCTAssertEqual(LetterStego.decode(run), payload, "letters n=\(n)")
        }
    }

    func testCoversCarryOnlyPersianLettersAndPersianPunctuation() throws {
        let payload = randomBytes(240)
        let words = try XCTUnwrap(TextStego.encode(payload, language: .persian))
        for character in words {
            XCTAssertTrue(letters.contains(character) || "\u{0622} \u{060C}.\u{061F}!".contains(character),
                          "words: \(character)")
        }
        let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: .persian))
        for character in smart {
            XCTAssertTrue(letters.contains(character) || "\u{0622} \u{060C}.!\n".contains(character),
                          "smart: \(character)")
        }
        let run = try XCTUnwrap(LetterStego.encode(payload, language: .persian))
        for character in run { XCTAssertTrue(letters.contains(character), "letters: \(character)") }
    }

    func testLettersIsTheMostCompactMode() throws {
        let payload = randomBytes(120)
        let words = try XCTUnwrap(TextStego.encode(payload, language: .persian)).count
        let run = try XCTUnwrap(LetterStego.encode(payload, language: .persian)).count
        XCTAssertLessThan(run * 2, words)
    }

    func testCrossPlatformVectors() {
        let payload = Data((0 ... 0x20).map { UInt8($0) })
        XCTAssertEqual(TextStego.encode(payload, language: .persian, seed: 0x5C), "صاحب کنید لین نباید، نکنیم ازدواج؟ دکستر میتونه نزن دادین چطوری عشق یارو. گذشته، سوزی میتونه محاصره یو اسمت هیچوقت. چجور، عاشق میفهمی، گذشته نسبت؟")
        XCTAssertEqual(SmartTextStego.encode(probe, language: .persian, seed: 0x5C), "وانگهی، کشاورز آن سوزن را شمرد، اما نانوا یک جعبه را دوشید. امسال عکاس یک روبان را سپرد. دیشب خریدار آن میخ را دوشید، اما ماهیگیر شش قوری را برداشت.")
        XCTAssertEqual(LetterStego.encode(probe, language: .persian, seed: 0x5C), "رظضچدزعطصپییغجچژچچعتکرمثتل")
    }

    func testOrdinaryPersianProseIsRejected() {
        let prose = "\u{0633}\u{0644}\u{0627}\u{0645} \u{062F}\u{0648}\u{0633}\u{062A} \u{0645}\u{0646}\u{060C} " +
            "\u{0627}\u{0645}\u{0631}\u{0648}\u{0632} \u{0647}\u{0648}\u{0627} \u{062E}\u{06CC}\u{0644}\u{06CC} " +
            "\u{062E}\u{0648}\u{0628} \u{0627}\u{0633}\u{062A} \u{0648} \u{0645}\u{0646} \u{0628}\u{0647} " +
            "\u{067E}\u{0627}\u{0631}\u{06A9} \u{0631}\u{0641}\u{062A}\u{0645}."
        XCTAssertNil(TextStego.decode(prose))
        XCTAssertNil(SmartTextStego.decode(prose))
        XCTAssertNil(LetterStego.decode(prose))
    }

    func testDataSetsCarryNothingBlocked() {
        for word in StegoWordlists.persian {
            XCTAssertFalse(StegoSafety.blocks(word), word)
            XCTAssertFalse(StegoSafety.containsBlocked(word), word)
        }
        let g = SmartStegoData.persian
        var tokens = g.openers
        for slot in g.slots { tokens += slot }
        for row in g.structures { tokens += row.filter { !$0.hasPrefix("#") } }
        for token in tokens {
            XCTAssertFalse(StegoSafety.blocks(token), token)
            XCTAssertFalse(StegoSafety.containsBlocked(token), token)
        }
    }

    func testPersianWordsSurvivesLeadingChatter() throws {
        let payload = Data((0 ..< 48).map { UInt8($0 &* 5 &+ 1) })
        let cover = try XCTUnwrap(TextStego.encode(payload, language: .persian))
        let chatter = ["سلام", "امروز", "خیلی", "کتاب", "دوست", "خانه", "این"]
        for junk in 1 ... 7 {
            let text = chatter.prefix(junk).joined(separator: " ") + " " + cover
            XCTAssertEqual(TextStego.decode(text), payload, "failed with \(junk) leading words")
        }
    }
}
