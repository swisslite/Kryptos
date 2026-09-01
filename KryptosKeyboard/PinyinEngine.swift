import Foundation

struct PinyinCandidate: Equatable {
    let text: String
    let consumed: Int
}

final class PinyinEngine: @unchecked Sendable {
    static let shared = PinyinEngine()

    private let lock = NSLock()
    private var loaded = false

    private var keyChars: [UInt8] = []
    private var keyStarts: [Int32] = []
    private var initChars: [UInt8] = []
    private var initStarts: [Int32] = []
    private var wordChars: [UInt16] = []
    private var wordStarts: [Int32] = []
    private var ranks: [Int32] = []
    private var byInitials: [Int32] = []
    private var syllables: Set<String> = []
    private var maxSyllable = 6

    private var personal: [String: Int] = [:]
    private var sessionNoted: [String: Int] = [:]
    private var personalDirty = false
    private var personalLoaded = false
    private var loadStarted = false
    private var wipeGeneration = 0
    private var storedCopyExists = false

    private static let joinPenalty = 45000
    private static let boundaryPenalty = 400000
    private static let initialsPenalty = 20000
    private static let overshootPenalty = 1 << 20
    private static let partialBase = 1 << 22
    private static let userBoost = 4000
    private static let personalCap = 400
    private static let maxInput = 32
    private static let storeKey = TypingMemory.pinyinKey

    var isLoaded: Bool {
        lock.lock(); defer { lock.unlock() }
        return loaded
    }

    func warmUp() {
        lock.lock()
        let already = loaded
        let hadPersonal = personalLoaded
        let busy = loadStarted
        let generation = wipeGeneration
        if !already || !hadPersonal { loadStarted = true }
        lock.unlock()
        if already && hadPersonal { return }
        if busy { return }
        DispatchQueue.global(qos: .utility).async { [self] in
            load(already: already, hadPersonal: hadPersonal, generation: generation)
        }
    }

    private func load(already: Bool, hadPersonal: Bool, generation: Int) {
        let built = already ? nil : PinyinEngine.build()
        var stored: [String: Int]?
        if !hadPersonal {
            if let data = SharedStore.read(PinyinEngine.storeKey) {
                stored = try? JSONDecoder().decode([String: Int].self, from: data)
            }
        }

        lock.lock()
        guard wipeGeneration == generation else {
            loadStarted = false
            lock.unlock()
            return
        }
        if !loaded, let built {
            keyChars = built.keyChars; keyStarts = built.keyStarts
            initChars = built.initChars; initStarts = built.initStarts
            wordChars = built.wordChars; wordStarts = built.wordStarts
            ranks = built.ranks; byInitials = built.byInitials
            syllables = built.syllables; maxSyllable = built.maxSyllable
            loaded = true
        }
        if !personalLoaded {
            personalLoaded = true
            if let stored {
                personal = stored
                storedCopyExists = true
            }
        }
        loadStarted = false
        lock.unlock()
    }

    func note(_ word: String) {
        guard !word.isEmpty else { return }
        lock.lock()
        personal[word, default: 0] += 1
        sessionNoted[word, default: 0] += 1
        if personal.count > PinyinEngine.personalCap {
            let keep = personal.sorted { $0.value > $1.value }.prefix(PinyinEngine.personalCap)
            personal = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
        }
        personalDirty = true
        lock.unlock()
    }

    func forgetTypingSession() {
        lock.lock()
        defer { lock.unlock() }
        guard !sessionNoted.isEmpty else { return }
        for (word, count) in sessionNoted {
            let left = (personal[word] ?? 0) - count
            if left > 0 { personal[word] = left } else { personal[word] = nil }
        }
        sessionNoted.removeAll()
        personalDirty = true
    }

    func dropIfStoreGone() {
        lock.lock()
        let hadCopy = storedCopyExists
        lock.unlock()
        guard hadCopy, SharedStore.read(PinyinEngine.storeKey) == nil else { return }
        dropEverything()
    }

    func dropEverything() {
        lock.lock()
        wipeGeneration &+= 1
        personal = [:]
        sessionNoted.removeAll()
        personalDirty = false
        personalLoaded = false
        storedCopyExists = false
        lock.unlock()
    }

    func persist() {
        lock.lock()
        guard personalDirty, personalLoaded else { lock.unlock(); return }
        guard SharedStore.isShared else {
            personalDirty = false
            storedCopyExists = false
            lock.unlock()
            SharedStore.delete(PinyinEngine.storeKey)
            return
        }
        if storedCopyExists, SharedStore.read(PinyinEngine.storeKey) == nil {
            personal = [:]
            sessionNoted.removeAll()
            personalDirty = false
            storedCopyExists = false
            lock.unlock()
            return
        }
        let snapshot = personal
        lock.unlock()
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        guard SharedStore.write(PinyinEngine.storeKey, data) else { return }
        lock.lock()
        personalDirty = false
        storedCopyExists = true
        lock.unlock()
    }

