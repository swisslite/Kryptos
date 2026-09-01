import UIKit
import CryptoKit
import UniformTypeIdentifiers

@MainActor
enum Clipboard {
    private static var lastDigest: String?
    private static var lastChangeCount: Int?

    static func wroteExactly(_ text: String) -> Bool {
        guard let lastDigest else { return false }
        return lastDigest == digest(text)
    }

    static func copyCipher(_ text: String) {
        OwnCipherMarker.mark(text)
        copy(text)
    }

    static func copy(_ text: String) {
        let options = PrivacyConfig.clipboardOptions()
        write(text, expiry: options.expiry, localOnly: options.localOnly)
    }

    static func copyPlain(_ text: String) {
        write(text, expiry: 0, localOnly: PrivacyConfig.clipboardLocalOnly)
    }

    static func copyPublicKey(_ text: String) {
        write(text, expiry: 0, localOnly: false)
    }

    private static func write(_ text: String, expiry: Double, localOnly: Bool) {
        var options: [UIPasteboard.OptionsKey: Any] = [:]
        if localOnly { options[.localOnly] = true }
        if expiry > 0 { options[.expirationDate] = Date().addingTimeInterval(expiry) }
        UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: text]], options: options)
        lastDigest = digest(text)
        lastChangeCount = UIPasteboard.general.changeCount
    }

    static func clearIfHolds(_ texts: [String]) {
        guard let lastDigest, let lastChangeCount, !texts.isEmpty,
              UIPasteboard.general.changeCount == lastChangeCount,
              texts.contains(where: { digest($0) == lastDigest }) else { return }
        clear()
    }

    static func clear() {
        lastDigest = nil
        lastChangeCount = nil
        UIPasteboard.general.setItems([], options: [:])
    }

    private static func digest(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
