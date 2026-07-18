import UIKit
import UniformTypeIdentifiers

enum Clipboard {
    nonisolated(unsafe) private(set) static var lastWritten: String?

    static func copy(_ text: String) {
        lastWritten = text
        OwnCipherMarker.mark(text)
        var options: [UIPasteboard.OptionsKey: Any] = [:]
        if PrivacyConfig.clipboardLocalOnly { options[.localOnly] = true }
        let expiry = PrivacyConfig.clipboardExpiry
        if expiry > 0 { options[.expirationDate] = Date().addingTimeInterval(expiry) }
        UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: text]], options: options)
    }
}
