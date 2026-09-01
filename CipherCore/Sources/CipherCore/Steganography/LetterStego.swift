import Foundation

public enum LetterStego {
    private static let magic: [UInt8] = [0xC5, 0x3A]
    private static let minFrameBytes = 5

    private struct Alphabet: Sendable {
        let letters: [Character]
        let index: [Character: Int]
        let base: Int
        let blockBytes: Int
        let blockChars: Int
        let charsForBytes: [Int]
        let bytesForChars: [Int: Int]
        let limits: [Int]
        let minChars: Int
    }

    private static func alphabet(_ source: String, blockBytes: Int) -> Alphabet {
        let letters = Array(source)
        let base = letters.count
        precondition(base >= 2, "letter alphabet is too small")
        precondition(Set(letters).count == base, "letter alphabet has duplicates")
        var index = [Character: Int](minimumCapacity: base)
        for (i, c) in letters.enumerated() { index[c] = i }

        var charsForBytes = [Int](repeating: 0, count: blockBytes + 1)
        var bytesForChars = [Int: Int](minimumCapacity: blockBytes)
        var limits = [Int](repeating: 0, count: blockBytes + 1)
        for r in 1 ... blockBytes {
            var limit = 1
            for _ in 0 ..< r { limit *= 256 }
            limits[r] = limit
            var chars = 0
            var capacity = 1
            while capacity < limit {
                capacity *= base
                chars += 1
            }
            charsForBytes[r] = chars
            if r < blockBytes { bytesForChars[chars] = r }
        }
        let blockChars = charsForBytes[blockBytes]
        precondition(bytesForChars[blockChars] == nil, "tail length collides with a full block")
        let rest = minFrameBytes % blockBytes
        let minChars = (minFrameBytes / blockBytes) * blockChars + (rest == 0 ? 0 : charsForBytes[rest])
        return Alphabet(letters: letters, index: index, base: base,
                        blockBytes: blockBytes, blockChars: blockChars,
                        charsForBytes: charsForBytes, bytesForChars: bytesForChars, limits: limits,
                        minChars: minChars)
    }

    private static let russian = alphabet("абвгдежзийклмнопрстуфхцчшщъыьэюя", blockBytes: 5)
    private static let english = alphabet("abcdefghijklmnopqrstuvwxyz", blockBytes: 7)
    private static let chinese = alphabet("一不与丢个中为久么之乔也买了事于些什仍从令以们份但住使信俩借假停像先克全关其再写冷刚别到前办加却又另只叫号吃名后向吧听吹呀呆呢和哇哈哎哦哪哭唱啊啥啦喂喝嗨嘛嘿回因在坏块多够太她好如妳字它完家对将少就已带年弧当往很得德心忘忙快怎怕怪总恨恩您想懂成我或戴所才扔把抓抱拍拜拿挂指按挺换掉提搞搬救敢斯新於无时是晚更曾替最月有未来查梦欠次正此每比水求没滚点热爸特猜猫玩球用由画留疼病盯盾看真着睡瞧碰祝神离秒穿站笑第等算米糟约给美老而耶能脚脸腿臭船花若著街被要见让讲读谁谈谢赢跑跟躲躺车达还这远连送选都酒里金错长问间陪饿高鱼", blockBytes: 1)

    private static let persian = alphabet("ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی", blockBytes: 5)

    private static let tables = [russian, english, chinese, persian]

    private static func table(for language: StegoLanguage) -> Alphabet {
        switch language {
        case .russian: return russian
        case .chinese: return chinese
        case .persian: return persian
        case .english, .german: return english
        }
    }

    static func alphabetCharacters(for language: StegoLanguage) -> [Character] {
        table(for: language).letters
    }

    public static func encode(_ data: Data, language: StegoLanguage = .forSystem()) -> String? {
        StegoSafety.firstCleanCover(payload: data.count) { seed in
            encode(data, language: language, seed: seed)
        }
    }

