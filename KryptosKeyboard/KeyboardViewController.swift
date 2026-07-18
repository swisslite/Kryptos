import UIKit
import SwiftUI
import AudioToolbox
import LocalAuthentication
import CipherCore

let kryptosTextDidChange = Notification.Name("kryptos.textDidChange")

final class KeyboardViewController: UIInputViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        SuggestionEngine.shared.warmUp()

        let backdrop = UIInputView(frame: .zero, inputViewStyle: .keyboard)
        backdrop.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(backdrop)

        let panel = KryptosKeyboardView(
            proxy: textDocumentProxy,
            hasFullAccess: hasFullAccess,
            nextKeyboard: { [weak self] in self?.advanceToNextInputMode() },
            playClick: { AudioServicesPlaySystemSound(1104) }
        )
        let host = UIHostingController(rootView: panel)
        host.view.backgroundColor = .clear
        addChild(host)
        view.addSubview(host.view)
        host.view.translatesAutoresizingMaskIntoConstraints = false

        var height: CGFloat = KeyboardConfig.compose ? 356 : 258
        if KeyboardConfig.suggestions { height += 34 }
        NSLayoutConstraint.activate([
            backdrop.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            backdrop.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            backdrop.topAnchor.constraint(equalTo: view.topAnchor),
            backdrop.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            view.heightAnchor.constraint(equalToConstant: height)
        ])
        host.didMove(toParent: self)
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        NotificationCenter.default.post(name: kryptosTextDidChange, object: nil)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        SuggestionEngine.shared.persist()
    }
}

extension KeyboardViewController: UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

enum KeyKind { case normal, special, accent }

private enum KB {
    static let keyH: CGFloat = 42
    static let gap: CGFloat = 6
    static let rowGap: CGFloat = 8

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

    static func keyColorU(_ k: KeyKind, pressed: Bool) -> UIColor {
        UIColor { t in
            let dark = t.userInterfaceStyle == .dark
            switch k {
            case .accent:
                let a = dark ? UIColor(red: 0.46, green: 0.55, blue: 1, alpha: 1) : UIColor(red: 0.22, green: 0.30, blue: 0.80, alpha: 1)
                return pressed ? a.withAlphaComponent(0.8) : a
            case .special:
                return dark ? UIColor(white: 1, alpha: pressed ? 0.20 : 0.10) : UIColor(white: 0, alpha: pressed ? 0.22 : 0.12)
            case .normal:
                return dark ? UIColor(white: 1, alpha: pressed ? 0.30 : 0.17) : (pressed ? UIColor(white: 0.80, alpha: 1) : .white)
            }
        }
    }
}

private struct DecryptedMessage { let name: String; let text: String; let date: Date }

private enum KeyLayout { case english, russian, numbers, symbols }
private enum ShiftState { case off, on, locked }
private enum Special: Hashable { case shift, backspace, space, ret, digits, letters, symbols, lang, emoji }
private enum Cap: Hashable { case ch(String); case sp(Special) }

private struct KryptosKeyboardView: View {
    let proxy: UITextDocumentProxy
    let hasFullAccess: Bool
    let nextKeyboard: () -> Void
    let playClick: () -> Void

    @State private var profiles: [Profile] = []
    @State private var store: SharedSignalStore?
    @State private var loaded = false
    @State private var selected: Contact?
    @State private var status: String?
    @State private var isError = false
    @State private var statusGen = 0

    @State private var layout: KeyLayout = .english
    @State private var letterLayout: KeyLayout = .english
    @State private var langEnEnabled = true
    @State private var langRuEnabled = false
    @State private var shift: ShiftState = .off
    @State private var lastShiftTap = Date.distantPast
    @State private var lastSpaceTap = Date.distantPast
    @State private var autoShifted = false

    @State private var haptics = true
    @State private var sounds = true
    @State private var compose = false
    @State private var autoDecrypt = true
    @State private var suggestionsOn = true
    @State private var autocorrectOn = true
    @State private var emojiOn = true

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
    @State private var returnIcon = "return"
    @State private var clipHint = false
    @State private var lastClipCount =
        UserDefaults.standard.object(forKey: "kb.clipGeneration") as? Int ?? Int.min
    @State private var decryptCache: [String: DecryptedMessage] = [:]
    private let clipTimer = Timer.publish(every: 0.6, on: .main, in: .common).autoconnect()
    @State private var draft = ""
    @State private var caret = 0
    @State private var revealed: String?
    @State private var feedback = UIImpactFeedbackGenerator(style: .soft)
    @State private var cryptoUnlocked = false
    @State private var authInFlight = false

