import XCTest
@testable import CipherCore

final class VectorGenTests: XCTestCase {
    func testPrintVectors() throws {
        let armored = try Kryptos.encrypt(text: "привет, Android! 🔐", password: "correct horse")
        print("VECTOR-PASSWORD-NEW-BEGIN\n\(armored)\nVECTOR-PASSWORD-NEW-END")

        let padded = try Kryptos.encrypt(text: "секрет", password: "pw", pad: true)
        print("VECTOR-PASSWORD-PAD-BEGIN\n\(padded)\nVECTOR-PASSWORD-PAD-END")

        let body = Data((0 ..< 80).map { UInt8($0) })
        let pairKey = Data("alicebob".utf8)
        let salt = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88])
        let token = WireFormat.wrap(body, type: 3, deflate: false, padded: false, pairKey: pairKey, salt: salt)
        print("VECTOR-WIRE-BEGIN\n\(token)\nVECTOR-WIRE-END")

        let deflateInput = Data(String(repeating: "Kryptos secure message ", count: 20).utf8)
        let deflated = Deflate.compress(deflateInput)!
        print("VECTOR-DEFLATE-BEGIN\n\(deflated.map { String(format: "%02x", $0) }.joined())\nVECTOR-DEFLATE-END")

        let payload = Data((0...0x20).map { UInt8($0) })
        print("VECTOR-STEGO-EN-BEGIN\n\(TextStego.encode(payload, language: .english))\nVECTOR-STEGO-EN-END")
        print("VECTOR-STEGO-RU-BEGIN\n\(TextStego.encode(payload, language: .russian))\nVECTOR-STEGO-RU-END")

        var pixels = [UInt8]()
        for i in 0 ..< 64 { pixels += [UInt8(i*3 % 256), UInt8(i*5 % 256), UInt8(i*7 % 256), 255] }
        let stego = try LSBStego.embed(payload: Data("KX-LSB-TEST".utf8), intoRGBA: pixels)
        print("VECTOR-LSB-BEGIN\n\(stego.map { String(format: "%02x", $0) }.joined())\nVECTOR-LSB-END")
    }
}
