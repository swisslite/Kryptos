import Foundation

public enum SmartTextStego {
    private static let magic: UInt8 = 0xC6
    public static let maxPayloadBytes = 0x7FFF

    public static func encode(_ data: Data, language: StegoLanguage = .forSystem()) -> String {
        encode(data, language: language, seed: Int.random(in: 0 ... 255))
    }

    static func encode(_ data: Data, language: StegoLanguage, seed: Int) -> String {
        precondition(data.count <= maxPayloadBytes, "payload too large for the smart stego frame")
        let g = grammar(language)
        let framed = frameEncode(data, seed: seed)
        let reader = BitReader(framed)
        var bodies: [String] = []
        while reader.position < reader.total {
            let openerIdx = reader.read(g.openerBits)
            let structure = g.structures[g.structOf[openerIdx]]
            var parts: [String] = []
            parts.reserveCapacity(structure.count)
            for element in structure {
                switch element {
                case .literal(let word):
                    parts.append(word)
                case .slot(let type):
                    parts.append(g.slots[type][reader.read(g.slotBits[type])])
                }
            }
            bodies.append(render(opener: g.openers[openerIdx], kind: g.openerKind[openerIdx], parts: parts))
        }
        return assemble(bodies, seed: seed)
    }

    public static func decode(_ text: String) -> Data? {
        let tokens = tokenize(text)
        guard !tokens.isEmpty else { return nil }
        for g in grammars {
            if let data = decode(tokens: tokens, grammar: g) { return data }
        }
        return nil
    }

    public static func looksLikeStego(_ text: String) -> Bool { decode(text) != nil }

    private static func decode(tokens rawTokens: [String], grammar g: Grammar) -> Data? {
        let tokens = rawTokens.filter { g.vocab.contains($0) }
        guard !tokens.isEmpty else { return nil }
        var pos = 0
        let writer = BitWriter()
        while pos < tokens.count {
            guard let openerIdx = g.openerIndex[tokens[pos]] else { return nil }
            pos += 1
            writer.append(openerIdx, bits: g.openerBits)
            let structure = g.structures[g.structOf[openerIdx]]
            for element in structure {
                switch element {
                case .literal(let word):
                    guard pos < tokens.count, tokens[pos] == word else { return nil }
                    pos += 1
                case .slot(let type):
                    guard pos < tokens.count, let idx = g.slotIndex[type][tokens[pos]] else { return nil }
                    writer.append(idx, bits: g.slotBits[type])
                    pos += 1
                }
            }
        }
        return frameDecode(writer.bytes)
    }

    private static let commaBefore: Set<String> = ["but", "so", "yet", "then", "while", "because", "though", "и", "но", "а", "затем", "потом", "пока", "когда", "поэтому"]

    private static func render(opener: String, kind: Int, parts: [String]) -> String {
        var sentence = opener.prefix(1).uppercased() + opener.dropFirst()
        if kind == 0 { sentence += "," }
        for part in parts {
            if commaBefore.contains(part) { sentence += "," }
            sentence += " " + part
        }
        return sentence
    }

    private static func assemble(_ bodies: [String], seed: Int) -> String {
        var x = (seed ^ 0x3B) & 0xFF
        func next() -> Int {
            x = (x * 197 + 91) & 0xFF
            return x
        }
        var out = ""
        for (i, body) in bodies.enumerated() {
            if i > 0 { out += next() % 7 == 0 ? "\n" : " " }
            out += body
            out += next() % 10 == 9 ? "!" : "."
        }
        return out
    }

    private static func frameEncode(_ data: Data, seed: Int) -> [UInt8] {
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
        return framed
    }

    private static func frameDecode(_ bytes: [UInt8]) -> Data? {
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

    private static func tokenize(_ text: String) -> [String] {
        text.precomposedStringWithCanonicalMapping.lowercased()
            .split(whereSeparator: { !$0.isLetter })
            .map(String.init)
    }

    private static let englishGrammar = Grammar(SmartStegoData.english)
    private static let russianGrammar = Grammar(SmartStegoData.russian)
    private static let grammars = [englishGrammar, russianGrammar]

    private static func grammar(_ language: StegoLanguage) -> Grammar {
        language == .russian ? russianGrammar : englishGrammar
    }

    private struct Grammar {
        enum Element {
            case literal(String)
            case slot(Int)
        }

        let openers: [String]
        let openerKind: [Int]
        let structOf: [Int]
        let slots: [[String]]
        let structures: [[Element]]
        let openerBits: Int
        let slotBits: [Int]
        let openerIndex: [String: Int]
        let slotIndex: [[String: Int]]
        let vocab: Set<String>

        init(_ raw: SmartStegoData.Grammar) {
            let normalizedOpeners = raw.openers.map { $0.precomposedStringWithCanonicalMapping }
            let normalizedSlots = raw.slots.map { $0.map { $0.precomposedStringWithCanonicalMapping } }
            openers = normalizedOpeners
            openerKind = raw.openerKind
            structOf = raw.structOf
            slots = normalizedSlots
            openerBits = Grammar.bits(normalizedOpeners.count)
            slotBits = normalizedSlots.map { Grammar.bits($0.count) }
            structures = raw.structures.map { row in
                row.map { token -> Element in
                    if token.hasPrefix("#") { return .slot(Int(token.dropFirst())!) }
                    return .literal(token.precomposedStringWithCanonicalMapping)
                }
            }
            var openerMap = [String: Int](minimumCapacity: normalizedOpeners.count)
            for (i, word) in normalizedOpeners.enumerated() { openerMap[word] = i }
            openerIndex = openerMap
            slotIndex = normalizedSlots.map { list in
                var map = [String: Int](minimumCapacity: list.count)
                for (i, word) in list.enumerated() { map[word] = i }
                return map
            }
            var allWords = Set(normalizedOpeners)
            for list in normalizedSlots { allWords.formUnion(list) }
            for row in structures {
                for element in row {
                    if case .literal(let word) = element { allWords.insert(word) }
                }
            }
            vocab = allWords
        }

        static func bits(_ n: Int) -> Int {
            precondition(n > 0 && (n & (n - 1)) == 0, "grammar list size must be a power of two")
            return n.trailingZeroBitCount
        }
    }

    private final class BitReader {
        private let bytes: [UInt8]
        let total: Int
        private(set) var position = 0

        init(_ bytes: [UInt8]) {
            self.bytes = bytes
            total = bytes.count * 8
        }

        func read(_ n: Int) -> Int {
            var value = 0
            for _ in 0 ..< n {
                let bit = position < total ? Int((bytes[position >> 3] >> (7 - (position & 7))) & 1) : 0
                value = (value << 1) | bit
                position += 1
            }
            return value
        }
    }

    private final class BitWriter {
        private var out = [UInt8]()
        private var current = 0
        private var count = 0

        func append(_ value: Int, bits n: Int) {
            var i = n - 1
            while i >= 0 {
                current = (current << 1) | ((value >> i) & 1)
                count += 1
                if count == 8 {
                    out.append(UInt8(current))
                    current = 0
                    count = 0
                }
                i -= 1
            }
        }

        var bytes: [UInt8] {
            if count > 0 { return out + [UInt8((current << (8 - count)) & 0xFF)] }
            return out
        }
    }
}
