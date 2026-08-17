import XCTest
@testable import CipherCore

final class ChineseStegoTests: XCTestCase {
    private let probe = Data([0x03, 0x02, 0xAB, 0xCD, 0xEF, 0x10, 0x22, 0x77, 0x91, 0x04, 0x5C, 0xBE])

    private func isHan(_ character: Character) -> Bool { StegoTokenizer.isHan(character) }

    func testRoundTripAcrossSizes() throws {
        for n in [0, 1, 2, 3, 5, 16, 33, 64, 127, 128, 200, 512, 1500] {
            let payload = randomBytes(n)
            let words = try XCTUnwrap(TextStego.encode(payload, language: .chinese), "words n=\(n)")
            XCTAssertEqual(TextStego.decode(words), payload, "words n=\(n)")
            let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: .chinese), "smart n=\(n)")
            XCTAssertEqual(SmartTextStego.decode(smart), payload, "smart n=\(n)")
            guard n > 0 else { continue }
            let letters = try XCTUnwrap(LetterStego.encode(payload, language: .chinese), "letters n=\(n)")
            XCTAssertEqual(LetterStego.decode(letters), payload, "letters n=\(n)")
        }
    }

    func testCoversCarryOnlyHanAndFullWidthPunctuation() throws {
        let payload = randomBytes(240)
        let words = try XCTUnwrap(TextStego.encode(payload, language: .chinese))
        for character in words {
            XCTAssertTrue(isHan(character) || "，。？！".contains(character), "words: \(character)")
        }
        let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: .chinese))
        for character in smart {
            XCTAssertTrue(isHan(character) || "，。！\n".contains(character), "smart: \(character)")
        }
        let letters = try XCTUnwrap(LetterStego.encode(payload, language: .chinese))
        XCTAssertTrue(letters.allSatisfy(isHan))
    }

    func testLettersIsOneBytePerCharacter() throws {
        for n in [1, 12, 64, 300] {
            let letters = try XCTUnwrap(LetterStego.encode(randomBytes(n), language: .chinese))
            XCTAssertEqual(letters.count, n + 4, "n=\(n)")
        }
    }

    func testWordsIsTheMostCompactMode() throws {
        let payload = randomBytes(200)
        let words = try XCTUnwrap(TextStego.encode(payload, language: .chinese)).count
        let letters = try XCTUnwrap(LetterStego.encode(payload, language: .chinese)).count
        let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: .chinese)).count
        XCTAssertLessThan(words, letters)
        XCTAssertLessThan(letters, smart)
    }

    func testDecodeSurvivesScreenChrome() throws {
        let payload = randomBytes(96)
        let frames = ["李明: %@ 14:52", "Anna: %@ edited", "%@\n已读", "王芳  昨天  %@  ✓✓"]
        for frame in frames {
            let words = String(format: frame, try XCTUnwrap(TextStego.encode(payload, language: .chinese)))
            XCTAssertEqual(TextStego.decode(words), payload, "words in \(frame)")
            let smart = String(format: frame, try XCTUnwrap(SmartTextStego.encode(payload, language: .chinese)))
            XCTAssertEqual(SmartTextStego.decode(smart), payload, "smart in \(frame)")
            let letters = String(format: frame, try XCTUnwrap(LetterStego.encode(payload, language: .chinese)))
            XCTAssertEqual(LetterStego.decode(letters), payload, "letters in \(frame)")
        }
    }

    func testSmartRecoversFromOddLeadingJunk() throws {
        let payload = randomBytes(64)
        let smart = try XCTUnwrap(SmartTextStego.encode(payload, language: .chinese))
        for junk in ["我", "我的兄", "他", "学生老"] {
            XCTAssertEqual(SmartTextStego.decode(junk + smart), payload, "junk=\(junk)")
        }
    }

    func testOrdinaryProseIsRejected() {
        for text in ["今天天气真好，我们一起去公园散步吧。",
                     "你好，请问这本书多少钱？",
                     "我昨天在学校门口遇到了一个老朋友。"] {
            XCTAssertNil(TextStego.decode(text), text)
            XCTAssertNil(SmartTextStego.decode(text), text)
            XCTAssertNil(LetterStego.decode(text), text)
        }
    }

    func testTokenizerSplitsHanButKeepsLatinAndCyrillicRuns() {
        XCTAssertEqual(StegoTokenizer.split("你好world"), ["你", "好", "world"])
        XCTAssertEqual(StegoTokenizer.split("привет 你 好"), ["привет", "你", "好"])
        XCTAssertEqual(StegoTokenizer.split("hello world"), ["hello", "world"])
        XCTAssertEqual(StegoTokenizer.runs("你好world"), ["你好world"])
    }

    func testDataSetsCarryNothingBlocked() {
        for word in StegoWordlists.chinese {
            XCTAssertEqual(word.count, 1, "words list must hold single characters: \(word)")
            XCTAssertFalse(StegoSafety.blocks(word), word)
        }
        var tokens = SmartStegoData.chinese.openers
        for slot in SmartStegoData.chinese.slots { tokens += slot }
        for token in tokens {
            XCTAssertEqual(token.count, 2, "grammar word must hold two characters: \(token)")
            XCTAssertFalse(StegoSafety.containsBlocked(token), token)
        }
    }

    func testLettersAlphabetCannotSpellAnyBlockedTerm() {
        let alphabet = Set(LetterStego.alphabetCharacters(for: .chinese))
        XCTAssertEqual(alphabet.count, 256)
        for stem in StegoSafety.hanStems where stem.count > 1 {
            XCTAssertFalse(stem.allSatisfy { alphabet.contains($0) }, "alphabet can spell \(stem)")
        }
    }

    func testGeneratedCoversAreClean() throws {
        for _ in 0 ..< 40 {
            let payload = randomBytes(Int.random(in: 12 ... 400))
            let covers = [try XCTUnwrap(TextStego.encode(payload, language: .chinese)),
                          try XCTUnwrap(SmartTextStego.encode(payload, language: .chinese)),
                          try XCTUnwrap(LetterStego.encode(payload, language: .chinese))]
            for cover in covers {
                XCTAssertFalse(StegoSafety.containsBlocked(cover), String(cover.prefix(80)))
            }
        }
    }

    func testCrossPlatformVectors() {
        let payload = Data((0...0x20).map { UInt8($0) })
        XCTAssertEqual(TextStego.encode(payload, language: .chinese, seed: 0x5C), "掷行睛难，浮班萦吻朱袁特唉骂毛晨吻。疲墩暮戴锉洞瓜，毛腰。")
        XCTAssertEqual(SmartTextStego.encode(probe, language: .chinese, seed: 0x5C),
                       "我的兄弟准备多数手表，然后全部演员更换那些雨伞。据说，多数老人展示他的椅子。"
                       + "他的邻居已经核对不少地图。前天，大量村民购买这些书本，而且这些学生购买这些书本。")
        XCTAssertEqual(LetterStego.encode(probe, language: .chinese, seed: 0x5C), "它讲带玩新把睡问无糟另水呀却带我")
        XCTAssertEqual(TextStego.decode("掷行睛难，浮班萦吻朱袁特唉骂毛晨吻。疲墩暮戴锉洞瓜，毛腰。"), payload)
        XCTAssertEqual(LetterStego.decode("它讲带玩新把睡问无糟另水呀却带我"), probe)
    }

    func testBlocklistCoversChineseProfanityAndDangerousTerms() throws {
        for character in [
            "屎", "尿", "屁", "贱", "操", "屌", "杀", "枪", "炸", "毒", "尸", "奸", "妓"
        ] {
            XCTAssertTrue(StegoSafety.blocks(character), "single not blocked: \(character)")
            XCTAssertFalse(StegoWordlists.chinese.contains(character), "still in wordlist: \(character)")
        }
        for term in [
            "鸡巴", "龟头", "精液", "拉屎", "撒尿", "狗屎", "你妈", "尼玛", "草泥马", "卧槽", "干你", "混账", "杂种", "野种", "贱货",
            "荡妇", "窑子", "援交", "包养", "二奶", "小三", "三级片", "毛片", "性感", "猥琐", "同志", "蕾丝", "人妖", "男同", "女同",
            "娘炮", "杀死", "砍死", "打死", "弄死", "害死", "灭口", "割喉", "断头", "斩首", "肢解", "去死", "找死", "该死", "自尽", "寻死",
            "跳河", "卧轨", "自残", "割脉", "烧炭", "安眠药", "遗言", "绝笔", "枪支", "弹匣", "炸药", "火药", "雷管", "引爆", "爆破",
            "射杀", "白粉", "溜冰", "吸食", "摇头丸", "黑社会", "混混", "打手", "保护费", "绑票", "撕票", "抢夺", "行窃", "扒手", "小偷",
            "传销", "黑钱", "赃款", "上访", "维权", "联署", "串联", "声援", "围堵", "封路", "打倒", "变天", "暴徒", "乱党", "极权", "专政",
            "万岁", "白纸", "台独", "藏独", "疆独", "港独", "小日本", "鬼佬", "白皮", "洋垃圾", "法轮", "邪教", "洗脑"
        ] {
            XCTAssertTrue(StegoSafety.containsBlocked(term), "term not blocked: \(term)")
        }
    }

    func testInnocentChineseWordsAreNotBlocked() {
        for word in [
            "鸡蛋", "龟壳", "干净", "死机", "上课", "万一", "白天", "小学", "专心", "同学", "变化", "装修", "男孩", "女孩", "打开", "打算",
            "火车", "火柴", "联系", "声音", "围巾", "封面", "保护", "抢先", "行动", "传统", "洗手", "极限"
        ] {
            XCTAssertFalse(StegoSafety.blocks(word), "false positive: \(word)")
            XCTAssertFalse(StegoSafety.containsBlocked(word), "false positive: \(word)")
        }
    }

    func testNoBlockedTermCanBeSpelledByTheDataSets() throws {
        let pool = Set(StegoWordlists.chinese)
        var grammar = Set<String>()
        for word in SmartStegoData.chinese.openers { grammar.insert(word) }
        for slot in SmartStegoData.chinese.slots { for word in slot { grammar.insert(word) } }
        let alphabet = Set(LetterStego.alphabetCharacters(for: .chinese))
        for stem in StegoSafety.hanStems {
            if stem.count == 1 {
                let character = try XCTUnwrap(stem.first)
                XCTAssertFalse(pool.contains(stem), "wordlist can emit \(stem)")
                XCTAssertFalse(alphabet.contains(character), "alphabet can emit \(stem)")
                continue
            }
            XCTAssertFalse(stem.allSatisfy { alphabet.contains($0) }, "alphabet can spell \(stem)")
            for word in grammar {
                XCTAssertFalse(word.contains(stem), "grammar word \(word) contains \(stem)")
            }
        }
    }
}
