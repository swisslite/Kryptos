import SwiftUI
import UIKit
import CipherCore
import LibSignalClient

struct ChatView: View {
    @EnvironmentObject private var signal: SignalService
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var lock: LockGate
    @Environment(\.dismiss) private var dismiss
    let contact: Contact

    private struct SentCipher {
        let text: String
        let hidden: Bool
    }

    @State private var lastCipher: SentCipher?
    @State private var errorText: String?
    @State private var confirmClear = false
    @State private var confirmDeleteContact = false
    @State private var renaming = false
    @State private var renameText = ""
    @State private var purgeTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    private var live: Contact { signal.contacts.first { $0.fingerprint == contact.fingerprint } ?? contact }
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
        .navigationTitle(live.displayName)
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
                    Button { renameText = live.displayName; renaming = true } label: {
                        Label("Rename contact", systemImage: "pencil")
                    }
                    Button(role: .destructive) { confirmClear = true } label: {
                        Label("Clear chat", systemImage: "trash")
                    }
                    Button(role: .destructive) { confirmDeleteContact = true } label: {
                        Label("Delete contact & chat", systemImage: "person.badge.minus")
                    }
                } label: { Image(systemName: "ellipsis.circle") }
                .confirmationDialog("Securely erase this conversation? This can't be undone.",
                                    isPresented: $confirmClear, titleVisibility: .visible) {
                    Button("Clear chat", role: .destructive) { signal.clearChat(contact) }
                }
                .confirmationDialog("Delete this contact and your conversation? Their key and session are erased from this device; your own key stays. This can't be undone.",
                                    isPresented: $confirmDeleteContact, titleVisibility: .visible) {
                    Button("Delete contact & chat", role: .destructive) {
                        if signal.removeContact(contact) {
                            dismiss()
                        } else {
                            errorText = String(localized: "Could not delete this contact — try again.")
                        }
                    }
                }
                .alert("Rename contact", isPresented: $renaming) {
                    TextField("Name", text: $renameText)
                    Button("Save") { signal.renameContact(contact, to: renameText) }
                    Button("Cancel", role: .cancel) {}
                }
            }
        }
        .onAppear { signal.reloadCurrentFromDisk() }
        .onReceive(purgeTimer) { _ in signal.purgeExpiredMessages() }
        .onChange(of: lock.isLocked) { _, locked in
            if locked { renaming = false }
        }
        .onChange(of: signal.contacts) { _, list in
            if signal.isLoaded, !list.contains(where: { $0.fingerprint == contact.fingerprint }) { dismiss() }
        }
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

    private func sentBanner(_ cipher: SentCipher) -> some View {
        HStack(spacing: 10) {
            Image(systemName: cipher.hidden ? "text.word.spacing" : "checkmark.circle.fill")
                .foregroundStyle(Color(red: 0.2, green: 0.72, blue: 0.45))
            Text(cipher.hidden
                 ? "Hidden in text & copied — paste it to your contact."
                 : "Encrypted & copied — paste it to your contact.")
                .font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
            ShareLink(item: cipher.text) { Image(systemName: "square.and.arrow.up").foregroundStyle(KTheme.accent) }
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
                .contentShape(shape)
                .contentShape(.contextMenuPreview, shape)
                .contextMenu {
                    Button { Clipboard.copy(m.text) } label: { Label("Copy", systemImage: "doc.on.doc") }
                    Button(role: .destructive) { signal.deleteMessage(m, from: contact) } label: {
                        Label("Delete message", systemImage: "trash")
                    }
                }
            if !m.mine { Spacer(minLength: 40) }
        }
    }

    private var inputBar: some View {
        ChatInputBar(onPaste: decryptClipboard, onSend: encrypt)
    }

    private func encrypt(_ raw: String) async -> Bool {
        errorText = nil
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        do {
            let sent = try await signal.encrypt(text, to: contact)
            Clipboard.copyCipher(sent.cipher)
            lastCipher = SentCipher(text: sent.cipher, hidden: sent.hidden)
            return true
        } catch {
            errorText = (error as? SignalServiceError)?.errorDescription
                ?? String(localized: "Could not encrypt the message.")
            return false
        }
    }

    private func decryptClipboard() async {
        errorText = nil
        guard let clip = UIPasteboard.general.string, !clip.isEmpty else {
            errorText = String(localized: "Clipboard is empty.")
            return
        }
        let probe = await Task.detached(priority: .userInitiated) { () -> (cache: Data?, wire: Data?, encrypted: Bool) in
            let cache = DecryptCacheKey.stegoPayload(clip)
            let wire = cache ?? SignalWire.stegoPayload(clip)
            return (cache, wire, wire != nil || WireFormat.isToken(clip))
        }.value
        do {
            _ = try signal.decrypt(clip, from: contact, stego: .some(probe.cache),
                                   wireStego: .some(probe.wire))
        } catch {
            errorText = ChatView.decryptFailureMessage(for: error, encrypted: probe.encrypted)
        }
    }

    static func decryptFailureMessage(for error: Error, encrypted: Bool) -> String {
        if !encrypted {
            return String(localized: "The clipboard has no Kryptos message — copy the encrypted text first.")
        }
        switch error {
        case CipherError.unsupportedFormat:
            return String(localized: "This message was made by a newer version of Kryptos. Update the app to read it.")
        case SignalServiceError.sessionLost, SignalError.sessionNotFound:
            return String(localized: "The secure session with this contact is gone. Send them your key again and add their key — that restores the conversation.")
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

private struct ChatInputBar: View {
    let onPaste: () async -> Void
    let onSend: (String) async -> Bool

    @State private var draft = ""
    @State private var busy = false

    var body: some View {
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return HStack(spacing: 10) {
            Button {
                guard !busy else { return }
                busy = true
                Task { await onPaste(); busy = false }
            } label: {
                Image(systemName: "tray.and.arrow.down.fill")
                    .font(.system(size: 19, weight: .semibold)).foregroundStyle(KTheme.accent)
                    .frame(width: 48, height: 48)
            }
            .glassSurface(Circle())
            .disabled(busy)

            TextField("Message", text: $draft, axis: .vertical)
                .lineLimit(1 ... 4)
                .padding(.horizontal, 18).padding(.vertical, 14)
                .glassSurface(Capsule())

            Button {
                guard !busy else { return }
                busy = true
                let text = draft
                Task {
                    if await onSend(text) { draft = "" }
                    busy = false
                }
            } label: {
                Image(systemName: busy ? "hourglass" : "lock.fill")
                    .font(.system(size: 19, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 48, height: 48)
            }
            .glassSurface(Circle(), tint: KTheme.accent)
            .shadow(color: KTheme.accent.opacity(empty ? 0 : 0.35), radius: 10, y: 3)
            .opacity(empty ? 0.55 : 1)
            .disabled(empty || busy)
        }
        .padding(.horizontal, 12)
        .padding(.top, 6)
        .padding(.bottom, 4)
    }
}
