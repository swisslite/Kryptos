import SwiftUI
@preconcurrency import AVFoundation
import CoreImage.CIFilterBuiltins
import UIKit

struct QRWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

enum QRCode {
    private static let context = CIContext()
    private static let quietZone = 4

    @MainActor
    static func exactSide(modules: Int, available: CGFloat) -> CGFloat {
        let density = max(1, UIScreen.main.scale)
        guard modules > 0, available > 0 else { return 0 }
        let availablePixels = Int((available * density).rounded(.down))
        let scale = max(1, availablePixels / modules)
        return CGFloat(modules * scale) / density
    }

    static func image(from payload: Data) -> UIImage? {
        guard !payload.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = payload
        filter.correctionLevel = "L"
        guard let output = filter.outputImage,
              let code = context.createCGImage(output, from: output.extent) else { return nil }
        let width = code.width + quietZone * 2
        let height = code.height + quietZone * 2
        guard let ctx = CGContext(data: nil, width: width, height: height,
                                  bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceGray(),
                                  bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        ctx.interpolationQuality = .none
        ctx.setFillColor(gray: 1, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.draw(code, in: CGRect(x: quietZone, y: quietZone, width: code.width, height: code.height))
        guard let framed = ctx.makeImage() else { return nil }
        return UIImage(cgImage: framed)
    }

    static func payload(from descriptor: CIQRCodeDescriptor) -> Data? {
        let bytes = [UInt8](descriptor.errorCorrectedPayload)
        let total = bytes.count * 8
        var index = 0
        func read(_ count: Int) -> Int? {
            guard index + count <= total else { return nil }
            var value = 0
            for _ in 0 ..< count {
                let bit = (bytes[index >> 3] >> (7 - UInt8(index & 7))) & 1
                value = (value << 1) | Int(bit)
                index += 1
            }
            return value
        }
        var mode = read(4)
        if mode == 7 {
            guard let first = read(8) else { return nil }
            if first & 0x80 != 0 {
                guard read((first & 0xC0) == 0x80 ? 8 : 16) != nil else { return nil }
            }
            mode = read(4)
        }
        guard mode == 4 else { return nil }
        let countBits = descriptor.symbolVersion >= 10 ? 16 : 8
        guard let length = read(countBits), length > 0, index + length * 8 <= total else { return nil }
        var out = Data(capacity: length)
        for _ in 0 ..< length {
            guard let byte = read(8) else { return nil }
            out.append(UInt8(byte))
        }
        return out
    }
}

struct QRScannerView: UIViewControllerRepresentable {
    var onFound: (Data) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFound: onFound) }
    func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.onFound = context.coordinator.handle
        return vc
    }
    func updateUIViewController(_ vc: ScannerViewController, context: Context) {}

    final class Coordinator {
        private let onFound: (Data) -> Void
        private var delivered = false
        init(onFound: @escaping (Data) -> Void) { self.onFound = onFound }
        func handle(_ value: Data) {
            guard !delivered else { return }
            delivered = true
            onFound(value)
        }
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((Data) -> Void)?
    private nonisolated(unsafe) let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private let sessionQueue = DispatchQueue(label: "kryptos.qr.session", qos: .userInitiated)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer
        sessionQueue.async { [session, weak self] in
            session.beginConfiguration()
            if session.canSetSessionPreset(.hd1920x1080) {
                session.sessionPreset = .hd1920x1080
            } else {
                session.sessionPreset = .high
            }
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                session.commitConfiguration()
                return
            }
            session.addInput(input)
            ScannerViewController.tuneForDenseCodes(device)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else {
                session.commitConfiguration()
                return
            }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            session.commitConfiguration()
            if !session.isRunning { session.startRunning() }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stop()
    }

    private nonisolated func stop() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private nonisolated static func tuneForDenseCodes(_ device: AVCaptureDevice) {
        guard (try? device.lockForConfiguration()) != nil else { return }
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isSmoothAutoFocusSupported { device.isSmoothAutoFocusEnabled = true }
        if device.isAutoFocusRangeRestrictionSupported { device.autoFocusRangeRestriction = .near }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        device.unlockForConfiguration()
    }

    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let obj = objects.first as? AVMetadataMachineReadableCodeObject else { return }
        let decoded: Data?
        if let descriptor = obj.descriptor as? CIQRCodeDescriptor {
            decoded = QRCode.payload(from: descriptor)
        } else {
            decoded = nil
        }
        guard let value = decoded ?? obj.stringValue.flatMap({ $0.data(using: .isoLatin1) }) else { return }
        stop()
        MainActor.assumeIsolated { onFound?(value) }
    }
}
