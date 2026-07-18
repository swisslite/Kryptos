import Foundation

public enum Kryptos {

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
