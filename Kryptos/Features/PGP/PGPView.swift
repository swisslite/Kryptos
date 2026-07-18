import SwiftUI
import UIKit

struct PGPView: View {
    @StateObject private var pgp = PGPService()

    enum Mode { case encrypt, decrypt }
    @State private var mode: Mode = .encrypt
    @State private var recipient: PGPRecipient?
    @State private var input = ""
    @State private var output = ""
    @State private var verification: PGPVerification?
    @State private var errorText: String?
    @State private var showMyKey = false
    @State private var showRecipients = false
    @State private var showKeys = false
    @State private var copied = false
    @State private var autoCopied = false

    var body: some View {
        ScreenScaffold("PGP") {
            if pgp.busy && !pgp.ready {
                generatingCard
            } else {
                identityCard
                keyButtons
                Picker("", selection: $mode) {
                    Text("Encrypt").tag(Mode.encrypt)
                    Text("Decrypt").tag(Mode.decrypt)
                }
                .pickerStyle(.segmented)
                workCard
                if let errorText { banner(errorText) }
                if mode == .decrypt, let verification, !output.isEmpty { verificationBanner(verification) }
                if autoCopied { CopiedBanner() }
                if !output.isEmpty { outputCard }
            }
        }
        .sheet(isPresented: $showMyKey) { MyPGPKeyView(armoredKey: pgp.myPublicKey, title: pgp.currentIdentity?.name ?? "My key") }
        .sheet(isPresented: $showRecipients) { PGPRecipientsView().environmentObject(pgp) }
        .sheet(isPresented: $showKeys) { PGPKeysView().environmentObject(pgp) }
    }

