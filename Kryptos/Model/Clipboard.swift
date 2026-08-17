import UIKit
import UniformTypeIdentifiers

@MainActor
enum Clipboard {
    private(set) static var lastWritten: String?

    static func copyCipher(_ text: String) {
        OwnCipherMarker.mark(text)
        copy(text)
    }

    static func copy(_ text: String) {
        write(text, expiry: PrivacyConfig.clipboardOptions().expiry)
    }

    static func copyPlain(_ text: String) {
        write(text, expiry: 0)
    }

    private static func write(_ text: String, expiry: Double) {
        lastWritten = text
        var options: [UIPasteboard.OptionsKey: Any] = [:]
        if PrivacyConfig.clipboardLocalOnly { options[.localOnly] = true }
        if expiry > 0 { options[.expirationDate] = Date().addingTimeInterval(expiry) }
        UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: text]], options: options)
    }

    static func clear() {
        lastWritten = nil
        UIPasteboard.general.setItems([], options: [:])
    }
}
