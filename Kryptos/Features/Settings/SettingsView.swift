import SwiftUI
import UniformTypeIdentifiers
import CipherCore

struct SettingsView: View {
    @State private var confirmReset = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink { InterfaceView() } label: {
                        SettingsLabel("Interface", icon: "paintbrush.fill", color: KTheme.accent)
                    }
                    NavigationLink { PrivacySettingsView() } label: {
                        SettingsLabel("Privacy", icon: "hand.raised.fill", color: KTheme.accent)
                    }
                    NavigationLink { StegoSettingsView() } label: {
                        SettingsLabel("Steganography", icon: "text.word.spacing", color: KTheme.accent)
                    }
                    NavigationLink { KeyboardSettingsView() } label: {
                        SettingsLabel("Keyboard", icon: "keyboard.fill", color: KTheme.accent)
                    }
                }

                Section {
                    NavigationLink { KeyBackupView() } label: {
                        SettingsLabel("Key backup", icon: "arrow.up.arrow.down", color: KTheme.accent)
                    }
                }

                Section {
                    NavigationLink { HowToView() } label: {
                        SettingsLabel("How to use", icon: "book.fill", color: Color(.systemGray))
                    }
                    NavigationLink { FAQView() } label: {
                        SettingsLabel("Questions and answers", icon: "questionmark.circle.fill", color: Color(.systemGray))
                    }
                    NavigationLink { AboutView() } label: {
                        SettingsLabel("About", icon: "info.circle.fill", color: Color(.systemGray))
                    }
                    NavigationLink { DeveloperView() } label: {
                        SettingsLabel("Developer", icon: "person.crop.circle.fill", color: Color(.systemGray))
                    }
                }

                Section {
                    Button(role: .destructive) { confirmReset = true } label: {
                        SettingsLabel("Erase everything", icon: "trash.fill", color: .red, textColor: .red)
                    }
                    .confirmationDialog("Erase ALL data? Your identities and private keys, contacts' keys, every conversation, PGP keys and all settings will be destroyed and the app will close. This cannot be undone.",
                                        isPresented: $confirmReset, titleVisibility: .visible) {
                        Button("Erase everything", role: .destructive) { AppReset.eraseEverythingAndQuit() }
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

struct SettingsLabel: View {
    let title: LocalizedStringKey
    let icon: String
    let color: Color
    var textColor: Color?

    init(_ title: LocalizedStringKey, icon: String, color: Color, textColor: Color? = nil) {
        self.title = title; self.icon = icon; self.color = color; self.textColor = textColor
    }

    var body: some View {
        Label {
            Text(title).foregroundStyle(textColor ?? .primary)
        } icon: {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 29, height: 29)
                .background(RoundedRectangle(cornerRadius: 6.5, style: .continuous).fill(color))
        }
    }
}

private struct PrivacySettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var panicSet = false
    @State private var appCodeSet = false
    private let lockAvailable = LockGate.canAuthenticate

    var body: some View {
        List {
            Section {
                Toggle("Lock with Face ID", isOn: $settings.appLock).disabled(!lockAvailable)
                Toggle("Hide content when switching apps", isOn: $settings.privacyShield)
            } header: {
                Text("Security")
            } footer: {
                if !lockAvailable {
                    Text("To use the lock, set a passcode on your device.")
                }
            }

            Section {
                NavigationLink {
                    LockCodeView(code: LockCodes.app, other: LockCodes.panic, isSet: $appCodeSet,
                                 title: "App passcode",
                                 explanation: "An ordinary passcode that opens Kryptos when you do not want to use biometrics. Optional.",
                                 newLabel: "New passcode", repeatLabel: "Repeat passcode",
                                 saveLabel: "Save passcode", setFooter: "At least 4 characters. It must be different from the panic password.",
                                 removeLabel: "Remove app passcode",
                                 removeConfirm: "Remove the app passcode? You will still be able to unlock with biometrics or your device passcode.",
                                 savedMessage: "App passcode saved.", removedMessage: "App passcode removed.")
                } label: {
                    HStack {
                        Text("App passcode")
                        Spacer()
                        Text(appCodeSet ? "Set" : "Not set").foregroundStyle(.secondary)
                    }
                }
                NavigationLink {
                    LockCodeView(code: LockCodes.panic, other: LockCodes.app, isSet: $panicSet,
                                 title: "Panic password",
                                 explanation: "Type it on the lock screen and Kryptos immediately and permanently erases everything, then opens empty like a fresh install.",
                                 newLabel: "New password", repeatLabel: "Repeat password",
                                 saveLabel: "Save password", setFooter: "At least 4 characters. Pick something you would never type by accident.",
                                 removeLabel: "Remove panic password",
                                 removeConfirm: "Remove the panic password? Typing it will no longer erase anything.",
                                 savedMessage: "Panic password saved.", removedMessage: "Panic password removed.")
                } label: {
                    HStack {
                        Text("Panic password")
                        Spacer()
                        Text(panicSet ? "Set" : "Not set").foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("Lock screen codes")
            } footer: {
                Text("The app passcode opens Kryptos; the panic password erases everything.")
            }

            Section {
                Toggle("Auto-decrypt copied messages", isOn: $settings.clipboardAutoDecrypt)
                Toggle("This device only", isOn: $settings.clipboardLocalOnly)
                Picker("Auto-clear clipboard", selection: $settings.clipboardExpiry) {
                    Text("Off").tag(0.0)
                    Text("30 s").tag(30.0)
                    Text("1 min").tag(60.0)
                    Text("5 min").tag(300.0)
                }
            } header: {
                Text("Clipboard")
            } footer: {
                Text("“This device only” keeps copied text off your other Apple devices. Auto-clear erases it after the chosen time.")
            }

            Section {
                Toggle("Mask message length", isOn: $settings.lengthPadding)
            } header: {
                Text("Metadata")
            } footer: {
                Text("Pads Chats and Password to fixed sizes. Not applied to photos or PGP.")
            }
        }
        .navigationTitle("Privacy")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            panicSet = LockCodes.panic.isSet
            appCodeSet = LockCodes.app.isSet
        }
    }
}

private struct LockCodeView: View {
    let code: LockCode
    let other: LockCode
    @Binding var isSet: Bool
    let title: LocalizedStringKey
    let explanation: LocalizedStringKey
    let newLabel: LocalizedStringKey
    let repeatLabel: LocalizedStringKey
    let saveLabel: LocalizedStringKey
    let setFooter: LocalizedStringKey
    let removeLabel: LocalizedStringKey
    let removeConfirm: LocalizedStringKey
    let savedMessage: LocalizedStringKey
    let removedMessage: LocalizedStringKey

    @EnvironmentObject private var settings: AppSettings
    @State private var entry = ""
    @State private var confirm = ""
    @State private var message: LocalizedStringKey?
    @State private var failed = false
    @State private var busy = false
    @State private var confirmRemove = false
    private let lockAvailable = LockGate.canAuthenticate

    var body: some View {
        List {
            Section {
                HStack {
                    Text(title)
                    Spacer()
                    Text(isSet ? "Set" : "Not set").foregroundStyle(.secondary)
                }
            } footer: {
                Text(explanation)
            }

            if !settings.appLock || !lockAvailable {
                Section {
                    if lockAvailable, !settings.appLock {
                        Button("Turn on app lock") { settings.appLock = true }
                    }
                } header: {
                    if !lockAvailable { Text("Not active right now") }
                } footer: {
                    Text(lockAvailable
                         ? "Turn on the app lock so the codes can be used."
                         : "This device has no passcode, so Kryptos never shows its lock screen.")
                }
            }

            Section {
                SecureField(newLabel, text: $entry)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField(repeatLabel, text: $confirm)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button(busy ? "Working…" : saveLabel) { save() }
                    .disabled(busy || entry.isEmpty || confirm.isEmpty)
                if let message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(failed ? KTheme.danger : .secondary)
                }
            } header: {
                Text("Set a password")
            } footer: {
                Text(setFooter)
            }

            if isSet {
                Section {
                    Button(removeLabel, role: .destructive) { confirmRemove = true }
                        .disabled(busy)
                        .confirmationDialog(removeConfirm, isPresented: $confirmRemove, titleVisibility: .visible) {
                            Button(removeLabel, role: .destructive) { remove() }
                        }
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func save() {
        message = nil
        guard entry == confirm else {
            failed = true
            entry = ""
            confirm = ""
            message = "The passwords do not match."
            return
        }
        let typed = entry
        busy = true
        Task { @MainActor in
            let result = await LockCodes.storeOffMain(typed, into: code, other: other)
            busy = false
            failed = result != .ok
            entry = ""
            confirm = ""
            switch result {
            case .ok:
                isSet = true
                message = savedMessage
            case .tooShort:
                message = "Use at least 4 characters."
            case .duplicate:
                message = "This is already used as the other code. Choose a different one."
            case .failed:
                message = "Could not save the code."
            }
        }
    }

    private func remove() {
        code.clear()
        isSet = false
        entry = ""
        confirm = ""
        failed = false
        message = removedMessage
    }
}

private struct StegoSettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var sample = ""

    private var sampleKey: String {
        "\(settings.effectiveLanguage.rawValue)-\(settings.chatStegoMode.rawValue)"
    }

    var body: some View {
        List {
            Section {
                Toggle("Steganography for Chats", isOn: $settings.chatStegoEnabled)
                if settings.chatStegoEnabled {
                    Picker("Cover language", selection: $settings.chatStegoLanguage) {
                        ForEach(AppSettings.LanguageChoice.allCases) { Text($0.title).tag($0) }
                    }
                    Picker("Mode", selection: $settings.chatStegoMode) {
                        ForEach(StegoMode.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.menu)
                }
            } footer: {
                Text("Signal messages are wrapped in ordinary text — Mode decides how that text looks.")
            }

            if settings.chatStegoEnabled {
                Section {
                    Text(sample)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(.primary)
                } header: {
                    Text("Example output")
                } footer: {
                    Text(exampleFooter)
                }
            }
        }
        .navigationTitle("Steganography")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: sampleKey) {
            let language = settings.effectiveLanguage
            let mode = settings.chatStegoMode
            sample = await Task.detached(priority: .userInitiated) {
                Self.sampleStego(language: language, mode: mode)
            }.value
        }
    }

    private var exampleFooter: LocalizedStringKey {
        switch settings.chatStegoMode {
        case .words:
            return "This is how your messages look. The first message to a new contact is longer."
        case .smart:
            return "Real sentences instead of a word salad, at the cost of a longer text."
        case .letters:
            return "The shortest mode — about half the length of Simple words."
        }
    }

    nonisolated private static func sampleStego(language: StegoLanguage, mode: StegoMode) -> String {
        let sample = Data([0x03, 0x02, 0x41, 0x9c, 0x2a, 0xf7, 0x10, 0x88, 0x3d, 0x6b, 0xe0, 0x54])
        switch mode {
        case .words: return TextStego.encode(sample, language: language)
        case .smart: return SmartTextStego.encode(sample, language: language)
        case .letters: return LetterStego.encode(sample, language: language)
        }
    }
}

private struct KeyboardSettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var confirmForget = false

    var body: some View {
        List {
            Section {
                Toggle("Auto-decrypt on open", isOn: $settings.keyboardAutoDecrypt)
            } footer: {
                Text("The keyboard reveals an encrypted message from the clipboard as it opens.")
            }

            Section {
                NavigationLink {
                    KeyboardLanguagesView()
                } label: {
                    HStack {
                        Text("Languages")
                        Spacer()
                        Text(languagesSummary)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }

            Section {
                Toggle("Word suggestions", isOn: $settings.keyboardSuggestions)
                Toggle("Auto-correction", isOn: $settings.keyboardAutocorrect)
                Toggle("Emoji key", isOn: $settings.keyboardEmoji)
                if settings.keyboardSuggestions || settings.keyboardAutocorrect {
                    Button("Forget learned words", role: .destructive) { confirmForget = true }
                        .confirmationDialog("Forget the words the keyboard has learned from your typing? The built-in dictionaries stay.",
                                            isPresented: $confirmForget, titleVisibility: .visible) {
                            Button("Forget learned words", role: .destructive) { SharedStore.delete("kbdict") }
                        }
                }
            } header: {
                Text("Typing")
            } footer: {
                Text("Completions and auto-correction from offline dictionaries. Off in password fields.")
            }

            Section {
                Toggle("Key vibration", isOn: $settings.keyboardHaptics)
                Toggle("Key sounds", isOn: $settings.keyboardSounds)
            } header: {
                Text("Feedback")
            } footer: {
                Text("Soft vibration and the native click on each key press.")
            }

            Section {
                Toggle("Message field in the keyboard", isOn: $settings.keyboardCompose)
                Toggle("Field button on the keyboard", isOn: $settings.keyboardComposeToggle)
            } footer: {
                Text("The field lets you type outside the messenger, so it cannot keep a draft. The button toggles that field from the keyboard.")
            }
        }
        .navigationTitle("Keyboard")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var languagesSummary: String {
        keyboardLanguageCatalog
            .filter { settings.keyboardLanguages.contains($0.code) }
            .map { String(localized: $0.title) }
            .joined(separator: ", ")
    }
}

private let keyboardLanguageCatalog: [(code: String, title: String.LocalizationValue)] = [
    ("en", "English"),
    ("ru", "Russian"),
    ("de", "German")
]

private struct KeyboardLanguagesView: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        List {
            Section {
                ForEach(keyboardLanguageCatalog, id: \.code) { lang in
                    Toggle(String(localized: lang.title), isOn: binding(for: lang.code))
                        .disabled(settings.keyboardLanguages == [lang.code])
                }
            } footer: {
                Text("The language key switches layouts. At least one language always stays on.")
            }
        }
        .navigationTitle("Languages")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func binding(for code: String) -> Binding<Bool> {
        Binding(
            get: { settings.keyboardLanguages.contains(code) },
            set: { on in
                var langs = settings.keyboardLanguages
                if on { langs.append(code) } else { langs.removeAll { $0 == code } }
                settings.keyboardLanguages = langs
            }
        )
    }
}

private struct KeyBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

private struct KeyBackupView: View {
    static let maxArchiveBytes = 8 * 1024 * 1024

    @EnvironmentObject private var signal: SignalService
    @EnvironmentObject private var pgp: PGPService
    @EnvironmentObject private var lock: LockGate

    @State private var exportPassword = ""
    @State private var exportConfirm = ""
    @State private var document: KeyBackupDocument?
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var pending: KeyArchive?
    @State private var importPassword = ""
    @State private var importText: String?
    @State private var confirmImport = false
    @State private var busy = false
    @State private var exportMessage: String?
    @State private var exportFailed = false
    @State private var importMessage: String?
    @State private var importFailed = false

    var body: some View {
        List {
            Section {
                Text("The file holds your keys, encrypted with the password you choose here. Kryptos keeps no copy of it.")
            } footer: {
                Text("Your message history is not included.")
            }

            Section {
                SecureField("Backup password", text: $exportPassword)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Repeat password", text: $exportConfirm)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button(busy ? "Working…" : "Create backup file") { export() }
                    .disabled(busy || exportPassword.isEmpty || exportConfirm.isEmpty)
                if let exportMessage {
                    Text(exportMessage)
                        .font(.footnote)
                        .foregroundStyle(exportFailed ? KTheme.danger : .secondary)
                }
            } header: {
                Text("Export")
            } footer: {
                Text("At least 8 characters. Keep the file and the password apart.")
            }

            Section {
                Button("Choose a backup file") { showImporter = true }
                    .disabled(busy)
                if let pending {
                    Text(summary(for: pending)).font(.footnote).foregroundStyle(.secondary)
                }
                if importText != nil {
                    SecureField("Backup password", text: $importPassword)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button(busy ? "Working…" : "Restore keys") { unlockArchive() }
                        .disabled(busy || importPassword.isEmpty)
                        .confirmationDialog("Replace the keys on this device with the ones from the backup? The keys currently here will be gone.",
                                            isPresented: $confirmImport, titleVisibility: .visible) {
                            Button("Restore keys", role: .destructive) { applyArchive() }
                        }
                }
                if let importMessage {
                    Text(importMessage)
                        .font(.footnote)
                        .foregroundStyle(importFailed ? KTheme.danger : .secondary)
                }
            } header: {
                Text("Import")
            } footer: {
                Text("Restoring replaces the keys currently on this device.")
            }

        }
        .navigationTitle("Key backup")
        .navigationBarTitleDisplayMode(.inline)
        .fileExporter(isPresented: $showExporter, document: document,
                      contentType: .plainText, defaultFilename: "kryptos-keys") { result in
            document = nil
            switch result {
            case .success:
                exportPassword = ""
                exportConfirm = ""
                exportFailed = false
                exportMessage = String(localized: "Backup file created.")
            case .failure(let error):
                if (error as NSError).code != NSUserCancelledError {
                    failExport(String(localized: "Could not create the backup file."))
                }
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.plainText, .text, .data]) { result in
            loadFile(result)
        }
        .onChange(of: lock.isLocked) { _, locked in
            guard locked else { return }
            showExporter = false
            showImporter = false
            confirmImport = false
            document = nil
            pending = nil
            importText = nil
            exportPassword = ""
            exportConfirm = ""
            importPassword = ""
        }
    }

    private func summary(for archive: KeyArchive) -> String {
        String(localized: "\(archive.profiles.count) identities, \(archive.contactCount) contacts, \(archive.pgpIdentities.count) PGP keys")
    }

    private func export() {
        exportMessage = nil
        guard exportPassword == exportConfirm else {
            failExport(String(localized: "The passwords do not match."))
            return
        }
        let secret = exportPassword
        busy = true
        Task { @MainActor in
            await Task.yield()
            guard let profiles = signal.archivedProfiles(), let pgpKeys = pgp.archivedIdentities() else {
                busy = false
                failExport(String(localized: "Could not create the backup file."))
                return
            }
            let archive = KeyArchive.make(profiles: profiles,
                                          pgpIdentities: pgpKeys,
                                          pgpRecipients: pgp.archivedRecipients())
            let outcome = await Task.detached(priority: .userInitiated) { () -> Result<String, Error> in
                do { return .success(try archive.sealed(password: secret)) } catch { return .failure(error) }
            }.value
            busy = false
            switch outcome {
            case .success(let text):
                document = KeyBackupDocument(text: text)
                showExporter = true
            case .failure(let error):
                failExport((error as? LocalizedError)?.errorDescription ?? String(localized: "Could not create the backup file."))
            }
        }
    }

    private func loadFile(_ result: Result<URL, Error>) {
        importMessage = nil
        pending = nil
        importPassword = ""
        guard case .success(let url) = result else { return }
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0, size <= KeyBackupView.maxArchiveBytes,
              let data = try? Data(contentsOf: url), data.count <= KeyBackupView.maxArchiveBytes,
              let text = String(data: data, encoding: .utf8) else {
            failImport(String(localized: "Could not read the file."))
            return
        }
        importText = text
    }

    private func unlockArchive() {
        importMessage = nil
        guard let text = importText else { return }
        let secret = importPassword
        busy = true
        Task { @MainActor in
            let outcome = await Task.detached(priority: .userInitiated) { () -> Result<KeyArchive, Error> in
                do { return .success(try KeyArchive.opened(text, password: secret)) } catch { return .failure(error) }
            }.value
            busy = false
            switch outcome {
            case .success(let archive):
                pending = archive
                confirmImport = true
            case .failure(let error):
                pending = nil
                failImport((error as? LocalizedError)?.errorDescription ?? String(localized: "Could not read this backup — wrong password, or the file is not a Kryptos key backup."))
            }
        }
    }

    private func applyArchive() {
        guard let archive = pending else { return }
        busy = true
        Task { @MainActor in
            await Task.yield()
            let signalOK = archive.profiles.isEmpty || signal.restoreProfiles(archive.profiles)
            let pgpRestored = pgp.restore(identities: archive.pgpIdentities, recipients: archive.pgpRecipients)
            let pgpOK = archive.pgpIdentities.isEmpty || pgpRestored
            pending = nil
            importText = nil
            importPassword = ""
            busy = false
            if signalOK, pgpOK {
                importFailed = false
                importMessage = String(localized: "Keys restored.")
            } else {
                failImport(String(localized: "Could not restore the keys from this backup."))
            }
        }
    }

    private func failExport(_ text: String) {
        exportFailed = true
        exportMessage = text
    }

    private func failImport(_ text: String) {
        importFailed = true
        importMessage = text
    }
}

private struct AboutView: View {
    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(v) (\(b))"
    }

    var body: some View {
        List {
            Section {
                header.listRowBackground(Color.clear)
            }

            Section {
                Text("Kryptos is a private-communication tool that works through any messenger. You write a message, Kryptos encrypts it on your device, and you send the result through WhatsApp, Telegram, SMS — anything. Only your contact's Kryptos can read it; the messenger only ever sees ciphertext.")
            } footer: {
                Text("Fully offline: no servers, no accounts, no analytics. Keys never leave the device.")
            }

            Section {
                infoRow("checkmark.shield.fill", "Signal Protocol — official libsignal",
                        "The very library the Signal app uses: post-quantum key agreement (PQXDH with Kyber) and the Double Ratchet — a fresh key for every message.")
                infoRow("envelope.fill", "OpenPGP — ObjectivePGP",
                        "Classic asymmetric encryption with signatures, compatible with other PGP tools.")
                infoRow("key.fill", "Password mode — Argon2id",
                        "A shared passphrase: Argon2id key derivation, then AES-256-GCM from Apple CryptoKit.")
                infoRow("photo.fill", "Steganography",
                        "Hides the already-encrypted message inside an ordinary photo or a run of everyday words.")
            } header: {
                Text("Under the hood")
            }

            Section {
                Link(destination: URL(string: "https://github.com/swisslite/Kryptos")!) {
                    LinkRow(icon: "chevron.left.forwardslash.chevron.right", title: "GitHub", value: "swisslite/Kryptos")
                }
            } header: {
                Text("Source code")
            } footer: {
                Text("The source is public — read it or build the app yourself.")
            }

            Section {
                Link(destination: URL(string: "https://datakeeper.pages.dev/kryptos")!) {
                    LinkRow(icon: "globe", title: "Kryptos", value: "datakeeper.pages.dev/kryptos")
                }
            } header: {
                Text("Website")
            }
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var header: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(KTheme.accentGradient)
                    .frame(width: 78, height: 78)
                    .shadow(color: KTheme.accent.opacity(0.35), radius: 12, y: 5)
                Image(systemName: "lock.fill")
                    .font(.system(size: 36, weight: .semibold)).foregroundStyle(.white)
            }
            VStack(spacing: 3) {
                Text("Kryptos").font(.title2.weight(.bold))
                Text("Encrypt anywhere. Talk over any channel.")
                    .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Text("Version \(version)")
                    .font(.footnote).foregroundStyle(.tertiary).padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
    }

    private func infoRow(_ icon: String, _ title: LocalizedStringKey, _ subtitle: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(KTheme.accent)
                .frame(width: 28)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.body.weight(.medium))
                Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 4)
    }

}

private struct LinkRow: View {
    let icon: String
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(KTheme.accent)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.body).foregroundStyle(.primary)
            }
            Spacer(minLength: 0)
            Image(systemName: "arrow.up.right").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

private struct DeveloperView: View {
    var body: some View {
        List {
            Section {
                Link(destination: URL(string: "https://datakeeper.pages.dev")!) {
                    LinkRow(icon: "globe", title: "Website", value: "datakeeper.pages.dev")
                }
                Link(destination: URL(string: "mailto:datakeepers@proton.me")!) {
                    LinkRow(icon: "envelope.fill", title: "Email", value: "datakeepers@proton.me")
                }
                Link(destination: URL(string: "https://t.me/datakeeper")!) {
                    LinkRow(icon: "paperplane.fill", title: "Telegram", value: "@datakeeper")
                }
                Link(destination: URL(string: "https://t.me/KryptosApp")!) {
                    LinkRow(icon: "megaphone.fill", title: "Telegram channel", value: "@KryptosApp")
                }
                Link(destination: URL(string: "https://github.com/swisslite")!) {
                    LinkRow(icon: "chevron.left.forwardslash.chevron.right",
                            title: "GitHub profile", value: "@swisslite")
                }
            } footer: {
                Text("Questions, ideas or a bug? I'd be glad to hear from you.")
            }
        }
        .navigationTitle("Developer")
        .navigationBarTitleDisplayMode(.inline)
    }
}
