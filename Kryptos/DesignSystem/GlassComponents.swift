import SwiftUI
import UIKit

extension View {
    func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                        to: nil, from: nil, for: nil)
    }
}

struct ScreenBackground: View {
    var body: some View {
        ZStack {
            KTheme.bg.ignoresSafeArea()
            GeometryReader { geo in
                RadialGradient(
                    colors: [KTheme.accent.opacity(0.16), .clear],
                    center: .topTrailing,
                    startRadius: 0,
                    endRadius: geo.size.width * 1.05
                )
                .ignoresSafeArea()
            }
        }
    }
}

struct GlassCard: ViewModifier {
    var cornerRadius: CGFloat = KTheme.corner

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26.0, *) {
            content
                .padding(18)
                .glassEffect(.regular, in: shape)
        } else {
            content
                .padding(18)
                .background(.ultraThinMaterial, in: shape)
                .overlay(shape.strokeBorder(KTheme.hairline, lineWidth: 1))
                .shadow(color: .black.opacity(0.12), radius: 14, y: 6)
        }
    }
}

extension View {
    func glassCard(cornerRadius: CGFloat = KTheme.corner) -> some View {
        modifier(GlassCard(cornerRadius: cornerRadius))
    }
}

struct GlassSurface<S: InsettableShape>: ViewModifier {
    let shape: S
    var tint: Color? = nil
    var interactive = false
    @Environment(\.isEnabled) private var isEnabled

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            let glass = tint.map { Glass.regular.tint($0) } ?? .regular
            content.glassEffect(glass.interactive(interactive && isEnabled), in: shape)
        } else {
            content
                .background(tint.map { AnyShapeStyle($0.gradient) } ?? AnyShapeStyle(.ultraThinMaterial), in: shape)
                .overlay(shape.strokeBorder(KTheme.hairline, lineWidth: 1))
        }
    }
}

extension View {
    func glassSurface<S: InsettableShape>(_ shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        modifier(GlassSurface(shape: shape, tint: tint, interactive: interactive))
    }

    @ViewBuilder
    func softScrollEdges() -> some View {
        if #available(iOS 26.0, *) {
            scrollEdgeEffectStyle(.soft, for: .vertical)
        } else {
            self
        }
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Surface(configuration: configuration)
    }

    private struct Surface: View {
        @Environment(\.isEnabled) private var isEnabled
        let configuration: Configuration

        var body: some View {
            configuration.label
                .font(.kHeadline())
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
                    .fill(KTheme.accentGradient))
                .shadow(color: KTheme.accent.opacity(shadow), radius: 10, y: 4)
                .opacity(isEnabled ? (configuration.isPressed ? 0.92 : 1) : 0.45)
                .scaleEffect(configuration.isPressed ? 0.985 : 1)
                .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
        }

        private var shadow: Double {
            guard isEnabled else { return 0 }
            return configuration.isPressed ? 0.12 : 0.28
        }
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    var accent = false

    func makeBody(configuration: Configuration) -> some View {
        Surface(configuration: configuration, accent: accent)
    }

    private struct Surface: View {
        @Environment(\.isEnabled) private var isEnabled
        let configuration: Configuration
        let accent: Bool

        var body: some View {
            let shape = RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
            return configuration.label
                .font(.kHeadline())
                .foregroundStyle(accent ? KTheme.accentInk : KTheme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background {
                    shape.fill(.ultraThinMaterial)
                    if accent { shape.fill(KTheme.accent.opacity(0.14)) }
                }
                .overlay(shape.strokeBorder(accent ? KTheme.accent.opacity(0.45) : KTheme.hairline,
                                            lineWidth: accent ? 1.5 : 1))
                .opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.45)
                .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
        }
    }
}

struct FieldBackground: View {
    var body: some View {
        RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
            .fill(KTheme.fieldFill)
            .overlay(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
                .strokeBorder(KTheme.hairline, lineWidth: 1))
    }
}

struct CopiedBanner: View {
    var text: LocalizedStringKey = "Encrypted and copied. Send it to your contact."
    private let green = Color(red: 0.2, green: 0.72, blue: 0.45)
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(green)
            Text(text).font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous).fill(green.opacity(0.12)))
    }
}

struct ScreenScaffold<Content: View>: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey?
    @ViewBuilder var content: () -> Content

    init(_ title: LocalizedStringKey,
         subtitle: LocalizedStringKey? = nil,
         @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.content = content
    }

    var body: some View {
        NavigationStack {
            ZStack {
                ScreenBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        if let subtitle {
                            Text(subtitle)
                                .font(.kBody())
                                .foregroundStyle(KTheme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        content()
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture { hideKeyboard() }
                }
                .scrollDismissesKeyboard(.interactively)
            }
            .navigationTitle(title)
        }
    }
}

struct PlaintextShareButton: View {
    let text: String
    let plaintext: Bool
    @State private var confirming = false

    var body: some View {
        Group {
            if plaintext {
                Button { confirming = true } label: {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            } else {
                ShareLink(item: text) { Label("Share", systemImage: "square.and.arrow.up") }
            }
        }
        .confirmationDialog("Share the decrypted text?", isPresented: $confirming, titleVisibility: .visible) {
            ShareLink(item: text) { Text("Share") }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It will be handed to the app you pick, and the system share sheet shows a preview of it.")
        }
    }
}
