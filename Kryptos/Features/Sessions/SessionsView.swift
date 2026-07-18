import SwiftUI

struct SessionsView: View {
    @EnvironmentObject private var signal: SignalService
    @State private var showMyKey = false
    @State private var showAdd = false
    @State private var showProfiles = false
    @State private var confirmWipeAll = false
    @State private var confirmWipeContacts = false
    @State private var confirmDeleteContact: Contact?
    @State private var engineOK: Bool?
    nonisolated(unsafe) private static var cachedEngineOK: Bool?
    #if DEBUG
    nonisolated(unsafe) static var ranIsolationTest = false
    #endif

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        engineCard
                        if signal.contacts.isEmpty {
                            emptyCard
                        } else {
                            ForEach(signal.contacts) { contact in
                                NavigationLink { ChatView(contact: contact) } label: { contactRow(contact) }
                                    .buttonStyle(.plain)
                                    .contextMenu {
                                        Button(role: .destructive) { confirmDeleteContact = contact } label: {
                                            Label("Delete contact & chat", systemImage: "person.badge.minus")
                                        }
                                    }
                            }
                        }
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Chats")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showMyKey = true } label: { Image(systemName: "qrcode") }
                }
                ToolbarItem(placement: .principal) {
                    Menu {
                        ForEach(signal.profiles) { p in
                            Button { signal.switchTo(p.id) } label: {
                                Label(p.name, systemImage: p.id == signal.currentID ? "checkmark" : "person")
                            }
                        }
                        Divider()
                        Button { showProfiles = true } label: { Label("Manage identities…", systemImage: "person.crop.circle.badge.plus") }
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
                }
            }
            .sheet(isPresented: $showMyKey) { MyKeyView() }
            .sheet(isPresented: $showAdd) { AddContactView() }
            .sheet(isPresented: $showProfiles) { ProfilesView() }
            .confirmationDialog("Securely erase every conversation? Contacts stay; message history is wiped. This can't be undone.",
                                isPresented: $confirmWipeAll, titleVisibility: .visible) {
                Button("Clear all chats", role: .destructive) { signal.wipeAllChats() }
            }
            .confirmationDialog("Delete every contact and conversation? Their keys and sessions are erased from this device; your own key stays. This can't be undone.",
                                isPresented: $confirmWipeContacts, titleVisibility: .visible) {
                Button("Delete contacts & chats", role: .destructive) { signal.wipeContactsAndChats() }
            }
            .confirmationDialog("Delete this contact and your conversation? Their key and session are erased from this device; your own key stays. This can't be undone.",
                                isPresented: Binding(get: { confirmDeleteContact != nil },
                                                     set: { if !$0 { confirmDeleteContact = nil } }),
                                titleVisibility: .visible) {
                Button("Delete contact & chat", role: .destructive) {
                    if let c = confirmDeleteContact { signal.removeContact(c) }
                }
            }
        }
        .task {
            if Self.cachedEngineOK == nil {
                Self.cachedEngineOK = await Task.detached { SignalWire.selfTestError() == nil }.value
            }
            engineOK = Self.cachedEngineOK
            #if DEBUG
            if !Self.ranIsolationTest {
                Self.ranIsolationTest = true
                NSLog("PROFILE_ISOLATION=%@", signal.profileIsolationSelfTestError() ?? "PASS")
            }
            #endif
        }
        .onAppear { signal.reloadCurrentFromDisk() }
    }

    private var engineCard: some View {
        HStack(spacing: 12) {
            Image(systemName: engineOK == false ? "xmark.seal.fill" : "checkmark.seal.fill")
                .foregroundStyle(engineOK == false ? KTheme.danger : Color(red: 0.2, green: 0.72, blue: 0.45))
            VStack(alignment: .leading, spacing: 2) {
                Text("Signal engine").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                Text(engineOK == false ? "The libsignal check failed." : "Official libsignal — verified working.")
                    .font(.kBody()).foregroundStyle(KTheme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .glassCard()
    }

    private var emptyCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 26, weight: .semibold)).foregroundStyle(KTheme.accent)
            Text("No conversations yet").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
            Text("Exchange keys to start: open **My key** (top-left) and let your contact scan it, then add their key with **+** (top-right). Both of you add each other once.")
                .font(.kBody()).foregroundStyle(KTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    private func contactRow(_ contact: Contact) -> some View {
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
            if signal.autoDeleteInterval(for: contact.fingerprint) != nil {
                Image(systemName: "timer").font(.footnote).foregroundStyle(KTheme.accent)
            }
            Image(systemName: "chevron.right").font(.footnote.weight(.bold)).foregroundStyle(KTheme.textSecondary)
        }
        .glassCard()
    }
}
