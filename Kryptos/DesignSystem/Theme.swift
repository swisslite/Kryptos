import SwiftUI
import UIKit

extension Color {
    init(light: UIColor, dark: UIColor) {
        self = Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? dark : light
        })
    }
}

enum KTheme {
    static let bg = Color(
        light: UIColor(red: 0.945, green: 0.953, blue: 0.965, alpha: 1),
        dark: UIColor(red: 0.043, green: 0.051, blue: 0.063, alpha: 1))

    static let textPrimary = Color(
        light: UIColor(red: 0.07, green: 0.08, blue: 0.10, alpha: 1),
        dark: UIColor(red: 0.95, green: 0.96, blue: 0.98, alpha: 1))

    static let textSecondary = Color(
        light: UIColor(red: 0.07, green: 0.08, blue: 0.10, alpha: 0.55),
        dark: UIColor(red: 1, green: 1, blue: 1, alpha: 0.58))

    static let accent = Color(
        light: UIColor(red: 0.216, green: 0.286, blue: 0.760, alpha: 1),
        dark: UIColor(red: 0.420, green: 0.520, blue: 0.980, alpha: 1))

    static let accentBright = Color(
        light: UIColor(red: 0.310, green: 0.390, blue: 0.880, alpha: 1),
        dark: UIColor(red: 0.520, green: 0.610, blue: 1.000, alpha: 1))

    static let accentGradient = LinearGradient(colors: [accentBright, accent],
                                               startPoint: .top, endPoint: .bottom)

    static let danger = Color(
        light: UIColor(red: 0.78, green: 0.18, blue: 0.22, alpha: 1),
        dark: UIColor(red: 1.0, green: 0.42, blue: 0.46, alpha: 1))

    static let hairline = Color(
        light: UIColor(white: 0, alpha: 0.10),
        dark: UIColor(white: 1, alpha: 0.10))

    static let fieldFill = Color(
        light: UIColor(white: 0, alpha: 0.04),
        dark: UIColor(white: 1, alpha: 0.05))

    static let corner: CGFloat = 20
    static let cornerSmall: CGFloat = 14
}

extension Font {
    static func kHeadline() -> Font { .system(.headline, design: .default).weight(.semibold) }
    static func kBody() -> Font { .system(.body, design: .default) }
    static func kLabel() -> Font { .system(.caption, design: .default).weight(.semibold) }
    static func kMono() -> Font { .system(.footnote, design: .monospaced) }
}
