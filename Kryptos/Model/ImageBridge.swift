import UIKit

extension UIImage {
    var normalizedUp: UIImage {
        guard imageOrientation != .up else { return self }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }
    }
}

enum ImageBridge {
    private static let bitmapInfo = CGImageAlphaInfo.noneSkipLast.rawValue
    private static let maxPixels = 100_000_000

    static func rgba(from image: UIImage) -> (pixels: [UInt8], width: Int, height: Int)? {
        guard let cg = image.cgImage else { return nil }
        let w = cg.width, h = cg.height
        guard w > 0, h > 0, w * h <= maxPixels else { return nil }
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let cs = CGColorSpaceCreateDeviceRGB()
        let drawn = pixels.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h,
                                      bitsPerComponent: 8, bytesPerRow: w * 4, space: cs,
                                      bitmapInfo: bitmapInfo) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return drawn ? (pixels, w, h) : nil
    }

    static func pngData(fromRGBA pixels: [UInt8], width w: Int, height h: Int) -> Data? {
        var data = pixels
        let cs = CGColorSpaceCreateDeviceRGB()
        let image = data.withUnsafeMutableBytes { raw -> CGImage? in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h,
                                      bitsPerComponent: 8, bytesPerRow: w * 4, space: cs,
                                      bitmapInfo: bitmapInfo) else { return nil }
            return ctx.makeImage()
        }
        guard let image else { return nil }
        return UIImage(cgImage: image).pngData()
    }
}
