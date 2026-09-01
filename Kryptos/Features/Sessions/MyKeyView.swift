import SwiftUI
import UIKit

struct MyKeyView: View {
    @EnvironmentObject private var signal: SignalService
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var share: SignalService.KeyShare?
    @State private var showQR = false
    @State private var qrImage: UIImage?
    @State private var qrTried = false
    @State private var savedBrightness: CGFloat?
    @State private var containerWidth: CGFloat = 0

    static let platePadding: CGFloat = 6

    private var key: String { share?.text ?? "" }

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
                    .background {
                        GeometryReader { proxy in
                            Color.clear.preference(key: QRWidthKey.self, value: proxy.size.width)
                        }
                    }
                    .onPreferenceChange(QRWidthKey.self) { containerWidth = $0 }
                    .padding(20)
                }
            }
            .navigationTitle("My key")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .task { if share == nil { share = signal.myKeyShare() } }
        .task(id: qrRequest) {
            guard showQR, qrImage == nil, !qrTried, let payload = share?.payload else { return }
            let rendered = await Task.detached(priority: .userInitiated) { QRCode.image(from: payload) }.value
            qrTried = true
            qrImage = rendered
        }
        .onChange(of: showQR) { _, on in setBright(on) }
        .onDisappear { setBright(false) }
    }

    private var qrRequest: String { "\(showQR)|\(share == nil)" }

    private func setBright(_ on: Bool) {
        if on {
            if savedBrightness == nil { savedBrightness = UIScreen.main.brightness }
            UIScreen.main.brightness = 1
        } else if let level = savedBrightness {
            UIScreen.main.brightness = level
            savedBrightness = nil
        }
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
            if let qrImage {
                let side = QRCode.exactSide(modules: Int(qrImage.size.width),
                                            available: containerWidth - MyKeyView.platePadding * 2)
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: side, height: side)
                    .padding(MyKeyView.platePadding)
            } else if !qrTried {
                Color.clear.aspectRatio(1, contentMode: .fit).overlay { ProgressView() }
            } else {
                Color.clear.aspectRatio(1, contentMode: .fit).overlay {
                    VStack(spacing: 10) {
                        Image(systemName: "qrcode").font(.system(size: 40)).foregroundStyle(.black.opacity(0.35))
                        Text("QR unavailable — use Copy key or Share below.")
                            .font(.kBody()).foregroundStyle(.black.opacity(0.55))
                            .multilineTextAlignment(.center).padding(.horizontal, 16)
                    }
                }
            }
        }
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous))
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
            Button { Clipboard.copyPublicKey(key); flash() } label: {
                Label(copied ? "Copied" : "Copy key", systemImage: copied ? "checkmark" : "doc.on.doc")
            }.buttonStyle(SecondaryButtonStyle(accent: true))
            ShareLink(item: key) { Label("Share", systemImage: "square.and.arrow.up") }
                .buttonStyle(PrimaryButtonStyle())
        }
    }

    private func flash() {
        withAnimation { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { withAnimation { copied = false } }
    }
}
