import UIKit
import SwiftUI
import AudioToolbox
import CryptoKit
import LocalAuthentication
import CipherCore

let kryptosTextDidChange = Notification.Name("kryptos.textDidChange")
let kryptosInputSessionDidStart = Notification.Name("kryptos.inputSessionDidStart")
let kryptosInputSessionDidEnd = Notification.Name("kryptos.inputSessionDidEnd")

enum KeyboardMetrics {
    static let cryptoBarTop: CGFloat = 4
    static let cryptoBarHeight: CGFloat = 46
    static let barGap: CGFloat = 6
    static let suggestionHeight: CGFloat = 34
    private static let composeChrome: CGFloat = 48
    private static let chromeSlack: CGFloat = 2

    static func fieldHeight(_ fieldSize: KeyboardConfig.FieldSize, compact: Bool) -> CGFloat {
        guard compact else { return fieldSize.height }
        return min(fieldSize.height, KeyboardConfig.FieldSize.small.height)
    }

    static func composeHeight(_ fieldSize: KeyboardConfig.FieldSize, compact: Bool) -> CGFloat {
        fieldHeight(fieldSize, compact: compact) + composeChrome
    }

    static func panelHeight(compose: Bool, suggestions: Bool, fieldSize: KeyboardConfig.FieldSize,
                            compact: Bool) -> CGFloat {
        cryptoBarTop + cryptoBarHeight + barGap + chromeSlack + KB.rowsHeight(compact: compact)
            + (compose ? composeHeight(fieldSize, compact: compact) : 0)
            + (suggestions ? suggestionHeight : 0)
    }

    static func keyAreaTop(compose: Bool, suggestions: Bool, fieldSize: KeyboardConfig.FieldSize,
                           compact: Bool) -> CGFloat {
        var top = cryptoBarTop + cryptoBarHeight + barGap
        if compose { top += composeHeight(fieldSize, compact: compact) }
        if suggestions { top += suggestionHeight }
        return top
    }
}

@MainActor
final class KeyboardSizing: ObservableObject {
    @Published var fieldSize: KeyboardConfig.FieldSize = .small
}

final class KeyboardViewController: UIInputViewController {
    private var heightConstraint: NSLayoutConstraint?
    private var suggestionsEnabled = true
    private var composeEnabled = false
    private let sizing = KeyboardSizing()

    private static func applyAppLanguage() {
        let code = InterfaceConfig.language
        let defaults = UserDefaults.standard
        if InterfaceConfig.supportedLanguages.contains(code) {
            if (defaults.array(forKey: "AppleLanguages") as? [String])?.first != code {
                defaults.set([code], forKey: "AppleLanguages")
            }
        } else if defaults.object(forKey: "AppleLanguages") != nil {
            defaults.removeObject(forKey: "AppleLanguages")
        }
    }

    static func enabledLanguages(_ config: KeyboardConfig.Snapshot) -> [String] {
        config.languages.isEmpty ? ["en"] : config.languages
    }

    static func activeLanguage(_ config: KeyboardConfig.Snapshot) -> String {
        let enabled = enabledLanguages(config)
        let saved = UserDefaults.standard.string(forKey: "kb.lang") ?? KeyboardConfig.systemLanguage
        return enabled.contains(saved) ? saved : enabled[0]
    }

    static func warmSuggestions(_ config: KeyboardConfig.Snapshot, language: String) {
        SuggestionEngine.shared.warmUp(languages: [language],
                                       typingAids: config.suggestions || config.autocorrect)
        if language == "zh" { PinyinEngine.shared.warmUp() }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        Self.applyAppLanguage()
        view.backgroundColor = .clear
        let config = KeyboardConfig.snapshot()
        adoptConfig(config, animated: false)
        Self.warmSuggestions(config, language: Self.activeLanguage(config))

        let backdrop = UIInputView(frame: .zero, inputViewStyle: .keyboard)
        backdrop.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(backdrop)

        let panel = KryptosKeyboardView(
            config: config,
            sizing: sizing,
            proxy: textDocumentProxy,
            hasFullAccess: hasFullAccess,
            nextKeyboard: { [weak self] in self?.advanceToNextInputMode() },
            playClick: { AudioServicesPlaySystemSound(1104) },
            composeHeightChanged: { [weak self] compose in
                guard let self else { return }
                self.composeEnabled = compose
                self.applyPanelHeight(animated: true)
            },
            configChanged: { [weak self] config in
                self?.adoptConfig(config, animated: true)
            }
        )
        let host = UIHostingController(rootView: panel)
        host.view.backgroundColor = .clear
        view.semanticContentAttribute = .forceLeftToRight
        host.view.semanticContentAttribute = .forceLeftToRight
        addChild(host)
        view.addSubview(host.view)
        host.view.translatesAutoresizingMaskIntoConstraints = false

        let height = KeyboardMetrics.panelHeight(compose: composeEnabled, suggestions: suggestionsEnabled,
                                                 fieldSize: sizing.fieldSize, compact: compactLayout)
        let heightAnchor = view.heightAnchor.constraint(equalToConstant: height)
        heightConstraint = heightAnchor
        NSLayoutConstraint.activate([
            backdrop.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            backdrop.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            backdrop.topAnchor.constraint(equalTo: view.topAnchor),
            backdrop.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            heightAnchor
        ])
        host.didMove(toParent: self)
        registerForTraitChanges([UITraitVerticalSizeClass.self]) { (self: Self, _) in
            self.applyPanelHeight(animated: false)
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        NotificationCenter.default.post(name: kryptosInputSessionDidStart, object: nil)
        let size = KeyboardConfig.fieldSize
        guard size != sizing.fieldSize else { return }
        sizing.fieldSize = size
        applyPanelHeight(animated: false)
    }

    private func adoptConfig(_ config: KeyboardConfig.Snapshot, animated: Bool) {
        suggestionsEnabled = config.suggestions || config.languages.contains("zh")
        composeEnabled = config.compose
        sizing.fieldSize = config.fieldSize
        applyPanelHeight(animated: animated)
    }

    private var compactLayout: Bool { traitCollection.verticalSizeClass == .compact }

    private func applyPanelHeight(animated: Bool) {
        guard let constraint = heightConstraint else { return }
        let target = KeyboardMetrics.panelHeight(compose: composeEnabled, suggestions: suggestionsEnabled,
                                                 fieldSize: sizing.fieldSize, compact: compactLayout)
        guard constraint.constant != target else { return }
        constraint.constant = target
        guard animated else { return }
        UIView.animate(withDuration: 0.18) { self.view.superview?.layoutIfNeeded() }
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        NotificationCenter.default.post(name: kryptosTextDidChange, object: nil)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        NotificationCenter.default.post(name: kryptosInputSessionDidEnd, object: nil)
        _ = TypingSession.sync()
        SuggestionEngine.shared.persist()
        PinyinEngine.shared.persist()
    }
}

extension KeyboardViewController: UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

enum KeyKind { case normal, special, accent }

enum KB {
    static let keyH: CGFloat = 42
    static let glowHold: CFTimeInterval = 0.07
    static let glowFade: CFTimeInterval = 0.16
    static let gap: CGFloat = 6
    static let rowGap: CGFloat = 12
    static let compactKeyH: CGFloat = 32
    static let compactRowGap: CGFloat = 8

    static func keyHeight(compact: Bool) -> CGFloat { compact ? compactKeyH : keyH }
    static func rowSpacing(compact: Bool) -> CGFloat { compact ? compactRowGap : rowGap }
    static func rowH(compact: Bool) -> CGFloat { keyHeight(compact: compact) + rowSpacing(compact: compact) }
    static func rowsHeight(compact: Bool) -> CGFloat { rowH(compact: compact) * 4 }

    static let accent = dyn(dark: UIColor(red: 0.46, green: 0.55, blue: 1, alpha: 1),
                            light: UIColor(red: 0.22, green: 0.30, blue: 0.80, alpha: 1))
    static let keyText = dyn(dark: .white, light: UIColor(red: 0.06, green: 0.07, blue: 0.10, alpha: 1))
    static let textSecondary = dyn(dark: UIColor(white: 1, alpha: 0.55), light: UIColor(white: 0, alpha: 0.5))
    static let stroke = dyn(dark: UIColor(white: 1, alpha: 0.06), light: UIColor(white: 0, alpha: 0.05))
    static let fieldFill = dyn(dark: UIColor(white: 1, alpha: 0.10), light: UIColor(white: 0, alpha: 0.06))
    static let calloutFill = dyn(dark: UIColor(red: 0.21, green: 0.23, blue: 0.28, alpha: 1), light: .white)

    private static func dyn(dark: UIColor, light: UIColor) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }

    static let keyTextU = UIColor { $0.userInterfaceStyle == .dark ? .white
                                                                   : UIColor(red: 0.06, green: 0.07, blue: 0.10, alpha: 1) }
    static let textSecondaryU = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.55)
                                                                         : UIColor(white: 0, alpha: 0.5) }
    static let strokeU = UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 1, alpha: 0.06)
                                                                   : UIColor(white: 0, alpha: 0.05) }
    static let calloutFillU = UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.21, green: 0.23, blue: 0.28, alpha: 1)
                                                                       : .white }
    static let keyShadowU = UIColor(white: 0, alpha: 0.22)
    static let accentU = UIColor { $0.userInterfaceStyle == .dark
        ? UIColor(red: 0.46, green: 0.55, blue: 1, alpha: 1)
        : UIColor(red: 0.22, green: 0.30, blue: 0.80, alpha: 1) }

    static func keyColorU(_ k: KeyKind) -> UIColor {
        UIColor { t in
            let dark = t.userInterfaceStyle == .dark
            switch k {
            case .accent:
                return dark ? UIColor(red: 0.46, green: 0.55, blue: 1, alpha: 1)
                            : UIColor(red: 0.22, green: 0.30, blue: 0.80, alpha: 1)
            case .special:
                return dark ? UIColor(white: 1, alpha: 0.10) : UIColor(white: 0, alpha: 0.12)
            case .normal:
                return dark ? UIColor(white: 1, alpha: 0.17) : .white
            }
        }
    }

    static func keyPressOverlayU(_ k: KeyKind) -> UIColor {
        UIColor { t in
            let dark = t.userInterfaceStyle == .dark
            switch k {
            case .accent:
                return UIColor(white: 1, alpha: 0.22)
            case .special:
                return dark ? UIColor(white: 1, alpha: 0.12) : UIColor(white: 1, alpha: 1)
            case .normal:
                return dark ? UIColor(white: 1, alpha: 0.16) : UIColor(white: 0, alpha: 0.18)
            }
        }
    }
}

private struct DecryptedMessage { let name: String; let text: String; let date: Date }

private struct RevealedText { let name: String; let text: String }

private enum KeyLayout { case english, russian, german, chinese, persian, numbers, symbols }
private enum ShiftState { case off, on, locked }
private enum Special: Hashable { case shift, backspace, space, ret, digits, letters, symbols, lang, emoji, zwnj }
private enum Cap: Hashable { case ch(String); case sp(Special) }

private struct KryptosKeyboardView: View {
    let config: KeyboardConfig.Snapshot
    @ObservedObject var sizing: KeyboardSizing
    let proxy: UITextDocumentProxy
    let hasFullAccess: Bool
    let nextKeyboard: () -> Void
    let playClick: () -> Void
    let composeHeightChanged: (Bool) -> Void
    let configChanged: (KeyboardConfig.Snapshot) -> Void

    @State private var profiles: [Profile] = []
    @State private var store: SharedSignalStore?
    @State private var loaded = false
    @State private var storageRetries = 0
    @State private var selected: Contact?
    @State private var status: String?
    @State private var isError = false
    @State private var statusGen = 0

