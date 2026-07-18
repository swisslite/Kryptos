import Foundation
import Security
import UIKit
import CipherCore

enum RemoteClipboard {
    private static let marker = "com.apple.is-remote-clipboard"
    static var isRemote: Bool { UIPasteboard.general.contains(pasteboardTypes: [marker]) }
}

enum AppGroup {
    static let fallbackIdentifier = "group.com.kryptos.app"

    static let identifier: String = {
        let fm = FileManager.default
        var seen = Set<String>()
        let candidates = allCandidateGroups().filter { seen.insert($0).inserted }
        let working = candidates.filter { fm.containerURL(forSecurityApplicationGroupIdentifier: $0) != nil }
        return working.sorted().first ?? fallbackIdentifier
    }()

    private static func allCandidateGroups() -> [String] {
        var groups = provisionedGroups()
        if let team = teamIdentifierPrefix() {
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

    private static func teamIdentifierPrefix() -> String? {
        let account = "kryptos.teamid.probe"
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrAccount as String: account,
                                   kSecAttrService as String: account]
        var query = base
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        var status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            SecItemAdd(base as CFDictionary, nil)
            status = SecItemCopyMatching(query as CFDictionary, &result)
        }
        guard status == errSecSuccess,
              let attrs = result as? [String: Any],
              let group = attrs[kSecAttrAccessGroup as String] as? String,
              let team = group.split(separator: ".").first else { return nil }
        return String(team)
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
    private struct Config: Codable { var enabled = false; var lang = "auto"; var smart: Bool? = false }

    private static func config() -> Config {
        guard let d = SharedStore.read(key), let c = try? JSONDecoder().decode(Config.self, from: d) else { return Config() }
        return c
    }

    static func save(enabled: Bool, language: String, smart: Bool) {
        if let d = try? JSONEncoder().encode(Config(enabled: enabled, lang: language, smart: smart)) { SharedStore.write(key, d) }
    }

    static var isEnabled: Bool { config().enabled }
    static var languageRaw: String { config().lang }
    static var isSmart: Bool { config().smart ?? false }

    static func resolvedLanguage() -> StegoLanguage? {
        let c = config()
        guard c.enabled else { return nil }
        switch c.lang {
        case "english": return .english
        case "russian": return .russian
        default: return .forSystem()
        }
    }

    static func resolvedSmart() -> Bool {
        let c = config()
        return c.enabled && (c.smart ?? false)
    }
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

    private static func config() -> Config {
        guard let d = SharedStore.read(key), let c = try? JSONDecoder().decode(Config.self, from: d) else { return Config() }
        return c
    }

    static func save(appLock: Bool, shield: Bool, clipboardLocalOnly: Bool, clipboardExpiry: Double, clipboardAutoDecrypt: Bool, lengthPadding: Bool) {
        if let d = try? JSONEncoder().encode(Config(appLock: appLock, shield: shield,
                                                    clipboardLocalOnly: clipboardLocalOnly, clipboardExpiry: clipboardExpiry,
                                                    clipboardAutoDecrypt: clipboardAutoDecrypt, lengthPadding: lengthPadding)) {
            SharedStore.write(key, d)
        }
    }

    static var appLock: Bool { config().appLock }
    static var shield: Bool { config().shield }
    static var clipboardLocalOnly: Bool { config().clipboardLocalOnly }
    static var clipboardExpiry: Double { config().clipboardExpiry }
    static var clipboardAutoDecrypt: Bool { config().clipboardAutoDecrypt ?? true }
    static var lengthPadding: Bool { config().lengthPadding ?? false }
}

enum KeyboardConfig {
    private static let key = "kbconfig"
    private struct Config: Codable {
        var haptics = true
        var compose = false
        var sounds = true
        var autoDecrypt: Bool? = true
        var suggestions: Bool? = true
        var emoji: Bool? = true
        var autocorrect: Bool? = true
        var langs: [String]? = nil
    }

    private static func config() -> Config {
        guard let d = SharedStore.read(key), let c = try? JSONDecoder().decode(Config.self, from: d) else { return Config() }
        return c
    }

    static func save(haptics: Bool, compose: Bool, sounds: Bool, autoDecrypt: Bool, suggestions: Bool,
                     emoji: Bool, autocorrect: Bool, languages: [String]?) {
        if let d = try? JSONEncoder().encode(Config(haptics: haptics, compose: compose, sounds: sounds,
                                                    autoDecrypt: autoDecrypt, suggestions: suggestions,
                                                    emoji: emoji, autocorrect: autocorrect,
                                                    langs: languages.map(cleaned))) {
            SharedStore.write(key, d)
        }
    }

    static var haptics: Bool { config().haptics }
    static var compose: Bool { config().compose }
    static var sounds: Bool { config().sounds }
    static var autoDecrypt: Bool { config().autoDecrypt ?? true }
    static var suggestions: Bool { config().suggestions ?? true }
    static var emoji: Bool { config().emoji ?? true }
    static var autocorrect: Bool { config().autocorrect ?? true }
    static var languages: [String] { storedLanguages ?? (systemPrefersRussian ? ["en", "ru"] : ["en"]) }

    static var storedLanguages: [String]? {
        guard let raw = config().langs else { return nil }
        let langs = cleaned(raw)
        return langs.isEmpty ? nil : langs
    }

    static var systemPrefersRussian: Bool { StegoLanguage.forSystem() == .russian }

    static let supported = ["en", "ru"]

    private static func cleaned(_ raw: [String]) -> [String] { supported.filter(raw.contains) }
}
