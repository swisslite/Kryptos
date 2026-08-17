import Foundation
import Security
import UIKit
import CipherCore

enum RemoteClipboard {
    private static let marker = "com.apple.is-remote-clipboard"
    static var isRemote: Bool { UIPasteboard.general.contains(pasteboardTypes: [marker]) }
}

final class ConfigCache<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: T?
    private var at: TimeInterval = 0
    private let ttl: TimeInterval

    init(ttl: TimeInterval = 0.5) { self.ttl = ttl }

    func get(_ make: () -> T) -> T {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        if let stored, now - at < ttl {
            lock.unlock()
            return stored
        }
        lock.unlock()
        let fresh = make()
        lock.lock()
        stored = fresh
        at = now
        lock.unlock()
        return fresh
    }

    func invalidate() {
        lock.lock()
        stored = nil
        at = 0
        lock.unlock()
    }
}

enum ConfigCaches {
    static func invalidateAll() {
        ChatStego.invalidateCache()
        PrivacyConfig.invalidateCache()
        InterfaceConfig.invalidateCache()
        KeyboardConfig.invalidateCache()
    }
}

enum AppGroup {
    static let fallbackIdentifier = "group.com.kryptos.app"

    private static let identifierLock = NSLock()
    private nonisolated(unsafe) static var resolvedIdentifier: String?

    static var identifier: String {
        identifierLock.lock()
        if let resolvedIdentifier {
            identifierLock.unlock()
            return resolvedIdentifier
        }
        identifierLock.unlock()
        let fresh = resolveIdentifier()
        identifierLock.lock()
        if resolvedIdentifier == nil { resolvedIdentifier = fresh }
        let value = resolvedIdentifier ?? fresh
        identifierLock.unlock()
        return value
    }

    static func revalidate() {
        identifierLock.lock()
        let current = resolvedIdentifier
        identifierLock.unlock()
        guard current == fallbackIdentifier else { return }
        KeychainProbe.forget()
        let fresh = resolveIdentifier()
        guard fresh != fallbackIdentifier else { return }
        identifierLock.lock()
        resolvedIdentifier = fresh
        identifierLock.unlock()
    }

    private static func resolveIdentifier() -> String {
        let fm = FileManager.default
        var seen = Set<String>()
        let candidates = allCandidateGroups().filter { seen.insert($0).inserted }
        let working = candidates.filter { fm.containerURL(forSecurityApplicationGroupIdentifier: $0) != nil }
        return working.sorted().first ?? fallbackIdentifier
    }

    private static func allCandidateGroups() -> [String] {
        var groups = provisionedGroups()
        if let team = KeychainProbe.teamPrefix() {
            groups.append("group.\(team).com.kryptos.app")
        }
        groups.append(fallbackIdentifier)
        return groups
    }

    private static func provisionedGroups() -> [String] {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else { return [] }
        guard let text = String(data: data, encoding: .isoLatin1),
              let s = text.range(of: "<plist"), let e = text.range(of: "</plist>"),
              let plistData = String(text[s.lowerBound ..< e.upperBound]).data(using: .isoLatin1),
              let obj = try? PropertyListSerialization.propertyList(from: plistData, options: [], format: nil),
              let dict = obj as? [String: Any],
              let ent = dict["Entitlements"] as? [String: Any],
              let groups = ent["com.apple.security.application-groups"] as? [String] else { return [] }
        return groups
    }

    static var container: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier) {
            return url
        }
        return (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
    }

    static var isShared: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier) != nil
    }
}

enum ChatStego {
    private static let key = "stego"
    private struct Config: Codable {
        var enabled = false
        var lang = "auto"
        var smart: Bool? = false
        var mode: String?
    }

    private static let cache = ConfigCache<Config>()

    static func invalidateCache() { cache.invalidate() }

    private static func config() -> Config {
        cache.get {
            guard let d = SharedStore.read(key), let c = try? JSONDecoder().decode(Config.self, from: d) else { return Config() }
            return c
        }
    }

    private static func mode(of c: Config) -> StegoMode {
        StegoMode.resolve(c.mode, legacySmart: c.smart ?? false)
    }

