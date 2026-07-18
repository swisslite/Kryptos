import SwiftUI
import CipherCore

struct SettingsView: View {
    @State private var confirmReset = false

    var body: some View {
        NavigationStack {
            List {
                Section {
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
                    NavigationLink { AboutView() } label: {
                        SettingsLabel("About", icon: "info.circle.fill", color: Color(.systemGray))
                    }
                }

                Section {
                    Button(role: .destructive) { confirmReset = true } label: {
                        SettingsLabel("Erase everything", icon: "trash.fill", color: .red, textColor: .red)
                    }
                } footer: {
                    Text("Permanently erases all app data — your keys, contacts' keys, conversations and settings.")
                }
            }
            .navigationTitle("Settings")
            .confirmationDialog("Erase ALL data? Your identities and private keys, contacts' keys, every conversation, PGP keys and all settings will be destroyed and the app will close. This cannot be undone.",
                                isPresented: $confirmReset, titleVisibility: .visible) {
                Button("Erase everything", role: .destructive) { AppReset.eraseEverythingAndQuit() }
            }
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
    private let lockAvailable = LockGate.canAuthenticate

    var body: some View {
        List {
            Section {
                Toggle("Lock with Face ID", isOn: $settings.appLock).disabled(!lockAvailable)
                Toggle("Hide content when switching apps", isOn: $settings.privacyShield)
            } header: {
                Text("Security")
            } footer: {
                Text(lockAvailable
                     ? "Face ID or the device passcode is required to open Kryptos. Outside the app the screen is covered, so the app-switcher snapshot never shows your chats."
                     : "To use the lock, set a passcode on your device. Outside the app the screen is covered, so the app-switcher snapshot never shows your chats.")
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
                Text("Auto-decrypt: copy an encrypted message, open Kryptos, and it is shown at once. “This device only” keeps copied text off Universal Clipboard. Auto-clear erases whatever Kryptos copied after the chosen time.")
            }

            Section {
                Toggle("Mask message length", isOn: $settings.lengthPadding)
            } header: {
                Text("Metadata")
            } footer: {
                Text("Pads the ciphertext to a fixed set of sizes so its length no longer hints at how long your message is. Makes the ciphertext somewhat larger.")
            }
        }
        .navigationTitle("Privacy")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct StegoSettingsView: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        List {
            Section {
                Toggle("Steganography for Chats", isOn: $settings.chatStegoEnabled)
                if settings.chatStegoEnabled {
                    Picker("Cover language", selection: $settings.chatStegoLanguage) {
                        ForEach(AppSettings.LanguageChoice.allCases) { Text($0.title).tag($0) }
                    }
                    Toggle("Smart sentences", isOn: $settings.chatStegoSmart)
                }
            } footer: {
                Text("When on, a message in Chats is first encrypted with Signal, then disguised as a harmless run of words. Off, it is sent as a compact code. The encryption is identical either way — only the outer wrapping changes.")
            }

            if settings.chatStegoEnabled {
                Section {
                    Text(StegoSettingsView.sampleStego(language: settings.effectiveLanguage, smart: settings.chatStegoSmart))
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(.primary)
                } header: {
                    Text("Example output")
                } footer: {
                    Text(settings.chatStegoSmart
                         ? "Smart mode builds real, grammatical sentences that read like an ordinary note, at the cost of a somewhat longer message. Turn it off for the shortest possible cover text."
                         : "Your everyday messages look like this. The first message to a new contact carries the post-quantum key setup, so it is longer — but once they reply even once, every message after that is short.")
                }
            }
        }
        .navigationTitle("Steganography")
        .navigationBarTitleDisplayMode(.inline)
    }

    private static func sampleStego(language: StegoLanguage, smart: Bool) -> String {
        let sample = Data([0x03, 0x02, 0x41, 0x9c, 0x2a, 0xf7, 0x10, 0x88, 0x3d, 0x6b, 0xe0, 0x54])
        return smart ? SmartTextStego.encode(sample, language: language) : TextStego.encode(sample, language: language)
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
                Text("When the clipboard holds an encrypted message, the Kryptos keyboard decrypts it the moment it opens — copy a message in any messenger and it is revealed right there, no extra taps.")
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
                }
            } header: {
                Text("Typing")
            } footer: {
                Text("Context-aware completions, next-word predictions and typo fixes from built-in offline dictionaries (Russian and English). Auto-correction fixes a mistyped word the moment you finish it — keyboard-slip typos and a missing space (какдела → как дела). Backspace right after — or a tap on your word shown «quoted» in the strip — undoes the fix and the keyboard never touches that word again. Everything is learned right on the device, stored encrypted, and disabled in password fields. Changes apply the next time the keyboard opens.")
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
            } footer: {
                Text("Type your message inside the keyboard and encrypt it there. Only the encrypted result reaches the messenger — it can never store the plaintext you typed before sending. Changes apply the next time you open the Kryptos keyboard.")
            }
        }
        .navigationTitle("Keyboard")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Forget the words the keyboard has learned from your typing? The built-in dictionaries stay.",
                            isPresented: $confirmForget, titleVisibility: .visible) {
            Button("Forget learned words", role: .destructive) { SharedStore.delete("kbdict") }
        }
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
    ("ru", "Russian")
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
                Text("The keyboard offers a letter layout for each enabled language, and the language key switches between them. At least one language always stays on. Changes apply the next time the keyboard opens.")
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
                Text("Fully offline: no servers, no accounts, no analytics. Your private keys never leave this device.")
            }

            Section {
                infoRow("checkmark.shield.fill", "Signal Protocol — official libsignal",
                        "The very library the Signal app uses: post-quantum key agreement (PQXDH with Kyber) and the Double Ratchet — a fresh key for every message.")
                infoRow("envelope.fill", "OpenPGP — ObjectivePGP",
                        "Classic asymmetric encryption with signatures, compatible with other PGP tools.")
                infoRow("key.fill", "Password mode — Apple CryptoKit",
                        "A shared passphrase: PBKDF2-HMAC-SHA256 and AES-256-GCM.")
                infoRow("photo.fill", "Steganography",
                        "Hides the already-encrypted message inside an ordinary photo or a run of everyday words.")
            } header: {
                Text("Under the hood")
            }

            Section {
                Link(destination: URL(string: "mailto:datakeepers@proton.me")!) {
                    linkRow("envelope.fill", "Email", "datakeepers@proton.me")
                }
                Link(destination: URL(string: "https://t.me/datakeeper")!) {
                    linkRow("paperplane.fill", "Telegram", "@datakeeper")
                }
                Link(destination: URL(string: "https://t.me/KryptosApp")!) {
                    linkRow("megaphone.fill", "Telegram channel", "@KryptosApp")
                }
            } header: {
                Text("Developer")
            } footer: {
                Text("Questions, ideas or a bug to report? I'd be glad to hear from you.")
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

    private func linkRow(_ icon: String, _ title: LocalizedStringKey, _ value: String) -> some View {
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
