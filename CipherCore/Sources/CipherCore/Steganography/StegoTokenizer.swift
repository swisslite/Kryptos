import Foundation

enum StegoTokenizer {
    static func isHan(_ scalar: Unicode.Scalar) -> Bool {
        (0x4E00 ... 0x9FFF).contains(scalar.value)
            || (0x3400 ... 0x4DBF).contains(scalar.value)
            || (0xF900 ... 0xFAFF).contains(scalar.value)
    }

    static func isHan(_ character: Character) -> Bool {
        guard let scalar = character.unicodeScalars.first,
              character.unicodeScalars.count == 1 else { return false }
        return isHan(scalar)
    }

    static func split(_ text: String) -> [String] {
        var tokens: [String] = []
        var current = ""
        for character in text.precomposedStringWithCanonicalMapping.lowercased() {
            if isHan(character) {
                if !current.isEmpty {
                    tokens.append(current)
                    current = ""
                }
                tokens.append(String(character))
            } else if character.isLetter {
                current.append(character)
            } else if !current.isEmpty {
                tokens.append(current)
                current = ""
            }
        }
        if !current.isEmpty { tokens.append(current) }
        return tokens
    }

    static func runs(_ text: String) -> [Substring] {
        text.precomposedStringWithCanonicalMapping.lowercased().split(whereSeparator: { !$0.isLetter })
    }
}