    @State private var layout: KeyLayout = .english
    @State private var letterLayout: KeyLayout = .english
    @State private var enabledLangs: [String] = ["en"]
    @State private var shift: ShiftState = .off
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var lastShiftTap = Date.distantPast
    @State private var lastSpaceTap = Date.distantPast
    @State private var autoShifted = false

    @State private var haptics = true
    @State private var sounds = true
    @State private var compose = false
    @State private var composeToggleEnabled = true
    @State private var shieldVisible = true
    @State private var autoDecrypt = true
    @State private var suggestionsOn = true
    @State private var autocorrectOn = true
    @State private var emojiOn = true

    @State private var pinyin = ""
    @State private var candidates: [PinyinCandidate] = []
    @State private var suggestions: [String] = []
    @State private var pendingFix: String?
    @State private var pendingFixTyped: String?
    @State private var suggestionsStamp: String?
    @State private var lastAutoFix: AutoFix?

    private struct AutoFix {
        let original: String
        let corrected: String
        let separator: String
        let at: Date
    }
    @State private var showEmoji = false
    @State private var emojiCategory = 0
    @State private var secureField = false
    @State private var noAidsField = false
    @State private var hostIsKryptos = false
    @State private var returnIcon = "return"
    @State private var clipHint = false
    @State private var lastClipCount =
        UserDefaults.standard.object(forKey: "kb.clipGeneration") as? Int ?? Int.min
    @State private var decryptCache: [String: DecryptedMessage] = [:]
    @State private var purgeToken: String?
    @State private var purgeTokenKnown = false
    @State private var lockToken: String?
    @State private var lockTokenKnown = false
    private let clipTimer = Timer.publish(every: 0.6, on: .main, in: .common).autoconnect()
    @State private var draft = ""
    @State private var caret = 0
    @State private var revealed: RevealedText?
    @State private var feedback = UIImpactFeedbackGenerator(style: .soft)
    @State private var cryptoUnlockedAt: Date?
    @State private var authInFlight = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: 6) {
            cryptoBar
            if stripOn && !showEmoji {
                if isChinese { pinyinBar } else { suggestionBar }
            }
            if showEmoji { emojiPanel } else { keyboard }
        }
        .padding(.horizontal, 3)
        .padding(.top, 4)
        .padding(.bottom, 2)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .overlay(alignment: .top) { if let status, revealed == nil { statusToast(status) } }
        .overlay { if let revealed { resultPanel(revealed) } }
        .onAppear { loadOnce(config) }
        .onReceive(clipTimer) { _ in scanClipboard() }
        .onReceive(NotificationCenter.default.publisher(for: kryptosTextDidChange)) { _ in hostTextChanged() }
        .onReceive(NotificationCenter.default.publisher(for: kryptosInputSessionDidStart)) { _ in sessionStarted() }
        .onReceive(NotificationCenter.default.publisher(for: kryptosInputSessionDidEnd)) { _ in sessionEnded() }
    }

    private var isChinese: Bool { letterLayout == .chinese }

    private var isPersian: Bool { letterLayout == .persian }

    private var stripOn: Bool { suggestionsOn || enabledLangs.contains("zh") }

    private var keyAreaTop: CGFloat {
        KeyboardMetrics.keyAreaTop(compose: compose, suggestions: stripOn && !showEmoji,
                                   fieldSize: sizing.fieldSize, compact: compactLayout)
    }

    private var compactLayout: Bool { verticalSizeClass == .compact }

    private var panelTotalHeight: CGFloat {
        KeyboardMetrics.panelHeight(compose: compose, suggestions: stripOn, fieldSize: sizing.fieldSize,
                                    compact: compactLayout)
    }

    private func resultPanel(_ reveal: RevealedText) -> some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        let dim = scheme == .dark ? 0.42 : 0.20
        let fade = min(0.85, max(0.12, keyAreaTop / panelTotalHeight))
        return ZStack {
            LinearGradient(
                stops: [
                    Gradient.Stop(color: .black.opacity(0), location: 0),
                    Gradient.Stop(color: .black.opacity(dim), location: fade),
                    Gradient.Stop(color: .black.opacity(dim), location: 1)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .contentShape(Rectangle())
            .onTapGesture { revealed = nil }

            VStack(spacing: 0) {
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 10) {
                        Label(String(localized: "Decrypted · \(reveal.name)"), systemImage: "lock.open.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(KB.accent)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                        Button { revealed = nil } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 20))
                                .foregroundStyle(KB.textSecondary)
                        }
                    }
                    ScrollView {
                        Text(reveal.text)
                            .font(.system(size: 16))
                            .lineSpacing(2)
                            .foregroundStyle(KB.keyText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 120)
                }
                .padding(16)
                .background {
                    shape
                        .fill(KB.calloutFill)
                        .overlay(shape.strokeBorder(KB.stroke, lineWidth: 0.5))
                        .compositingGroup()
                        .shadow(color: .black.opacity(0.28), radius: 16, y: 6)
                }
                .fixedSize(horizontal: false, vertical: true)
                .contentShape(shape)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 10)
        }
    }

    private var cryptoBar: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                if shieldVisible {
                    Image(systemName: "lock.shield.fill").foregroundStyle(KB.accent).font(.system(size: 15, weight: .semibold))
                }
                if composeToggleEnabled { composeToggleButton }
                if let store {
                    if !profiles.isEmpty { profileMenu(store) }
                    if store.contacts.isEmpty {
                        Text("No contacts in this profile").font(.system(size: 12)).foregroundStyle(KB.textSecondary).lineLimit(1)
                        Spacer(minLength: 0)
                    } else {
                        contactMenu(store)
                        Spacer(minLength: 0)
                        HStack(spacing: 12) {
                            iconButton("lock.open.fill", accent: false) { withCryptoGate { decrypt(store) } }
                                .overlay(alignment: .topTrailing) {
                                    if clipHint {
                                        Circle().fill(Color(red: 0.2, green: 0.72, blue: 0.45))
                                            .frame(width: 7, height: 7)
                                            .offset(x: -5, y: 5)
                                            .allowsHitTesting(false)
                                    }
                                }
                            iconButton("lock.fill", accent: true) { withCryptoGate { encrypt(store) } }
                        }
                    }
                } else {
                    Text(hint).font(.system(size: 12)).foregroundStyle(KB.textSecondary).lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
            }
            if compose { composeField }
        }
        .padding(.horizontal, 6).padding(.vertical, 2)
    }

    private func statusToast(_ text: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: isError ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
            Text(text).lineLimit(2).multilineTextAlignment(.leading)
        }
        .font(.system(size: 12, weight: .medium))
        .foregroundStyle(isError ? Color.red : KB.accent)
        .padding(.horizontal, 12).padding(.vertical, 7)
        .background(Capsule().fill(KB.calloutFill))
        .overlay(Capsule().strokeBorder(KB.stroke, lineWidth: 0.5))
        .shadow(color: .black.opacity(0.25), radius: 8, y: 2)
        .padding(.top, 52)
        .padding(.horizontal, 10)
        .allowsHitTesting(false)
    }

    private var hint: String {
        if !hasFullAccess { return String(localized: "To encrypt, enable “Full Access” in the keyboard settings.") }
        return String(localized: "Open the Kryptos app and add a contact.")
    }

    private func profileMenu(_ store: SharedSignalStore) -> some View {
        Menu {
            ForEach(profiles) { p in Button(p.name) { select(profile: p, remember: true) } }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "person.2.fill").font(.system(size: 11))
                Text(store.profile.name).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
            }
            .font(.system(size: 13, weight: .medium)).foregroundStyle(KB.accent)
            .padding(.horizontal, 9).padding(.vertical, 6)
            .background(Capsule().fill(KB.accent.opacity(0.14)))
        }
    }

    private func contactMenu(_ store: SharedSignalStore) -> some View {
        Menu {
            ForEach(store.contacts) { c in
                Button(c.displayName) {
                    selected = c
                    KeyboardSelection.rememberContact(c.fingerprint, profileID: store.profile.id)
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(selected?.displayName ?? String(localized: "Contact")).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
            }
            .font(.system(size: 14, weight: .medium)).foregroundStyle(KB.keyText)
        }
    }

    private var composeField: some View {
        VStack(alignment: .leading, spacing: 7) {
            ScrollViewReader { sp in
                ScrollView(.vertical, showsIndicators: true) {
                    Group {
                        if draft.isEmpty {
                            Text("Type or paste text — the messenger won't see it until it's encrypted")
                                .font(.system(size: 13)).foregroundStyle(KB.textSecondary)
                        } else {
                            let safe = max(0, min(caret, draft.count))
                            if safe == draft.count {
                                (Text(draft) + Text("▏").foregroundColor(KB.accent))
                                    .font(.system(size: 15)).foregroundStyle(KB.keyText)
                            } else {
                                Text(draft)
                                    .font(.system(size: 15)).foregroundStyle(KB.keyText)
                                    .frame(maxWidth: .infinity, alignment: .topLeading)
                                    .overlay(alignment: .topLeading) { DraftCaret(text: draft, offset: safe) }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    Color.clear.frame(height: 1).id("composeBottom")
                }
                .frame(height: KeyboardMetrics.fieldHeight(sizing.fieldSize, compact: compactLayout))
                .onChange(of: draft) { _, _ in
                    withAnimation(.easeOut(duration: 0.15)) { sp.scrollTo("composeBottom", anchor: .bottom) }
                }
            }
            HStack(spacing: 12) {
                Button { pasteIntoDraft() } label: {
                    Label("Paste", systemImage: "doc.on.clipboard")
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(KB.accent)
                }
                Spacer(minLength: 0)
                if !draft.isEmpty {
                    Button { clearDraft() } label: {
                        Label("Clear", systemImage: "xmark.circle")
                            .font(.system(size: 12, weight: .medium)).foregroundStyle(KB.textSecondary)
                    }
                }
            }
        }
        .padding(.horizontal, 11).padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(KB.fieldFill))
    }

    private func pasteIntoDraft() {
        guard let s = UIPasteboard.general.string, !s.isEmpty else {
            return flash(String(localized: "Clipboard is empty."), error: true)
        }
        insertIntoDraft(s)
        updateAutoShift()
        updateSuggestions()
        status = nil
    }

    private var composeToggleButton: some View {
        Button {
            let next = !compose
            compose = next
            KeyboardConfig.setCompose(next)
            composeHeightChanged(next)
            press()
        } label: {
            Image(systemName: "rectangle.and.pencil.and.ellipsis")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(compose ? Color.white : KB.accent)
                .frame(width: 34, height: 26)
                .background(Capsule().fill(compose ? KB.accent : KB.accent.opacity(0.16)))
                .contentShape(Capsule())
        }
        .accessibilityLabel(Text("Compose field"))
    }

    private func iconButton(_ icon: String, accent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 19, weight: .semibold))
                .foregroundStyle(accent ? Color.white : KB.accent)
                .frame(width: 58, height: 42)
                .background(RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(accent ? KB.accent : KB.accent.opacity(0.16)))
        }
    }

    private var keyboard: some View {
        KeyGridRepresentable(
            rows: currentRows(),
            shiftState: shift,
            languageCode: Self.code(for: letterLayout),
            langKeyLabel: nextLanguageLabel,
            returnIcon: returnIcon,
            spaceMovable: true,
            secureInput: secureField,
            compact: compactLayout,
            onPressFeedback: { press() },
            onChar: { insertChar($0) },
            onSpecial: { performSpecial($0) },
            onBackspaceFirst: { press(); backspaceDelete() },
            onBackspaceRepeat: { backspaceDelete() },
            onSpaceTap: { press(); spaceTapped() },
            onCaretMove: { moveCursor($0) },
            onCaretMoveVertical: { moveCursorVertical($0) },
            alternates: { alternates(for: $0) },
            onAlternate: { replaceTyped(with: $0) }
        )
        .frame(height: KB.rowsHeight(compact: compactLayout))
    }

    private static let fullWidth: [String: String] = [
        ",": "\u{FF0C}", ".": "\u{3002}", "?": "\u{FF1F}", "!": "\u{FF01}",
        ":": "\u{FF1A}", ";": "\u{FF1B}", "(": "\u{FF08}", ")": "\u{FF09}",
        "<": "\u{300A}", ">": "\u{300B}", "\\": "\u{3001}", "$": "\u{FFE5}",
        "~": "\u{FF5E}", "_": "\u{2014}\u{2014}", "^": "\u{2026}\u{2026}"
    ]

    private var pinyinBar: some View {
        HStack(spacing: 0) {
            if !pinyin.isEmpty {
                Text(pinyin)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(KB.accent)
                    .lineLimit(1)
                    .padding(.horizontal, 8)
                Rectangle().fill(KB.keyText.opacity(0.14)).frame(width: 1, height: 16)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(Array(candidates.enumerated()), id: \.offset) { index, candidate in
                        Button {
                            press()
                            commitCandidate(candidate)
                        } label: {
                            Text(candidate.text)
                                .font(.system(size: 18, weight: index == 0 ? .semibold : .regular))
                                .foregroundStyle(KB.keyText)
                                .padding(.horizontal, 11)
                                .frame(maxHeight: .infinity)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .frame(height: 28)
        .padding(.horizontal, 4)
    }

    private func refreshCandidates() {
        guard isChinese, !pinyin.isEmpty else {
            if !candidates.isEmpty { candidates = [] }
            return
        }
        candidates = PinyinEngine.shared.candidates(for: pinyin, limit: 24)
    }

    private func commitCandidate(_ candidate: PinyinCandidate) {
        type(candidate.text)
        if learningAllowed { PinyinEngine.shared.note(candidate.text) }
        let scalars = Array(pinyin.unicodeScalars)
        pinyin = candidate.consumed >= scalars.count
            ? ""
            : String(String.UnicodeScalarView(scalars[candidate.consumed...]))
        refreshCandidates()
        updateAutoShift()
    }

    private func clearPinyin() {
        guard !pinyin.isEmpty || !candidates.isEmpty else { return }
        pinyin = ""
        candidates = []
    }

    private var suggestionBar: some View {
        HStack(spacing: 0) {
            suggestionSlot(1)
            suggestionDivider(visible: suggestions.count >= 2)
            suggestionSlot(0)
            suggestionDivider(visible: suggestions.count >= 3)
            suggestionSlot(2)
        }
        .frame(height: 28)
        .padding(.horizontal, 4)
    }

    private func suggestionDivider(visible: Bool) -> some View {
        Rectangle()
            .fill(KB.keyText.opacity(visible ? 0.14 : 0))
            .frame(width: 1, height: 15)
    }

    private func suggestionSlot(_ i: Int) -> some View {
        let raw: String? = suggestions.indices.contains(i) ? suggestions[i] : nil
        let word: String? = (raw?.isEmpty == false) ? raw : nil
        let quoted = i == 1 && pendingFixTyped != nil && word == pendingFixTyped
        return Button {
            if let word { press(); applySuggestion(word) }
        } label: {
            Text(word.map { quoted ? "«\($0)»" : $0 } ?? " ")
                .font(.system(size: 15, weight: i == 0 ? .semibold : .regular))
                .foregroundStyle(i == 0 && pendingFix != nil ? KB.accent : KB.keyText)
                .lineLimit(1)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func isWordChar(_ c: Character) -> Bool { c.isLetter || c == "'" || c == "’" || c == "-" }

    private func wordContext() -> (prefix: String, previous: String?) {
        let before = compose ? String(draft.prefix(max(0, min(caret, draft.count))))
                             : (proxy.documentContextBeforeInput ?? "")
        let chars = Array(before.suffix(64))
        var i = chars.count
        while i > 0, isWordChar(chars[i - 1]) { i -= 1 }
        let prefix = String(chars[i...])
        if prefix.count > 24 { return ("", nil) }
        var j = i
        while j > 0, !isWordChar(chars[j - 1]) {
            if chars[j - 1] == "\n" || ".!?…".contains(chars[j - 1]) { return (prefix, nil) }
            j -= 1
        }
        var k = j
        while k > 0, isWordChar(chars[k - 1]) { k -= 1 }
        let previous = String(chars[k ..< j])
        return (prefix, previous.isEmpty ? nil : previous)
    }

    private func clearSuggestions() {
        if !suggestions.isEmpty { suggestions = [] }
        pendingFix = nil
        pendingFixTyped = nil
        suggestionsStamp = nil
    }

    private func updateSuggestions() {
        guard !isChinese else {
            clearSuggestions()
            return
        }
        guard suggestionsOn, typingAidsAllowed else {
            clearSuggestions()
            return
        }
        let ctx = wordContext()
        let stamp = "\(ctx.prefix)\u{1}\(ctx.previous ?? "\u{2}")\u{1}\(Self.code(for: letterLayout))\u{1}\(autocorrectOn)"
        if stamp == suggestionsStamp { return }
        suggestionsStamp = stamp
        var list = SuggestionEngine.shared.suggest(prefix: ctx.prefix, previous: ctx.previous,
                                                   language: Self.code(for: letterLayout))
        var pending: String?
        if autocorrectOn, ctx.prefix.count >= 3 {
            pending = SuggestionEngine.shared.autocorrect(ctx.prefix, previous: ctx.previous,
                                                          language: Self.code(for: letterLayout), deep: false)
            if let p = pending {
                let alt = list.first { $0 != p && $0 != ctx.prefix }
                list = [p, ctx.prefix, alt ?? ""]
            }
        }
        pendingFix = pending
        pendingFixTyped = pending != nil ? ctx.prefix : nil
        suggestions = list
    }

    private func applySuggestion(_ word: String) {
        lastAutoFix = nil
        let ctx = wordContext()
        if learningAllowed, word == ctx.prefix, word == pendingFixTyped {
            SuggestionEngine.shared.noteRejectedCorrection(word)
        }
        if compose {
            for _ in 0 ..< ctx.prefix.count { deleteFromDraft() }
            insertIntoDraft(word + " ")
        } else {
            for _ in 0 ..< ctx.prefix.count { proxy.deleteBackward() }
            proxy.insertText(word + " ")
        }
        if learningAllowed { SuggestionEngine.shared.learn(word, previous: ctx.previous) }
        if shift == .on { shift = .off; autoShifted = false }
        updateAutoShift()
        updateSuggestions()
    }

    private func learnFinishedWord() {
        guard suggestionsOn || autocorrectOn, learningAllowed else { return }
        let ctx = wordContext()
        if !ctx.prefix.isEmpty { SuggestionEngine.shared.learn(ctx.prefix, previous: ctx.previous) }
    }

    private func commitWordBeforeSeparator(_ separator: String) {
        lastAutoFix = nil
        guard typingAidsAllowed else { return }
        if autocorrectOn {
            let ctx = wordContext()
            if !ctx.prefix.isEmpty,
               let fixed = SuggestionEngine.shared.autocorrect(ctx.prefix, previous: ctx.previous,
                                                               language: Self.code(for: letterLayout)),
               fixed != ctx.prefix {
                replaceCurrentWord(ctx.prefix, with: fixed)
                lastAutoFix = AutoFix(original: ctx.prefix, corrected: fixed, separator: separator, at: Date())
            }
        }
        learnFinishedWord()
    }

    private func replaceCurrentWord(_ old: String, with new: String) {
        if compose {
            for _ in 0 ..< old.count { deleteFromDraft() }
            insertIntoDraft(new)
        } else {
            for _ in 0 ..< old.count { proxy.deleteBackward() }
            proxy.insertText(new)
        }
    }

    private func undoAutoFix(_ fix: AutoFix) -> Bool {
        let tail = fix.corrected + fix.separator
        let restored = fix.original + fix.separator
        if compose {
            let before = String(draft.prefix(max(0, min(caret, draft.count))))
            guard before.hasSuffix(tail) else { return false }
            for _ in 0 ..< tail.count { deleteFromDraft() }
            insertIntoDraft(restored)
        } else {
            let before = proxy.documentContextBeforeInput ?? ""
            guard before.hasSuffix(tail) else { return false }
            for _ in 0 ..< tail.count { proxy.deleteBackward() }
            proxy.insertText(restored)
        }
        SuggestionEngine.shared.noteUndoneCorrection(fix.original)
        return true
    }

    private static let secretContentTypes: Set<String> = [
        UITextContentType.password.rawValue,
        UITextContentType.newPassword.rawValue,
        UITextContentType.oneTimeCode.rawValue,
        UITextContentType.creditCardNumber.rawValue,
    ]

    private var typingAidsAllowed: Bool { !secureField && !noAidsField }

    private var learningAllowed: Bool { typingAidsAllowed && !hostIsKryptos }

    private func adoptFieldTraits() {
        returnIcon = Self.returnIconName(proxy.returnKeyType)
        let contentType = (proxy.textContentType ?? nil)?.rawValue
        secureField = (proxy.isSecureTextEntry ?? false) ||
            (contentType.map(Self.secretContentTypes.contains) ?? false)
        noAidsField = (proxy.autocorrectionType ?? .default) == .no
            || (proxy.spellCheckingType ?? .default) == .no
        let kt = proxy.keyboardType ?? .default
        if kt == .numberPad || kt == .decimalPad || kt == .phonePad || kt == .asciiCapableNumberPad,
           layout == letterLayout {
            layout = .numbers
        }
    }

    private static func returnIconName(_ t: UIReturnKeyType?) -> String {
        switch t {
        case .search, .google, .yahoo: return "magnifyingglass"
        case .send: return "arrow.up"
        case .go, .join, .route, .continue: return "arrow.right"
        case .done: return "checkmark"
        case .next: return "arrow.right.to.line"
        default: return "return"
        }
    }

    private func sessionStarted() {
        hostIsKryptos = ForegroundMarker.isOpen
        recoverStorage()
        if TypingSession.sync() { dropWipedState() }
        dropWipedProfile()
        dropPurgedPlaintext()
        dropLockedPlaintext()
        purgeStaleDecrypts()
        refreshClipHint()
        revealed = nil
        status = nil
        clearPinyin()
        lastAutoFix = nil
        adoptFieldTraits()
        updateAutoShift()
        updateSuggestions()
    }

    private func sessionEnded() {
        revealed = nil
        status = nil
        clearSuggestions()
        clearPinyin()
        lastAutoFix = nil
    }

    private func dropLockedPlaintext() {
        let token = LockMarker.token()
        guard lockTokenKnown else {
            lockToken = token
            lockTokenKnown = true
            return
        }
        guard token != lockToken else { return }
        lockToken = token
        clearDraft()
        decryptCache.removeAll()
        revealed = nil
        status = nil
        cryptoUnlockedAt = nil
    }

    private func purgeStaleDecrypts() {
        let fresh = decryptCache.filter { Self.withinTTL($0.value.date) }
        if fresh.count != decryptCache.count { decryptCache = fresh }
    }

    private func refreshClipHint() {
        guard hasFullAccess else { return }
        clipHint = UIPasteboard.general.hasStrings
    }

    private func hostTextChanged() {
        adoptFieldTraits()
        if !isChinese { clearPinyin() }
        updateAutoShift()
        updateSuggestions()
    }

    private func rememberPlane() {
        let name: String
        switch layout {
        case .numbers: name = "numbers"
        case .symbols: name = "symbols"
        default: name = "letters"
        }
        let d = UserDefaults.standard
        d.set(name, forKey: "kb.lastPlane")
        d.set(Date().timeIntervalSince1970, forKey: "kb.lastPlaneAt")
    }

    private func restoreRecentPlane() {
        let d = UserDefaults.standard
        guard Date().timeIntervalSince1970 - d.double(forKey: "kb.lastPlaneAt") < 8 else { return }
        switch d.string(forKey: "kb.lastPlane") {
        case "numbers": layout = .numbers
        case "symbols": layout = .symbols
        default: break
        }
    }

    private var emojiPanel: some View {
        VStack(spacing: 4) {
            ScrollView(showsIndicators: false) {
                let list = emojiCategory < 0 ? EmojiData.recents() : EmojiData.categories[emojiCategory].emoji
                if list.isEmpty {
                    Text("No recent emoji yet — pick a category below")
                        .font(.system(size: 13)).foregroundStyle(KB.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 28)
                } else {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 8), spacing: 2) {
                        ForEach(list, id: \.self) { e in
                            Button {
                                press()
                                insertChar(e)
                                if learningAllowed { EmojiData.addRecent(e) }
                            } label: {
                                Text(e).font(.system(size: 27))
                                    .frame(maxWidth: .infinity, minHeight: 40)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 2)
                }
            }
            HStack(spacing: 8) {
                Button {
                    press(); showEmoji = false; updateSuggestions()
                } label: {
                    Text(Self.modeLabel(Self.code(for: letterLayout)))
                        .font(.system(size: 14, weight: .medium)).foregroundStyle(KB.keyText)
                        .frame(width: 52, height: 32)
                        .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(KB.fieldFill))
                }
                .buttonStyle(.plain)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 2) {
                        emojiTab("🕘", index: -1)
                        ForEach(Array(EmojiData.categories.enumerated()), id: \.offset) { i, c in
                            emojiTab(c.icon, index: i)
                        }
                    }
                }
                Button { press(); backspaceDelete() } label: {
                    Image(systemName: "delete.left")
                        .font(.system(size: 17, weight: .medium)).foregroundStyle(KB.keyText)
                        .frame(width: 52, height: 32)
                        .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(KB.fieldFill))
                }
                .buttonStyle(.plain)
                .buttonRepeatBehavior(.enabled)
            }
            .padding(.horizontal, 2)
        }
        .frame(maxHeight: .infinity)
    }

    private func emojiTab(_ icon: String, index: Int) -> some View {
        Button { emojiCategory = index } label: {
            Text(icon).font(.system(size: 17))
                .frame(width: 34, height: 30)
                .background(RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(emojiCategory == index ? KB.accent.opacity(0.16) : .clear))
        }
        .buttonStyle(.plain)
    }

    private func currentRows() -> [[Cap]] {
        switch layout {
        case .english: return letters("qwertyuiop", "asdfghjkl", "zxcvbnm")
        case .russian: return letters("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        case .german: return letters("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")
        case .chinese: return letters("qwertyuiop", "asdfghjkl", "zxcvbnm")
        case .persian: return letters("ضصثقفغعهخحجچ", "شسیبلاتنمکگ", "ظطژزرذدپوآ")
        case .numbers:
            return isPersian
                ? symbols(["۱۲۳۴۵۶۷۸۹۰", "-/:؛()﷼&@\"", ".،؟!'ءئؤ"], mode: .symbols)
                : symbols(["1234567890", "-/:;()$&@\"", ".,?!'"], mode: .symbols)
        case .symbols:
            return isPersian
                ? symbols(["1234567890", "#%*+=_\\|«»", ".،؟!'"], mode: .digits)
                : symbols(["[]{}#%^*+=", "_\\|~<>€£₽•", ".,?!'"], mode: .digits)
        }
    }

    private func letters(_ a: String, _ b: String, _ c: String) -> [[Cap]] {
        let up = shift != .off && !isPersian
        func caps(_ s: String) -> [Cap] {
            s.map { .ch(up && $0 != "ß" ? String($0).uppercased() : String($0)) }
        }
        var r3 = caps(c)
        r3.insert(.sp(isPersian ? .zwnj : .shift), at: 0)
        r3.append(.sp(.backspace))
        return [caps(a), caps(b), r3, bottomCaps]
    }

    private func symbols(_ rows: [String], mode: Special) -> [[Cap]] {
        func caps(_ s: String) -> [Cap] { s.map { .ch(String($0)) } }
        var r3 = caps(rows[2]); r3.insert(.sp(mode), at: 0); r3.append(.sp(.backspace))
        return [caps(rows[0]), caps(rows[1]), r3, bottomCaps]
    }

    private var bottomCaps: [Cap] {
        let mode: Cap = (layout == .numbers || layout == .symbols) ? .sp(.letters) : .sp(.digits)
        var row: [Cap] = [mode]
        if enabledLangs.count > 1 { row.append(.sp(.lang)) }
        if emojiOn { row.append(.sp(.emoji)) }
        row += [.sp(.space), .sp(.ret)]
        return row
    }

    private func haptic() {
        guard haptics, hasFullAccess else { return }
        feedback.impactOccurred(intensity: 0.85)
        feedback.prepare()
    }

    private func sound() {
        guard sounds, hasFullAccess else { return }
        playClick()
    }

    private func press() { haptic(); sound() }

    private func type(_ s: String) {
        if compose { insertIntoDraft(s) } else { proxy.insertText(s) }
    }

    private static let letterAlternates: [String: [String]] = [
        "е": ["е", "ё"],
        "ь": ["ь", "ъ"]
    ]

    private func alternates(for label: String) -> [String] {
        guard !secureField else { return [] }
        let lower = label.lowercased()
        guard let base = Self.letterAlternates[lower] else { return [] }
        return label == lower ? base : base.map { $0.uppercased() }
    }

    private func replaceTyped(with s: String) {
        lastAutoFix = nil
        backspaceDelete()
        type(s)
        updateAutoShift()
        updateSuggestions()
    }

    private func insertChar(_ s: String) {
        if isChinese {
            if let f = s.first, s.count == 1, f.isASCII, f.isLetter {
                pinyin += s.lowercased()
                refreshCandidates()
                if shift == .on { shift = .off; autoShifted = false }
                return
            }
            if s == "'", !pinyin.isEmpty {
                pinyin += s
                refreshCandidates()
                returnToLetters()
                return
            }
            if let best = candidates.first { commitCandidate(best) }
            type(KryptosKeyboardView.fullWidth[s] ?? s)
            if shift == .on { shift = .off; autoShifted = false }
            updateAutoShift()
            return
        }
        if let f = s.first, !f.isLetter, !f.isNumber, !isWordChar(f) { commitWordBeforeSeparator(s) }
        else { lastAutoFix = nil }
        type(s)
        if shift == .on { shift = .off; autoShifted = false }
        updateAutoShift()
        updateSuggestions()
    }

    private func backspaceDelete() {
        if isChinese, !pinyin.isEmpty {
            pinyin = String(pinyin.dropLast())
            refreshCandidates()
            return
        }
        if let fix = lastAutoFix {
            lastAutoFix = nil
            if Self.withinTTL(fix.at, Self.autoFixUndoWindow), undoAutoFix(fix) {
                updateAutoShift()
                updateSuggestions()
                return
            }
        }
        if compose { deleteFromDraft() }
        else { proxy.deleteBackward() }
        updateAutoShift()
        updateSuggestions()
    }

    private func moveCursor(_ n: Int) {
        lastAutoFix = nil
        if compose { caret = max(0, min(draft.count, caret + n)) }
        else { proxy.adjustTextPosition(byCharacterOffset: n) }
        updateAutoShift()
        updateSuggestions()
    }

    private func moveCursorVertical(_ n: Int) {
        lastAutoFix = nil
        var steps = n
        while steps != 0 {
            let dir = steps < 0 ? -1 : 1
            if compose {
                let safe = max(0, min(caret, draft.count))
                let before = String(draft.prefix(safe))
                let after = String(draft.suffix(draft.count - safe))
                guard let delta = Self.verticalDelta(before: before, after: after, direction: dir) else { break }
                caret = max(0, min(draft.count, caret + delta))
            } else {
                let before = proxy.documentContextBeforeInput ?? ""
                let after = proxy.documentContextAfterInput ?? ""
                guard let delta = Self.verticalDelta(before: before, after: after, direction: dir) else { break }
                proxy.adjustTextPosition(byCharacterOffset: delta)
            }
            steps -= dir
        }
        updateAutoShift()
        updateSuggestions()
    }

    private static func verticalDelta(before: String, after: String, direction: Int) -> Int? {
        let b = Array(before)
        var lineStart = b.count
        while lineStart > 0, b[lineStart - 1] != "\n" { lineStart -= 1 }
        let column = b.count - lineStart

        if direction < 0 {
            guard lineStart > 0 else { return nil }
            let prevEnd = lineStart - 1
            var prevStart = prevEnd
            while prevStart > 0, b[prevStart - 1] != "\n" { prevStart -= 1 }
            let prevLen = prevEnd - prevStart
            let target = prevStart + min(column, prevLen)
            return target - b.count
        } else {
            let a = Array(after)
            guard let nl = a.firstIndex(of: "\n") else { return nil }
            let nextStart = nl + 1
            var nextEnd = nextStart
            while nextEnd < a.count, a[nextEnd] != "\n" { nextEnd += 1 }
            let nextLen = nextEnd - nextStart
            return nextStart + min(column, nextLen)
        }
    }

    private func spaceTapped() {
        if isChinese {
            if let best = candidates.first {
                commitCandidate(best)
            } else {
                type(" ")
                updateAutoShift()
            }
            returnToLetters()
            return
        }
        commitWordBeforeSeparator(" ")
        let now = Date()
        if now.timeIntervalSince(lastSpaceTap) < 0.6,
           charBeforeCaret(1) == " ",
           let word = charBeforeCaret(2), word.isLetter || word.isNumber {
            backspaceDelete()
            type(". ")
            lastSpaceTap = .distantPast
        } else {
            type(" ")
            lastSpaceTap = now
        }
        returnToLetters()
        updateAutoShift()
        updateSuggestions()
    }

    private func charBeforeCaret(_ offset: Int) -> Character? {
        let before = compose ? String(draft.prefix(max(0, min(caret, draft.count))))
                             : (proxy.documentContextBeforeInput ?? "")
        guard before.count >= offset else { return nil }
        return before[before.index(before.endIndex, offsetBy: -offset)]
    }

    private func returnToLetters() {
        if layout == .numbers || layout == .symbols { layout = letterLayout; rememberPlane() }
    }

    private func updateAutoShift() {
        guard shift != .locked else { return }
        let before = compose ? String(draft.prefix(max(0, min(caret, draft.count))))
                             : (proxy.documentContextBeforeInput ?? "")
        let capType: UITextAutocapitalizationType = compose ? .sentences : (proxy.autocapitalizationType ?? .sentences)
        let should = KryptosKeyboardView.needsAutoCap(before, type: capType)
        if should, shift == .off {
            shift = .on
            autoShifted = true
        } else if !should, shift == .on, autoShifted {
            shift = .off
            autoShifted = false
        }
    }

    private static func needsAutoCap(_ before: String, type: UITextAutocapitalizationType) -> Bool {
        switch type {
        case .none:
            return false
        case .allCharacters:
            return true
        case .words:
            return before.last.map { $0.isWhitespace || $0.isNewline } ?? true
        default:
            guard let last = before.last else { return true }
            if last.isNewline { return true }
            var rest = before[...]
            var spaces = 0
            while rest.last == " " { spaces += 1; rest = rest.dropLast() }
            if rest.isEmpty { return true }
            guard spaces > 0, let c = rest.last else { return false }
            return ".!?…".contains(c)
        }
    }

    private func insertIntoDraft(_ s: String) {
        caret = max(0, min(caret, draft.count))
        let head = String(draft.prefix(caret)) + s
        draft = head + String(draft.dropFirst(caret))
        caret = head.count
    }

    private func deleteFromDraft() {
        caret = max(0, min(caret, draft.count))
        guard caret > 0 else { return }
        let head = String(draft.prefix(caret))
        let tail = String(draft.dropFirst(caret))
        let shortened: String
        if head.unicodeScalars.last == KryptosKeyboardView.zwnjScalar {
            var scalars = head.unicodeScalars
            scalars.removeLast()
            shortened = String(scalars)
        } else {
            shortened = String(head.dropLast())
        }
        draft = shortened + tail
        caret = shortened.count
    }

    private func clearDraft() { draft = ""; caret = 0; updateAutoShift(); updateSuggestions() }

    private func performSpecial(_ sp: Special) {
        switch sp {
        case .shift:     shiftTapped()
        case .backspace: backspaceDelete()
        case .space:     spaceTapped()
        case .ret:
            if isChinese, !pinyin.isEmpty {
                type(pinyin)
                clearPinyin()
                return
            }
            commitWordBeforeSeparator("\n")
            type("\n")
            returnToLetters()
            updateAutoShift()
            updateSuggestions()
        case .digits:    layout = .numbers; rememberPlane()
        case .symbols:   layout = .symbols; rememberPlane()
        case .letters:   layout = letterLayout; rememberPlane(); updateAutoShift(); refreshCandidates()
        case .lang:      toggleLanguage()
        case .zwnj:
            commitWordBeforeSeparator(KryptosKeyboardView.zwnj)
            type(KryptosKeyboardView.zwnj)
            updateSuggestions()
        case .emoji:
            emojiCategory = EmojiData.recents().isEmpty ? 0 : -1
            showEmoji = true
        }
    }

    private func shiftTapped() {
        let now = Date()
        if shift != .off && now.timeIntervalSince(lastShiftTap) < 0.3 { shift = .locked }
        else { shift = (shift == .off) ? .on : .off }
        autoShifted = false
        lastShiftTap = now
    }

    private func toggleLanguage() {
        guard enabledLangs.count > 1 else { return }
        clearPinyin()
        let current = Self.code(for: letterLayout)
        let i = enabledLangs.firstIndex(of: current) ?? 0
        let next = enabledLangs[(i + 1) % enabledLangs.count]
        letterLayout = Self.layout(for: next)
        if layout != .numbers, layout != .symbols { layout = letterLayout }
        UserDefaults.standard.set(next, forKey: "kb.lang")
        KeyboardViewController.warmSuggestions(config, language: next)
        updateSuggestions()
    }

    static let zwnj = "\u{200C}"
    static let zwnjScalar: Unicode.Scalar = "\u{200C}"

    private static func code(for layout: KeyLayout) -> String {
        switch layout {
        case .russian: return "ru"
        case .german: return "de"
        case .chinese: return "zh"
        case .persian: return "fa"
        default: return "en"
        }
    }

    private static func layout(for code: String) -> KeyLayout {
        switch code {
        case "ru": return .russian
        case "de": return .german
        case "zh": return .chinese
        case "fa": return .persian
        default: return .english
        }
    }

    static func languageName(_ code: String) -> String {
        switch code {
        case "ru": return "Русский"
        case "de": return "Deutsch"
        case "zh": return "中文"
        case "fa": return "فارسی"
        default: return "English"
        }
    }

    static func modeLabel(_ code: String) -> String {
        switch code {
        case "ru": return "АБВ"
        case "fa": return "ابپ"
        default: return "ABC"
        }
    }

    private static func shortLabel(_ code: String) -> String {
        switch code {
        case "ru": return "РУ"
        case "de": return "DE"
        case "zh": return "中"
        case "fa": return "فا"
        default: return "EN"
        }
    }

    private var nextLanguageLabel: String {
        guard enabledLangs.count > 1 else { return "" }
        let current = Self.code(for: letterLayout)
        let i = enabledLangs.firstIndex(of: current) ?? 0
        return Self.shortLabel(enabledLangs[(i + 1) % enabledLangs.count])
    }

    private func recoverStorage() {
        guard store == nil else { return }
        AppGroup.revalidate()
        SharedStore.revalidateBackend()
        guard SharedStore.isShared else { scheduleStorageRetry(); return }
        ConfigCaches.invalidateAll()
        let fresh = KeyboardConfig.snapshot()
        loaded = false
        loadOnce(fresh)
        configChanged(fresh)
    }

    private func scheduleStorageRetry() {
        guard storageRetries < Self.storageRetryLimit else { return }
        storageRetries += 1
        let delay = Self.storageRetryStep * storageRetries
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(delay))
            recoverStorage()
        }
    }

    private func loadOnce(_ c: KeyboardConfig.Snapshot) {
        guard !loaded else { return }
        haptics = c.haptics
        sounds = c.sounds
        compose = c.compose
        composeToggleEnabled = c.composeToggle
        shieldVisible = c.shield
        autoDecrypt = c.autoDecrypt
        suggestionsOn = c.suggestions
        autocorrectOn = c.autocorrect
        emojiOn = c.emoji
        if haptics { feedback.prepare() }
        profiles = SharedSignalStore.profiles()
        let savedID = KeyboardSelection.profileID()
        let currentID = SharedSignalStore.index()?.currentID
        if let p = profiles.first(where: { $0.id == savedID })
                ?? profiles.first(where: { $0.id == currentID })
                ?? profiles.first {
            select(profile: p)
        }
        loaded = true
        hostIsKryptos = ForegroundMarker.isOpen
        purgeExpiredInBackground()
        enabledLangs = KeyboardViewController.enabledLanguages(c)
        let lang = KeyboardViewController.activeLanguage(c)
        letterLayout = Self.layout(for: lang)
        layout = letterLayout
        restoreRecentPlane()
        adoptFieldTraits()
        updateAutoShift()
        updateSuggestions()
        refreshClipHint()
        scanClipboard()
        if store == nil { scheduleStorageRetry() }
    }

    private func purgeExpiredInBackground() {
        guard let profile = store?.profile else { return }
        Task.detached(priority: .utility) {
            SharedSignalStore(profile: profile)?.purgeExpired()
        }
    }

    private static let keyboardUnlockTTL: TimeInterval = 5 * 60

    private static func withinTTL(_ stamp: Date, _ ttl: TimeInterval = cacheTTL) -> Bool {
        let age = Date().timeIntervalSince(stamp)
        return age >= 0 && age < ttl
    }

    private var cryptoUnlocked: Bool {
        guard let at = cryptoUnlockedAt else { return false }
        return Self.withinTTL(at, Self.keyboardUnlockTTL)
    }

    private var cryptoLocked: Bool {
        guard PrivacyConfig.appLock else { return false }
        if PrivacyConfig.appLockCodeOnly { return !LockSession.isOpen }
        return !cryptoUnlocked && LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    private func withCryptoGate(_ action: @escaping () -> Void) {
        guard cryptoLocked else { action(); return }
        guard !PrivacyConfig.appLockCodeOnly else {
            flash(String(localized: "Locked — open Kryptos and unlock it"), error: true)
            return
        }
        guard !authInFlight else { return }
        authInFlight = true
        let ctx = LAContext()
        Task { @MainActor in
            defer { authInFlight = false }
            let reason = String(localized: "Unlock Kryptos")
            if (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) == true {
                cryptoUnlockedAt = Date()
                action()
            } else {
                flash(String(localized: "Locked — unlock to use encryption"), error: true)
            }
        }
    }

    private func scanClipboard() {
        guard hasFullAccess else { return }
        let pb = UIPasteboard.general
        let generation = pb.changeCount
        guard generation != lastClipCount else { return }
        clipHint = pb.hasStrings
        guard autoDecrypt, let store, revealed == nil else { return }
        guard !cryptoLocked else { return }
        lastClipCount = generation
        UserDefaults.standard.set(generation, forKey: "kb.clipGeneration")
        guard !RemoteClipboard.isRemote else { return }
        guard pb.hasStrings, let clip = pb.string, !clip.isEmpty else { return }
        let marker = OwnCipherMarker.storedKey()
        Task { @MainActor in
            let verdict = await Task.detached(priority: .userInitiated) {
                ClipProbe.inspect(clip, ownMarker: marker)
            }.value
            guard verdict.worthDecrypting, revealed == nil, !cryptoLocked,
                  UIPasteboard.general.changeCount == generation else { return }
            reveal(clip, using: store, manual: false, stego: .some(verdict.stego))
            if UIPasteboard.general.changeCount != generation { revealed = nil; status = nil }
        }
    }

    private static func cacheKey(_ clip: String) -> String {
        SHA256.hash(data: Data(clip.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func cached(_ clip: String) -> DecryptedMessage? {
        guard let hit = decryptCache[Self.cacheKey(clip)], Self.withinTTL(hit.date) else { return nil }
        return hit
    }

    private func reveal(_ clip: String, using store: SharedSignalStore, manual: Bool, stego: Data?? = nil) {
        if let hit = cached(clip) {
            status = nil
            revealed = RevealedText(name: hit.name, text: hit.text)
            return
        }
        decryptCache[Self.cacheKey(clip)] = nil
        if let result = store.decryptFromAnyContact(clip, stego: stego) {
            cache(clip, name: result.contact.displayName, text: result.text)
            status = nil
            revealed = RevealedText(name: result.contact.displayName, text: result.text)
        } else if manual {
            flash(String(localized: "Could not decrypt — different profile/contact, or the message is damaged"), error: true)
        }
    }

    private static let storageRetryLimit = 4
    private static let storageRetryStep = 150
    private static let cacheTTL: TimeInterval = 5 * 60
    private static let cacheLimit = 40
    private static let autoFixUndoWindow: TimeInterval = 15

    private func cache(_ clip: String, name: String, text: String) {
        let now = Date()
        decryptCache = decryptCache.filter { Self.withinTTL($0.value.date) }
        while decryptCache.count >= Self.cacheLimit {
            guard let oldest = decryptCache.min(by: { $0.value.date < $1.value.date })?.key else { break }
            decryptCache.removeValue(forKey: oldest)
        }
        decryptCache[Self.cacheKey(clip)] = DecryptedMessage(name: name, text: text, date: now)
    }

    private func dropPurgedPlaintext() {
        let token = DecryptPurgeMarker.token()
        guard purgeTokenKnown else {
            purgeToken = token
            purgeTokenKnown = true
            return
        }
        guard token != purgeToken else { return }
        purgeToken = token
        decryptCache.removeAll()
        revealed = nil
    }

    private func dropWipedState() {
        clearDraft()
        decryptCache.removeAll()
        revealed = nil
        status = nil
        cryptoUnlockedAt = nil
        let d = UserDefaults.standard
        for key in ["kb.lang", "kb.clipGeneration", "kb.lastPlane", "kb.lastPlaneAt"] {
            d.removeObject(forKey: key)
        }
    }

    private func dropWipedProfile() {
        guard let current = store?.profile.id else { return }
        let known = SharedSignalStore.profiles()
        guard !known.contains(where: { $0.id == current }) else { return }
        decryptCache.removeAll()
        revealed = nil
        status = nil
        profiles = known
        let currentID = SharedSignalStore.index()?.currentID
        guard let next = known.first(where: { $0.id == currentID }) ?? known.first else {
            store = nil
            selected = nil
            return
        }
        select(profile: next)
    }

    private func select(profile: Profile, remember: Bool = false) {
        if store?.profile.id != profile.id {
            decryptCache.removeAll()
            revealed = nil
        }
        store = SharedSignalStore(profile: profile)
        if remember, store != nil { KeyboardSelection.rememberProfile(profile.id) }
        if let store {
            let saved = KeyboardSelection.contactFingerprint(profileID: profile.id)
            selected = store.contacts.first { $0.fingerprint == saved } ?? store.contacts.first
        } else {
            selected = nil
        }
        status = nil
    }

    private func encrypt(_ store: SharedSignalStore) {
        guard !secureField else { return flash(String(localized: "Not available in a password field"), error: true) }
        guard let selected else { return flash(String(localized: "Choose a contact first"), error: true) }
        if compose {
            guard !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return flash(String(localized: "Enter some text."), error: true)
            }
            do {
                let cipher = try store.encrypt(draft, to: selected.fingerprint)
                forgetWhatWasTypedForThisMessage()
                proxy.insertText(cipher)
                clearDraft()
                flash(String(localized: "Encrypted for \(selected.displayName)"), error: false)
            } catch {
                flash(String(localized: "Could not encrypt"), error: true)
            }
            return
        }
        guard proxy.hasText else { return flash(String(localized: "Enter some text."), error: true) }
        let harvest = harvestHostField()
        guard harvest.cleared else {
            if !harvest.text.isEmpty { proxy.insertText(harvest.text) }
            return flash(String(localized: "This app will not let the keyboard clear the field — encrypt in the Kryptos app instead."), error: true)
        }
        let text = harvest.text
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            if !text.isEmpty { proxy.insertText(text) }
            return flash(String(localized: "Enter some text."), error: true)
        }
        do {
            let cipher = try store.encrypt(text, to: selected.fingerprint)
            forgetWhatWasTypedForThisMessage()
            proxy.insertText(cipher)
            flash(String(localized: "Encrypted for \(selected.displayName)"), error: false)
        } catch {
            proxy.insertText(text)
            flash(String(localized: "Could not encrypt"), error: true)
        }
    }

    private func forgetWhatWasTypedForThisMessage() {
        TypingSession.forget()
        SuggestionEngine.shared.persist()
        PinyinEngine.shared.persist()
    }

    private func decrypt(_ store: SharedSignalStore) {
        guard !secureField else { return flash(String(localized: "Not available in a password field"), error: true) }
        let field = fullText()
        if showDecrypted(from: field, using: store) { return }
        let clip = UIPasteboard.general.string ?? ""
        if showDecrypted(from: clip, using: store) { return }
        let empty = field.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && clip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        flash(empty
              ? String(localized: "No message — paste it into the field or copy it")
              : String(localized: "Could not decrypt — check the profile and contact"), error: true)
    }

    private func showDecrypted(from source: String, using store: SharedSignalStore) -> Bool {
        guard !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        if let hit = cached(source) {
            status = nil
            revealed = RevealedText(name: hit.name, text: hit.text)
            return true
        }
        guard let result = store.decryptFromAnyContact(source) else { return false }
        cache(source, name: result.contact.displayName, text: result.text)
        status = nil
        revealed = RevealedText(name: result.contact.displayName, text: result.text)
        return true
    }

    private func flash(_ message: String, error: Bool) {
        status = message
        isError = error
        statusGen += 1
        let gen = statusGen
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if statusGen == gen { status = nil }
        }
    }

    private func fullText() -> String {
        (proxy.documentContextBeforeInput ?? "") + (proxy.documentContextAfterInput ?? "")
    }

    private static let fieldHarvestRounds = 64

    private func harvestHostField() -> (text: String, cleared: Bool) {
        var rounds = 0
        while rounds < Self.fieldHarvestRounds {
            let after = proxy.documentContextAfterInput ?? ""
            if after.isEmpty { break }
            proxy.adjustTextPosition(byCharacterOffset: after.count)
            rounds += 1
        }
        var chunks: [String] = []
        var cleared = false
        rounds = 0
        while rounds < Self.fieldHarvestRounds {
            let before = proxy.documentContextBeforeInput ?? ""
            if before.isEmpty {
                cleared = true
                break
            }
            for _ in 0 ..< before.count { proxy.deleteBackward() }
            if (proxy.documentContextBeforeInput ?? "") == before { break }
            chunks.append(before)
            rounds += 1
        }
        return (chunks.reversed().joined(), cleared)
    }
}

private struct DraftCaret: View {
    let text: String
    let offset: Int

    private static let fontSize: CGFloat = 15
    private static let thickness: CGFloat = 1.5

    var body: some View {
        GeometryReader { geo in
            let rect = Self.caretRect(text: text, offset: offset, width: geo.size.width)
            Rectangle()
                .fill(KB.accent)
                .frame(width: Self.thickness, height: rect.height)
                .offset(x: min(max(0, rect.minX), max(0, geo.size.width - Self.thickness)), y: rect.minY)
        }
    }

    private static func caretRect(text: String, offset: Int, width: CGFloat) -> CGRect {
        let font = UIFont.systemFont(ofSize: fontSize)
        let style = NSMutableParagraphStyle()
        style.alignment = .natural
        style.lineBreakMode = .byWordWrapping
        let attributed = NSAttributedString(string: text, attributes: [.font: font, .paragraphStyle: style])
        let typesetter = CTTypesetterCreateWithAttributedString(attributed)
        let total = attributed.length
        let target = utf16Index(in: text, characters: offset)
        let box = max(width, 1)
        var start = 0
        var top: CGFloat = 0
        while start < total {
            let length = max(1, CTTypesetterSuggestLineBreak(typesetter, start, Double(box)))
            let line = CTTypesetterCreateLine(typesetter, CFRange(location: start, length: length))
            var ascent: CGFloat = 0, descent: CGFloat = 0, leading: CGFloat = 0
            CTLineGetTypographicBounds(line, &ascent, &descent, &leading)
            let height = ascent + descent + leading
            let end = start + length
            if target < end || end >= total {
                return CGRect(x: CTLineGetOffsetForStringIndex(line, min(target, end), nil),
                              y: top, width: thickness, height: height)
            }
            start = end
            top += height
        }
        return CGRect(x: 0, y: top, width: thickness, height: font.lineHeight)
    }

    private static func utf16Index(in text: String, characters: Int) -> CFIndex {
        let clamped = max(0, min(characters, text.count))
        let index = text.index(text.startIndex, offsetBy: clamped)
        return CFIndex(text.utf16.distance(from: text.utf16.startIndex, to: index.samePosition(in: text.utf16) ?? text.utf16.endIndex))
    }
}

private struct KeyGridRepresentable: UIViewRepresentable {
    let rows: [[Cap]]
    let shiftState: ShiftState
    let languageCode: String
    let langKeyLabel: String
    let returnIcon: String
    let spaceMovable: Bool
    let secureInput: Bool
    let compact: Bool
    let onPressFeedback: () -> Void
    let onChar: (String) -> Void
    let onSpecial: (Special) -> Void
    let onBackspaceFirst: () -> Void
    let onBackspaceRepeat: () -> Void
    let onSpaceTap: () -> Void
    let onCaretMove: (Int) -> Void
    let onCaretMoveVertical: (Int) -> Void
    let alternates: (String) -> [String]
    let onAlternate: (String) -> Void

    func makeUIView(context: Context) -> KeyGridView {
        let v = KeyGridView()
        apply(to: v)
        return v
    }

    func updateUIView(_ v: KeyGridView, context: Context) {
        apply(to: v)
    }

    private func apply(to v: KeyGridView) {
        v.onPressFeedback = onPressFeedback
        v.onChar = onChar
        v.onSpecial = onSpecial
        v.onBackspaceFirst = onBackspaceFirst
        v.onBackspaceRepeat = onBackspaceRepeat
        v.onSpaceTap = onSpaceTap
        v.onCaretMove = onCaretMove
        v.onCaretMoveVertical = onCaretMoveVertical
        v.alternates = alternates
        v.onAlternate = onAlternate
        v.configure(rows: rows, shiftState: shiftState, languageCode: languageCode, langKeyLabel: langKeyLabel,
                    returnIcon: returnIcon, spaceMovable: spaceMovable, secureInput: secureInput,
                    compact: compact)
    }
}

private struct GridKey {
    let rect: CGRect
    let visibleRect: CGRect
    let cap: Cap
}

private struct KeyGlow {
    let pressedAt: CFTimeInterval
    var releasedAt: CFTimeInterval?
}

private final class KeyTouch {
    enum Origin { case char, backspace, space, special }
    let id: Int
    let origin: Origin
    var cellIndex: Int
    var startTime = Date()
    var startX: CGFloat = 0
    var lastX: CGFloat = 0
    var startY: CGFloat = 0
    var lastY: CGFloat = 0
    var moved = false
    var initialTimer: Timer?
    init(id: Int, origin: Origin, cellIndex: Int) { self.id = id; self.origin = origin; self.cellIndex = cellIndex }
    func stopTimers() { initialTimer?.invalidate(); initialTimer = nil }
}

private final class KeyGridView: UIView {
    private var rows: [[Cap]] = []
    private var shiftState: ShiftState = .off
    private var languageCode = "en"
    private var langKeyLabel = "РУ"
    private var returnIcon = "return"
    var spaceMovable = true
    private var secureInput = false
    private var keys: [GridKey] = []
    private var compact = false

    private var keyH: CGFloat { KB.keyHeight(compact: compact) }
    private var rowGap: CGFloat { KB.rowSpacing(compact: compact) }

    private let touchYBias: CGFloat = 5

    private var active: [ObjectIdentifier: KeyTouch] = [:]
    private var backspaceRepeat: Task<Void, Never>?
    private var touchSeq = 0
    private var glows: [Int: KeyGlow] = [:]
    private var glowLink: CADisplayLink?
    private var popupChar: (index: Int, char: String)?
    private let popupView = KeyPopupView()
    private let altView = AltPopupView()
    private var altOptions: [String] = []
    private var altSelected = 0
    private var altTouchID: Int?
    private var altOrigin: CGFloat = 0

    private var trackpadActive = false
    private var labelAlpha: CGFloat = 1
    private var labelFadeTarget: CGFloat = 1
    private var labelFadeLink: CADisplayLink?

    private func setTrackpad(_ on: Bool) {
        guard trackpadActive != on else { return }
        trackpadActive = on
        labelFadeTarget = on ? 0 : 1
        labelFadeLink?.invalidate()
        let proxy = DisplayLinkProxy(target: self, mode: .labelFade)
        let link = CADisplayLink(target: proxy, selector: #selector(DisplayLinkProxy.step(_:)))
        link.add(to: .main, forMode: .common)
        labelFadeLink = link
    }

    fileprivate func stepLabelFade(_ link: CADisplayLink) {
        let dt = link.targetTimestamp - link.timestamp
        let step = CGFloat(dt > 0 ? dt : 1.0 / 60.0) / 0.16
        if labelAlpha < labelFadeTarget {
            labelAlpha = min(labelFadeTarget, labelAlpha + step)
        } else {
            labelAlpha = max(labelFadeTarget, labelAlpha - step)
        }
        setNeedsDisplay()
        if labelAlpha == labelFadeTarget {
            link.invalidate()
            if labelFadeLink === link { labelFadeLink = nil }
        }
    }

    var onPressFeedback: () -> Void = {}
    var onChar: (String) -> Void = { _ in }
    var onSpecial: (Special) -> Void = { _ in }
    var onBackspaceFirst: () -> Void = {}
    var onBackspaceRepeat: () -> Void = {}
    var onSpaceTap: () -> Void = {}
    var onCaretMove: (Int) -> Void = { _ in }
    var onCaretMoveVertical: (Int) -> Void = { _ in }
    var alternates: (String) -> [String] = { _ in [] }
    var onAlternate: (String) -> Void = { _ in }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isExclusiveTouch = false
        backgroundColor = .clear
        isOpaque = false
        clipsToBounds = false
        contentMode = .redraw
        popupView.isHidden = true
        addSubview(popupView)
        altView.isHidden = true
        addSubview(altView)
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (view: KeyGridView, _) in
            view.setNeedsDisplay()
            view.popupView.setNeedsDisplay()
        }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(rows: [[Cap]], shiftState: ShiftState, languageCode: String, langKeyLabel: String,
                   returnIcon: String, spaceMovable: Bool, secureInput: Bool, compact: Bool) {
        self.spaceMovable = spaceMovable
        if compact != self.compact {
            self.compact = compact
            rebuildGeometry()
            updatePopup()
            setNeedsDisplay()
        }
        if secureInput != self.secureInput {
            self.secureInput = secureInput
            if secureInput, popupChar != nil {
                popupChar = nil
                updatePopup()
            }
        }
        let shapeChanged = !KeyGridView.sameShape(rows, self.rows)
        let labelsChanged = rows != self.rows
        let visualChanged = labelsChanged || shiftState != self.shiftState || languageCode != self.languageCode
            || langKeyLabel != self.langKeyLabel || returnIcon != self.returnIcon
        guard visualChanged else { return }
        self.rows = rows
        self.shiftState = shiftState
        self.languageCode = languageCode
        self.langKeyLabel = langKeyLabel
        self.returnIcon = returnIcon
        if shapeChanged {
            clearGlows()
            popupChar = nil
            rebuildGeometry()
            updatePopup()
        } else if labelsChanged {
            refreshCaps()
        }
        setNeedsDisplay()
    }

    private func refreshCaps() {
        var i = 0
        for row in rows {
            for cap in row {
                guard i < keys.count else { return }
                keys[i] = GridKey(rect: keys[i].rect, visibleRect: keys[i].visibleRect, cap: cap)
                i += 1
            }
        }
    }

    private static func sameShape(_ a: [[Cap]], _ b: [[Cap]]) -> Bool {
        guard a.count == b.count else { return false }
        for (r, row) in a.enumerated() {
            guard row.count == b[r].count else { return false }
            for (i, cap) in row.enumerated() {
                switch (cap, b[r][i]) {
                case (.ch, .ch): continue
                case (.sp(let x), .sp(let y)) where x == y: continue
                default: return false
                }
            }
        }
        return true
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        rebuildGeometry()
        updatePopup()
        setNeedsDisplay()
    }

    private func rebuildGeometry() {
        keys.removeAll(keepingCapacity: true)
        guard bounds.width > 2, rows.count == 4 else { return }
        let inner = bounds.width - 2
        let left: CGFloat = 1
        let rowH = keyH + rowGap
        let cols = rows[0].count
        let letterW = inner / CGFloat(max(cols, 1))
        for r in 0 ..< 4 {
            let caps = rows[r]
            let widths = (r == 3) ? bottomWidths(caps, inner: inner, cols: cols)
                                  : cellWidths(caps, inner: inner, cols: cols)
            let centered = r != 3 && caps.count < cols && caps.allSatisfy(isCharCap)
            var x = left
            let y = CGFloat(r) * rowH
            for (i, cap) in caps.enumerated() {
                let w = i < widths.count ? widths[i] : letterW
                let cell = CGRect(x: x, y: y, width: w, height: rowH)
                var visible = cell.insetBy(dx: KB.gap / 2, dy: rowGap / 2)
                if centered, i == 0 {
                    visible = CGRect(x: cell.maxX - letterW + KB.gap / 2, y: visible.minY,
                                     width: letterW - KB.gap, height: visible.height)
                } else if centered, i == caps.count - 1 {
                    visible = CGRect(x: cell.minX + KB.gap / 2, y: visible.minY,
                                     width: letterW - KB.gap, height: visible.height)
                }
                keys.append(GridKey(rect: cell, visibleRect: visible, cap: cap))
                x += w
            }
        }
    }

    private func cellWidths(_ caps: [Cap], inner: CGFloat, cols: Int) -> [CGFloat] {
        let letterW = inner / CGFloat(max(cols, 1))
        let letters = caps.filter(isCharCap).count
        let specials = caps.count - letters
        if specials == 0 {
            var w = caps.map { _ in letterW }
            let extra = inner - CGFloat(letters) * letterW
            if extra > 0.5, w.count >= 2 { w[0] += extra / 2; w[w.count - 1] += extra / 2 }
            return w
        }
        if isSymbolPlaneRow(caps), letters > 0 {
            let specialW = letterW * 1.5
            let charW = (inner - CGFloat(specials) * specialW) / CGFloat(letters)
            return caps.map { isCharCap($0) ? charW : specialW }
        }
        let rest = inner - CGFloat(letters) * letterW
        let evenSpecial = specials > 0 ? rest / CGFloat(specials) : 0
        if evenSpecial >= letterW - 0.5 {
            return caps.map { isCharCap($0) ? letterW : evenSpecial }
        }
        let unit = inner / (CGFloat(letters) + CGFloat(specials) * 1.4)
        return caps.map { isCharCap($0) ? unit : unit * 1.4 }
    }

    private func isSymbolPlaneRow(_ caps: [Cap]) -> Bool {
        caps.contains {
            if case .sp(.symbols) = $0 { return true }
            if case .sp(.digits) = $0 { return true }
            return false
        }
    }

    private func bottomWidths(_ caps: [Cap], inner: CGFloat, cols: Int) -> [CGFloat] {
        let letterW = inner / CGFloat(max(cols, 1))
        let hasEmoji = caps.contains(.sp(.emoji))
        var widths: [CGFloat] = caps.map { cap in
            switch cap {
            case .sp(.space): return 0
            case .sp(.ret): return letterW * 2.3
            case .sp(.emoji): return letterW * 1.25
            case .sp(.digits): return letterW * 1.25
            case .sp(.lang): return letterW * (hasEmoji ? 1.25 : 1.6)
            default: return letterW * 1.6
            }
        }
        if let i = caps.firstIndex(of: .sp(.space)) {
            widths[i] = inner - widths.reduce(0, +)
        }
        return widths
    }

    private func isCharCap(_ c: Cap) -> Bool { if case .ch = c { return true }; return false }
    private func kind(_ c: Cap) -> KeyKind {
        switch c { case .ch: return .normal; case .sp(.ret): return .accent; case .sp: return .special }
    }

    private func cellIndex(at rawPoint: CGPoint) -> Int? {
        guard !keys.isEmpty else { return nil }
        let p = CGPoint(x: rawPoint.x, y: rawPoint.y - touchYBias)
        for (i, key) in keys.enumerated() where key.rect.contains(p) { return i }
        return nearestIndex(at: p) { _ in true }
    }

    private func nearestIndex(at p: CGPoint, where predicate: (GridKey) -> Bool) -> Int? {
        var best: Int?
        var bestD = CGFloat.greatestFiniteMagnitude
        for (i, key) in keys.enumerated() where predicate(key) {
            let dx = p.x - key.rect.midX, dy = p.y - key.rect.midY
            let d = dx * dx + dy * dy
            if d < bestD { bestD = d; best = i }
        }
        return best
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            let p = t.location(in: self)
            guard let idx = cellIndex(at: p) else { continue }
            let cap = keys[idx].cap
            touchSeq &+= 1
            let touchID = touchSeq
            let info: KeyTouch
            switch cap {
            case .ch(let s):
                info = KeyTouch(id: touchID, origin: .char, cellIndex: idx)
                onPressFeedback()
                onChar(s)
                if !secureInput { popupChar = (idx, s) }
                let options = secureInput ? [] : alternates(s)
                if options.count > 1 {
                    info.initialTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
                        MainActor.assumeIsolated {
                            guard let self, let live = self.touch(id: touchID), !live.moved else { return }
                            self.openAlternates(for: touchID, index: idx, options: options)
                            self.setNeedsDisplay()
                        }
                    }
                }
            case .sp(.backspace):
                info = KeyTouch(id: touchID, origin: .backspace, cellIndex: idx)
                onBackspaceFirst()
                startBackspaceRepeat(info)
            case .sp(.space):
                info = KeyTouch(id: touchID, origin: .space, cellIndex: idx)
                info.startTime = Date()
                info.startX = p.x; info.lastX = p.x
                info.startY = p.y; info.lastY = p.y
                if spaceMovable {
                    info.initialTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
                        MainActor.assumeIsolated {
                            guard let self, let live = self.touch(id: touchID), !live.moved else { return }
                            live.moved = true
                            self.onPressFeedback()
                            self.setTrackpad(true)
                        }
                    }
                }
            default:
                info = KeyTouch(id: touchID, origin: .special, cellIndex: idx)
                onPressFeedback()
            }
            beginGlow(idx)
            active[ObjectIdentifier(t)] = info
        }
        updatePopup()
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        var changed = false
        for t in touches {
            guard let info = active[ObjectIdentifier(t)] else { continue }
            let p = t.location(in: self)
            switch info.origin {
            case .char:
                if altTouchID == info.id { moveAlternates(to: p.x); changed = true }
            case .space:
                guard spaceMovable else { break }
                if !info.moved,
                   Date().timeIntervalSince(info.startTime) > 0.25
                   || abs(p.x - info.startX) > 16 || abs(p.y - info.startY) > 16 {
                    info.moved = true; info.lastX = p.x; info.lastY = p.y; changed = true
                    info.stopTimers()
                    onPressFeedback()
                    setTrackpad(true)
                }
                if info.moved {
                    let stepX: CGFloat = 7
                    let dx = p.x - info.lastX
                    if abs(dx) >= stepX {
                        let n = Int((dx / stepX).rounded(.towardZero))
                        onCaretMove(n); info.lastX += CGFloat(n) * stepX
                    }
                    let stepY: CGFloat = 18
                    let dy = p.y - info.lastY
                    if abs(dy) >= stepY {
                        let n = Int((dy / stepY).rounded(.towardZero))
                        onCaretMoveVertical(n); info.lastY += CGFloat(n) * stepY
                    }
                }
            case .backspace, .special:
                break
            }
        }
        if changed { updatePopup(); setNeedsDisplay() }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { finish(t, cancelled: false) }
        updatePopup(); setNeedsDisplay()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { finish(t, cancelled: true) }
        updatePopup(); setNeedsDisplay()
    }

    private func finish(_ t: UITouch, cancelled: Bool) {
        guard let info = active[ObjectIdentifier(t)] else { return }
        if !cancelled {
            switch info.origin {
            case .char:
                break
            case .space:
                if !info.moved { onSpaceTap() }
            case .special:
                let p = t.location(in: self)
                if let idx = cellIndex(at: p), idx == info.cellIndex, case .sp(let sp) = keys[idx].cap {
                    onSpecial(sp)
                }
            case .backspace:
                break
            }
        }
        info.stopTimers()
        if altTouchID == info.id { closeAlternates(commit: !cancelled) }
        if info.origin == .backspace { stopBackspaceRepeat() }
        active[ObjectIdentifier(t)] = nil
        if !active.values.contains(where: { $0.cellIndex == info.cellIndex }) { endGlow(info.cellIndex) }
        if info.origin == .space, !active.values.contains(where: { $0.origin == .space }) {
            setTrackpad(false)
        }
        if popupChar?.index == info.cellIndex,
           !active.values.contains(where: { $0.origin == .char && $0.cellIndex == info.cellIndex }) {
            popupChar = nil
        }
    }

    private func touch(id: Int) -> KeyTouch? {
        active.values.first { $0.id == id }
    }

    private func beginGlow(_ idx: Int) {
        glows[idx] = KeyGlow(pressedAt: CACurrentMediaTime(), releasedAt: nil)
    }

    private func endGlow(_ idx: Int) {
        guard var glow = glows[idx], glow.releasedAt == nil else { return }
        glow.releasedAt = CACurrentMediaTime()
        glows[idx] = glow
        startGlowLink()
    }

    private func clearGlows() {
        glows.removeAll()
        glowLink?.invalidate()
        glowLink = nil
    }

    private func glowLevel(_ idx: Int, now: CFTimeInterval) -> CGFloat {
        guard let glow = glows[idx] else { return 0 }
        guard let released = glow.releasedAt else { return 1 }
        let start = max(released, glow.pressedAt + KB.glowHold)
        if now <= start { return 1 }
        let t = (now - start) / KB.glowFade
        if t >= 1 { return 0 }
        let rest = CGFloat(1 - t)
        return rest * rest
    }

    private func startGlowLink() {
        guard glowLink == nil else { return }
        let proxy = DisplayLinkProxy(target: self, mode: .keyGlow)
        let link = CADisplayLink(target: proxy, selector: #selector(DisplayLinkProxy.step(_:)))
        link.add(to: .main, forMode: .common)
        glowLink = link
    }

    fileprivate func stepKeyGlow() {
        let now = CACurrentMediaTime()
        var fading = false
        for (idx, glow) in glows where glow.releasedAt != nil {
            if glowLevel(idx, now: now) <= 0 { glows[idx] = nil } else { fading = true }
        }
        setNeedsDisplay()
        if !fading {
            glowLink?.invalidate()
            glowLink = nil
        }
    }

    private func startBackspaceRepeat(_ info: KeyTouch) {
        info.stopTimers()
        stopBackspaceRepeat()
        let touchID = info.id
        backspaceRepeat = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            var step = 0
            while !Task.isCancelled {
                guard let self, self.touch(id: touchID) != nil else { return }
                self.onBackspaceRepeat()
                step += 1
                try? await Task.sleep(for: .milliseconds(step < 12 ? 90 : 45))
            }
        }
    }

    private func stopBackspaceRepeat() {
        backspaceRepeat?.cancel()
        backspaceRepeat = nil
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let now = CACurrentMediaTime()
        for (i, key) in keys.enumerated() {
            drawKey(key, level: trackpadActive ? 0 : glowLevel(i, now: now), ctx: ctx)
        }
    }

    private func drawKey(_ key: GridKey, level: CGFloat, ctx: CGContext) {
        let k = kind(key.cap)
        let shape = UIBezierPath(roundedRect: key.visibleRect, cornerRadius: 7)
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: 1), blur: 0.5, color: KB.keyShadowU.cgColor)
        KB.keyColorU(k).setFill()
        shape.fill()
        ctx.restoreGState()
        if level > 0.001 {
            ctx.saveGState()
            ctx.setAlpha(level)
            KB.keyPressOverlayU(k).setFill()
            shape.fill()
            ctx.restoreGState()
        }
        KB.strokeU.setStroke()
        shape.lineWidth = 0.5
        shape.stroke()
        if labelAlpha > 0.01 {
            drawContent(key, in: key.visibleRect, alpha: labelAlpha)
        }
    }

    private func drawContent(_ key: GridKey, in rect: CGRect, alpha: CGFloat) {
        let accent = kind(key.cap) == .accent
        let fg = (accent ? UIColor.white : KB.keyTextU).withAlphaComponent(alpha)
        switch key.cap {
        case .ch(let s):
            drawCentered(s, font: .systemFont(ofSize: 22), color: fg, in: rect)
        case .sp(.backspace):
            drawSymbol("delete.left", size: 20, weight: .medium, color: fg, in: rect)
        case .sp(.shift):
            let name = shiftState == .locked ? "capslock.fill" : (shiftState == .on ? "shift.fill" : "shift")
            drawSymbol(name, size: 19, weight: .medium, color: fg, in: rect)
        case .sp(.ret):
            drawSymbol(returnIcon, size: 18, weight: .medium, color: fg, in: rect)
        case .sp(.emoji):
            drawSymbol("face.smiling", size: 19, weight: .medium, color: fg, in: rect)
        case .sp(.digits):
            drawCentered(languageCode == "fa" ? "۱۲۳" : "123",
                         font: .systemFont(ofSize: 16, weight: .medium), color: fg, in: rect)
        case .sp(.letters):
            drawCentered(KryptosKeyboardView.modeLabel(languageCode),
                         font: .systemFont(ofSize: 15, weight: .medium), color: fg, in: rect)
        case .sp(.zwnj):
            drawCentered("\u{0640} \u{0640}",
                         font: .systemFont(ofSize: 20, weight: .medium), color: fg, in: rect)
        case .sp(.symbols):
            drawCentered("#+=", font: .systemFont(ofSize: 15, weight: .medium), color: fg, in: rect)
        case .sp(.lang):
            drawCentered(langKeyLabel, font: .systemFont(ofSize: 15, weight: .semibold), color: fg, in: rect)
        case .sp(.space):
            drawCentered(KryptosKeyboardView.languageName(languageCode),
                         font: .systemFont(ofSize: 15, weight: .medium),
                         color: KB.textSecondaryU.withAlphaComponent(alpha), in: rect)
        }
    }

    private static let centeredParagraph: NSParagraphStyle = {
        let p = NSMutableParagraphStyle()
        p.alignment = .center
        return p
    }()

    private static var symbolCache: [String: UIImage] = [:]

    private static func symbol(_ name: String, size: CGFloat, weight: UIImage.SymbolWeight) -> UIImage? {
        let key = "\(name)|\(Int(size))|\(weight.rawValue)"
        if let cached = symbolCache[key] { return cached }
        let cfg = UIImage.SymbolConfiguration(pointSize: size, weight: weight)
        guard let img = UIImage(systemName: name, withConfiguration: cfg) else { return nil }
        symbolCache[key] = img
        return img
    }

    private func drawCentered(_ text: String, font: UIFont, color: UIColor, in rect: CGRect) {
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color,
            .paragraphStyle: KeyGridView.centeredParagraph,
        ]
        let s = NSAttributedString(string: text, attributes: attrs)
        let size = s.size()
        let y = rect.midY - size.height / 2
        s.draw(in: CGRect(x: rect.minX, y: y, width: rect.width, height: size.height))
    }

    private func drawSymbol(_ name: String, size: CGFloat, weight: UIImage.SymbolWeight, color: UIColor, in rect: CGRect) {
        guard let base = KeyGridView.symbol(name, size: size, weight: weight) else { return }
        let img = base.withTintColor(color, renderingMode: .alwaysOriginal)
        img.draw(at: CGPoint(x: rect.midX - img.size.width / 2, y: rect.midY - img.size.height / 2))
    }

    private func updatePopup() {
        guard let popup = popupChar, popup.index < keys.count, isCharCap(keys[popup.index].cap) else {
            hidePopup()
            return
        }
        let v = keys[popup.index].visibleRect
        let w = max(v.width, 34) + 8
        let h = keyH + 6
        popupView.text = popup.char
        popupView.frame = CGRect(x: v.midX - w / 2, y: v.minY - h * 0.72, width: w, height: h)
        popupView.layer.removeAllAnimations()
        popupView.alpha = 1
        popupView.isHidden = false
        popupView.setNeedsDisplay()
    }

    private func openAlternates(for touchID: Int, index: Int, options: [String]) {
        guard options.count > 1, index < keys.count else { return }
        popupChar = nil
        updatePopup()
        altOptions = options
        altSelected = 0
        altTouchID = touchID
        altView.options = options
        altView.selected = 0
        let cellW = AltPopupView.cellW
        let pad = AltPopupView.pad
        let w = CGFloat(options.count) * cellW + pad * 2
        let h = AltPopupView.cellH + pad * 2
        let anchor = keys[index].visibleRect
        var x = anchor.midX - cellW / 2 - pad
        x = min(max(x, 2), max(bounds.width - w - 2, 2))
        altOrigin = x + pad
        altView.frame = CGRect(x: x, y: anchor.minY - h - 4, width: w, height: h)
        altView.isHidden = false
        altView.setNeedsDisplay()
    }

    private func moveAlternates(to x: CGFloat) {
        guard !altOptions.isEmpty else { return }
        let idx = Int((x - altOrigin) / AltPopupView.cellW)
        let clamped = min(max(idx, 0), altOptions.count - 1)
        guard clamped != altSelected else { return }
        altSelected = clamped
        altView.selected = clamped
    }

    private func closeAlternates(commit: Bool) {
        let options = altOptions
        let chosen = altSelected
        altOptions = []
        altTouchID = nil
        altView.isHidden = true
        guard commit, chosen > 0, chosen < options.count else { return }
        onAlternate(options[chosen])
    }

    private func hidePopup() {
        guard !popupView.isHidden else { return }
        UIView.animate(withDuration: 0.10, delay: 0, options: [.curveEaseOut, .beginFromCurrentState]) {
            self.popupView.alpha = 0
        } completion: { _ in
            if self.popupChar == nil {
                self.popupView.isHidden = true
                self.popupView.alpha = 1
            }
        }
    }
}