    func forget() {
        lock.lock()
        personal = [:]
        sessionNoted.removeAll()
        personalDirty = false
        storedCopyExists = false
        lock.unlock()
        SharedStore.delete(PinyinEngine.storeKey)
    }

    func candidates(for buffer: String, limit: Int) -> [PinyinCandidate] {
        lock.lock()
        defer { lock.unlock() }
        guard loaded else { return [] }

        var letters: [UInt8] = []
        var origin: [Int] = []
        var forced = Set<Int>()
        for (i, ch) in buffer.unicodeScalars.enumerated() {
            if ch.value >= 97 && ch.value <= 122 {
                letters.append(UInt8(ch.value))
                origin.append(i)
            } else if ch == "'" {
                forced.insert(letters.count)
            }
            if letters.count >= PinyinEngine.maxInput { break }
        }
        let n = letters.count
        guard n > 0 else { return [] }

        func consumedFor(_ count: Int) -> Int {
            guard count > 0 else { return 0 }
            guard count < n else { return buffer.unicodeScalars.count }
            return origin[count - 1] + 1
        }

        var scored: [(cost: Int, tier: Int, cand: PinyinCandidate)] = []
        var seen = Set<String>()

        func offer(_ text: String, consumed: Int, cost: Int, tier: Int) {
            guard !text.isEmpty, seen.insert(text).inserted else { return }
            let boost = personal[text].map { $0 * PinyinEngine.userBoost } ?? 0
            scored.append((max(0, cost - boost), tier, PinyinCandidate(text: text, consumed: consumed)))
        }

        let segments = forced.filter { $0 > 0 && $0 < n }.count + 1
        let full = keyRange(letters)
        var i = full.lowerBound
        while i < full.upperBound, keyLength(i) == n {
            let short = initialsLength(i) < segments ? PinyinEngine.boundaryPenalty : 0
            offer(word(i), consumed: consumedFor(n), cost: Int(ranks[i]) + short, tier: 0)
            i += 1
        }

        if let path = compose(letters, forced: forced), path.count > 1 {
            let text = path.map { word($0.entry) }.joined()
            let cost = path.reduce(0) { $0 + Int(ranks[$1.entry]) } + (path.count - 1) * PinyinEngine.joinPenalty
            offer(text, consumed: consumedFor(n), cost: cost, tier: 0)
        }

        let inits = initialsRange(letters)
        var k = inits.lowerBound
        while k < inits.upperBound, k < byInitials.count {
            let e = Int(byInitials[k])
            if initialsLength(e) != n { break }
            offer(word(e), consumed: consumedFor(n), cost: Int(ranks[e]) + PinyinEngine.initialsPenalty, tier: 1)
            k += 1
        }

        var j = i
        while j < full.upperBound, scored.count < limit * 3 {
            offer(word(j), consumed: consumedFor(n), cost: Int(ranks[j]) + PinyinEngine.overshootPenalty, tier: 2)
            j += 1
        }

        var length = n - 1
        while length >= 1, scored.count < limit * 3 {
            let range = keyRange(Array(letters[0 ..< length]))
            var p = range.lowerBound
            var added = 0
            while p < range.upperBound, keyLength(p) == length, added < limit {
                offer(word(p), consumed: consumedFor(length),
                      cost: Int(ranks[p]) + (n - length) * PinyinEngine.partialBase, tier: 3)
                p += 1
                added += 1
            }
            length -= 1
        }

        scored.sort { a, b in
            if a.tier != b.tier { return a.tier < b.tier }
            if a.cost != b.cost { return a.cost < b.cost }
            return a.cand.text < b.cand.text
        }
        return scored.prefix(limit).map(\.cand)
    }

    private struct Step { let entry: Int; let from: Int }

