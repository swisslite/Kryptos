import SwiftUI

struct ProfilesView: View {
    @EnvironmentObject private var signal: SignalService
    @Environment(\.dismiss) private var dismiss

    @State private var renameText = ""
    @State private var renameTarget: Profile?
    @State private var newName = ""
    @State private var confirmRegen = false
    @State private var confirmDelete: Profile?
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        intro
                        if let failure { failureCard(failure) }
                        profilesCard
                        currentCard
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Profiles")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .alert("Regenerate this profile?", isPresented: $confirmRegen) {
                Button("Regenerate", role: .destructive) { report(signal.regenerateCurrentIdentity()) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("A brand-new key is created for this profile. All its contacts and conversations become unreadable and must be set up again.")
            }
            .alert(item: $confirmDelete) { profile in
                Alert(title: Text("Delete profile?"),
                      message: Text("This permanently removes this profile, its keys, contacts and messages."),
                      primaryButton: .destructive(Text("Delete")) { report(signal.deleteProfile(profile.id)) },
                      secondaryButton: .cancel())
            }
            .alert("Rename profile",
                   isPresented: Binding(get: { renameTarget != nil },
                                        set: { if !$0 { renameTarget = nil } }),
                   presenting: renameTarget) { profile in
                TextField("Name", text: $renameText)
                Button("Save") { signal.renameProfile(profile.id, to: renameText) }
                Button("Cancel", role: .cancel) {}
            }
        }
    }

    private func report(_ ok: Bool) {
        failure = ok ? nil : String(localized: "Secure storage is unavailable right now. Try again in a moment.")
    }

    private func failureCard(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(KTheme.danger)
            Text(text).font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
            .fill(KTheme.danger.opacity(0.12)))
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("A profile is a separate keypair with its own contacts. Create several to keep things apart. Your **safety number** is public (safe to share, used only to verify a contact); your private key never leaves this device.")
                .font(.kBody()).foregroundStyle(KTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    private var profilesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("PROFILES").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            ForEach(signal.profiles) { profile in
                HStack(spacing: 12) {
                    Image(systemName: profile.id == signal.currentID ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(profile.id == signal.currentID ? KTheme.accent : KTheme.textSecondary)
                    Text(profile.name).font(.kHeadline()).foregroundStyle(KTheme.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                    Button { renameText = profile.name; renameTarget = profile } label: {
                        Image(systemName: "pencil").foregroundStyle(KTheme.accent)
                            .frame(width: 36, height: 36).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Rename"))
                    if signal.profiles.count > 1 {
                        Button { confirmDelete = profile } label: {
                            Image(systemName: "trash").foregroundStyle(KTheme.danger)
                                .frame(width: 36, height: 36).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("Delete"))
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { report(signal.switchTo(profile.id)) }
                .padding(.vertical, 4)
            }
            Divider().overlay(KTheme.hairline)
            HStack(spacing: 10) {
                TextField("New profile name", text: $newName).padding(10).background(FieldBackground())
                Button {
                    report(signal.createProfile(name: newName) != nil)
                    newName = ""
                } label: { Image(systemName: "plus").font(.headline).foregroundStyle(.white).frame(width: 40, height: 40).background(Circle().fill(KTheme.accent)) }
            }
        }
        .glassCard()
    }

    private var currentCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("CURRENT PROFILE").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            Text(signal.currentProfile?.name ?? "").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
            Text("SAFETY NUMBER").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            Text(signal.mySafetyNumber).font(.system(.subheadline, design: .monospaced)).foregroundStyle(KTheme.accent).textSelection(.enabled)
            Button { confirmRegen = true } label: { Label("Regenerate key", systemImage: "arrow.triangle.2.circlepath") }
                .buttonStyle(SecondaryButtonStyle())
        }
        .glassCard()
    }
}
