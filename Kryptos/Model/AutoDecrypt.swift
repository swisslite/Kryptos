import SwiftUI
import CipherCore

struct RevealedIncoming: Identifiable {
    let id = UUID()
    let contact: Contact
    let text: String
}

@MainActor
enum AutoDecrypt {
    private static var lastChangeCount = Int.min

    static func scan(signal: SignalService) -> RevealedIncoming? {
        guard PrivacyConfig.clipboardAutoDecrypt else { return nil }
        let pb = UIPasteboard.general
        guard pb.changeCount != lastChangeCount, pb.hasStrings else { return nil }
        lastChangeCount = pb.changeCount
        guard !RemoteClipboard.isRemote else { return nil }
        guard let s = pb.string, !s.isEmpty, s != Clipboard.lastWritten else { return nil }
        let stegoSized = s.utf16.count >= 40 && s.utf16.count <= 64_000
        guard WireFormat.isToken(s) || (stegoSized && (TextStego.looksLikeStego(s) || SmartTextStego.looksLikeStego(s))) else { return nil }
        guard !OwnCipherMarker.matches(s) else { return nil }
        if let hit = signal.cachedDecrypt(s) {
            return RevealedIncoming(contact: hit.contact, text: hit.text)
        }
        for contact in signal.contacts {
            if let text = try? signal.decrypt(s, from: contact) {
                return RevealedIncoming(contact: contact, text: text)
            }
        }
        return nil
    }
}

struct IncomingRevealView: View {
    let reveal: RevealedIncoming
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            ScreenBackground()
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    Image(systemName: "lock.open.fill")
                        .font(.system(size: 17, weight: .semibold)).foregroundStyle(KTheme.accent)
                    Text("Message decrypted").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                    Spacer(minLength: 0)
                    Button { dismiss() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 24)).foregroundStyle(KTheme.textSecondary)
                    }
                }

                HStack(spacing: 7) {
                    Image(systemName: "person.crop.circle.fill").font(.system(size: 14))
                    Text(reveal.contact.displayName).font(.kLabel().weight(.bold))
                }
                .foregroundStyle(KTheme.accent)
                .padding(.horizontal, 11).padding(.vertical, 6)
                .background(Capsule().fill(KTheme.accent.opacity(0.13)))

                ScrollView {
                    Text(reveal.text)
                        .font(.kBody()).foregroundStyle(KTheme.textPrimary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(14)
                .background(FieldBackground())

                Text("Saved to the chat history.")
                    .font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            }
            .padding(20)
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
