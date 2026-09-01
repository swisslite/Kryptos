import SwiftUI

struct SessionsView: View {
    @EnvironmentObject private var signal: SignalService
    @EnvironmentObject private var lock: LockGate
    @State private var showMyKey = false
    @State private var showAdd = false
    @State private var showProfiles = false
    @State private var confirmWipeAll = false
    @State private var confirmWipeContacts = false
    @State private var confirmDeleteContact: Contact?
    @State private var confirmClearChat: Contact?
    @State private var renameTarget: Contact?
    @State private var renameText = ""
    @State private var switchFailed = false
    @State private var showHowTo = false

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        if switchFailed { switchFailedCard }
                        if signal.keyMaterialLost { keyLostCard }
                        if signal.contacts.isEmpty {
                            engineCard
                            emptyCard
                        } else {
                            ForEach(orderedContacts) { contact in
                                ContactCell(contact: contact,
                                            autoDelete: signal.autoDeleteInterval(for: contact.fingerprint) != nil,
                                            pinned: signal.pinned.contains(contact.fingerprint),
                                            onPin: { signal.setPinned(!signal.pinned.contains($0.fingerprint), for: $0) },
                                            renameTarget: $renameTarget,
                                            renameText: $renameText,
                                            clearTarget: $confirmClearChat,
                                            deleteTarget: $confirmDeleteContact,
                                            onClear: { signal.clearChat($0) },
                                            onDelete: { signal.removeContact($0) })
                            }
                        }
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Chats")
            .navigationDestination(isPresented: $showHowTo) { HowToView() }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showMyKey = true } label: { Image(systemName: "qrcode") }
                }
                ToolbarItem(placement: .principal) {
                    Menu {
                        ForEach(signal.profiles) { p in
                            Button { switchFailed = !signal.switchTo(p.id) } label: {
                                Label(p.name, systemImage: p.id == signal.currentID ? "checkmark" : "person")
                            }
                        }
                        Divider()
                        Button { showProfiles = true } label: { Label("Manage profiles…", systemImage: "person.crop.circle.badge.plus") }
                    } label: {
                        HStack(spacing: 4) {
                            Text(signal.currentProfile?.name ?? "Chats").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                            Image(systemName: "chevron.down").font(.caption2).foregroundStyle(KTheme.textSecondary)
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { showAdd = true } label: { Label("Add contact", systemImage: "person.badge.plus") }
                        if !signal.contacts.isEmpty {
                            Divider()
                            Button(role: .destructive) { confirmWipeAll = true } label: {
                                Label("Clear all chats", systemImage: "trash")
                            }
                            Button(role: .destructive) { confirmWipeContacts = true } label: {
                                Label("Delete contacts & chats", systemImage: "person.2.slash")
                            }
                        }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .confirmationDialog("Delete every conversation? Contacts stay, message history is deleted. This can't be undone.",
                                        isPresented: $confirmWipeAll, titleVisibility: .visible) {
                        Button("Clear all chats", role: .destructive) { signal.wipeAllChats() }
                    }
                    .confirmationDialog("Delete every contact and conversation? Their keys and sessions are erased from this device; your own key stays. This can't be undone.",
                                        isPresented: $confirmWipeContacts, titleVisibility: .visible) {
                        Button("Delete contacts & chats", role: .destructive) { signal.wipeContactsAndChats() }
                    }
                }
            }
            .sheet(isPresented: $showMyKey) { MyKeyView() }
            .sheet(isPresented: $showAdd) { AddContactView() }
            .sheet(isPresented: $showProfiles) { ProfilesView() }
            .alert("Rename contact",
                   isPresented: Binding(get: { renameTarget != nil },
                                        set: { if !$0 { renameTarget = nil } }),
                   presenting: renameTarget) { target in
                TextField("Name", text: $renameText)
                Button("Save") { signal.renameContact(target, to: renameText) }
                Button("Cancel", role: .cancel) {}
            }
        }
        .onAppear { signal.reloadCurrentFromDisk() }
        .onChange(of: lock.isLocked) { _, locked in
            guard locked else { return }
            showMyKey = false
            showAdd = false
            showProfiles = false
            renameTarget = nil
            confirmClearChat = nil
            confirmDeleteContact = nil
        }
    }

    private var orderedContacts: [Contact] {
        let pinned = signal.pinned
        guard !pinned.isEmpty else { return signal.contacts }
        return signal.contacts.filter { pinned.contains($0.fingerprint) }
            + signal.contacts.filter { !pinned.contains($0.fingerprint) }
    }

    private var engineCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.seal.fill")
                .foregroundStyle(Color(red: 0.2, green: 0.72, blue: 0.45))
            VStack(alignment: .leading, spacing: 2) {
                Text("Signal engine").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                Text("Chats run on the official libsignal.")
                    .font(.kBody()).foregroundStyle(KTheme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .glassCard()
    }

    private var switchFailedCard: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(KTheme.danger)
            Text("Secure storage is unavailable right now. Try again in a moment.")
                .font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
            .fill(KTheme.danger.opacity(0.12)))
    }

    private var keyLostCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(KTheme.danger)
                Text("This profile's key is gone").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                Spacer(minLength: 0)
            }
            Text("The data saved for this profile can no longer be decrypted. Open the profile menu above, choose Manage profiles and regenerate the key to use this profile again — the old contacts and messages cannot be recovered.")
                .font(.kBody()).foregroundStyle(KTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button { showProfiles = true } label: {
                Label("Manage profiles…", systemImage: "person.crop.circle.badge.plus")
            }
            .buttonStyle(SecondaryButtonStyle(accent: true))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    private var emptyCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 26, weight: .semibold)).foregroundStyle(KTheme.accent)
            Text("No conversations yet").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
            Text("Exchange keys to start: tap the QR icon (top left) to show **My key** and let your contact scan it. Then open the **…** menu (top right) and choose **Add contact** to add their key. Both of you add each other once. A step-by-step guide with screenshots is in “[How to use](kryptos://howto)”.")
                .font(.kBody()).foregroundStyle(KTheme.textSecondary)
                .tint(KTheme.accent)
                .environment(\.openURL, OpenURLAction { url in
                    guard url.scheme == "kryptos", url.host == "howto" else { return .systemAction }
                    showHowTo = true
                    return .handled
                })
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }
}