    static func save(enabled: Bool, language: String, mode: StegoMode) {
        let c = Config(enabled: enabled, lang: language, smart: mode == .smart, mode: mode.rawValue)
        if let d = try? JSONEncoder().encode(c) { SharedStore.write(key, d) }
        cache.invalidate()
    }

    static func resolvedCover() -> (language: StegoLanguage?, mode: StegoMode) {
        let c = config()
        guard c.enabled else { return (nil, .words) }
        let language: StegoLanguage
        switch c.lang {
        case "english": language = .english
        case "russian": language = .russian
        case "german": language = .german
        case "chinese": language = .chinese
        default: language = .forSystem()
        }
        return (language, mode(of: c))
    }

    static var isEnabled: Bool { config().enabled }
    static var languageRaw: String { config().lang }
    static var storedMode: StegoMode { mode(of: config()) }
}

enum PrivacyConfig {
    private static let key = "privacy"
    private struct Config: Codable {
        var appLock = false
        var shield = true
        var clipboardLocalOnly = true
        var clipboardExpiry: Double = 0
        var clipboardAutoDecrypt: Bool? = true
        var lengthPadding: Bool? = false
    }

    private static let cache = ConfigCache<Config>()

    static func invalidateCache() { cache.invalidate() }

    private static func config() -> Config {
        cache.get {
            guard let d = SharedStore.read(key), let c = try? JSONDecoder().decode(Config.self, from: d) else { return Config() }
            return c
        }
    }

    static func save(appLock: Bool, shield: Bool, clipboardLocalOnly: Bool, clipboardExpiry: Double, clipboardAutoDecrypt: Bool, lengthPadding: Bool) {
        if let d = try? JSONEncoder().encode(Config(appLock: appLock, shield: shield,
                                                    clipboardLocalOnly: clipboardLocalOnly, clipboardExpiry: clipboardExpiry,
                                                    clipboardAutoDecrypt: clipboardAutoDecrypt, lengthPadding: lengthPadding)) {
            SharedStore.write(key, d)
        }
        cache.invalidate()
    }

    static func clipboardOptions() -> (localOnly: Bool, expiry: Double) {
        let c = config()
        return (c.clipboardLocalOnly, c.clipboardExpiry)
    }

    static func coverState() -> (shield: Bool, appLock: Bool) {
        let c = config()
        return (c.shield, c.appLock)
    }

    static var appLock: Bool { config().appLock }
    static var shield: Bool { config().shield }
    static var clipboardLocalOnly: Bool { config().clipboardLocalOnly }
    static var clipboardExpiry: Double { config().clipboardExpiry }
    static var clipboardAutoDecrypt: Bool { config().clipboardAutoDecrypt ?? true }
    static var lengthPadding: Bool { config().lengthPadding ?? false }
}

enum InterfaceConfig {
    static let supportedLanguages = ["en", "ru", "de", "zh-Hans"]

    private static let key = "interface"
    private struct Config: Codable {
        var theme = "auto"
        var language = "auto"
        var hiddenTabs: [String] = []
    }

    private static let cache = ConfigCache<Config>()

    static func invalidateCache() { cache.invalidate() }

    private static func config() -> Config {
        cache.get {
            guard let d = SharedStore.read(key), let c = try? JSONDecoder().decode(Config.self, from: d) else { return Config() }
            return c
        }
    }

    private static func save(_ c: Config) {
        if let d = try? JSONEncoder().encode(c) { SharedStore.write(key, d) }
        cache.invalidate()
    }

    static var theme: String { config().theme }
    static var language: String { config().language }
    static var hiddenTabs: [String] { config().hiddenTabs }

    static func setTheme(_ v: String) { var c = config(); c.theme = v; save(c) }
    static func setLanguage(_ v: String) { var c = config(); c.language = v; save(c) }
    static func setHiddenTabs(_ v: [String]) { var c = config(); c.hiddenTabs = v; save(c) }
}

enum KeyboardConfig {
    enum FieldSize: String, CaseIterable, Sendable {
        case small, medium, large

        var height: CGFloat {
            switch self {
            case .small: return 50
            case .medium: return 86
            case .large: return 122
            }
        }

        static func resolve(_ raw: String?) -> FieldSize {
            guard let raw, let size = FieldSize(rawValue: raw) else { return .small }
            return size
        }
    }

