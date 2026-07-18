import SwiftUI
import UIKit

struct AddContactView: View {
    @EnvironmentObject private var signal: SignalService
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var keyText = ""
    @State private var showScanner = false
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 14) {
                            fieldLabel("CONTACT NAME")
                            TextField("e.g. Alice", text: $name)
                                .padding(12).background(FieldBackground())

                            fieldLabel("THEIR KEY")
                            TextEditor(text: $keyText)
                                .font(.kMono()).frame(minHeight: 90).scrollContentBackground(.hidden)
                                .padding(8).background(FieldBackground())

                            HStack(spacing: 12) {
                                Button { showScanner = true } label: { Label("Scan QR", systemImage: "qrcode.viewfinder") }
                                    .buttonStyle(SecondaryButtonStyle())
                                Button { if let s = UIPasteboard.general.string { keyText = s } } label: {
                                    Label("Paste", systemImage: "doc.on.clipboard")
                                }.buttonStyle(SecondaryButtonStyle())
                            }
                        }
                        .glassCard()

                        if let errorText {
                            HStack(spacing: 10) {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(KTheme.danger)
                                Text(errorText).font(.kBody()).foregroundStyle(KTheme.textPrimary)
                                Spacer(minLength: 0)
                            }
                            .padding(14)
                            .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous).fill(KTheme.danger.opacity(0.12)))
                        }

                        Button(action: add) { Label("Add contact", systemImage: "person.fill.badge.plus") }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Add contact")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Cancel") { dismiss() } } }
            .fullScreenCover(isPresented: $showScanner) {
                ZStack(alignment: .topTrailing) {
                    QRScannerView { value in keyText = value; showScanner = false }
                        .ignoresSafeArea()
                    Button { showScanner = false } label: {
                        Image(systemName: "xmark.circle.fill").font(.largeTitle).foregroundStyle(.white).padding()
                    }
                }
            }
        }
    }

    private func fieldLabel(_ t: LocalizedStringKey) -> some View {
        Text(t).font(.kLabel()).foregroundStyle(KTheme.textSecondary)
    }

    private func add() {
        errorText = nil
        do {
            try signal.addContact(fromKeyString: keyText, displayName: name.trimmingCharacters(in: .whitespaces))
            dismiss()
        } catch {
            errorText = (error as? LocalizedError)?.errorDescription ?? String(localized: "This is not a valid Kryptos key.")
        }
    }
}