    private func compose(_ letters: [UInt8], forced: Set<Int>) -> [Step]? {
        let n = letters.count
        var forward = [Bool](repeating: false, count: n + 1)
        forward[0] = true
        for i in 0 ..< n where forward[i] {
            var l = 1
            while l <= maxSyllable, i + l <= n {
                if !(i + 1 ... i + l).contains(where: { $0 < n && forced.contains($0) }),
                   syllables.contains(String(decoding: letters[i ..< i + l], as: UTF8.self)) {
                    forward[i + l] = true
                }
                l += 1
            }
        }
        guard forward[n] else { return nil }

        var backward = [Bool](repeating: false, count: n + 1)
        backward[n] = true
        var i = n - 1
        while i >= 0 {
            if forward[i] {
                var l = 1
                while l <= maxSyllable, i + l <= n {
                    if backward[i + l],
                       !(i + 1 ... i + l).contains(where: { $0 < n && forced.contains($0) }),
                       syllables.contains(String(decoding: letters[i ..< i + l], as: UTF8.self)) {
                        backward[i] = true
                        break
                    }
                    l += 1
                }
            }
            i -= 1
        }

        let stops = (0 ... n).filter { forward[$0] && backward[$0] }
        guard stops.count > 1 else { return nil }

        var best = [Int](repeating: Int.max, count: n + 1)
        var via = [Step?](repeating: nil, count: n + 1)
        best[0] = 0
        for a in stops where best[a] != Int.max {
            for b in stops where b > a {
                let range = keyRange(Array(letters[a ..< b]))
                guard range.lowerBound < range.upperBound,
                      keyLength(range.lowerBound) == b - a else { continue }
                let entry = range.lowerBound
                let cost = best[a] + Int(ranks[entry]) + (a > 0 ? PinyinEngine.joinPenalty : 0)
                if cost < best[b] {
                    best[b] = cost
                    via[b] = Step(entry: entry, from: a)
                }
            }
        }
        guard best[n] != Int.max else { return nil }

        var path: [Step] = []
        var at = n
        while at > 0, let step = via[at] {
            path.append(step)
            at = step.from
        }
        return path.reversed()
    }

    private func keyLength(_ i: Int) -> Int { Int(keyStarts[i + 1] - keyStarts[i]) }
    private func initialsLength(_ i: Int) -> Int { Int(initStarts[i + 1] - initStarts[i]) }

    private func word(_ i: Int) -> String {
        let from = Int(wordStarts[i]), to = Int(wordStarts[i + 1])
        return wordChars.withUnsafeBufferPointer {
            String(utf16CodeUnits: $0.baseAddress! + from, count: to - from)
        }
    }

    private func compareKey(_ i: Int, _ prefix: [UInt8]) -> Int {
        let from = Int(keyStarts[i]), len = keyLength(i)
        var p = 0
        while p < prefix.count {
            if p >= len { return -1 }
            let a = keyChars[from + p], b = prefix[p]
            if a != b { return a < b ? -1 : 1 }
            p += 1
        }
        return 0
    }

    private func compareInitials(_ slot: Int, _ prefix: [UInt8]) -> Int {
        let i = Int(byInitials[slot])
        let from = Int(initStarts[i]), len = initialsLength(i)
        var p = 0
        while p < prefix.count {
            if p >= len { return -1 }
            let a = initChars[from + p], b = prefix[p]
            if a != b { return a < b ? -1 : 1 }
            p += 1
        }
        return 0
    }

    private func keyRange(_ prefix: [UInt8]) -> Range<Int> {
        bounds(count: ranks.count, prefix: prefix, compare: compareKey)
    }

    private func initialsRange(_ prefix: [UInt8]) -> Range<Int> {
        bounds(count: byInitials.count, prefix: prefix, compare: compareInitials)
    }

    private func bounds(count: Int, prefix: [UInt8], compare: (Int, [UInt8]) -> Int) -> Range<Int> {
        guard count > 0, !prefix.isEmpty else { return 0 ..< 0 }
        var lo = 0, hi = count
        while lo < hi {
            let mid = (lo + hi) / 2
            if compare(mid, prefix) < 0 { lo = mid + 1 } else { hi = mid }
        }
        let start = lo
        hi = count
        while lo < hi {
            let mid = (lo + hi) / 2
            if compare(mid, prefix) <= 0 { lo = mid + 1 } else { hi = mid }
        }
        return start ..< lo
    }

    private struct Built {
        var keyChars: [UInt8]; var keyStarts: [Int32]
        var initChars: [UInt8]; var initStarts: [Int32]
        var wordChars: [UInt16]; var wordStarts: [Int32]
        var ranks: [Int32]; var byInitials: [Int32]
        var syllables: Set<String>; var maxSyllable: Int
    }

    private static func bundleURL(_ name: String) -> URL? {
        Bundle.main.url(forResource: name, withExtension: "txt")
            ?? Bundle.main.url(forResource: name, withExtension: "txt", subdirectory: "dict")
    }