    static func encode(_ data: Data, language: StegoLanguage, seed: Int) -> String {
        precondition(!data.isEmpty, "letter stego needs a payload")
        var inner = [UInt8]()
        inner.reserveCapacity(magic.count + 1 + data.count)
        inner.append(contentsOf: magic)
        inner.append(contentsOf: data)
        inner.append(crc8(inner, 0, inner.count))

        var framed = [UInt8]()
        framed.reserveCapacity(1 + inner.count)
        framed.append(UInt8(seed & 0xFF))
        var x = seed & 0xFF
        for byte in inner {
            x = (x * 197 + 91) & 0xFF
            framed.append(byte ^ UInt8(x))
        }
        return pack(framed, table(for: language))
    }

    public static func decode(_ text: String) -> Data? {
        for token in tokenize(text) {
            let chars = Array(token)
            for a in tables {
                guard chars.count >= a.minChars, a.index[chars[0]] != nil,
                      structurallyValid(chars.count, a) else { continue }
                guard let framed = unpack(chars, a) else { continue }
                if let payload = unframe(framed) { return payload }
            }
        }
        return nil
    }

    private static func structurallyValid(_ count: Int, _ a: Alphabet) -> Bool {
        let tail = count % a.blockChars
        return tail == 0 || a.bytesForChars[tail] != nil
    }

    public static func looksLikeStego(_ text: String) -> Bool { decode(text) != nil }

    private static func pack(_ bytes: [UInt8], _ a: Alphabet) -> String {
        var out = ""
        out.reserveCapacity(bytes.count * a.blockChars / a.blockBytes + a.blockChars)
        var i = 0
        while i < bytes.count {
            let r = min(a.blockBytes, bytes.count - i)
            let c = a.charsForBytes[r]
            var value = 0
            for k in 0 ..< r { value = (value << 8) | Int(bytes[i + k]) }
            var digits = [Int](repeating: 0, count: c)
            var k = c - 1
            while k >= 0 {
                digits[k] = value % a.base
                value /= a.base
                k -= 1
            }
            for d in digits { out.append(a.letters[d]) }
            i += r
        }
        return out
    }

    private static func unpack(_ chars: [Character], _ a: Alphabet) -> [UInt8]? {
        var out = [UInt8]()
        out.reserveCapacity(chars.count * a.blockBytes / a.blockChars + a.blockBytes)
        var i = 0
        while i < chars.count {
            let remaining = chars.count - i
            let c: Int
            let r: Int
            if remaining >= a.blockChars {
                c = a.blockChars
                r = a.blockBytes
            } else {
                guard let bytes = a.bytesForChars[remaining] else { return nil }
                c = remaining
                r = bytes
            }
            var value = 0
            for k in 0 ..< c {
                guard let digit = a.index[chars[i + k]] else { return nil }
                value = value * a.base + digit
            }
            guard value < a.limits[r] else { return nil }
            var k = r - 1
            while k >= 0 {
                out.append(UInt8((value >> (8 * k)) & 0xFF))
                k -= 1
            }
            i += c
        }
        return out
    }

    private static func unframe(_ framed: [UInt8]) -> Data? {
        guard framed.count >= magic.count + 3 else { return nil }
        var x = Int(framed[0])
        var inner = [UInt8]()
        inner.reserveCapacity(framed.count - 1)
        for i in 1 ..< framed.count {
            x = (x * 197 + 91) & 0xFF
            inner.append(framed[i] ^ UInt8(x))
        }
        for (i, byte) in magic.enumerated() where inner[i] != byte { return nil }
        guard inner[inner.count - 1] == crc8(inner, 0, inner.count - 1) else { return nil }
        return Data(inner[magic.count ..< inner.count - 1])
    }

    private static func crc8(_ data: [UInt8], _ from: Int, _ to: Int) -> UInt8 {
        var crc = 0
        for i in from ..< to {
            crc ^= Int(data[i])
            for _ in 0 ..< 8 {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF
            }
        }
        return UInt8(crc)
    }

    private static func tokenize(_ text: String) -> [Substring] { StegoTokenizer.runs(text) }
}
