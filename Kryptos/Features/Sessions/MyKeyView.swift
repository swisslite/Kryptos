import SwiftUI
import UIKit

struct MyKeyView: View {
    @EnvironmentObject private var signal: SignalService
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var key = ""
    @State private var showQR = false

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(spacing: 18) {
                        profileChip
                        qrSection
                        infoCard
                        actionButtons
                    }
                    .padding(20)
                }
            }
            .navigationTitle("My key")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .task { if key.isEmpty { key = signal.myKeyString() } }
    }

    private var profileChip: some View {
        HStack(spacing: 8) {
            Image(systemName: "person.crop.circle.fill").foregroundStyle(KTheme.accent)
            Text("Key for profile").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            Text(signal.currentProfile?.name ?? "").font(.kLabel().weight(.bold)).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(Capsule().fill(KTheme.accent.opacity(0.12)))
    }

    @ViewBuilder private var qrSection: some View {
        if showQR {
            VStack(spacing: 14) {
                qrCard
                Button {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { showQR = false }
                } label: {
                    Label("Hide QR code", systemImage: "eye.slash").font(.kBody()).foregroundStyle(KTheme.accent)
                }
            }
            .transition(.scale(scale: 0.92).combined(with: .opacity))
        } else {
            Button {
                withAnimation(.spring(response: 0.45, dampingFraction: 0.82)) { showQR = true }
            } label: {
                VStack(spacing: 12) {
                    Image(systemName: "qrcode").font(.system(size: 46, weight: .regular)).foregroundStyle(KTheme.accent)
                    Text("Show QR code").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                    Text("Let your contact scan it to add you.")
                        .font(.kBody()).foregroundStyle(KTheme.textSecondary).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 32)
                .glassCard()
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder private var qrCard: some View {
        Group {
            if let qr = QRCode.image(from: key) {
                Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                    .frame(width: 260, height: 260)
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "qrcode").font(.system(size: 40)).foregroundStyle(.black.opacity(0.35))
                    Text("QR unavailable — use Copy key or Share below.")
                        .font(.kBody()).foregroundStyle(.black.opacity(0.55))
                        .multilineTextAlignment(.center).padding(.horizontal, 16)
                }
                .frame(width: 260, height: 260)
            }
        }
        .padding(16).background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: KTheme.corner, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 16, y: 6)
    }

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("SAFETY NUMBER").font(.kLabel()).foregroundStyle(KTheme.textSecondary)
            Text(signal.mySafetyNumber).font(.system(.headline, design: .monospaced))
                .foregroundStyle(KTheme.accent).textSelection(.enabled)
            Text("Have your contact scan this QR code, or send them the key text below through any channel. This is your PUBLIC key — safe to share. Your private key never leaves this device.")
                .font(.kBody()).foregroundStyle(KTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    private var actionButtons: some View {
        HStack(spacing: 12) {
            Button { Clipboard.copy(key); flash() } label: {
                Label(copied ? "Copied" : "Copy key", systemImage: copied ? "checkmark" : "doc.on.doc")
            }.buttonStyle(SecondaryButtonStyle())
            ShareLink(item: key) { Label("Share", systemImage: "square.and.arrow.up") }
                .buttonStyle(PrimaryButtonStyle())
        }
    }

    private func flash() {
        withAnimation { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { withAnimation { copied = false } }
    }
}
