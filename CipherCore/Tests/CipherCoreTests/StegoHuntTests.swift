import XCTest
@testable import CipherCore

final class StegoIntegrityTests: XCTestCase {
    func testWordListsAreCleanAndUnique() {
        for words in [StegoWordlists.english, StegoWordlists.russian] {
            XCTAssertEqual(words.count, 4096)
            XCTAssertEqual(Set(words).count, 4096, "duplicate words")
            for w in words {
                XCTAssertFalse(w.isEmpty)
                XCTAssertTrue(w.unicodeScalars.allSatisfy { Character($0).isLetter },
                              "word '\(w)' has a non-letter character")
            }
        }
    }

    func testExhaustiveRoundTrip() throws {
        for language in StegoLanguage.allCases {
            for n in 1...80 {
                for trial in 0..<40 {
                    var g = SplitMix(seed: UInt64(n) &* 1000 &+ UInt64(trial) &+ (language == .russian ? 500_000 : 0))
                    let payload = Data((0..<n).map { _ in UInt8(g.next() & 0xFF) })
                    let text = TextStego.encode(payload, language: language)
                    let back = try XCTUnwrap(TextStego.decode(text), "decode nil n=\(n) trial=\(trial) lang=\(language)")
                    XCTAssertEqual(back, payload, "mismatch n=\(n) trial=\(trial) lang=\(language)")
                }
            }
        }
    }
}

private struct SplitMix {
    var s: UInt64
    init(seed: UInt64) { s = seed }
    mutating func next() -> UInt64 {
        s &+= 0x9E37_79B9_7F4A_7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
