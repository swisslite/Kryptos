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
        lastWritten = text
        var options: [UIPasteboard.OptionsKey: Any] = [:]
        let policy = PrivacyConfig.clipboardOptions()
        if policy.localOnly { options[.localOnly] = true }
        if policy.expiry > 0 { options[.expirationDate] = Date().addingTimeInterval(policy.expiry) }
        UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: text]], options: options)
    }

    static func clear() {
        lastWritten = nil
        UIPasteboard.general.setItems([], options: [:])
    }
}
