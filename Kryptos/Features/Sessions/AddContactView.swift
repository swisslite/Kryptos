import SwiftUI
import UIKit
import AVFoundation

struct AddContactView: View {
    @EnvironmentObject private var signal: SignalService
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var keyText = ""
    @State private var showScanner = false
    @State private var errorText: String?
    @State private var cameraDenied = false

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

                            Button { requestScan() } label: {
                                Label("Scan QR", systemImage: "qrcode.viewfinder")
                            }
                            .buttonStyle(SecondaryButtonStyle(accent: true))

                            Button { if let s = UIPasteboard.general.string { keyText = s } } label: {
                                Label("Paste", systemImage: "doc.on.clipboard")
                            }
                            .buttonStyle(SecondaryButtonStyle(accent: true))

                            if cameraDenied {
                                Text("Camera access is off, so the QR scanner cannot open. Allow the camera in Settings, or paste the key as text.")
                                    .font(.kBody()).foregroundStyle(KTheme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                Button(action: openAppSettings) {
                                    Label("Open app settings", systemImage: "arrow.up.forward.app")
                                }
                                .buttonStyle(SecondaryButtonStyle(accent: true))
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

    private func requestScan() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            cameraDenied = false
            showScanner = true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    cameraDenied = !granted
                    showScanner = granted
                }
            }
        default:
            cameraDenied = true
        }
    }

    private func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
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
