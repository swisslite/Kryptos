import Foundation

public enum Kryptos {
    /// Shortest password the UI offers when encrypting. Decryption accepts any length so that
    /// messages made by older versions still open.
    public static let minPasswordLength = 6

    public static func encrypt(text: String, password: String, pad: Bool = false) throws -> String {
        let raw = try PasswordCipher.encrypt(Data(text.utf8), password: password, pad: pad)
        return WireFormat.token(raw)
    }

    public static func decrypt(armored text: String, password: String) throws -> String {
        guard let raw = WireFormat.tokenBytes(text) else { throw CipherError.notAKryptosMessage }
        let plaintext = try PasswordCipher.decrypt(raw, password: password)
        return String(decoding: plaintext, as: UTF8.self)
    }

    public static func containsMessage(_ text: String) -> Bool {
        WireFormat.isToken(text)
    }
}