    private static func build() -> Built? {
        guard let syllableURL = bundleURL("pinyin-syllables"),
              let dictURL = bundleURL("pinyin-zh"),
              let syllableData = try? Data(contentsOf: syllableURL, options: .mappedIfSafe),
              let dictData = try? Data(contentsOf: dictURL, options: .mappedIfSafe) else { return nil }

        var syllables = Set<String>()
        var maxSyllable = 1
        for line in String(decoding: syllableData, as: UTF8.self).split(separator: "\n") {
            let s = String(line)
            guard !s.isEmpty else { continue }
            syllables.insert(s)
            maxSyllable = max(maxSyllable, s.utf8.count)
        }
        guard !syllables.isEmpty else { return nil }

        var keyChars: [UInt8] = []; var keyStarts: [Int32] = [0]
        var initChars: [UInt8] = []; var initStarts: [Int32] = [0]
        var wordChars: [UInt16] = []; var wordStarts: [Int32] = [0]
        var ranks: [Int32] = []

        keyChars.reserveCapacity(dictData.count / 3)
        wordChars.reserveCapacity(dictData.count / 8)

        dictData.withUnsafeBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            let n = raw.count
            var i = 0
            while i < n {
                let keyFrom = i
                while i < n, base[i] != 9 { i += 1 }
                guard i < n else { break }
                let keyTo = i; i += 1

                let initFrom = i
                while i < n, base[i] != 9 { i += 1 }
                guard i < n else { break }
                let initTo = i; i += 1

                let wordFrom = i
                while i < n, base[i] != 9 { i += 1 }
                guard i < n else { break }
                let wordTo = i; i += 1

                var rank: Int32 = 0
                while i < n, base[i] >= 48, base[i] <= 57 {
                    rank = rank &* 10 &+ Int32(base[i] - 48)
                    i += 1
                }
                while i < n, base[i] != 10 { i += 1 }
                i += 1

                for p in keyFrom ..< keyTo { keyChars.append(base[p]) }
                keyStarts.append(Int32(keyChars.count))
                for p in initFrom ..< initTo { initChars.append(base[p]) }
                initStarts.append(Int32(initChars.count))

                var w = wordFrom
                while w < wordTo {
                    let b = base[w]
                    if b < 0x80 {
                        wordChars.append(UInt16(b)); w += 1
                    } else if b < 0xE0 {
                        guard w + 1 < wordTo else { break }
                        wordChars.append((UInt16(b & 0x1F) << 6) | UInt16(base[w + 1] & 0x3F))
                        w += 2
                    } else if b < 0xF0 {
                        guard w + 2 < wordTo else { break }
                        wordChars.append((UInt16(b & 0x0F) << 12) | (UInt16(base[w + 1] & 0x3F) << 6)
                            | UInt16(base[w + 2] & 0x3F))
                        w += 3
                    } else {
                        guard w + 3 < wordTo else { break }
                        guard w + 3 < wordTo else { break }
                        let scalar = (UInt32(b & 0x07) << 18) | (UInt32(base[w + 1] & 0x3F) << 12)
                            | (UInt32(base[w + 2] & 0x3F) << 6) | UInt32(base[w + 3] & 0x3F)
                        guard scalar >= 0x10000 else { w += 4; continue }
                        let cp = scalar - 0x10000
                        wordChars.append(UInt16(0xD800 + (cp >> 10)))
                        wordChars.append(UInt16(0xDC00 + (cp & 0x3FF)))
                        w += 4
                    }
                }
                wordStarts.append(Int32(wordChars.count))
                ranks.append(rank)
            }
        }

        guard !ranks.isEmpty else { return nil }

        var order = [Int32](repeating: 0, count: ranks.count)
        for i in 0 ..< ranks.count { order[i] = Int32(i) }
        order.sort { a, b in
            let ia = Int(a), ib = Int(b)
            let fa = Int(initStarts[ia]), la = Int(initStarts[ia + 1]) - fa
            let fb = Int(initStarts[ib]), lb = Int(initStarts[ib + 1]) - fb
            var p = 0
            while p < la, p < lb {
                let x = initChars[fa + p], y = initChars[fb + p]
                if x != y { return x < y }
                p += 1
            }
            if la != lb { return la < lb }
            if ranks[ia] != ranks[ib] { return ranks[ia] < ranks[ib] }
            return ia < ib
        }

        return Built(keyChars: keyChars, keyStarts: keyStarts,
                     initChars: initChars, initStarts: initStarts,
                     wordChars: wordChars, wordStarts: wordStarts,
                     ranks: ranks, byInitials: order,
                     syllables: syllables, maxSyllable: maxSyllable)
    }
}