    private static let key = "kbconfig"
    private struct Config: Codable {
        var haptics = true
        var compose = false
        var sounds = true
        var autoDecrypt: Bool? = true
        var suggestions: Bool? = true
        var emoji: Bool? = true
        var autocorrect: Bool? = true
        var composeToggle: Bool? = true
        var shield: Bool? = true
        var langs: [String]? = nil
        var fieldSize: String? = nil
    }

    private static let cache = ConfigCache<Config>()

    static func invalidateCache() { cache.invalidate() }

    private static func config() -> Config {
        cache.get {
            guard let d = SharedStore.read(key), let c = try? JSONDecoder().decode(Config.self, from: d) else { return Config() }
            return c
        }
    }

    static func save(haptics: Bool, compose: Bool, sounds: Bool, autoDecrypt: Bool, suggestions: Bool,
                     emoji: Bool, autocorrect: Bool, composeToggle: Bool, shield: Bool,
                     languages: [String]?, fieldSize: FieldSize) {
        if let d = try? JSONEncoder().encode(Config(haptics: haptics, compose: compose, sounds: sounds,
                                                    autoDecrypt: autoDecrypt, suggestions: suggestions,
                                                    emoji: emoji, autocorrect: autocorrect,
                                                    composeToggle: composeToggle, shield: shield,
                                                    langs: languages.map(cleaned),
                                                    fieldSize: fieldSize.rawValue)) {
            SharedStore.write(key, d)
        }
        cache.invalidate()
    }

    static func setCompose(_ value: Bool) {
        var c = config()
        c.compose = value
        if let d = try? JSONEncoder().encode(c) { SharedStore.write(key, d) }
        cache.invalidate()
    }

    struct Snapshot: Sendable {
        var haptics: Bool
        var compose: Bool
        var composeToggle: Bool
        var shield: Bool
        var sounds: Bool
        var autoDecrypt: Bool
        var suggestions: Bool
        var autocorrect: Bool
        var emoji: Bool
        var languages: [String]
        var fieldSize: FieldSize
    }

    static func snapshot() -> Snapshot {
        let c = config()
        let langs = c.langs.map(cleaned) ?? []
        return Snapshot(haptics: c.haptics,
                        compose: c.compose,
                        composeToggle: c.composeToggle ?? true,
                        shield: c.shield ?? true,
                        sounds: c.sounds,
                        autoDecrypt: c.autoDecrypt ?? true,
                        suggestions: c.suggestions ?? true,
                        autocorrect: c.autocorrect ?? true,
                        emoji: c.emoji ?? true,
                        languages: langs.isEmpty ? defaultLanguages : langs,
                        fieldSize: FieldSize.resolve(c.fieldSize))
    }

    static var haptics: Bool { config().haptics }
    static var compose: Bool { config().compose }
    static var sounds: Bool { config().sounds }
    static var autoDecrypt: Bool { config().autoDecrypt ?? true }
    static var suggestions: Bool { config().suggestions ?? true }
    static var emoji: Bool { config().emoji ?? true }
    static var autocorrect: Bool { config().autocorrect ?? true }
    static var composeToggle: Bool { config().composeToggle ?? true }
    static var shield: Bool { config().shield ?? true }
    static var fieldSize: FieldSize { FieldSize.resolve(config().fieldSize) }
    static var languages: [String] { storedLanguages ?? defaultLanguages }

    static var storedLanguages: [String]? {
        guard let raw = config().langs else { return nil }
        let langs = cleaned(raw)
        return langs.isEmpty ? nil : langs
    }

    private static let nonLatinLanguages: Set<String> = ["ru", "zh"]

    static var defaultLanguages: [String] {
        let sys = systemLanguage
        guard nonLatinLanguages.contains(sys) else { return [sys] }
        return ["en", sys]
    }

    static var systemLanguage: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        if code.hasPrefix("ru") { return "ru" }
        if code.hasPrefix("de") { return "de" }
        if code.hasPrefix("zh") { return "zh" }
        return "en"
    }

    static let supported = ["en", "ru", "de", "zh"]

    private static func cleaned(_ raw: [String]) -> [String] { supported.filter(raw.contains) }
}
