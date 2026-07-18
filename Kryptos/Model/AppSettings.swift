import SwiftUI
import CipherCore

@MainActor
final class AppSettings: ObservableObject {
    enum LanguageChoice: String, CaseIterable, Identifiable {
        case auto, english, russian
        var id: String { rawValue }
        var title: LocalizedStringKey {
            switch self {
            case .auto: return "Automatic (system language)"
            case .english: return "English"
            case .russian: return "Russian"
            }
        }
    }

    @Published var chatStegoEnabled: Bool {
        didSet { persist() }
    }

    @Published var chatStegoLanguage: LanguageChoice {
        didSet { persist() }
    }

    @Published var chatStegoSmart: Bool {
        didSet { persist() }
    }

    @Published var keyboardHaptics: Bool {
        didSet { persistKeyboard() }
    }

    @Published var keyboardCompose: Bool {
        didSet { persistKeyboard() }
    }

    @Published var keyboardSounds: Bool {
        didSet { persistKeyboard() }
    }

    @Published var keyboardAutoDecrypt: Bool {
        didSet { persistKeyboard() }
    }

    @Published var keyboardSuggestions: Bool {
        didSet { persistKeyboard() }
    }

    @Published var keyboardEmoji: Bool {
        didSet { persistKeyboard() }
    }

    @Published var keyboardAutocorrect: Bool {
        didSet { persistKeyboard() }
    }

    @Published var keyboardLanguages: [String] {
        didSet {
            langsExplicit = true
            let langs = KeyboardConfig.supported.filter(keyboardLanguages.contains)
            if langs.isEmpty { keyboardLanguages = oldValue.isEmpty ? ["en"] : oldValue; return }
            if langs != keyboardLanguages { keyboardLanguages = langs; return }
            persistKeyboard()
        }
    }

    private var langsExplicit = false

    @Published var appLock: Bool {
        didSet { persistPrivacy() }
    }

    @Published var privacyShield: Bool {
        didSet { persistPrivacy() }
    }

    @Published var clipboardLocalOnly: Bool {
        didSet { persistPrivacy() }
    }

    @Published var clipboardExpiry: Double {
        didSet { persistPrivacy() }
    }

    @Published var clipboardAutoDecrypt: Bool {
        didSet { persistPrivacy() }
    }

    @Published var lengthPadding: Bool {
        didSet { persistPrivacy() }
    }

    init() {
        chatStegoEnabled = ChatStego.isEnabled
        chatStegoLanguage = LanguageChoice(rawValue: ChatStego.languageRaw) ?? .auto
        chatStegoSmart = ChatStego.isSmart
        keyboardHaptics = KeyboardConfig.haptics
        keyboardCompose = KeyboardConfig.compose
        keyboardSounds = KeyboardConfig.sounds
        keyboardAutoDecrypt = KeyboardConfig.autoDecrypt
        keyboardSuggestions = KeyboardConfig.suggestions
        keyboardEmoji = KeyboardConfig.emoji
        keyboardAutocorrect = KeyboardConfig.autocorrect
        keyboardLanguages = KeyboardConfig.languages
        langsExplicit = KeyboardConfig.storedLanguages != nil
        appLock = PrivacyConfig.appLock
        privacyShield = PrivacyConfig.shield
        clipboardLocalOnly = PrivacyConfig.clipboardLocalOnly
        clipboardExpiry = PrivacyConfig.clipboardExpiry
        clipboardAutoDecrypt = PrivacyConfig.clipboardAutoDecrypt
        lengthPadding = PrivacyConfig.lengthPadding
    }

    private func persist() { ChatStego.save(enabled: chatStegoEnabled, language: chatStegoLanguage.rawValue, smart: chatStegoSmart) }
    private func persistKeyboard() {
        KeyboardConfig.save(haptics: keyboardHaptics, compose: keyboardCompose,
                            sounds: keyboardSounds, autoDecrypt: keyboardAutoDecrypt,
                            suggestions: keyboardSuggestions, emoji: keyboardEmoji,
                            autocorrect: keyboardAutocorrect, languages: langsExplicit ? keyboardLanguages : nil)
    }
    private func persistPrivacy() {
        PrivacyConfig.save(appLock: appLock, shield: privacyShield,
                           clipboardLocalOnly: clipboardLocalOnly, clipboardExpiry: clipboardExpiry,
                           clipboardAutoDecrypt: clipboardAutoDecrypt, lengthPadding: lengthPadding)
    }

    var effectiveLanguage: StegoLanguage {
        switch chatStegoLanguage {
        case .english: return .english
        case .russian: return .russian
        case .auto: return .forSystem()
        }
    }
}
