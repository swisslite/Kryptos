import XCTest
@testable import CipherCore

final class VectorGenTests: XCTestCase {
    func testPrintVectors() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["KRYPTOS_EMIT_VECTORS"] == "1",
                          "set KRYPTOS_EMIT_VECTORS=1 to regenerate cross-platform vectors")
        try emitVectors()
    }

    private func emitVectors() throws {
        let profileSalt = Data((0 ..< 16).map { UInt8($0) })
        let profile = try Argon2id.derive(password: Data("correct horse".utf8), salt: profileSalt, length: 44)
        print("VECTOR-ARGON2-PROFILE-BEGIN\n\(profile.map { String(format: "%02x", $0) }.joined())\nVECTOR-ARGON2-PROFILE-END")

        let armored = try Kryptos.encrypt(text: "привет, Android! 🔐", password: "correct horse")
        print("VECTOR-PASSWORD-NEW-BEGIN\n\(armored)\nVECTOR-PASSWORD-NEW-END")

        let padded = try Kryptos.encrypt(text: "секрет", password: "pw", pad: true)
        print("VECTOR-PASSWORD-PAD-BEGIN\n\(padded)\nVECTOR-PASSWORD-PAD-END")

        let body = Data((0 ..< 80).map { UInt8($0) })
        let pairKey = Data("alicebob".utf8)
        let salt = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88])
        let token = try WireFormat.wrap(body, type: 3, deflate: false, padded: false, pairKey: pairKey, salt: salt)
        print("VECTOR-WIRE-BEGIN\n\(token)\nVECTOR-WIRE-END")

        let deflateInput = Data(String(repeating: "Kryptos secure message ", count: 20).utf8)
        let deflated = Deflate.compress(deflateInput)!
        print("VECTOR-DEFLATE-BEGIN\n\(deflated.map { String(format: "%02x", $0) }.joined())\nVECTOR-DEFLATE-END")

        let payload = Data((0...0x20).map { UInt8($0) })
        print("VECTOR-STEGO-EN-BEGIN\n\(TextStego.encode(payload, language: .english, seed: 0x5C))\nVECTOR-STEGO-EN-END")
        print("VECTOR-STEGO-RU-BEGIN\n\(TextStego.encode(payload, language: .russian, seed: 0xB3))\nVECTOR-STEGO-RU-END")
        print("VECTOR-STEGO-DE-BEGIN\n\(TextStego.encode(payload, language: .german, seed: 0x41))\nVECTOR-STEGO-DE-END")
        print("VECTOR-STEGO-ZH-BEGIN\n\(TextStego.encode(payload, language: .chinese, seed: 0x5C))\nVECTOR-STEGO-ZH-END")

        let letterProbe = Data([0x03, 0x02, 0xAB, 0xCD, 0xEF, 0x10, 0x22, 0x77, 0x91, 0x04, 0x5C, 0xBE])
        print("VECTOR-LETTERS-EN-BEGIN\n\(LetterStego.encode(letterProbe, language: .english, seed: 0x5C))\nVECTOR-LETTERS-EN-END")
        print("VECTOR-LETTERS-RU-BEGIN\n\(LetterStego.encode(letterProbe, language: .russian, seed: 0xB3))\nVECTOR-LETTERS-RU-END")
        print("VECTOR-SMART-DE-BEGIN\n\(SmartTextStego.encode(letterProbe, language: .german, seed: 0x41))\nVECTOR-SMART-DE-END")
        print("VECTOR-SMART-EN-BEGIN\n\(SmartTextStego.encode(letterProbe, language: .english, seed: 0x5C))\nVECTOR-SMART-EN-END")
        print("VECTOR-SMART-RU-BEGIN\n\(SmartTextStego.encode(letterProbe, language: .russian, seed: 0xB3))\nVECTOR-SMART-RU-END")
        print("VECTOR-SMART-ZH-BEGIN\n\(SmartTextStego.encode(letterProbe, language: .chinese, seed: 0x5C))\nVECTOR-SMART-ZH-END")
        print("VECTOR-LETTERS-ZH-BEGIN\n\(LetterStego.encode(letterProbe, language: .chinese, seed: 0x5C))\nVECTOR-LETTERS-ZH-END")
        print("VECTOR-STEGO-FA-BEGIN\n\(TextStego.encode(payload, language: .persian, seed: 0x5C))\nVECTOR-STEGO-FA-END")
        print("VECTOR-SMART-FA-BEGIN\n\(SmartTextStego.encode(letterProbe, language: .persian, seed: 0x5C))\nVECTOR-SMART-FA-END")
        print("VECTOR-LETTERS-FA-BEGIN\n\(LetterStego.encode(letterProbe, language: .persian, seed: 0x5C))\nVECTOR-LETTERS-FA-END")

        let paddedCipher = Data((0 ..< 40).map { UInt8(($0 * 31) & 0xFF) })
        let paddedPayload = StegoWire.frame(paddedCipher, type: 2, deflate: false, padded: true)
        print("VECTOR-STEGO-PADDED-RU-BEGIN\n\(TextStego.encode(paddedPayload, language: .russian, seed: 0x11))\nVECTOR-STEGO-PADDED-RU-END")
        print("VECTOR-LETTERS-PADDED-RU-BEGIN\n\(LetterStego.encode(paddedPayload, language: .russian, seed: 0x11))\nVECTOR-LETTERS-PADDED-RU-END")
        print("VECTOR-STEGO-PADDED-CT-BEGIN\n\(paddedCipher.map { String(format: "%02x", $0) }.joined())\nVECTOR-STEGO-PADDED-CT-END")

        var state: UInt64 = 7
        func next() -> UInt8 {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return UInt8((state >> 33) & 0xFF)
        }
        var pixels = [UInt8](repeating: 0, count: 128 * 128 * 4)
        for y in 0 ..< 128 {
            for x in 0 ..< 128 {
                let base = (y * 128 + x) * 4
                if x < 64 { pixels[base] = 120; pixels[base + 1] = 130; pixels[base + 2] = 140 }
                else { pixels[base] = next(); pixels[base + 1] = next(); pixels[base + 2] = next() }
                pixels[base + 3] = 255
            }
        }
        let stego = try ImageStego.hide(Data("KX-STEGO-TEST".utf8), password: "stego pw",
                                        rgba: pixels, width: 128, height: 128)
        var diff = [String]()
        for i in 0 ..< pixels.count where pixels[i] != stego[i] {
            diff.append("\(i):\(stego[i])")
        }
        print("VECTOR-STEGO-IMG-BEGIN\n\(diff.joined(separator: ","))\nVECTOR-STEGO-IMG-END")
    }
}
