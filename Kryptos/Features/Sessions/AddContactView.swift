import SwiftUI
import UIKit
import AVFoundation

struct AddContactView: View {
    @EnvironmentObject private var signal: SignalService
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var keyText = ""
    @State private var scanned: Data?
    @State private var showScanner = false
    @State private var errorText: String?
    @State private var cameraDenied = false
    @State private var busy = false

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
                            TextEditor(text: Binding(get: { keyText },
                                                     set: { keyText = $0; scanned = nil }))
                                .font(.kMono()).frame(minHeight: 90).scrollContentBackground(.hidden)
                                .padding(8).background(FieldBackground())

                            if scanned != nil {
                                HStack(spacing: 8) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(Color(red: 0.2, green: 0.72, blue: 0.45))
                                    Text("Key scanned").font(.kBody()).foregroundStyle(KTheme.textPrimary)
                                    Spacer(minLength: 0)
                                }
                            }

                            Button { requestScan() } label: {
                                Label("Scan QR", systemImage: "qrcode.viewfinder")
                            }
                            .buttonStyle(SecondaryButtonStyle(accent: true))

                            Button {
                                if let s = UIPasteboard.general.string {
                                    keyText = s
                                    scanned = nil
                                }
                            } label: {
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

                        Button(action: add) {
                            Label(busy ? "Working…" : "Add contact",
                                  systemImage: busy ? "hourglass" : "person.fill.badge.plus")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(busy)
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Add contact")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Cancel") { dismiss() } } }
            .fullScreenCover(isPresented: $showScanner) {
                ZStack(alignment: .topTrailing) {
                    QRScannerView { value in
                        scanned = value
                        keyText = ""
                        errorText = nil
                        showScanner = false
                    }
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
        guard !busy else { return }
        errorText = nil
        let display = name.trimmingCharacters(in: .whitespaces)
        let payload = scanned
        let text = keyText
        busy = true
        Task { @MainActor in
            await Task.yield()
            defer { busy = false }
            do {
                if let payload {
                    try signal.addContact(scanned: payload, displayName: display)
                } else {
                    try signal.addContact(fromKeyString: text, displayName: display)
                }
                dismiss()
            } catch {
                errorText = (error as? LocalizedError)?.errorDescription ?? String(localized: "This is not a valid Kryptos key.")
            }
        }
    }
}
