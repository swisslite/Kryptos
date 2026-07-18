import SwiftUI

struct ProfilesView: View {
    @EnvironmentObject private var signal: SignalService
    @Environment(\.dismiss) private var dismiss

    @State private var renameText = ""
    @State private var newName = ""
    @State private var confirmRegen = false
    @State private var confirmDelete: Profile?

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        intro
                        profilesCard
                        currentCard
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Identities")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .onAppear { renameText = signal.currentProfile?.name ?? "" }
            .alert("Regenerate this identity?", isPresented: $confirmRegen) {
                Button("Regenerate", role: .destructive) { signal.regenerateCurrentIdentity(); renameText = signal.currentProfile?.name ?? "" }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("A brand-new key is created for this profile. All its contacts and conversations become unreadable and must be set up again.")
            }
            .alert(item: $confirmDelete) { profile in
                Alert(title: Text("Delete profile?"),
                      message: Text("This permanently removes this identity, its keys, contacts and messages."),
                      primaryButton: .destructive(Text("Delete")) { signal.deleteProfile(profile.id); renameText = signal.currentProfile?.name ?? "" },
                      secondaryButton: .cancel())
            }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("An identity is a separate keypair with its own contacts. Create several to keep things apart. Your **safety number** is public (safe to share, used only to verify a contact); your private key never leaves this device.")
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
                    Text(profile.name).font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                    Spacer(minLength: 0)
                    if signal.profiles.count > 1 {
                        Button { confirmDelete = profile } label: { Image(systemName: "trash").foregroundStyle(KTheme.danger) }
                            .buttonStyle(.plain)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { signal.switchTo(profile.id); renameText = signal.currentProfile?.name ?? "" }
                .padding(.vertical, 4)
            }
            Divider().overlay(KTheme.hairline)
            HStack(spacing: 10) {
                TextField("New profile name", text: $newName).padding(10).background(FieldBackground())
                Button {
                    signal.createProfile(name: newName); newName = ""; renameText = signal.currentProfile?.name ?? ""
                } label: { Image(systemName: "plus").font(.headline).foregroundStyle(.white).frame(width: 40, height: 40).background(Circle().fill(KTheme.accent)) }
            }
        }
        .glassCard()
    }

    private var currentCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("CURRENT PROFILE").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            HStack(spacing: 10) {
                TextField("Name", text: $renameText).padding(10).background(FieldBackground())
                Button("Save") { signal.renameCurrent(renameText) }.buttonStyle(SecondaryButtonStyle()).frame(width: 90)
            }
            Text("SAFETY NUMBER").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            Text(signal.mySafetyNumber).font(.system(.subheadline, design: .monospaced)).foregroundStyle(KTheme.accent).textSelection(.enabled)
            Button { confirmRegen = true } label: { Label("Regenerate key", systemImage: "arrow.triangle.2.circlepath") }
                .buttonStyle(SecondaryButtonStyle())
        }
        .glassCard()
    }
}
