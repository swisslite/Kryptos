import Foundation

final class SuggestionEngine {
    static let shared = SuggestionEngine()
    private init() {}

    private struct Dict {
        let rank: [String: Int]
        let sorted: [String]
        let size: Int

        init(byFrequency: [String]) {
            var r = [String: Int](minimumCapacity: byFrequency.count * 2)
            for (i, w) in byFrequency.enumerated() where r[w] == nil {
                r[w] = i
            }
            rank = r
            sorted = r.keys.sorted()
            size = max(1, r.count)
        }

        func contains(_ word: String) -> Bool { rank[word] != nil }

        private func lowerBound(_ prefix: String) -> Int {
            var lo = 0, hi = sorted.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if sorted[mid] < prefix { lo = mid + 1 } else { hi = mid }
            }
            return lo
        }

        func isPrefix(_ prefix: String) -> Bool {
            guard !prefix.isEmpty else { return false }
            let lo = lowerBound(prefix)
            return lo < sorted.count && sorted[lo].hasPrefix(prefix)
        }

        func complete(_ prefix: String, limit: Int) -> [String] {
            guard !prefix.isEmpty else { return [] }
            var best: [(String, Int)] = []
            var i = lowerBound(prefix)
            while i < sorted.count, sorted[i].hasPrefix(prefix) {
                let w = sorted[i]
                if w.count > prefix.count { best.append((w, rank[w] ?? .max)) }
                i += 1
            }
            best.sort { $0.1 < $1.1 }
            return best.prefix(limit).map(\.0)
        }
    }

    struct Lexicon {
        let chars: [UInt16]
        let starts: [Int32]
        let ranks: [Int32]

        static let noRank = Int32.max

        var size: Int { starts.count - 1 }

        func len(_ j: Int) -> Int { Int(starts[j + 1] - starts[j]) }
        func charAt(_ j: Int, _ p: Int) -> UInt16 { chars[Int(starts[j]) + p] }
        func rank(_ j: Int) -> Int32 { ranks[j] }

        func word(_ j: Int) -> String {
            chars.withUnsafeBufferPointer {
                String(utf16CodeUnits: $0.baseAddress! + Int(starts[j]), count: len(j))
            }
        }

        func contains(_ w: String) -> Bool {
            guard size > 0 else { return false }
            let t = Array(w.utf16)
            var lo = 0, hi = size - 1
            while lo <= hi {
                let mid = (lo + hi) / 2
                let c = cmpWord(mid, t)
                if c == 0 { return true }
                if c < 0 { lo = mid + 1 } else { hi = mid - 1 }
            }
            return false
        }

        private func cmpWord(_ j: Int, _ t: [UInt16]) -> Int {
            let from = Int(starts[j]), to = Int(starts[j + 1])
            var i = from, k = 0
            while i < to, k < t.count {
                if chars[i] != t[k] { return chars[i] < t[k] ? -1 : 1 }
                i += 1; k += 1
            }
            return (to - from) - t.count
        }

        func childEnd(_ from: Int, _ hi: Int, _ p: Int, _ c: UInt16) -> Int {
            var lo = from + 1
            var h = hi
            while lo < h {
                let mid = (lo + h) / 2
                if charAt(mid, p) <= c { lo = mid + 1 } else { h = mid }
            }
            return lo
        }

        func findChild(_ lo: Int, _ hi: Int, _ p: Int, _ c: UInt16) -> (Int, Int)? {
            var a = lo + (len(lo) == p ? 1 : 0)
            var b = hi
            while a < b {
                let mid = (a + b) / 2
                if charAt(mid, p) < c { a = mid + 1 } else { b = mid }
            }
            guard a < hi, charAt(a, p) == c else { return nil }
            return (a, childEnd(a, hi, p, c))
        }

        static func build(dictSorted: [String], rankOf: (String) -> Int32, vocab: Data) -> Lexicon {
            let text = String(decoding: vocab, as: UTF8.self)
            let units = Array(text.utf16)
            var lineCount = 0
            var scan = 0
            while scan < units.count {
                var e = scan
                while e < units.count, units[e] != 0x0A { e += 1 }
                if e > scan { lineCount += 1 }
                scan = e + 1
            }
            var dictChars = 0
            for w in dictSorted { dictChars += w.utf16.count }
            var chars: [UInt16] = []
            chars.reserveCapacity(units.count + dictChars)
            var starts: [Int32] = [0]
            starts.reserveCapacity(dictSorted.count + lineCount + 1)
            var ranks: [Int32] = []
            ranks.reserveCapacity(dictSorted.count + lineCount)
            var di = 0
            var pos = 0
            var dw: [UInt16] = di < dictSorted.count ? Array(dictSorted[di].utf16) : []

            func appendDict() {
                chars.append(contentsOf: dw)
                ranks.append(rankOf(dictSorted[di]))
                starts.append(Int32(chars.count))
                di += 1
                dw = di < dictSorted.count ? Array(dictSorted[di].utf16) : []
            }

            func appendVocab(_ from: Int, _ to: Int) {
                chars.append(contentsOf: units[from ..< to])
                ranks.append(noRank)
                starts.append(Int32(chars.count))
            }

            func cmpRegion(_ from: Int, _ to: Int) -> Int {
                var i = from, j = 0
                while i < to, j < dw.count {
                    if units[i] != dw[j] { return units[i] < dw[j] ? -1 : 1 }
                    i += 1; j += 1
                }
                return (to - from) - dw.count
            }

            while pos < units.count || di < dictSorted.count {
                if pos >= units.count { appendDict(); continue }
                var end = pos
                while end < units.count, units[end] != 0x0A { end += 1 }
                if end == pos { pos = end + 1; continue }
                if di >= dictSorted.count { appendVocab(pos, end); pos = end + 1; continue }
                let c = cmpRegion(pos, end)
                if c < 0 { appendVocab(pos, end); pos = end + 1 }
                else if c > 0 { appendDict() }
                else { appendDict(); pos = end + 1 }
            }
            return Lexicon(chars: chars, starts: starts, ranks: ranks)
        }
    }

    private struct Bigrams {
        let rank: [String: Int]
        let next: [String: [String]]
        let vocab: Set<String>
        let size: Int

        init(lines: [String]) {
            var r: [String: Int] = [:]
            var n: [String: [String]] = [:]
            var v: Set<String> = []
            var i = 0
            for line in lines {
                let parts = line.trimmingCharacters(in: .whitespaces).split(separator: " ")
                guard parts.count == 2 else { continue }
                let a = String(parts[0]), b = String(parts[1])
                let key = "\(a) \(b)"
                guard r[key] == nil else { continue }
                r[key] = i
                n[a, default: []].append(b)
                v.insert(a); v.insert(b)
                i += 1
            }
            rank = r
            next = n
            vocab = v
            size = max(1, i)
        }
    }

    private static let ruRows = ["йцукенгшщзх", "фывапролджэ", "ячсмитьбю"]
    private static let enRows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]

    private static func buildNeighbors(_ rows: [String]) -> [Character: Set<Character>] {
        let grid = rows.map(Array.init)
        var map: [Character: Set<Character>] = [:]
        for (r, row) in grid.enumerated() {
            for (c, ch) in row.enumerated() {
                var set = map[ch] ?? []
                if c > 0 { set.insert(row[c - 1]) }
                if c < row.count - 1 { set.insert(row[c + 1]) }
                for nr in [r - 1, r + 1] where grid.indices.contains(nr) {
                    for nc in [c - 1, c, c + 1] where grid[nr].indices.contains(nc) {
                        set.insert(grid[nr][nc])
                    }
                }
                map[ch] = set
            }
        }
        return map
    }

    private static let ruNeighbors = buildNeighbors(ruRows)
    private static let enNeighbors = buildNeighbors(enRows)

    private static let ruConfusable: Set<String> = ["еи", "ие", "ао", "оа", "ея", "яе", "ьъ", "ъь"]
    private static let enVowels: Set<Character> = ["a", "e", "i", "o", "u"]

    private static let ruAlphabet = Array("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
    private static let enAlphabet = Array("abcdefghijklmnopqrstuvwxyz")

    private static let startToken = "^"
    private static let ruCommon = ["привет", "да", "нет", "спасибо", "как", "хорошо", "я", "что"]
    private static let enCommon = ["hi", "yes", "no", "thanks", "how", "okay", "i", "the"]

    private let lock = NSLock()
    private var en: Dict?
    private var ru: Dict?
    private var enBigrams: Bigrams?
    private var ruBigrams: Bigrams?
    private var enLex: Lexicon?
    private var ruLex: Lexicon?
    private var loadStarted = false

    private static func buildLexicon(_ dict: Dict, _ vocab: Data) -> Lexicon {
        Lexicon.build(
            dictSorted: dict.sorted,
            rankOf: { dict.rank[$0].map(Int32.init) ?? Lexicon.noRank },
            vocab: vocab,
        )
    }

    func warmUp() {
        lock.lock()
        let start = !loadStarted
        loadStarted = true
        lock.unlock()
        guard start else { return }
        DispatchQueue.global(qos: .utility).async { [self] in
            func read(_ name: String) -> [String] {
                guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
                      let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
                return text.split(separator: "\n").map(String.init).filter { !$0.isEmpty }
            }
            let ruDict = Dict(byFrequency: read("dict-ru"))
            let enDict = Dict(byFrequency: read("dict-en"))
            let ruPairs = Bigrams(lines: read("bigrams-ru"))
            let enPairs = Bigrams(lines: read("bigrams-en"))
            func readData(_ name: String) -> Data {
                guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
                      let d = try? Data(contentsOf: url) else { return Data() }
                return d
            }
            let ruForms = Self.buildLexicon(ruDict, readData("vocab-ru"))
            let enForms = Self.buildLexicon(enDict, readData("vocab-en"))
            let personal = Self.loadPersonal()
            lock.lock()
            ru = ruDict
            en = enDict
            ruBigrams = ruPairs
            enBigrams = enPairs
            ruLex = ruForms
            enLex = enForms
            words = personal.words
            bigrams = personal.bigrams
            lock.unlock()
        }
    }

    func loadForTest(
        ruWords: [String], enWords: [String], ruPairs: [String], enPairs: [String],
        ruForms: Data = Data(), enForms: Data = Data(),
    ) {
        let ruDict = Dict(byFrequency: ruWords)
        let enDict = Dict(byFrequency: enWords)
        lock.lock()
        ru = ruDict
        en = enDict
        ruBigrams = Bigrams(lines: ruPairs)
        enBigrams = Bigrams(lines: enPairs)
        ruLex = Self.buildLexicon(ruDict, ruForms)
        enLex = Self.buildLexicon(enDict, enForms)
        loadStarted = true
        lock.unlock()
    }

    static let storeKey = "kbdict"
    private static let maxWords = 800
    private static let maxBigrams = 1600

    private struct Personal: Codable {
        var words: [String: Int] = [:]
        var bigrams: [String: Int] = [:]
    }

    private var words: [String: Int] = [:]
    private var bigrams: [String: Int] = [:]
    private var dirty = false

    private static func loadPersonal() -> Personal {
        guard let d = SharedStore.read(storeKey),
              let p = try? JSONDecoder().decode(Personal.self, from: d) else { return Personal() }
        return p
    }

    func persist() {
        lock.lock()
        defer { lock.unlock() }
        guard dirty else { return }
        dirty = false
        if let d = try? JSONEncoder().encode(Personal(words: words, bigrams: bigrams)) {
            SharedStore.write(Self.storeKey, d)
        }
    }

    private func decay(_ map: inout [String: Int]) {
        map = map.compactMapValues { $0 / 2 == 0 ? nil : $0 / 2 }
    }

    func learn(_ rawWord: String, previous rawPrevious: String?) {
        guard let word = Self.normalize(rawWord), word.count >= 2 else { return }
        lock.lock()
        defer { lock.unlock() }
        words[word, default: 0] += 1
        if words.count > Self.maxWords { decay(&words) }
        if let prev = rawPrevious.flatMap(Self.normalize) {
            bigrams["\(prev) \(word)", default: 0] += 1
        } else {
            bigrams["\(Self.startToken) \(word)", default: 0] += 1
        }
        if bigrams.count > Self.maxBigrams { decay(&bigrams) }
        dirty = true
    }

    private var sessionSkip: Set<String> = []

    func noteUndoneCorrection(_ rawWord: String) {
        guard let word = Self.normalize(rawWord) else { return }
        lock.lock()
        defer { lock.unlock() }
        sessionSkip.insert(word)
    }

    func noteRejectedCorrection(_ rawWord: String) {
        guard let word = Self.normalize(rawWord), word.count >= 2 else { return }
        lock.lock()
        defer { lock.unlock() }
        words[word, default: 0] += 2
        dirty = true
    }

    private static func normalize(_ raw: String) -> String? {
        let w = raw.trimmingCharacters(in: .whitespaces).lowercased()
        guard !w.isEmpty, w.count <= 32,
              let first = w.first, first.isLetter,
              w.allSatisfy({ $0.isLetter || $0 == "'" || $0 == "-" || $0 == "’" }) else { return nil }
        return w
    }

    private struct Lang {
        let dict: Dict
        let bigrams: Bigrams?
        let lex: Lexicon?
        let alphabet: [Character]
        let neighbors: [Character: Set<Character>]
        let russian: Bool

        func knows(_ w: String) -> Bool { dict.contains(w) || lex?.contains(w) == true }
    }

    private func langFor(firstChar: Character?, russian: Bool) -> Lang? {
        guard let ruDict = ru, let enDict = en else { return nil }
        let useRu: Bool
        if let c = firstChar, ("а"..."я").contains(c) || c == "ё" { useRu = true }
        else if let c = firstChar, ("a"..."z").contains(c) { useRu = false }
        else { useRu = russian }
        return useRu ? Lang(dict: ruDict, bigrams: ruBigrams, lex: ruLex,
                            alphabet: Self.ruAlphabet, neighbors: Self.ruNeighbors, russian: true)
                     : Lang(dict: enDict, bigrams: enBigrams, lex: enLex,
                            alphabet: Self.enAlphabet, neighbors: Self.enNeighbors, russian: false)
    }

    private static func toU16(_ c: Character) -> UInt16 { Array(String(c).utf16)[0] }

    private static let ruNeighborsU: [UInt16: Set<UInt16>] = {
        var m: [UInt16: Set<UInt16>] = [:]
        for (k, v) in ruNeighbors { m[toU16(k)] = Set(v.map(toU16)) }
        return m
    }()

    private static let enNeighborsU: [UInt16: Set<UInt16>] = {
        var m: [UInt16: Set<UInt16>] = [:]
        for (k, v) in enNeighbors { m[toU16(k)] = Set(v.map(toU16)) }
        return m
    }()

    private static let ruConfusableU: Set<UInt32> = Set(ruConfusable.map { pair -> UInt32 in
        let u = Array(pair.utf16)
        return (UInt32(u[0]) << 16) | UInt32(u[1])
    })

    private static let enVowelsU: Set<UInt16> = Set(enVowels.map(toU16))
    private static let yeU: UInt16 = 0x0435
    private static let yoU: UInt16 = 0x0451

    private static func subAdjustU(_ orig: UInt16, _ a: UInt16, ruPlane: Bool) -> Double {
        if (orig == yeU && a == yoU) || (orig == yoU && a == yeU) { return subYo }
        if ruConfusableU.contains((UInt32(orig) << 16) | UInt32(a)) { return subRuConfuse }
        let neighbors = ruPlane ? ruNeighborsU : enNeighborsU
        if neighbors[orig]?.contains(a) == true { return subAdjacent }
        if enVowelsU.contains(orig), enVowelsU.contains(a) { return subEnVowel }
        return subOther
    }

    private static let oovLM = -12.0
    private static let personalPerUse = 0.8
    private static let personalCap = 8
    private static let userBigramBase = 3.6
    private static let userBigramPerUse = 0.25
    private static let embeddedBigramTop = 3.4
    private static let embeddedBigramSpan = 1.6

    private static let subAdjacent = -1.1
    private static let subYo = -0.4
    private static let subRuConfuse = -0.9
    private static let subEnVowel = -1.3
    private static let subOther = -2.6
    private static let transpose = -1.2
    private static let deleteDup = -0.9
    private static let insertDup = -0.6
    private static let deleteCost = -1.6
    private static let insertCost = -1.6
    private static let completePerChar = -0.5
    private static let splitCost = -1.9
    private static let splitPairBonus = 2.6
    private static let extPreference = -0.15
    private static let fuzzyExtra = -0.3

    private static let fuzzySuggestBudget = 3.35
    private static let fuzzyAcBudget = 3.9
    private static let fuzzyTwoEditCap = 3.6
    private static let fuzzyThreeEditCap = 3.9
    private static let singleEditLead = 1.2
    private static let fuzzyPopsSuggest = 3500
    private static let fuzzyPopsAc = 6000
    private static let fuzzyScanRange = 4000
    private static let fuzzyScanTotal = 12000
    private static let fuzzyExtMax = 12
    private static let fuzzyResults = 12
    private static let cheapTwoEdits = 2.4

    struct FuzzyMatch {
        let word: String
        let cost: Double
        let edits: Int
        let ext: Int
        let rank: Int32
    }

    private struct FState {
        let lo: Int
        let hi: Int
        let p: Int
        let i: Int
        let cost: Double
        let edits: Int
    }

    private struct FHeap {
        var items: [FState] = []

        mutating func push(_ s: FState) {
            items.append(s)
            var i = items.count - 1
            while i > 0 {
                let parent = (i - 1) / 2
                if items[parent].cost <= items[i].cost { break }
                items.swapAt(parent, i)
                i = parent
            }
        }

        mutating func pop() -> FState? {
            guard let first = items.first else { return nil }
            let last = items.removeLast()
            if !items.isEmpty {
                items[0] = last
                var i = 0
                while true {
                    let l = 2 * i + 1, r = 2 * i + 2
                    var m = i
                    if l < items.count, items[l].cost < items[m].cost { m = l }
                    if r < items.count, items[r].cost < items[m].cost { m = r }
                    if m == i { break }
                    items.swapAt(i, m)
                    i = m
                }
            }
            return first
        }
    }

    private final class FuzzyHit {
        var cost: Double
        var edits: Int
        var ext: Int
        var proxy: Double

        init(cost: Double, edits: Int, ext: Int, proxy: Double) {
            self.cost = cost; self.edits = edits; self.ext = ext; self.proxy = proxy
        }
    }

    private func fuzzy(
        lex: Lexicon,
        lang: Lang,
        q: [UInt16],
        prefixMode: Bool,
        maxEdits: Int,
        budget: Double,
        maxPops: Int,
    ) -> [FuzzyMatch] {
        let qn = q.count
        guard qn > 0, lex.size > 0 else { return [] }
        var pq = FHeap()
        var seen: [Int64: Double] = [:]
        var hits: [Int: FuzzyHit] = [:]
        var pops = 0
        var scanBudget = Self.fuzzyScanTotal
        let ruPlane = lang.russian

        func key(_ lo: Int, _ p: Int, _ i: Int) -> Int64 {
            (Int64(lo) << 24) | (Int64(p) << 8) | Int64(i)
        }

        func push(_ lo: Int, _ hi: Int, _ p: Int, _ i: Int, _ cost: Double, _ edits: Int) {
            if cost > budget || edits > maxEdits { return }
            let k = key(lo, p, i)
            if let old = seen[k], old <= cost { return }
            seen[k] = cost
            pq.push(FState(lo: lo, hi: hi, p: p, i: i, cost: cost, edits: edits))
        }

        func emit(_ j: Int, _ cost: Double, _ edits: Int, _ ext: Int) {
            let rank = lex.rank(j)
            let lmProxy = rank == Lexicon.noRank ? Self.extraLM : -log(Double(rank) + 1.5)
            let proxy = lmProxy - cost + Self.extPreference * Double(ext)
            if let h = hits[j] {
                if proxy > h.proxy {
                    h.cost = cost; h.edits = edits; h.ext = ext; h.proxy = proxy
                }
            } else {
                hits[j] = FuzzyHit(cost: cost, edits: edits, ext: ext, proxy: proxy)
            }
        }

        func scanRange(_ lo: Int, _ hi: Int, _ cost: Double, _ edits: Int) {
            let count = hi - lo
            if count > Self.fuzzyScanRange || scanBudget <= 0 { return }
            scanBudget -= count
            for j in lo ..< hi {
                let ext = lex.len(j) - qn
                if ext > Self.fuzzyExtMax { continue }
                emit(j, cost, edits, max(0, ext))
            }
        }

        push(0, lex.size, 0, 0, 0.0, 0)
        while pops < maxPops, let s = pq.pop() {
            pops += 1
            if let recorded = seen[key(s.lo, s.p, s.i)], recorded < s.cost { continue }
            let terminal = lex.len(s.lo) == s.p
            if s.i == qn {
                if prefixMode {
                    if s.p >= 2 { scanRange(s.lo, s.hi, s.cost, s.edits) }
                    continue
                }
                if terminal { emit(s.lo, s.cost, s.edits, 0) }
            } else {
                let qc = q[s.i]
                let dup = (s.i > 0 && q[s.i - 1] == qc) || (s.i + 1 < qn && q[s.i + 1] == qc)
                push(s.lo, s.hi, s.p, s.i + 1, s.cost + (dup ? -Self.deleteDup : -Self.deleteCost), s.edits + 1)
            }
            let lastLex: UInt16 = s.p > 0 ? lex.charAt(s.lo, s.p - 1) : 0
            var j = s.lo + (terminal ? 1 : 0)
            while j < s.hi {
                let c = lex.charAt(j, s.p)
                let end = lex.childEnd(j, s.hi, s.p, c)
                if s.i < qn {
                    let qc = q[s.i]
                    if c == qc {
                        push(j, end, s.p + 1, s.i + 1, s.cost, s.edits)
                    } else {
                        push(j, end, s.p + 1, s.i + 1, s.cost - Self.subAdjustU(qc, c, ruPlane: ruPlane), s.edits + 1)
                        if s.i + 1 < qn, q[s.i + 1] == c, qc != c,
                           let g = lex.findChild(j, end, s.p + 1, qc) {
                            push(g.0, g.1, s.p + 2, s.i + 2, s.cost - Self.transpose, s.edits + 1)
                        }
                    }
                    let dupIns = c == lastLex || c == qc
                    push(j, end, s.p + 1, s.i, s.cost + (dupIns ? -Self.insertDup : -Self.insertCost), s.edits + 1)
                } else {
                    let dupIns = c == lastLex
                    push(j, end, s.p + 1, s.i, s.cost + (dupIns ? -Self.insertDup : -Self.insertCost), s.edits + 1)
                }
                j = end
            }
        }
        return hits
            .sorted { $0.value.proxy > $1.value.proxy }
            .prefix(Self.fuzzyResults)
            .map { j, h in FuzzyMatch(word: lex.word(j), cost: h.cost, edits: h.edits, ext: h.ext, rank: lex.rank(j)) }
    }

    private static func lm(_ word: String, dict: Dict) -> Double? {
        guard let r = dict.rank[word] else { return nil }
        return -log(Double(r) + 1.5)
    }

    private static let extraLM = -9.5

    private static func lmFull(_ word: String, lang: Lang) -> Double? {
        if let l = lm(word, dict: lang.dict) { return l }
        return lang.lex?.contains(word) == true ? extraLM : nil
    }

    private func base(_ word: String, lang: Lang, prevNorm: String?) -> Double {
        var s = Self.lmFull(word, lang: lang) ?? Self.oovLM
        if let c = words[word] { s += Double(min(c, Self.personalCap)) * Self.personalPerUse }
        if let prev = prevNorm {
            var big = 0.0
            if let c = bigrams["\(prev) \(word)"] {
                big = Self.userBigramBase + Double(min(c, 6)) * Self.userBigramPerUse
            }
            if let rk = lang.bigrams?.rank["\(prev) \(word)"] {
                big = max(big, Self.embeddedBigramTop - Self.embeddedBigramSpan * Double(rk) / Double(lang.bigrams?.size ?? 1))
            }
            s += big
        }
        return s
    }

    private static func edits1(_ word: String, lang: Lang) -> [String: Double] {
        var out: [String: Double] = [:]
        let chars = Array(word)
        let n = chars.count
        func offer(_ arr: [Character], _ cost: Double) {
            let s = String(arr)
            guard s != word else { return }
            if let old = out[s] { out[s] = max(old, cost) } else { out[s] = cost }
        }
        for i in 0 ..< n {
            var c = chars; c.remove(at: i)
            let dup = (i > 0 && chars[i] == chars[i - 1]) || (i < n - 1 && chars[i] == chars[i + 1])
            offer(c, dup ? deleteDup : deleteCost)
        }
        for i in 0 ..< max(0, n - 1) where chars[i] != chars[i + 1] {
            var c = chars; c.swapAt(i, i + 1)
            offer(c, transpose)
        }
        for i in 0 ..< n {
            let orig = chars[i]
            for a in lang.alphabet where a != orig {
                let cost: Double
                if (orig == "е" && a == "ё") || (orig == "ё" && a == "е") { cost = subYo }
                else if ruConfusable.contains("\(orig)\(a)") { cost = subRuConfuse }
                else if lang.neighbors[orig]?.contains(a) == true { cost = subAdjacent }
                else if enVowels.contains(orig), enVowels.contains(a) { cost = subEnVowel }
                else { cost = subOther }
                var c = chars; c[i] = a
                offer(c, cost)
            }
        }
        for i in 0 ... n {
            for a in lang.alphabet {
                var c = chars; c.insert(a, at: i)
                let dup = (i < n && a == chars[i]) || (i > 0 && a == chars[i - 1])
                offer(c, dup ? insertDup : insertCost)
            }
        }
        return out
    }

    func suggest(prefix: String, previous: String?, russian: Bool, limit: Int = 3) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        if prefix.isEmpty { return predictEmpty(previous: previous, russian: russian, limit: limit) }
        let folded = prefix.lowercased()
        guard let lang = langFor(firstChar: folded.first, russian: russian) else { return [] }
        let prevNorm = previous.flatMap(Self.normalize)

        struct Cand { var adjust: Double }
        var cands: [String: Cand] = [:]
        func offer(_ w: String, _ adjust: Double) {
            guard w != folded else { return }
            if var c = cands[w] {
                c.adjust = max(c.adjust, adjust)
                cands[w] = c
            } else {
                cands[w] = Cand(adjust: adjust)
            }
        }

        let completions = lang.dict.complete(folded, limit: limit + 9)
        for w in completions { offer(w, Self.extPreference * Double(w.count - folded.count)) }

        if let prev = prevNorm {
            for w in continuationsOf(prev, lang: lang, limit: limit * 3) where w.hasPrefix(folded) {
                offer(w, 0)
            }
        }

        for w in personalMatches(folded) { offer(w, 0) }

        let isPrefix = lang.dict.isPrefix(folded)
        if (2 ... 18).contains(folded.count), !(isPrefix && completions.count >= limit) {
            let edits = Self.edits1(folded, lang: lang)
            for (cand, cost) in edits where lang.knows(cand) {
                offer(cand, cost)
            }
            if folded.count >= 3, completions.count < 2, !isPrefix {
                var budget = 12
                outer: for (cand, cost) in edits where lang.dict.isPrefix(cand) {
                    for full in lang.dict.complete(cand, limit: 2) {
                        offer(full, cost + Self.fuzzyExtra
                                    + Self.extPreference * Double(full.count - folded.count))
                        budget -= 1
                        if budget <= 0 { break outer }
                    }
                }
            }
        }

        if (5 ... 18).contains(folded.count), !(isPrefix && completions.count >= limit), let lex = lang.lex {
            let maxE = folded.count >= 7 ? 3 : 2
            for m in fuzzy(lex: lex, lang: lang, q: Array(folded.utf16), prefixMode: true,
                           maxEdits: maxE, budget: Self.fuzzySuggestBudget, maxPops: Self.fuzzyPopsSuggest) {
                let adjust = -m.cost + Self.extPreference * Double(m.ext) + (m.edits > 0 ? Self.fuzzyExtra : 0)
                offer(m.word, adjust)
            }
        }

        let ranked = cands
            .map { (word: $0.key, score: base($0.key, lang: lang, prevNorm: prevNorm) + $0.value.adjust) }
            .sorted { $0.score > $1.score }
        return ranked.prefix(limit).map { Self.matchCase(typed: prefix, suggestion: $0.word) }
    }

    private func continuationsOf(_ prev: String, lang: Lang, limit: Int) -> [String] {
        var out: [String] = []
        var seen: Set<String> = []
        for w in continuationsOfUser(prev, limit: limit) where seen.insert(w).inserted {
            out.append(w)
        }
        if let curated = lang.bigrams?.next[prev] {
            for w in curated where out.count < limit && seen.insert(w).inserted {
                out.append(w)
            }
        }
        return out
    }

    private func continuationsOfUser(_ prev: String, limit: Int) -> [String] {
        let marker = "\(prev) "
        return bigrams
            .filter { $0.key.hasPrefix(marker) }
            .sorted { $0.value > $1.value }
            .prefix(limit)
            .map { String($0.key.dropFirst(marker.count)) }
    }

    private func personalMatches(_ folded: String) -> [String] {
        words
            .filter { $0.value >= 2 && $0.key.count > folded.count && $0.key.hasPrefix(folded) }
            .sorted { $0.value > $1.value }
            .prefix(3)
            .map(\.key)
    }

    private func predictEmpty(previous: String?, russian: Bool, limit: Int) -> [String] {
        let atStart = previous == nil
        let prevNorm = previous.flatMap(Self.normalize)
        var out: [String] = []
        func add(_ raw: String) {
            guard raw != prevNorm else { return }
            guard Self.matchesScript(raw, russian: russian) else { return }
            let w = atStart ? Self.capitalizeFirst(raw) : raw
            if !out.contains(w) { out.append(w) }
        }
        if atStart {
            continuationsOfUser(Self.startToken, limit: limit * 2).forEach(add)
        } else if let prev = prevNorm {
            continuationsOfUser(prev, limit: limit * 2).forEach(add)
            let table = (prev.first.map { ("а"..."я").contains($0) || $0 == "ё" } ?? russian) ? ruBigrams : enBigrams
            if let curated = table?.next[prev] {
                for w in curated where out.count < limit { add(w) }
            }
        }
        if out.count < limit {
            words.filter { $0.value >= 2 }
                .sorted { $0.value > $1.value }
                .prefix(limit * 2)
                .forEach { add($0.key) }
        }
        if atStart, out.count < limit {
            for w in (russian ? Self.ruCommon : Self.enCommon) where out.count < limit { add(w) }
        }
        return Array(out.prefix(limit))
    }

    private static let acMarginOOV = 0.8
    private static let acMarginInVocab = 2.2
    private static let acRareRank = 3000
    private static let acNearTie = 0.4
    static let acDeepFreqCap = 8000
    private static let acPrefixLead = 0.5

    private static let informalEN: Set<String> = [
        "dont", "cant", "didnt", "doesnt", "isnt", "wasnt", "arent", "havent", "hasnt", "hadnt",
        "couldnt", "shouldnt", "wouldnt", "wont", "aint", "im", "ive", "youre", "youve", "youll",
        "youd", "theyre", "theyve", "weve", "thats", "whats", "theres", "heres", "lets",
    ]
    private static let informalRU: Set<String> = ["спс", "пж", "плз", "мб", "хз", "крч", "оч", "прив", "пон", "лан", "збс", "кст", "чел"]

    func autocorrect(_ word: String, previous: String?, russian: Bool, deep: Bool = true) -> String? {
        let folded = word.lowercased()
        guard folded.allSatisfy({ $0.isLetter || $0 == "'" || $0 == "-" || $0 == "’" }) else { return nil }
        if folded == "i", word != "I" {
            lock.lock()
            defer { lock.unlock() }
            if (words["i"] ?? 0) >= 2 { return nil }
            return "I"
        }
        guard (3 ... 18).contains(folded.count) else { return nil }
        let cyr = folded.contains { ("а"..."я").contains($0) || $0 == "ё" }
        let lat = folded.contains { ("a"..."z").contains($0) }
        if cyr && lat { return nil }

        lock.lock()
        defer { lock.unlock() }
        guard let lang = langFor(firstChar: folded.first, russian: russian) else { return nil }

        if lang.bigrams?.vocab.contains(folded) == true { return nil }
        if Self.informalEN.contains(folded) || Self.informalRU.contains(folded) { return nil }
        if sessionSkip.contains(folded) { return nil }
        let personalUses = words[folded] ?? 0
        if personalUses >= 2 { return nil }

        let prevNorm = previous.flatMap(Self.normalize)
        let typedRank = lang.dict.rank[folded]
        let inVocab = typedRank != nil
        if !inVocab, lang.lex?.contains(folded) == true { return nil }
        if let tr = typedRank, tr < Self.acRareRank || folded.count < 4 || personalUses > 0 { return nil }

        let typedBase = inVocab ? base(folded, lang: lang, prevNorm: prevNorm) : Self.oovLM
        let margin = inVocab ? Self.acMarginInVocab : Self.acMarginOOV

        enum Kind { case edit, completion, twoEdits }
        struct Best { var word: String; var score: Double; var kind: Kind; var contextual: Bool; var adjust: Double }
        var best: Best?
        var second = -Double.infinity
        func offer(_ w: String, score s: Double, kind: Kind, contextual: Bool, adjust: Double) {
            if let b = best {
                if w == b.word {
                    if s > b.score { best = Best(word: w, score: s, kind: kind, contextual: contextual, adjust: adjust) }
                } else if s > b.score {
                    second = max(second, b.score)
                    best = Best(word: w, score: s, kind: kind, contextual: contextual, adjust: adjust)
                } else if s > second {
                    second = s
                }
            } else {
                best = Best(word: w, score: s, kind: kind, contextual: contextual, adjust: adjust)
            }
        }
        func pairKnown(_ w: String) -> Bool {
            guard let prev = prevNorm else { return false }
            return bigrams["\(prev) \(w)"] != nil || lang.bigrams?.rank["\(prev) \(w)"] != nil
        }
        func offerAdjusted(_ w: String, _ adjust: Double, kind: Kind) {
            offer(w, score: base(w, lang: lang, prevNorm: prevNorm) + adjust,
                  kind: kind, contextual: pairKnown(w), adjust: adjust)
        }

        let isPrefix = lang.dict.isPrefix(folded)
        var bestSingle = -Double.infinity

        for (cand, cost) in Self.edits1(folded, lang: lang) {
            guard lang.knows(cand) else { continue }
            if inVocab, cand.count != folded.count || cost < Self.subAdjacent { continue }
            if !inVocab, isPrefix, cand.count != folded.count { continue }
            let s = base(cand, lang: lang, prevNorm: prevNorm) + cost
            if s > bestSingle { bestSingle = s }
            offer(cand, score: s, kind: .edit, contextual: pairKnown(cand), adjust: cost)
        }

        if deep, !inVocab {
            if folded.count >= 5 {
                for full in lang.dict.complete(folded, limit: 3) {
                    let ext = full.count - folded.count
                    if ext <= 2 { offerAdjusted(full, Self.completePerChar * Double(ext), kind: .completion) }
                }
            }
        }
        if deep {
            let chars = Array(folded)
            for i in 1 ..< chars.count {
                let l = String(chars[..<i])
                let r = String(chars[i...])
                guard let lLm = Self.lm(l, dict: lang.dict), let rLm = Self.lm(r, dict: lang.dict) else { continue }
                let known = lang.bigrams?.rank["\(l) \(r)"] != nil || bigrams["\(l) \(r)"] != nil
                if !known {
                    if l.count < 3 || r.count < 3 { continue }
                    if (lang.dict.rank[l] ?? .max) >= 2000 || (lang.dict.rank[r] ?? .max) >= 2000 { continue }
                }
                let s = min(lLm, rLm) + Self.splitCost + (known ? Self.splitPairBonus : 0)
                offer("\(l) \(r)", score: s, kind: .edit, contextual: known, adjust: Self.splitCost)
            }
            if !inVocab, folded.count >= 5, !isPrefix, let lex = lang.lex {
                let maxE = folded.count >= 8 ? 3 : 2
                let matches = fuzzy(lex: lex, lang: lang, q: Array(folded.utf16), prefixMode: false,
                                    maxEdits: maxE, budget: Self.fuzzyAcBudget, maxPops: Self.fuzzyPopsAc)
                    .filter {
                        $0.cost <= ($0.edits <= 1 ? Double.greatestFiniteMagnitude
                                    : $0.edits == 2 ? Self.fuzzyTwoEditCap : Self.fuzzyThreeEditCap)
                    }
                for m in matches where m.edits <= 1 {
                    let s = base(m.word, lang: lang, prevNorm: prevNorm) - m.cost
                    if s > bestSingle { bestSingle = s }
                }
                for m in matches {
                    let s = base(m.word, lang: lang, prevNorm: prevNorm) - m.cost
                    if m.edits >= 2, s < bestSingle + Self.singleEditLead { continue }
                    offer(m.word, score: s, kind: m.edits >= 2 ? .twoEdits : .edit,
                          contextual: pairKnown(m.word), adjust: -m.cost)
                }
            }
        }

        guard let b = best else { return nil }
        if b.score < typedBase + margin { return nil }
        if inVocab, !b.contextual { return nil }
        if b.kind == .twoEdits, (lang.dict.rank[b.word] ?? .max) >= Self.acDeepFreqCap,
           b.adjust < -Self.cheapTwoEdits { return nil }
        if !inVocab, isPrefix, b.kind != .completion,
           let topCompletion = lang.dict.complete(folded, limit: 1).first {
            let ext = min(topCompletion.count - folded.count, 2)
            let prior = (Self.lm(topCompletion, dict: lang.dict) ?? Self.oovLM) + Self.completePerChar * Double(ext)
            if b.score < prior + Self.acPrefixLead { return nil }
        }
        if !b.contextual, second > b.score - Self.acNearTie { return nil }
        return Self.matchCase(typed: word, suggestion: b.word)
    }

    private static func matchesScript(_ w: String, russian: Bool) -> Bool {
        guard let c = w.lowercased().first(where: \.isLetter) else { return true }
        if ("а"..."я").contains(c) || c == "ё" { return russian }
        if ("a"..."z").contains(c) { return !russian }
        return true
    }

    private static func capitalizeFirst(_ w: String) -> String {
        guard let f = w.first else { return w }
        return f.uppercased() + w.dropFirst()
    }

    private static func matchCase(typed: String, suggestion: String) -> String {
        if typed.count >= 2, typed.allSatisfy({ !$0.isLetter || $0.isUppercase }) {
            return suggestion.uppercased()
        }
        if let first = typed.first, first.isUppercase {
            return suggestion.prefix(1).uppercased() + suggestion.dropFirst()
        }
        return suggestion
    }
}
