import XCTest
@testable import CipherCore

final class HardeningTests: XCTestCase {

    func testPaddingKeepsItsExistingShape() {
        XCTAssertEqual(Padding.target(0), 64)
        XCTAssertEqual(Padding.target(64), 64)
        XCTAssertEqual(Padding.target(65), 128)
        XCTAssertEqual(Padding.target(1 << 20), 1 << 20)
        XCTAssertEqual(Padding.target((1 << 20) + 1), 2 << 20)
        XCTAssertEqual(Padding.target(3 << 20), 3 << 20)
    }

    func testPaddingTargetSaturatesInsteadOfOverflowing() {
        XCTAssertEqual(Padding.target(Int.max), Int.max)
        XCTAssertEqual(Padding.target(Int.max - 1), Int.max)
    }

    func testPaddingFrameRefusesLengthsItCannotDescribe() {
        XCTAssertEqual(Padding.frame(Data()).count, 64)
        XCTAssertNotNil(Padding.unframe(Padding.frame(Data([1, 2, 3]))))
    }

    func testPixelBudgetShrinksWithAvailableMemory() {
        let small = ImageStego.pixelBudget(availableBytes: 40 * 1024 * 1024)
        let large = ImageStego.pixelBudget(availableBytes: 2048 * 1024 * 1024)
        XCTAssertEqual(small, ImageStego.minPixelBudget)
        XCTAssertGreaterThan(large, small)
        XCTAssertLessThanOrEqual(large, ImageStego.maxPixelBudget)
    }

    func testPixelBudgetClampsBothEnds() {
        XCTAssertEqual(ImageStego.pixelBudget(availableBytes: 0), ImageStego.minPixelBudget)
        XCTAssertEqual(ImageStego.pixelBudget(availableBytes: -1), ImageStego.minPixelBudget)
        XCTAssertEqual(ImageStego.pixelBudget(availableBytes: Int.max / 2), ImageStego.maxPixelBudget)
    }

    func testFiftyMegapixelPhotoIsRejectedOnAModestBudget() {
        let budget = ImageStego.pixelBudget(availableBytes: 300 * 1024 * 1024)
        XCTAssertGreaterThan(8160 * 6120, budget)
        XCTAssertLessThanOrEqual(3264 * 2448, budget)
    }

    func testDeflateRejectsOversizedExpansion() {
        let repetitive = Data(repeating: 0x41, count: 512 * 1024)
        let packed = Deflate.compress(repetitive)
        XCTAssertNotNil(packed)
        XCTAssertNil(Deflate.decompress(packed!, limit: 1024))
        XCTAssertEqual(Deflate.decompress(packed!), repetitive)
    }
}
