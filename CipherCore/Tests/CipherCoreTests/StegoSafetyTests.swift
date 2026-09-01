import XCTest
@testable import CipherCore

final class StegoSafetyTests: XCTestCase {
    private func wordlist(_ language: StegoLanguage) -> [String] {
        switch language {
        case .english: return StegoWordlists.english
        case .russian: return StegoWordlists.russian
        case .german: return StegoWordlists.german
        case .chinese: return StegoWordlists.chinese
        case .persian: return StegoWordlists.persian
        }
    }

    private func grammar(_ language: StegoLanguage) -> SmartStegoData.Grammar {
        switch language {
        case .english: return SmartStegoData.english
        case .russian: return SmartStegoData.russian
        case .german: return SmartStegoData.german
        case .chinese: return SmartStegoData.chinese
        case .persian: return SmartStegoData.persian
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

    func testBlocklistCoversPersianDangerousTerms() {
        for word in ["کیر", "کس", "کون", "کونی", "جنده", "جاکش", "کسکش", "سکس", "سکسی", "پورن", "لخت", "تجاوز", "فاحشه", "شهوت", "زنا", "همجنسگرا", "همجنسباز", "لزبین", "ترنس", "کشتن", "کشتار", "قتل", "قاتل", "اعدام", "جنایت", "ترور", "تروریست", "بمب", "انفجار", "تفنگ", "اسلحه", "گلوله", "موشک", "نارنجک", "شکنجه", "گروگان", "جنگ", "حمله", "خشونت", "شلیک", "مسلح", "چاقو", "خنجر", "جسد", "جنازه", "مخدر", "هروئین", "کوکائین", "تریاک", "حشیش", "معتاد", "اعتیاد", "دزد", "دزدی", "سرقت", "قاچاق", "اختلاس", "رشوه", "کلاهبرداری", "خودکشی", "زندان", "زندانی", "تظاهرات", "اعتراض", "اعتصاب", "شورش", "انقلاب", "براندازی", "آشوب", "دیکتاتور", "کودتا", "سرباز", "ارتش", "حرومزاده", "بیشرف", "پدرسگ", "عوضی", "کثافت", "احمق", "ابله", "کتک", "خفه", "مرگ", "بکش", "میکشمت", "رید", "شاش"] {
            XCTAssertTrue(StegoSafety.blocks(word), word)
        }
    }

    func testInnocentPersianWordsAreNotBlocked() {
        for word in ["سلام", "خوبی", "ممنون", "کتاب", "مدرسه", "خانه", "پنجره", "درخت", "باران", "آفتاب", "کلاس", "معلم", "برادر", "خواهر", "مادر", "پدر", "دوست", "شهر", "خیابان", "ماشین", "قطار", "هواپیما", "بیمارستان", "دکتر", "پرستار", "نان", "چای", "قهوه", "میوه", "سیب", "گربه", "دریا", "کوه", "رودخانه", "ستاره", "ماه", "خورشید", "کارگر", "مهندس", "نویسنده", "نقاش", "خواننده", "ورزش", "فوتبال", "توپ", "بازی", "خنده", "عشق", "زندگی", "امید", "صلح", "دوستی", "مهربان", "زیبا", "بزرگ", "کوچک", "تازه", "روشن", "گرم", "سرد", "شیرین", "کشور", "عکس", "کسی", "تکون", "زنان", "فرزندان", "مقابله", "کوسه", "گوزن", "حمل", "انتقام", "سلامت", "تظاهر", "مسلما", "شهادت", "کشیدن", "بگیر", "دیگه", "یعنی", "معنی", "خونه", "بزن", "پلیس", "درد", "زخم"] {
            XCTAssertFalse(StegoSafety.blocks(word), word)
            XCTAssertFalse(StegoSafety.containsBlocked(word), word)
        }
    }
}