    var body: some View {
        VStack(spacing: 6) {
            cryptoBar
            if suggestionsOn && !showEmoji { suggestionBar }
            if showEmoji { emojiPanel } else { keyboard }
        }
        .padding(.horizontal, 3)
        .padding(.top, 4)
        .padding(.bottom, 2)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .overlay(alignment: .top) { if let status { statusToast(status) } }
        .overlay { if let revealed { resultPanel(revealed) } }
        .onAppear(perform: loadOnce)
        .onReceive(clipTimer) { _ in scanClipboard() }
        .onReceive(NotificationCenter.default.publisher(for: kryptosTextDidChange)) { _ in hostTextChanged() }
    }

    private func resultPanel(_ text: String) -> some View {
        ZStack(alignment: .top) {
            Color.black.opacity(0.14).ignoresSafeArea().onTapGesture { revealed = nil }
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("Decrypted", systemImage: "lock.open.fill")
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(KB.accent)
                    Spacer()
                    Button { revealed = nil } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 21)).foregroundStyle(KB.textSecondary)
                    }
                }
                ScrollView {
                    Text(text).font(.system(size: 16)).foregroundStyle(KB.keyText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 120)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(KB.calloutFill))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(KB.stroke, lineWidth: 0.5))
            .shadow(color: .black.opacity(0.3), radius: 12, y: 4)
            .padding(.horizontal, 12)
            .padding(.top, 46)
        }
    }

    private var cryptoBar: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "lock.shield.fill").foregroundStyle(KB.accent).font(.system(size: 15, weight: .semibold))
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
                            let split = draft.index(draft.startIndex, offsetBy: safe)
                            (Text(String(draft[..<split])) + Text("▏").foregroundColor(KB.accent) + Text(String(draft[split...])))
                                .font(.system(size: 15)).foregroundStyle(KB.keyText)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    Color.clear.frame(height: 1).id("composeBottom")
                }
                .frame(height: 50)
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
            letterRussian: letterLayout == .russian,
            returnIcon: returnIcon,
            spaceMovable: true,
            onPressFeedback: { press() },
            onChar: { insertChar($0) },
            onSpecial: { performSpecial($0) },
            onBackspaceFirst: { press(); backspaceDelete() },
            onBackspaceRepeat: { backspaceDelete() },
            onSpaceTap: { press(); spaceTapped() },
            onCaretMove: { moveCursor($0) },
            onCaretMoveVertical: { moveCursorVertical($0) }
        )
        .frame(height: (KB.keyH + KB.rowGap) * 4)
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

    private func updateSuggestions() {
        guard suggestionsOn, !secureField else {
            if !suggestions.isEmpty { suggestions = [] }
            pendingFix = nil
            pendingFixTyped = nil
            suggestionsStamp = nil
            return
        }
        let ctx = wordContext()
        let stamp = "\(ctx.prefix)\u{1}\(ctx.previous ?? "\u{2}")\u{1}\(letterLayout == .russian)\u{1}\(autocorrectOn)"
        if stamp == suggestionsStamp { return }
        suggestionsStamp = stamp
        var list = SuggestionEngine.shared.suggest(prefix: ctx.prefix, previous: ctx.previous,
                                                   russian: letterLayout == .russian)
        var pending: String?
        if autocorrectOn, ctx.prefix.count >= 3 {
            pending = SuggestionEngine.shared.autocorrect(ctx.prefix, previous: ctx.previous,
                                                          russian: letterLayout == .russian, deep: false)
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
        if !secureField, word == ctx.prefix, word == pendingFixTyped {
            SuggestionEngine.shared.noteRejectedCorrection(word)
        }
        if compose {
            for _ in 0 ..< ctx.prefix.count { deleteFromDraft() }
            insertIntoDraft(word + " ")
        } else {
            for _ in 0 ..< ctx.prefix.count { proxy.deleteBackward() }
            proxy.insertText(word + " ")
        }
        if !secureField { SuggestionEngine.shared.learn(word, previous: ctx.previous) }
        if shift == .on { shift = .off; autoShifted = false }
        updateAutoShift()
        updateSuggestions()
    }

    private func learnFinishedWord() {
        guard suggestionsOn || autocorrectOn, !secureField else { return }
        let ctx = wordContext()
        if !ctx.prefix.isEmpty { SuggestionEngine.shared.learn(ctx.prefix, previous: ctx.previous) }
    }

    private func commitWordBeforeSeparator(_ separator: String) {
        lastAutoFix = nil
        guard !secureField else { return }
        if autocorrectOn {
            let ctx = wordContext()
            if !ctx.prefix.isEmpty,
               let fixed = SuggestionEngine.shared.autocorrect(ctx.prefix, previous: ctx.previous,
                                                               russian: letterLayout == .russian),
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

    private func adoptFieldTraits() {
        returnIcon = Self.returnIconName(proxy.returnKeyType)
        secureField = proxy.isSecureTextEntry ?? false
        let kt = proxy.keyboardType ?? .default
        if kt == .numberPad || kt == .decimalPad || kt == .phonePad || kt == .asciiCapableNumberPad,
           layout == .english || layout == .russian {
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

    private func hostTextChanged() {
        adoptFieldTraits()
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
                                EmojiData.addRecent(e)
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
                    Text(letterLayout == .russian ? "АБВ" : "ABC")
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
        case .numbers: return symbols(["1234567890", "-/:;()$&@\"", ".,?!'"], mode: .symbols)
        case .symbols: return symbols(["[]{}#%^*+=", "_\\|~<>€£₽•", ".,?!'"], mode: .digits)
        }
    }

    private func letters(_ a: String, _ b: String, _ c: String) -> [[Cap]] {
        let up = shift != .off
        func caps(_ s: String) -> [Cap] { s.map { .ch(up ? String($0).uppercased() : String($0)) } }
        var r3 = caps(c); r3.insert(.sp(.shift), at: 0); r3.append(.sp(.backspace))
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
        if langEnEnabled, langRuEnabled { row.append(.sp(.lang)) }
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

    private func insertChar(_ s: String) {
        if let f = s.first, !f.isLetter, !f.isNumber, !isWordChar(f) { commitWordBeforeSeparator(s) }
        else { lastAutoFix = nil }
        type(s)
        if shift == .on { shift = .off; autoShifted = false }
        updateAutoShift()
        updateSuggestions()
    }

    private func backspaceDelete() {
        if let fix = lastAutoFix {
            lastAutoFix = nil
            if Date().timeIntervalSince(fix.at) < 15, undoAutoFix(fix) {
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
        let idx = draft.index(draft.startIndex, offsetBy: caret)
        draft.insert(contentsOf: s, at: idx)
        caret += s.count
    }

    private func deleteFromDraft() {
        caret = max(0, min(caret, draft.count))
        guard caret > 0 else { return }
        let idx = draft.index(draft.startIndex, offsetBy: caret - 1)
        draft.remove(at: idx)
        caret -= 1
    }

    private func clearDraft() { draft = ""; caret = 0; updateAutoShift(); updateSuggestions() }

    private func performSpecial(_ sp: Special) {
        switch sp {
        case .shift:     shiftTapped()
        case .backspace: backspaceDelete()
        case .space:     spaceTapped()
        case .ret:
            commitWordBeforeSeparator("\n")
            type("\n")
            returnToLetters()
            updateAutoShift()
            updateSuggestions()
        case .digits:    layout = .numbers; rememberPlane()
        case .symbols:   layout = .symbols; rememberPlane()
        case .letters:   layout = letterLayout; rememberPlane(); updateAutoShift()
        case .lang:      toggleLanguage()
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
        guard langEnEnabled, langRuEnabled else { return }
        letterLayout = (letterLayout == .english) ? .russian : .english
        if layout == .english || layout == .russian { layout = letterLayout }
        UserDefaults.standard.set(letterLayout == .russian ? "ru" : "en", forKey: "kb.lang")
        updateSuggestions()
    }

    private func loadOnce() {
        guard !loaded else { return }
        haptics = KeyboardConfig.haptics
        sounds = KeyboardConfig.sounds
        compose = KeyboardConfig.compose
        autoDecrypt = KeyboardConfig.autoDecrypt
        suggestionsOn = KeyboardConfig.suggestions
        autocorrectOn = KeyboardConfig.autocorrect
        emojiOn = KeyboardConfig.emoji
        SuggestionEngine.shared.warmUp()
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
        let langs = KeyboardConfig.languages
        langEnEnabled = langs.contains("en")
        langRuEnabled = langs.contains("ru")
        var lang = UserDefaults.standard.string(forKey: "kb.lang")
            ?? (KeyboardConfig.systemPrefersRussian ? "ru" : "en")
        if lang == "ru", !langRuEnabled { lang = "en" }
        if lang != "ru", !langEnEnabled { lang = "ru" }
        letterLayout = (lang == "ru") ? .russian : .english
        layout = letterLayout
        restoreRecentPlane()
        adoptFieldTraits()
        updateAutoShift()
        updateSuggestions()
        scanClipboard()
    }

    private var cryptoLocked: Bool {
        PrivacyConfig.appLock && !cryptoUnlocked &&
            LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    private func withCryptoGate(_ action: @escaping () -> Void) {
        guard cryptoLocked else { action(); return }
        guard !authInFlight else { return }
        authInFlight = true
        let ctx = LAContext()
        Task { @MainActor in
            defer { authInFlight = false }
            let reason = String(localized: "Unlock Kryptos")
            if (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) == true {
                cryptoUnlocked = true
                action()
            } else {
                flash(String(localized: "Locked — unlock to use encryption"), error: true)
            }
        }
    }

    private func scanClipboard() {
        guard autoDecrypt, hasFullAccess, let store, revealed == nil, !cryptoLocked else { return }
        let pb = UIPasteboard.general
        clipHint = pb.hasStrings
        guard pb.changeCount != lastClipCount else { return }
        lastClipCount = pb.changeCount
        UserDefaults.standard.set(pb.changeCount, forKey: "kb.clipGeneration")
        guard !RemoteClipboard.isRemote else { return }
        guard pb.hasStrings, let clip = pb.string, !clip.isEmpty else { return }
        let stegoSized = clip.utf16.count >= 40 && clip.utf16.count <= 64_000
        guard WireFormat.isToken(clip) || (stegoSized && (TextStego.looksLikeStego(clip) || SmartTextStego.looksLikeStego(clip))) else { return }
        guard !OwnCipherMarker.matches(clip) else { return }
        reveal(clip, using: store, manual: false)
        if UIPasteboard.general.changeCount != lastClipCount { revealed = nil; status = nil }
    }

    private func reveal(_ clip: String, using store: SharedSignalStore, manual: Bool) {
        if let cached = decryptCache[clip], Date().timeIntervalSince(cached.date) < Self.cacheTTL {
            revealed = cached.text
            flash(String(localized: "Decrypted · \(cached.name)"), error: false)
            return
        }
        decryptCache[clip] = nil
        if let result = store.decryptFromAnyContact(clip) {
            cache(clip, name: result.contact.displayName, text: result.text)
            revealed = result.text
            flash(String(localized: "Decrypted · \(result.contact.displayName)"), error: false)
        } else if manual {
            flash(String(localized: "Could not decrypt — different profile/contact, or the message is damaged"), error: true)
        }
    }

    private static let cacheTTL: TimeInterval = 5 * 60

    private func cache(_ clip: String, name: String, text: String) {
        let now = Date()
        decryptCache = decryptCache.filter { now.timeIntervalSince($0.value.date) < Self.cacheTTL }
        if decryptCache.count >= 40 { decryptCache.removeAll() }
        decryptCache[clip] = DecryptedMessage(name: name, text: text, date: now)
    }

    private func select(profile: Profile, remember: Bool = false) {
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
        guard let selected else { return flash(String(localized: "Choose a contact first"), error: true) }
        let text = compose ? draft : fullText()
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return flash(String(localized: "Enter some text."), error: true) }
        do {
            let cipher = try store.encrypt(text, to: selected.fingerprint)
            if compose { proxy.insertText(cipher); clearDraft() } else { replaceAll(with: cipher) }
            flash(String(localized: "Encrypted for \(selected.displayName)"), error: false)
        } catch {
            flash(String(localized: "Could not encrypt"), error: true)
        }
    }

    private func decrypt(_ store: SharedSignalStore) {
        let field = fullText()
        let clip = UIPasteboard.general.string ?? ""
        let sources = [field, clip].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard !sources.isEmpty else {
            return flash(String(localized: "No message — paste it into the field or copy it"), error: true)
        }
        for src in sources {
            if let cached = decryptCache[src] {
                revealed = cached.text
                return flash(String(localized: "Decrypted · \(cached.name)"), error: false)
            }
        }
        for src in sources {
            guard let result = store.decryptFromAnyContact(src) else { continue }
            cache(src, name: result.contact.displayName, text: result.text)
            revealed = result.text
            return flash(String(localized: "Decrypted · \(result.contact.displayName)"), error: false)
        }
        flash(String(localized: "Could not decrypt — check the profile and contact"), error: true)
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

    private func replaceAll(with newText: String) {
        let before = proxy.documentContextBeforeInput ?? ""
        let after = proxy.documentContextAfterInput ?? ""
        proxy.adjustTextPosition(byCharacterOffset: after.count)
        for _ in 0 ..< (before.count + after.count) { proxy.deleteBackward() }
        proxy.insertText(newText)
    }
}

private struct KeyGridRepresentable: UIViewRepresentable {
    let rows: [[Cap]]
    let shiftState: ShiftState
    let letterRussian: Bool
    let returnIcon: String
    let spaceMovable: Bool
    let onPressFeedback: () -> Void
    let onChar: (String) -> Void
    let onSpecial: (Special) -> Void
    let onBackspaceFirst: () -> Void
    let onBackspaceRepeat: () -> Void
    let onSpaceTap: () -> Void
    let onCaretMove: (Int) -> Void
    let onCaretMoveVertical: (Int) -> Void

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
        v.configure(rows: rows, shiftState: shiftState, letterRussian: letterRussian,
                    returnIcon: returnIcon, spaceMovable: spaceMovable)
    }
}

private struct GridKey {
    let rect: CGRect
    let visibleRect: CGRect
    let cap: Cap
}

private final class KeyTouch {
    enum Origin { case char, backspace, space, special }
    let origin: Origin
    var cellIndex: Int
    var startTime = Date()
    var startX: CGFloat = 0
    var lastX: CGFloat = 0
    var startY: CGFloat = 0
    var lastY: CGFloat = 0
    var moved = false
    var initialTimer: Timer?
    var repeatTimer: Timer?
    init(origin: Origin, cellIndex: Int) { self.origin = origin; self.cellIndex = cellIndex }
    func stopTimers() { initialTimer?.invalidate(); initialTimer = nil; repeatTimer?.invalidate(); repeatTimer = nil }
}

private final class KeyGridView: UIView {
    private var rows: [[Cap]] = []
    private var shiftState: ShiftState = .off
    private var letterRussian = true
    private var returnIcon = "return"
    var spaceMovable = true
    private var keys: [GridKey] = []

    private let touchYBias: CGFloat = 5

    private var active: [UITouch: KeyTouch] = [:]
    private var pressedCells: Set<Int> = []
    private var popupChar: (index: Int, char: String)?
    private let popupView = KeyPopupView()

    private var trackpadActive = false
    private var labelAlpha: CGFloat = 1
    private var labelFadeTarget: CGFloat = 1
    private var labelFadeLink: CADisplayLink?

    deinit { labelFadeLink?.invalidate() }

    private func setTrackpad(_ on: Bool) {
        guard trackpadActive != on else { return }
        trackpadActive = on
        labelFadeTarget = on ? 0 : 1
        labelFadeLink?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(stepLabelFade(_:)))
        link.add(to: .main, forMode: .common)
        labelFadeLink = link
    }

    @objc private func stepLabelFade(_ link: CADisplayLink) {
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
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (view: KeyGridView, _) in
            view.setNeedsDisplay()
            view.popupView.setNeedsDisplay()
        }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(rows: [[Cap]], shiftState: ShiftState, letterRussian: Bool, returnIcon: String, spaceMovable: Bool) {
        self.spaceMovable = spaceMovable
        let geometryChanged = (rows != self.rows)
        self.rows = rows
        self.shiftState = shiftState
        self.letterRussian = letterRussian
        self.returnIcon = returnIcon
        if geometryChanged {
            pressedCells.removeAll()
            popupChar = nil
        }
        rebuildGeometry()
        updatePopup()
        setNeedsDisplay()
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
        let rowH = KB.keyH + KB.rowGap
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
                var visible = cell.insetBy(dx: KB.gap / 2, dy: KB.rowGap / 2)
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
            let info: KeyTouch
            switch cap {
            case .ch(let s):
                info = KeyTouch(origin: .char, cellIndex: idx)
                onPressFeedback()
                onChar(s)
                popupChar = (idx, s)
            case .sp(.backspace):
                info = KeyTouch(origin: .backspace, cellIndex: idx)
                onBackspaceFirst()
                startBackspaceRepeat(info)
            case .sp(.space):
                info = KeyTouch(origin: .space, cellIndex: idx)
                info.startTime = Date()
                info.startX = p.x; info.lastX = p.x
                info.startY = p.y; info.lastY = p.y
                if spaceMovable {
                    info.initialTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self, weak info] _ in
                        guard let self, let info, !info.moved, self.active.values.contains(where: { $0 === info }) else { return }
                        info.moved = true
                        self.onPressFeedback()
                        self.setTrackpad(true)
                    }
                }
            default:
                info = KeyTouch(origin: .special, cellIndex: idx)
                onPressFeedback()
            }
            pressedCells.insert(idx)
            active[t] = info
        }
        updatePopup()
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        var changed = false
        for t in touches {
            guard let info = active[t] else { continue }
            let p = t.location(in: self)
            switch info.origin {
            case .char:
                break
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
        guard let info = active[t] else { return }
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
        pressedCells.remove(info.cellIndex)
        active[t] = nil
        if info.origin == .space, !active.values.contains(where: { $0.origin == .space }) {
            setTrackpad(false)
        }
        if popupChar?.index == info.cellIndex,
           !active.values.contains(where: { $0.origin == .char && $0.cellIndex == info.cellIndex }) {
            popupChar = nil
        }
    }

    private func startBackspaceRepeat(_ info: KeyTouch) {
        info.stopTimers()
        info.initialTimer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: false) { [weak self, weak info] _ in
            guard let self, let info else { return }
            info.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.09, repeats: true) { [weak self] _ in
                self?.onBackspaceRepeat()
            }
        }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        for (i, key) in keys.enumerated() {
            drawKey(key, pressed: pressedCells.contains(i) && !trackpadActive, ctx: ctx)
        }
    }

    private func drawKey(_ key: GridKey, pressed: Bool, ctx: CGContext) {
        let k = kind(key.cap)
        let shape = UIBezierPath(roundedRect: key.visibleRect, cornerRadius: 7)
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: 1), blur: 0.5, color: KB.keyShadowU.cgColor)
        KB.keyColorU(k, pressed: pressed).setFill()
        shape.fill()
        ctx.restoreGState()
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
            drawCentered("123", font: .systemFont(ofSize: 16, weight: .medium), color: fg, in: rect)
        case .sp(.letters):
            drawCentered(letterRussian ? "АБВ" : "ABC", font: .systemFont(ofSize: 15, weight: .medium), color: fg, in: rect)
        case .sp(.symbols):
            drawCentered("#+=", font: .systemFont(ofSize: 15, weight: .medium), color: fg, in: rect)
        case .sp(.lang):
            drawCentered(letterRussian ? "EN" : "РУ", font: .systemFont(ofSize: 15, weight: .semibold), color: fg, in: rect)
        case .sp(.space):
            drawCentered(letterRussian ? "Русский" : "English",
                         font: .systemFont(ofSize: 15, weight: .medium),
                         color: KB.textSecondaryU.withAlphaComponent(alpha), in: rect)
        }
    }

    private func drawCentered(_ text: String, font: UIFont, color: UIColor, in rect: CGRect) {
        let para = NSMutableParagraphStyle(); para.alignment = .center
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .paragraphStyle: para]
        let s = NSAttributedString(string: text, attributes: attrs)
        let size = s.size()
        let y = rect.midY - size.height / 2
        s.draw(in: CGRect(x: rect.minX, y: y, width: rect.width, height: size.height))
    }

    private func drawSymbol(_ name: String, size: CGFloat, weight: UIImage.SymbolWeight, color: UIColor, in rect: CGRect) {
        let cfg = UIImage.SymbolConfiguration(pointSize: size, weight: weight)
        guard let img = UIImage(systemName: name, withConfiguration: cfg)?.withTintColor(color, renderingMode: .alwaysOriginal) else { return }
        img.draw(at: CGPoint(x: rect.midX - img.size.width / 2, y: rect.midY - img.size.height / 2))
    }

    private func updatePopup() {
        guard let popup = popupChar, popup.index < keys.count, isCharCap(keys[popup.index].cap) else {
            hidePopup()
            return
        }
        let v = keys[popup.index].visibleRect
        let w = max(v.width, 34) + 8
        let h = KB.keyH + 6
        popupView.text = popup.char
        popupView.frame = CGRect(x: v.midX - w / 2, y: v.minY - h * 0.72, width: w, height: h)
        popupView.layer.removeAllAnimations()
        popupView.alpha = 1
        popupView.isHidden = false
        popupView.setNeedsDisplay()
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
