import SwiftUI
import UIKit
import CipherCore
import LibSignalClient

struct ChatView: View {
    @EnvironmentObject private var signal: SignalService
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    let contact: Contact

    @State private var draft = ""
    @State private var lastCipher: String?
    @State private var errorText: String?
    @State private var confirmClear = false
    @State private var confirmDeleteContact = false

    private let purgeTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    private var msgs: [ChatMessage] { signal.messages[contact.fingerprint] ?? [] }
    private var currentPreset: AutoDeletePreset { AutoDeletePreset.matching(signal.autoDeleteInterval(for: contact.fingerprint)) }

    var body: some View {
        ZStack {
            ScreenBackground()
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            hint
                            if currentPreset != .off { autoDeleteHint }
                            ForEach(msgs) { bubble($0).id($0.id) }
                        }
                        .padding(16)
                    }
                    .onChange(of: msgs.count) { _, _ in
                        if let last = msgs.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                    }
                }
                if let errorText {
                    Text(errorText).font(.kBody()).foregroundStyle(KTheme.danger)
                        .padding(.horizontal, 16).padding(.bottom, 6)
                }
                if let lastCipher { sentBanner(lastCipher) }
                inputBar
            }
        }
        .navigationTitle(contact.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Menu {
                        ForEach(AutoDeletePreset.allCases) { preset in
                            Button { signal.setAutoDelete(preset.seconds, for: contact) } label: {
                                Label(preset.title, systemImage: currentPreset == preset ? "checkmark" : "timer")
                            }
                        }
                    } label: { Label("Disappearing messages", systemImage: "timer") }
                    Button(role: .destructive) { confirmClear = true } label: {
                        Label("Clear chat", systemImage: "trash")
                    }
                    Button(role: .destructive) { confirmDeleteContact = true } label: {
                        Label("Delete contact & chat", systemImage: "person.badge.minus")
                    }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .confirmationDialog("Securely erase this conversation? This can't be undone.",
                            isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear chat", role: .destructive) { signal.clearChat(contact) }
        }
        .confirmationDialog("Delete this contact and your conversation? Their key and session are erased from this device; your own key stays. This can't be undone.",
                            isPresented: $confirmDeleteContact, titleVisibility: .visible) {
            Button("Delete contact & chat", role: .destructive) {
                signal.removeContact(contact)
                dismiss()
            }
        }
        .onAppear { signal.reloadCurrentFromDisk() }
        .onReceive(purgeTimer) { _ in signal.purgeExpiredMessages() }
    }

    private var autoDeleteHint: some View {
        HStack(spacing: 6) {
            Image(systemName: "timer").font(.caption2)
            Text("Messages disappear after \(currentPreset.title).")
        }
        .font(.kLabel()).foregroundStyle(KTheme.textSecondary)
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(Capsule().fill(.ultraThinMaterial))
    }

    private func sentBanner(_ cipher: String) -> some View {
        let hidden = TextStego.looksLikeStego(cipher) || SmartTextStego.looksLikeStego(cipher)
        return HStack(spacing: 10) {
            Image(systemName: hidden ? "text.word.spacing" : "checkmark.circle.fill")
                .foregroundStyle(Color(red: 0.2, green: 0.72, blue: 0.45))
            Text(hidden
                 ? "Hidden in words & copied — paste it to your contact."
                 : "Encrypted & copied — paste it to your contact.")
                .font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
            ShareLink(item: cipher) { Image(systemName: "square.and.arrow.up").foregroundStyle(KTheme.accent) }
            Button { lastCipher = nil } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(KTheme.textSecondary) }
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .glassSurface(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 2)
    }

    private var hint: some View {
        Text("Encrypt a message, send the result through any messenger, and paste their reply here to decrypt.")
            .font(.kMono()).foregroundStyle(KTheme.textSecondary)
            .multilineTextAlignment(.center).padding(.vertical, 8)
    }

    private let incomingFill = Color(light: UIColor.white,
                                     dark: UIColor(red: 0.16, green: 0.17, blue: 0.21, alpha: 1))

    private func bubble(_ m: ChatMessage) -> some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        return HStack {
            if m.mine { Spacer(minLength: 40) }
            Text(m.text)
                .font(.kBody())
                .foregroundStyle(m.mine ? .white : KTheme.textPrimary)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(shape.fill(m.mine ? AnyShapeStyle(KTheme.accentGradient) : AnyShapeStyle(incomingFill)))
                .overlay { if !m.mine { shape.strokeBorder(KTheme.hairline, lineWidth: 1) } }
                .shadow(color: .black.opacity(m.mine ? 0 : 0.06), radius: 3, y: 1)
            if !m.mine { Spacer(minLength: 40) }
        }
    }

    private var inputBar: some View {
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return HStack(spacing: 10) {
            Button { decryptClipboard() } label: {
                Image(systemName: "tray.and.arrow.down.fill")
                    .font(.system(size: 19, weight: .semibold)).foregroundStyle(KTheme.accent)
                    .frame(width: 48, height: 48)
            }
            .glassSurface(Circle())

            TextField("Message", text: $draft, axis: .vertical)
                .lineLimit(1 ... 4)
                .padding(.horizontal, 18).padding(.vertical, 14)
                .glassSurface(Capsule())

            Button { encrypt() } label: {
                Image(systemName: "lock.fill")
                    .font(.system(size: 19, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 48, height: 48)
            }
            .glassSurface(Circle(), tint: KTheme.accent)
            .shadow(color: KTheme.accent.opacity(empty ? 0 : 0.35), radius: 10, y: 3)
            .opacity(empty ? 0.55 : 1)
            .disabled(empty)
        }
        .padding(.horizontal, 12)
        .padding(.top, 6)
        .padding(.bottom, 4)
    }

    private func encrypt() {
        errorText = nil
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        do {
            let armored = try signal.encrypt(text, to: contact)
            draft = ""
            Clipboard.copy(armored)
            lastCipher = armored
        } catch {
            errorText = String(localized: "Could not encrypt the message.")
        }
    }

    private func decryptClipboard() {
        errorText = nil
        guard let clip = UIPasteboard.general.string else {
            errorText = String(localized: "Clipboard is empty.")
            return
        }
        do {
            _ = try signal.decrypt(clip, from: contact)
        } catch {
            errorText = ChatView.decryptFailureMessage(for: error, in: clip)
        }
    }

    static func decryptFailureMessage(for error: Error, in clip: String) -> String {
        let stegoSized = clip.utf16.count >= 40 && clip.utf16.count <= 64_000
        if !WireFormat.isToken(clip) && !(stegoSized && (TextStego.looksLikeStego(clip) || SmartTextStego.looksLikeStego(clip))) {
            return String(localized: "The clipboard has no Kryptos message — copy the encrypted text first.")
        }
        switch error {
        case SignalServiceError.decryptedForOtherContact(let name):
            return String(localized: "This message is from another contact: \(name).")
        case SignalError.duplicatedMessage:
            return String(localized: "This message was already decrypted once — for security it can't be opened again.")
        default:
            return String(localized: """
            Could not decrypt. If every NEW message from this contact now fails, the session is out of \
            sync: share your key from “My Key” with them, have them add you again and send a new message \
            — that repairs the conversation.
            """)
        }
    }
}
