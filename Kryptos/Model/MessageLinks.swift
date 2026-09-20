import SwiftUI
import UIKit

enum MessageLinks {
    private static let scanLimit = 20_000
    private static let phoneSeparators: Set<Character> = [" ", "\u{00A0}", "-", "\u{2011}", "(", ")", ".", "/"]

    private static let detector = try? NSDataDetector(
        types: NSTextCheckingResult.CheckingType.link.rawValue
            | NSTextCheckingResult.CheckingType.phoneNumber.rawValue)

    static func sanitized(_ url: URL) -> URL? {
        switch url.scheme?.lowercased() {
        case "http", "https": return web(url)
        case "mailto": return mail(String(url.absoluteString.dropFirst("mailto:".count)))
        case "tel": return phone(String(url.absoluteString.dropFirst("tel:".count)))
        default: return nil
        }
    }

    /// NSDataDetector costs about 2 ms on a long message and runs on every redraw, so skip it for
    /// text that cannot hold a link, an address or a phone number: a link needs "://", "www." or a
    /// dot glued to a letter or digit, an address needs "@", and the shortest phone `phone(_:)`
    /// accepts has five digits.
    static func hasCandidate(_ text: String) -> Bool {
        var digits = 0
        var previousWasDot = false
        for character in text.prefix(scanLimit) {
            if character == "@" || character == ":" { return true }
            let isAlphanumeric = character.isLetter || character.isNumber
            if previousWasDot, isAlphanumeric { return true }
            previousWasDot = character == "."
            if character.isNumber {
                digits += 1
                if digits >= 5 { return true }
            }
        }
        return false
    }

    static func matches(in text: String) -> [(range: NSRange, url: URL)] {
        guard let detector, !text.isEmpty, hasCandidate(text) else { return [] }
        let ns = text as NSString
        let scan = NSRange(location: 0, length: min(ns.length, scanLimit))
        return detector.matches(in: text, options: [], range: scan).compactMap { match in
            guard let url = target(for: match) else { return nil }
            return (match.range, url)
        }
    }

    static func attributed(_ text: String, color: Color) -> AttributedString {
        var out = AttributedString(text)
        for match in matches(in: text) {
            guard let textRange = Range(match.range, in: text),
                  let range = Range(textRange, in: out) else { continue }
            out[range].link = match.url
            out[range].foregroundColor = color
            out[range].underlineStyle = .single
        }
        return out
    }

    static func attributed(_ text: String, font: UIFont, color: UIColor) -> NSAttributedString {
        let out = NSMutableAttributedString(string: text, attributes: [
            .font: font,
            .foregroundColor: color
        ])
        for match in matches(in: text) {
            out.addAttribute(.link, value: match.url, range: match.range)
        }
        return out
    }

    private static func target(for match: NSTextCheckingResult) -> URL? {
        if match.resultType == .phoneNumber { return match.phoneNumber.flatMap(phone) }
        return match.url.flatMap(sanitized)
    }

    private static func web(_ url: URL) -> URL? {
        guard let host = url.host, !host.isEmpty,
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.user == nil, parts.password == nil else { return nil }
        return url
    }

    private static func mail(_ rest: String) -> URL? {
        let address = String(rest.prefix { $0 != "?" && $0 != "#" })
        let decoded = address.removingPercentEncoding ?? address
        guard isAddress(decoded) else { return nil }
        return URL(string: "mailto:" + decoded)
    }

    private static func phone(_ rest: String) -> URL? {
        let decoded = rest.removingPercentEncoding ?? rest
        var digits = ""
        var international = false
        for (index, character) in decoded.enumerated() {
            if character == "+" {
                guard index == 0 else { return nil }
                international = true
            } else if character.isASCII, character.isNumber {
                digits.append(character)
            } else if !phoneSeparators.contains(character) {
                return nil
            }
        }
        guard (5 ... 15).contains(digits.count) else { return nil }
        return URL(string: "tel:" + (international ? "+" : "") + digits)
    }

    private static func isAddress(_ value: String) -> Bool {
        let parts = value.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        let local = parts[0], domain = parts[1]
        guard (1 ... 64).contains(local.count), (4 ... 255).contains(domain.count) else { return false }
        guard local.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "._%+-".contains($0)) }),
              domain.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-") })
        else { return false }
        guard let dot = domain.lastIndex(of: "."), domain.distance(from: dot, to: domain.endIndex) > 2,
              !domain.hasPrefix("."), !domain.hasPrefix("-"), !domain.hasSuffix("-") else { return false }
        return !domain.contains("..")
    }
}

struct LinkedText: View {
    let text: String
    let color: Color

    var body: some View {
        Text(MessageLinks.attributed(text, color: color)).tint(color)
    }
}

struct SelectableLinkedText: UIViewRepresentable {
    let text: String
    let color: UIColor
    let linkColor: UIColor
    var font: UIFont = .preferredFont(forTextStyle: .body)

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.adjustsFontForContentSizeCategory = true
        view.dataDetectorTypes = []
        view.delegate = context.coordinator
        view.setContentCompressionResistancePriority(.required, for: .vertical)
        view.setContentHuggingPriority(.required, for: .vertical)
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        view.linkTextAttributes = [.foregroundColor: linkColor,
                                   .underlineStyle: NSUnderlineStyle.single.rawValue]
        view.attributedText = MessageLinks.attributed(text, font: font, color: color)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let proposed = proposal.width ?? .greatestFiniteMagnitude
        let width = proposed.isFinite ? proposed : UIView.layoutFittingExpandedSize.width
        return uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UITextViewDelegate {
        func textView(_ textView: UITextView, primaryActionFor textItem: UITextItem,
                      defaultAction: UIAction) -> UIAction? {
            guard case .link(let url) = textItem.content, MessageLinks.sanitized(url) != nil else { return nil }
            return defaultAction
        }
    }
}