private struct ContactCell: View {
    let contact: Contact
    let autoDelete: Bool
    let pinned: Bool
    let onPin: (Contact) -> Void
    @Binding var renameTarget: Contact?
    @Binding var renameText: String
    @Binding var clearTarget: Contact?
    @Binding var deleteTarget: Contact?
    let onClear: (Contact) -> Void
    let onDelete: (Contact) -> Void

    var body: some View {
        NavigationLink { ChatView(contact: contact) } label: { row }
            .buttonStyle(.plain)
            .contextMenu {
                Button { onPin(contact) } label: {
                    Label(pinned ? "Unpin chat" : "Pin chat", systemImage: pinned ? "pin.slash" : "pin")
                }
                Button { renameText = contact.displayName; renameTarget = contact } label: {
                    Label("Rename", systemImage: "pencil")
                }
                Button(role: .destructive) { deleteTarget = nil; clearTarget = contact } label: {
                    Label("Clear chat", systemImage: "trash")
                }
                Button(role: .destructive) { clearTarget = nil; deleteTarget = contact } label: {
                    Label("Delete contact & chat", systemImage: "person.badge.minus")
                }
            }
            .confirmationDialog("Delete this conversation? This can't be undone.",
                                isPresented: presenting($clearTarget), titleVisibility: .visible) {
                Button("Clear chat", role: .destructive) { onClear(contact) }
            }
            .confirmationDialog("Delete this contact and your conversation? Their key and session are erased from this device; your own key stays. This can't be undone.",
                                isPresented: presenting($deleteTarget), titleVisibility: .visible) {
                Button("Delete contact & chat", role: .destructive) { onDelete(contact) }
            }
    }

    private func presenting(_ target: Binding<Contact?>) -> Binding<Bool> {
        Binding(get: { target.wrappedValue?.fingerprint == contact.fingerprint },
                set: { if !$0 { target.wrappedValue = nil } })
    }

    private var row: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(LinearGradient(colors: [KTheme.accent.opacity(0.26), KTheme.accent.opacity(0.12)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 44, height: 44)
                    .overlay(Circle().strokeBorder(KTheme.accent.opacity(0.25), lineWidth: 1))
                Text(String(contact.displayName.prefix(1)).uppercased())
                    .font(.kHeadline()).foregroundStyle(KTheme.accent)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(contact.displayName).font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                Text(contact.safetyNumber).font(.kMono()).foregroundStyle(KTheme.textSecondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if pinned {
                Image(systemName: "pin.fill").font(.footnote).foregroundStyle(KTheme.textSecondary)
            }
            if autoDelete {
                Image(systemName: "timer").font(.footnote).foregroundStyle(KTheme.accent)
            }
            Image(systemName: "chevron.forward").font(.footnote.weight(.bold)).foregroundStyle(KTheme.textSecondary)
        }
        .glassCard()
    }
}
