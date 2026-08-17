import SwiftUI
import UIKit

private struct CoinBadge: View {
    let coin: DonationCoin
    static let side: CGFloat = 26

    var body: some View {
        Group {
            switch coin.id {
            case "xmr":
                Image("CoinXMR").resizable()
            case "ton":
                ZStack {
                    Circle().fill(.white)
                    Image("CoinTON")
                        .renderingMode(.template)
                        .resizable()
                        .foregroundStyle(Color(red: 0.00, green: 0.60, blue: 0.92))
                }
            default:
                Image("CoinBTC").resizable()
            }
        }
        .frame(width: CoinBadge.side, height: CoinBadge.side)
        .accessibilityHidden(true)
    }
}

struct DonateView: View {
    @State private var copied: String?
    @State private var shown: String?
    @State private var codes: [String: UIImage] = [:]
    @State private var failed: Set<String> = []
    @State private var savedBrightness: CGFloat?

    private static let qrWidth: CGFloat = 250

    var body: some View {
        List {
            Section {
                Text("Kryptos is free, with no ads and no tracking. If it is useful to you, you can support its development.")
            } footer: {
                Text("Check the address before you send.")
            }

            ForEach(Donations.coins) { coin in
                Section {
                    identity(coin)
                    Text(coin.grouped)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(KTheme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityLabel(Text(coin.address))
                    Button { copy(coin) } label: {
                        Label(copied == coin.id ? "Copied" : "Copy address",
                              systemImage: copied == coin.id ? "checkmark" : "doc.on.doc")
                    }
                    Button { toggle(coin) } label: {
                        Label(shown == coin.id ? "Hide QR code" : "Show QR code", systemImage: "qrcode")
                    }
                    if shown == coin.id { qrRow(coin) }
                }
            }
        }
        .navigationTitle("Support the project")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: shown) { await renderCode() }
        .onChange(of: shown) { _, value in setBright(value != nil) }
        .onDisappear { setBright(false) }
    }

    private func identity(_ coin: DonationCoin) -> some View {
        HStack(spacing: 11) {
            CoinBadge(coin: coin)
            Text(verbatim: coin.name)
                .font(.kHeadline())
                .foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
            Text(verbatim: coin.ticker)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(KTheme.textSecondary)
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(Capsule().fill(KTheme.fieldFill))
        }
    }

    @ViewBuilder
    private func qrRow(_ coin: DonationCoin) -> some View {
        HStack {
            Spacer(minLength: 0)
            if let image = codes[coin.id] {
                let side = QRCode.exactSide(modules: Int(image.size.width), available: Self.qrWidth)
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: side, height: side)
                    .padding(6)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous))
                    .accessibilityHidden(true)
            } else if failed.contains(coin.id) {
                Text("Could not build the QR code — copy the address instead.")
                    .font(.kBody())
                    .foregroundStyle(KTheme.textSecondary)
                    .multilineTextAlignment(.center)
            } else {
                ProgressView().frame(width: Self.qrWidth, height: Self.qrWidth)
            }
            Spacer(minLength: 0)
        }
    }

    private func copy(_ coin: DonationCoin) {
        Clipboard.copyPlain(coin.address)
        let id = coin.id
        withAnimation { copied = id }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { if copied == id { copied = nil } }
        }
    }

    private func toggle(_ coin: DonationCoin) {
        withAnimation { shown = shown == coin.id ? nil : coin.id }
    }

    private func renderCode() async {
        guard let id = shown, codes[id] == nil, !failed.contains(id),
              let coin = Donations.coins.first(where: { $0.id == id }) else { return }
        let payload = Data(coin.address.utf8)
        let image = await Task.detached(priority: .userInitiated) {
            QRCode.image(from: payload, correction: "M")
        }.value
        guard let image else {
            failed.insert(id)
            return
        }
        codes[id] = image
    }

    private func setBright(_ on: Bool) {
        if on {
            if savedBrightness == nil { savedBrightness = UIScreen.main.brightness }
            UIScreen.main.brightness = 1
        } else if let level = savedBrightness {
            UIScreen.main.brightness = level
            savedBrightness = nil
        }
    }
}