    private var generatingCard: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text("Generating your PGP keypair…").font(.kBody()).foregroundStyle(KTheme.textSecondary)
            Spacer(minLength: 0)
        }
        .glassCard()
    }

    private var identityCard: some View {
        Button { showKeys = true } label: {
            HStack(spacing: 12) {
                Image(systemName: "key.horizontal.fill")
                    .font(.system(size: 20, weight: .semibold)).foregroundStyle(KTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("YOUR KEY — TAP TO MANAGE").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
                    Text(pgp.currentIdentity?.name ?? "My key").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                    Text(pgp.currentIdentity?.fingerprint ?? "").font(.kMono()).foregroundStyle(KTheme.textSecondary)
                        .lineLimit(1).minimumScaleFactor(0.5)
                }
                Spacer(minLength: 0)
                HStack(spacing: 4) {
                    Text("Manage").font(.kBody())
                    Image(systemName: "chevron.right").font(.footnote.weight(.bold))
                }.foregroundStyle(KTheme.accent)
            }
            .glassCard()
        }
        .buttonStyle(.plain)
        .disabled(pgp.busy)
    }

    private var keyButtons: some View {
        HStack(spacing: 10) {
            keyTile("My keys", icon: "key.fill") { showKeys = true }
            keyTile("Share key", icon: "square.and.arrow.up") { showMyKey = true }
            keyTile("Contacts", icon: "person.2.fill") { showRecipients = true }
        }
    }

    private func keyTile(_ title: LocalizedStringKey, icon: String, action: @escaping () -> Void) -> some View {
        let shape = RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
        return Button(action: action) {
            VStack(spacing: 7) {
                Image(systemName: icon).font(.system(size: 19, weight: .semibold)).foregroundStyle(KTheme.accent)
                Text(title).font(.kLabel()).foregroundStyle(KTheme.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.75)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(shape.fill(.ultraThinMaterial))
            .overlay(shape.strokeBorder(KTheme.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var workCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            if mode == .encrypt {
                fieldLabel("RECIPIENT")
                Menu {
                    ForEach(pgp.recipients) { r in Button(r.name) { recipient = r } }
                } label: {
                    HStack {
                        Text(recipient?.name ?? (pgp.recipients.isEmpty ? String(localized: "Add a recipient first") : String(localized: "Choose a recipient")))
                            .foregroundStyle(recipient == nil ? KTheme.textSecondary : KTheme.textPrimary)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down").foregroundStyle(KTheme.textSecondary)
                    }
                    .font(.kBody()).padding(12).background(FieldBackground())
                }
            }

            fieldLabel(mode == .encrypt ? "MESSAGE TEXT" : "PGP MESSAGE")
            TextEditor(text: $input)
                .font(mode == .encrypt ? .kBody() : .kMono())
                .frame(minHeight: 120).scrollContentBackground(.hidden)
                .padding(8).background(FieldBackground())

            HStack(spacing: 12) {
                Button { if let s = UIPasteboard.general.string { input = s } } label: {
                    Label("Paste", systemImage: "doc.on.clipboard")
                }.buttonStyle(SecondaryButtonStyle())
                Button(action: run) {
                    Label(mode == .encrypt ? "Encrypt" : "Decrypt", systemImage: mode == .encrypt ? "lock.fill" : "lock.open.fill")
                }.buttonStyle(PrimaryButtonStyle())
            }
        }
        .glassCard()
    }

    private func verificationBanner(_ v: PGPVerification) -> some View {
        let (icon, color, text): (String, Color, LocalizedStringKey) = {
            switch v {
            case .verified: return ("checkmark.seal.fill", Color(red: 0.2, green: 0.72, blue: 0.45), "Signature verified — from a saved recipient.")
            case .unverified: return ("exclamationmark.triangle.fill", Color(red: 0.85, green: 0.6, blue: 0.1), "Decrypted, but the signature is unverified — add the sender to Recipients to verify.")
            }
        }()
        return HStack(spacing: 10) {
            Image(systemName: icon).foregroundStyle(color)
            Text(text).font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous).fill(color.opacity(0.12)))
    }

    private var outputCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            fieldLabel(mode == .encrypt ? "ENCRYPTED — SEND THIS" : "DECRYPTED TEXT")
            ScrollView {
                Text(output).font(mode == .encrypt ? .kMono() : .kBody())
                    .foregroundStyle(KTheme.textPrimary).textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }.frame(maxHeight: 220)
            HStack(spacing: 12) {
                Button { Clipboard.copy(output); flash() } label: {
                    Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                }.buttonStyle(SecondaryButtonStyle())
                ShareLink(item: output) { Label("Share", systemImage: "square.and.arrow.up") }
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .glassCard()
    }

    private func fieldLabel(_ t: LocalizedStringKey) -> some View {
        Text(t).font(.kLabel()).foregroundStyle(KTheme.textSecondary)
    }

    private func banner(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(KTheme.danger)
            Text(text).font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous).fill(KTheme.danger.opacity(0.12)))
    }

    private func run() {
        errorText = nil; output = ""; verification = nil; copied = false; autoCopied = false
        guard !input.isEmpty else { errorText = String(localized: "Enter some text."); return }
        do {
            if mode == .encrypt {
                guard let recipient else { errorText = String(localized: "Choose a recipient."); return }
                output = try pgp.encrypt(input, to: recipient)
                Clipboard.copy(output)
                autoCopied = true
            } else {
                let result = try pgp.decrypt(input)
                output = result.text
                verification = result.verification
            }
        } catch {
            errorText = (error as? LocalizedError)?.errorDescription ?? String(localized: "Could not process the message.")
        }
    }

    private func flash() {
        withAnimation { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { withAnimation { copied = false } }
    }
}

