import SwiftUI
import CipherCore

enum AppTab: String, CaseIterable, Identifiable {
    case chats, pgp, quick, stego, settings

    var id: String { rawValue }
    var canHide: Bool { self != .settings }

    var title: LocalizedStringKey {
        switch self {
        case .chats: return "Chats"
        case .pgp: return "PGP"
        case .quick: return "Password"
        case .stego: return "Photo"
        case .settings: return "Settings"
        }
    }

    var icon: String {
        switch self {
        case .chats: return "bubble.left.and.bubble.right.fill"
        case .pgp: return "envelope.fill"
        case .quick: return "lock.fill"
        case .stego: return "photo.on.rectangle.angled"
        case .settings: return "gearshape.fill"
        }
    }
}

extension StegoMode {
    var title: LocalizedStringKey {
        switch self {
        case .words: return "Simple words"
        case .smart: return "Smart sentences"
        case .letters: return "Random letters"
        }
    }
}

@MainActor
final class AppSettings: ObservableObject {
    enum LanguageChoice: String, CaseIterable, Identifiable {
        case auto, english, russian, german
        var id: String { rawValue }
        var title: LocalizedStringKey {
            switch self {
            case .auto: return "Automatic (system language)"
            case .english: return "English"
            case .russian: return "Russian"
            case .german: return "German"
            }
        }
    }

    @Published var chatStegoEnabled: Bool {
        didSet { persist() }
    }

    @Published var chatStegoLanguage: LanguageChoice {
        didSet { persist() }
    }

    @Published var chatStegoMode: StegoMode {
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

    @Published var keyboardComposeToggle: Bool {
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

    @Published var uiTheme: String {
        didSet {
            guard !loading else { return }
            InterfaceConfig.setTheme(uiTheme)
        }
    }

    @Published var uiLanguage: String {
        didSet {
            guard !loading else { return }
            InterfaceConfig.setLanguage(uiLanguage)
            AppLanguage.apply(uiLanguage)
        }
    }

    @Published var hiddenTabs: Set<AppTab> {
        didSet {
            guard !loading else { return }
            InterfaceConfig.setHiddenTabs(hiddenTabs.map(\.rawValue))
        }
    }

    var visibleTabs: [AppTab] {
        AppTab.allCases.filter { !$0.canHide || !hiddenTabs.contains($0) }
    }

    var hideableOn: Int {
        AppTab.allCases.filter { $0.canHide && !hiddenTabs.contains($0) }.count
    }

    var colorScheme: ColorScheme? {
        switch uiTheme {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    func setTab(_ tab: AppTab, visible: Bool) {
        guard tab.canHide else { return }
        if visible {
            hiddenTabs.remove(tab)
        } else {
            guard hideableOn > 1 else { return }
            hiddenTabs.insert(tab)
        }
    }

    private var loading = false

    func reloadAfterWipe() {
        loading = true
        chatStegoEnabled = ChatStego.isEnabled
        chatStegoLanguage = LanguageChoice(rawValue: ChatStego.languageRaw) ?? .auto
        chatStegoMode = ChatStego.storedMode
        keyboardHaptics = KeyboardConfig.haptics
        keyboardCompose = KeyboardConfig.compose
        keyboardSounds = KeyboardConfig.sounds
        keyboardAutoDecrypt = KeyboardConfig.autoDecrypt
        keyboardSuggestions = KeyboardConfig.suggestions
        keyboardEmoji = KeyboardConfig.emoji
        keyboardAutocorrect = KeyboardConfig.autocorrect
        keyboardComposeToggle = KeyboardConfig.composeToggle
        keyboardLanguages = KeyboardConfig.languages
        appLock = PrivacyConfig.appLock
        privacyShield = PrivacyConfig.shield
        clipboardLocalOnly = PrivacyConfig.clipboardLocalOnly
        clipboardExpiry = PrivacyConfig.clipboardExpiry
        clipboardAutoDecrypt = PrivacyConfig.clipboardAutoDecrypt
        lengthPadding = PrivacyConfig.lengthPadding
        uiTheme = InterfaceConfig.theme
        uiLanguage = InterfaceConfig.language
        hiddenTabs = Set(InterfaceConfig.hiddenTabs.compactMap(AppTab.init(rawValue:)).filter(\.canHide))
        langsExplicit = KeyboardConfig.storedLanguages != nil
        AppLanguage.apply(InterfaceConfig.language)
        loading = false
    }

    init() {
        chatStegoEnabled = ChatStego.isEnabled
        chatStegoLanguage = LanguageChoice(rawValue: ChatStego.languageRaw) ?? .auto
        chatStegoMode = ChatStego.storedMode
        keyboardHaptics = KeyboardConfig.haptics
        keyboardCompose = KeyboardConfig.compose
        keyboardSounds = KeyboardConfig.sounds
        keyboardAutoDecrypt = KeyboardConfig.autoDecrypt
        keyboardSuggestions = KeyboardConfig.suggestions
        keyboardEmoji = KeyboardConfig.emoji
        keyboardAutocorrect = KeyboardConfig.autocorrect
        keyboardComposeToggle = KeyboardConfig.composeToggle
        keyboardLanguages = KeyboardConfig.languages
        langsExplicit = KeyboardConfig.storedLanguages != nil
        appLock = PrivacyConfig.appLock
        privacyShield = PrivacyConfig.shield
        clipboardLocalOnly = PrivacyConfig.clipboardLocalOnly
        clipboardExpiry = PrivacyConfig.clipboardExpiry
        clipboardAutoDecrypt = PrivacyConfig.clipboardAutoDecrypt
        lengthPadding = PrivacyConfig.lengthPadding
        uiTheme = InterfaceConfig.theme
        uiLanguage = InterfaceConfig.language
        hiddenTabs = Set(InterfaceConfig.hiddenTabs.compactMap(AppTab.init(rawValue:)).filter(\.canHide))
    }

    private func persist() {
        guard !loading else { return }
        ChatStego.save(enabled: chatStegoEnabled, language: chatStegoLanguage.rawValue, mode: chatStegoMode)
    }
    private func persistKeyboard() {
        guard !loading else { return }
        KeyboardConfig.save(haptics: keyboardHaptics, compose: keyboardCompose,
                            sounds: keyboardSounds, autoDecrypt: keyboardAutoDecrypt,
                            suggestions: keyboardSuggestions, emoji: keyboardEmoji,
                            autocorrect: keyboardAutocorrect, composeToggle: keyboardComposeToggle,
                            languages: langsExplicit ? keyboardLanguages : nil)
    }
    private func persistPrivacy() {
        guard !loading else { return }
        PrivacyConfig.save(appLock: appLock, shield: privacyShield,
                           clipboardLocalOnly: clipboardLocalOnly, clipboardExpiry: clipboardExpiry,
                           clipboardAutoDecrypt: clipboardAutoDecrypt, lengthPadding: lengthPadding)
    }

    var effectiveLanguage: StegoLanguage {
        switch chatStegoLanguage {
        case .english: return .english
        case .russian: return .russian
        case .german: return .german
        case .auto: return .forSystem()
        }
    }
}
