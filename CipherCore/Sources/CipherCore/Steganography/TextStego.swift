import Foundation

public enum StegoLanguage: String, Sendable, CaseIterable {
    case english
    case russian

    var words: [String] {
        switch self {
        case .english: return StegoWordlists.english
        case .russian: return StegoWordlists.russian
        }
    }

    public static func forSystem() -> StegoLanguage {
        let code = (Locale.preferredLanguages.first ?? "en").lowercased()
        return code.hasPrefix("ru") ? .russian : .english
    }
}

public enum TextStego {
    private static let bitsPerWord = 12
    private static let wordMask = 0xFFF
    private static let magic: UInt8 = 0xC7

    public static let maxPayloadBytes = 0x7FFF

    public static func encode(_ data: Data, language: StegoLanguage = .forSystem()) -> String {
        encode(data, language: language, seed: Int.random(in: 0 ... 255))
    }

    static func encode(_ data: Data, language: StegoLanguage, seed: Int) -> String {
        let words = language.words
        precondition(words.count == 1 << bitsPerWord, "word list must hold exactly 4096 words")
        precondition(data.count <= maxPayloadBytes, "payload too large for the stego frame")

        var inner = [UInt8]()
        inner.reserveCapacity(4 + data.count)
        inner.append(magic)
        if data.count <= 0x7F {
            inner.append(UInt8(data.count))
        } else {
            inner.append(UInt8(0x80 | (data.count >> 8)))
            inner.append(UInt8(data.count & 0xFF))
        }
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

        var indices: [Int] = []
        indices.reserveCapacity((framed.count * 8 + bitsPerWord - 1) / bitsPerWord)
        var acc = 0, bits = 0
        for byte in framed {
            acc = (acc << 8) | Int(byte)
            bits += 8
            while bits >= bitsPerWord {
                bits -= bitsPerWord
                indices.append((acc >> bits) & wordMask)
            }
        }
        if bits > 0 { indices.append((acc << (bitsPerWord - bits)) & wordMask) }

        return prettify(indices.map { words[$0] }, seed: seed)
    }

    public static func decode(_ text: String) -> Data? {
        let tokens = tokenize(text)
        guard !tokens.isEmpty else { return nil }
        for language in StegoLanguage.allCases {
            if let data = decode(tokens: tokens, language: language) { return data }
        }
        return nil
    }

    public static func looksLikeStego(_ text: String) -> Bool { decode(text) != nil }

    private static func decode(tokens: [String], language: StegoLanguage) -> Data? {
        let index = language.indexMap
        var acc = 0, bits = 0
        var bytes: [UInt8] = []
        bytes.reserveCapacity(tokens.count * bitsPerWord / 8 + 1)
        for token in tokens {
            guard let value = index[token] else { continue }
            acc = (acc << bitsPerWord) | value
            bits += bitsPerWord
            while bits >= 8 {
                bits -= 8
                bytes.append(UInt8((acc >> bits) & 0xFF))
            }
        }
        guard bytes.count >= 4 else { return nil }
        var x = Int(bytes[0])
        var inner = [UInt8]()
        inner.reserveCapacity(bytes.count - 1)
        for i in 1 ..< bytes.count {
            x = (x * 197 + 91) & 0xFF
            inner.append(bytes[i] ^ UInt8(x))
        }
        guard inner[0] == magic else { return nil }
        let b1 = Int(inner[1])
        let n: Int
        let offset: Int
        if b1 < 0x80 {
            n = b1
            offset = 2
        } else {
            guard inner.count >= 3 else { return nil }
            n = ((b1 & 0x7F) << 8) | Int(inner[2])
            offset = 3
        }
        guard inner.count >= offset + n + 1 else { return nil }
        guard inner[offset + n] == crc8(inner, 0, offset + n) else { return nil }
        return Data(inner[offset ..< offset + n])
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

    private static func prettify(_ words: [String], seed: Int) -> String {
        guard !words.isEmpty else { return "" }
        var x = (seed ^ 0xA5) & 0xFF
        func next() -> Int {
            x = (x * 197 + 91) & 0xFF
            return x
        }
        var sentences: [String] = []
        var i = 0
        while i < words.count {
            let len = 4 + next() % 6
            let chunk = Array(words[i ..< min(i + len, words.count)])
            var sentence = ""
            for (k, word) in chunk.enumerated() {
                if k > 0 { sentence += " " }
                sentence += word
                if k < chunk.count - 1, next() % 6 == 0 { sentence += "," }
            }
            let mark: String
            switch next() % 10 {
            case 8: mark = "?"
            case 9: mark = "!"
            default: mark = "."
            }
            sentence = sentence.prefix(1).uppercased() + sentence.dropFirst()
            sentences.append(sentence + mark)
            i += len
        }
        return sentences.joined(separator: " ")
    }

    private static func tokenize(_ text: String) -> [String] {
        let normalized = text.precomposedStringWithCanonicalMapping.lowercased()
        return normalized
            .split(whereSeparator: { !$0.isLetter })
            .map(String.init)
    }
}

private extension StegoLanguage {
    static let indexMaps: [StegoLanguage: [String: Int]] = {
        var maps = [StegoLanguage: [String: Int]](minimumCapacity: allCases.count)
        for language in allCases {
            var map = [String: Int](minimumCapacity: 4096)
            for (i, word) in language.words.enumerated() {
                map[word.precomposedStringWithCanonicalMapping] = i
            }
            maps[language] = map
        }
        return maps
    }()

    var indexMap: [String: Int] { Self.indexMaps[self]! }
}