@MainActor
private final class DisplayLinkProxy: NSObject {
    enum Mode { case labelFade, keyGlow }

    private weak var target: KeyGridView?
    private let mode: Mode

    init(target: KeyGridView, mode: Mode) {
        self.target = target
        self.mode = mode
        super.init()
    }

    @objc func step(_ link: CADisplayLink) {
        guard let target else {
            link.invalidate()
            return
        }
        switch mode {
        case .labelFade: target.stepLabelFade(link)
        case .keyGlow: target.stepKeyGlow()
        }
    }
}

private final class AltPopupView: UIView {
    var options: [String] = [] { didSet { setNeedsDisplay() } }
    var selected: Int = 0 { didSet { setNeedsDisplay() } }
    static let cellW: CGFloat = 44
    static let cellH: CGFloat = 48
    static let pad: CGFloat = 6

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func draw(_ rect: CGRect) {
        let panel = UIBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), cornerRadius: 12)
        KB.calloutFillU.setFill(); panel.fill()
        KB.strokeU.setStroke(); panel.lineWidth = 0.5; panel.stroke()
        for (i, option) in options.enumerated() {
            let cell = CGRect(x: Self.pad + CGFloat(i) * Self.cellW, y: Self.pad,
                              width: Self.cellW, height: Self.cellH)
            var color = KB.keyTextU
            if i == selected {
                let hl = UIBezierPath(roundedRect: cell.insetBy(dx: 2, dy: 2), cornerRadius: 8)
                KB.accentU.setFill(); hl.fill()
                color = .white
            }
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 24),
                .foregroundColor: color
            ]
            let text = NSAttributedString(string: option, attributes: attrs)
            let size = text.size()
            text.draw(at: CGPoint(x: cell.midX - size.width / 2, y: cell.midY - size.height / 2))
        }
    }
}

private final class KeyPopupView: UIView {
    var text: String = "" { didSet { setNeedsDisplay() } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func draw(_ rect: CGRect) {
        let path = UIBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), cornerRadius: 9)
        KB.calloutFillU.setFill(); path.fill()
        KB.strokeU.setStroke(); path.lineWidth = 0.5; path.stroke()
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 28),
            .foregroundColor: KB.keyTextU
        ]
        let s = NSAttributedString(string: text, attributes: attrs)
        let size = s.size()
        s.draw(at: CGPoint(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2))
    }
}