private struct MyPGPKeyView: View {
    let armoredKey: String
    let title: String
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Give this public key to anyone who wants to send you PGP messages, or to add you as a recipient (so they can verify your signature).")
                            .font(.kBody()).foregroundStyle(KTheme.textSecondary)
                        Text(armoredKey).font(.kMono()).foregroundStyle(KTheme.textPrimary)
                            .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12).background(FieldBackground())
                        HStack(spacing: 12) {
                            Button { Clipboard.copy(armoredKey); flash() } label: {
                                Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                            }.buttonStyle(SecondaryButtonStyle())
                            ShareLink(item: armoredKey) { Label("Share", systemImage: "square.and.arrow.up") }
                                .buttonStyle(PrimaryButtonStyle())
                        }
                    }
                    .padding(20)
                }
            }
            .navigationTitle(LocalizedStringKey(title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }

    private func flash() {
        withAnimation { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { withAnimation { copied = false } }
    }
}

private struct PGPKeysView: View {
    @EnvironmentObject private var pgp: PGPService
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var email = ""
    @State private var algo: PGPAlgo = .curve25519
    @State private var confirmDelete: PGPIdentity?
    @State private var confirmRegenerate = false

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        createCard
                        ForEach(pgp.identities) { identityRow($0) }
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Your PGP keys")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .confirmationDialog("Regenerate this key? The public key you shared before will stop working.",
                                isPresented: $confirmRegenerate, titleVisibility: .visible) {
                Button("Regenerate", role: .destructive) { pgp.regenerateCurrent(algo: algo) }
            }
            .confirmationDialog("Delete this key permanently?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }), titleVisibility: .visible) {
                Button("Delete", role: .destructive) { if let d = confirmDelete { pgp.deleteIdentity(d.id) } }
            }
        }
    }

    private var createCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("CREATE A NEW KEY").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            TextField("Name (e.g. Alice)", text: $name).padding(12).background(FieldBackground())
            TextField("Email (optional)", text: $email).textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(FieldBackground())
            Picker("Algorithm", selection: $algo) {
                ForEach(PGPAlgo.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.menu).tint(KTheme.accent)
            Button {
                pgp.generate(name: name.trimmingCharacters(in: .whitespaces), email: email.trimmingCharacters(in: .whitespaces), algo: algo)
                name = ""; email = ""
            } label: {
                Label(pgp.busy ? "Generating…" : "Generate key", systemImage: "plus")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(pgp.busy)
        }
        .glassCard()
    }

    private func identityRow(_ ident: PGPIdentity) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: ident.id == pgp.currentID ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(ident.id == pgp.currentID ? KTheme.accent : KTheme.textSecondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ident.name).font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                    Text(ident.algo).font(.kLabel()).foregroundStyle(KTheme.textSecondary)
                }
                Spacer(minLength: 0)
                if ident.id != pgp.currentID {
                    Button("Use") { pgp.switchTo(ident.id) }.buttonStyle(SecondaryButtonStyle())
                }
            }
            Text(ident.fingerprint).font(.kMono()).foregroundStyle(KTheme.textSecondary)
                .lineLimit(2).minimumScaleFactor(0.7)
            HStack(spacing: 12) {
                if ident.id == pgp.currentID {
                    Button { confirmRegenerate = true } label: { Label("Regenerate", systemImage: "arrow.triangle.2.circlepath") }
                        .font(.kBody()).foregroundStyle(KTheme.accent).disabled(pgp.busy)
                }
                Spacer(minLength: 0)
                Button { confirmDelete = ident } label: { Label("Delete", systemImage: "trash") }
                    .font(.kBody()).foregroundStyle(KTheme.danger).disabled(pgp.busy)
            }
        }
        .glassCard()
    }
}

private struct PGPRecipientsView: View {
    @EnvironmentObject private var pgp: PGPService
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var keyText = ""
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("NAME").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
                            TextField("e.g. Alice", text: $name).padding(12).background(FieldBackground())
                            Text("THEIR PUBLIC KEY").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
                            TextEditor(text: $keyText).font(.kMono()).frame(minHeight: 90)
                                .scrollContentBackground(.hidden).padding(8).background(FieldBackground())
                            HStack(spacing: 12) {
                                Button { if let s = UIPasteboard.general.string { keyText = s } } label: {
                                    Label("Paste", systemImage: "doc.on.clipboard")
                                }.buttonStyle(SecondaryButtonStyle())
                                Button(action: add) { Label("Add", systemImage: "plus") }
                                    .buttonStyle(PrimaryButtonStyle())
                            }
                            if let errorText { Text(errorText).font(.kBody()).foregroundStyle(KTheme.danger) }
                        }
                        .glassCard()

                        ForEach(pgp.recipients) { r in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(r.name).font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                                    Spacer()
                                    Button { pgp.removeRecipient(r) } label: {
                                        Image(systemName: "trash").foregroundStyle(KTheme.danger)
                                    }
                                }
                                if !r.fingerprint.isEmpty {
                                    Text(r.fingerprint).font(.kMono()).foregroundStyle(KTheme.textSecondary)
                                        .lineLimit(1).minimumScaleFactor(0.6)
                                }
                            }.glassCard()
                        }
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Recipients")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }

    private func add() {
        errorText = nil
        do {
            try pgp.addRecipient(name: name.trimmingCharacters(in: .whitespaces), armoredKey: keyText)
            name = ""; keyText = ""
        } catch {
            errorText = (error as? LocalizedError)?.errorDescription ?? String(localized: "This is not a valid PGP public key.")
        }
    }
}
