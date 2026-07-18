import SwiftUI
import UIKit
import CipherCore

struct QuickEncryptView: View {
    enum Mode {
        case encrypt, decrypt
        var title: LocalizedStringKey { self == .encrypt ? "Encrypt" : "Decrypt" }
        var icon: String { self == .encrypt ? "lock.fill" : "lock.open.fill" }
    }

    @State private var mode: Mode = .encrypt
    @State private var passphrase = ""
    @State private var input = ""
    @State private var output = ""
    @State private var errorText: String?
    @State private var copied = false
    @State private var autoCopied = false

    var body: some View {
        ScreenScaffold("Password",
                       subtitle: "A simple symmetric mode: you and your contact agree on one shared password, then encrypt/decrypt text for any messenger. (This is not PGP — PGP is on its own tab.)") {
            Picker("", selection: $mode) {
                Text("Encrypt").tag(Mode.encrypt)
                Text("Decrypt").tag(Mode.decrypt)
            }
            .pickerStyle(.segmented)

            inputCard

            if let errorText { banner(errorText) }
            if autoCopied { CopiedBanner() }
            if !output.isEmpty { outputCard }
        }
        .onChange(of: input) { _, newValue in autoDetect(newValue) }
        .animation(.easeInOut(duration: 0.25), value: output)
        .animation(.easeInOut(duration: 0.25), value: errorText)
    }

    private var inputCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            fieldLabel("PASSWORD")
            SecureField("shared secret", text: $passphrase)
                .font(.kBody())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(FieldBackground())

            fieldLabel(mode == .encrypt ? "MESSAGE TEXT" : "ENCRYPTED BLOCK")
            TextEditor(text: $input)
                .font(mode == .encrypt ? .kBody() : .kMono())
                .frame(minHeight: 130)
                .scrollContentBackground(.hidden)
                .padding(8)
                .background(FieldBackground())

            HStack(spacing: 12) {
                Button { paste() } label: {
                    Label("Paste", systemImage: "doc.on.clipboard")
                }
                .buttonStyle(SecondaryButtonStyle())

                Button(action: run) {
                    Label(mode.title, systemImage: mode.icon)
                }
                .buttonStyle(PrimaryButtonStyle())
            }
        }
        .glassCard()
    }

    private var outputCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            fieldLabel(mode == .encrypt ? "READY — PASTE THIS TO YOUR CONTACT" : "DECRYPTED TEXT")
            ScrollView {
                Text(output)
                    .font(mode == .encrypt ? .kMono() : .kBody())
                    .foregroundStyle(KTheme.textPrimary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 200)

            HStack(spacing: 12) {
                Button { copy() } label: {
                    Label(copied ? "Copied" : "Copy",
                          systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(SecondaryButtonStyle())

                ShareLink(item: output) { Label("Share", systemImage: "square.and.arrow.up") }
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .glassCard()
    }

    private func fieldLabel(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.kLabel())
            .foregroundStyle(KTheme.textSecondary)
    }

    private func banner(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(KTheme.danger)
            Text(text).font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
            .fill(KTheme.danger.opacity(0.12))
            .overlay(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
                .strokeBorder(KTheme.danger.opacity(0.35), lineWidth: 1)))
    }

    private func run() {
        errorText = nil
        copied = false
        autoCopied = false
        guard !passphrase.isEmpty else { errorText = String(localized: "Enter a password."); return }
        guard !input.isEmpty else { errorText = String(localized: "Enter some text."); return }
        do {
            switch mode {
            case .encrypt:
                output = try Kryptos.encrypt(text: input, password: passphrase, pad: PrivacyConfig.lengthPadding)
                Clipboard.copy(output)
                autoCopied = true
            case .decrypt:
                output = try Kryptos.decrypt(armored: input, password: passphrase)
            }
        } catch {
            output = ""
            errorText = friendlyMessage(for: error)
        }
    }

    private func paste() {
        if let s = UIPasteboard.general.string { input = s }
    }

    private func copy() {
        Clipboard.copy(output)
        withAnimation { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { copied = false }
        }
    }

    private func autoDetect(_ text: String) {
        autoCopied = false
        if mode == .encrypt, Kryptos.containsMessage(text) {
            withAnimation { mode = .decrypt }
        }
    }

    private func friendlyMessage(for error: Error) -> String {
        guard let error = error as? CipherError else {
            return String(localized: "Could not process the message.")
        }
        switch error {
        case .decryptionFailed: return String(localized: "Wrong password or corrupted data.")
        case .notAKryptosMessage: return String(localized: "No Kryptos message found in the text.")
        default: return String(localized: "Could not process the message.")
        }
    }
}

#Preview {
    QuickEncryptView()
}
