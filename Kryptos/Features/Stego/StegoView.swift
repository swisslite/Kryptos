import SwiftUI
import PhotosUI
import UIKit
import UniformTypeIdentifiers
import CipherCore

enum StegoOutcome: Sendable {
    case hidden(Data)
    case revealed(String)
    case failed(String)
}

private enum StegoWork {
    static let previewPixels = 900

    static func hide(cover: Data, password: String, message: String) -> StegoOutcome {
        guard let img = ImageBridge.coverImage(from: cover),
              let (pixels, w, h) = ImageBridge.rgba(from: img) else {
            return .failed(String(localized: "Could not read the photo."))
        }
        do {
            var stego = try ImageStego.hide(Data(message.utf8), password: password, rgba: pixels,
                                            width: w, height: h)
            guard let png = ImageBridge.pngData(fromRGBA: &stego, width: w, height: h) else {
                return .failed(String(localized: "Could not build the image."))
            }
            return .hidden(png)
        } catch CipherError.stegoCapacityExceeded {
            return .failed(String(localized: "The message is too large for this photo. Use a bigger or more detailed photo, or a shorter message."))
        } catch {
            return .failed(String(localized: "Could not hide the message."))
        }
    }

    static func reveal(carrier: Data, password: String) -> StegoOutcome {
        guard let img = UIImage(data: carrier) else {
            return .failed(String(localized: "Could not read the photo."))
        }
        guard ImageBridge.isWithinLimits(img) else {
            return .failed(String(localized: "This photo is too large. Use a smaller one."))
        }
        guard let (pixels, w, h) = ImageBridge.rgba(from: img) else {
            return .failed(String(localized: "Could not read the photo."))
        }
        do {
            let plain = try ImageStego.reveal(rgba: pixels, width: w, height: h, password: password)
            return .revealed(String(decoding: plain, as: UTF8.self))
        } catch {
            return .failed(String(localized: "No message found — wrong password, or this photo carries nothing."))
        }
    }
}

struct StegoView: View {
    enum Mode { case hide, reveal }

    @EnvironmentObject private var lock: LockGate
    @State private var mode: Mode = .hide
    @State private var pickerItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var sourceData: Data?
    @State private var preview: UIImage?
    @State private var password = ""
    @State private var message = ""
    @State private var resultImage: UIImage?
    @State private var resultFile: URL?
    @State private var revealed: String?
    @State private var errorText: String?
    @State private var busy = false

    var body: some View {
        ScreenScaffold("Photo",
                       subtitle: "Hide an encrypted message inside an ordinary photo. Send it as a file (not as a compressed photo) so the hidden data survives.") {
            Picker("", selection: $mode) {
                Text("Hide").tag(Mode.hide)
                Text("Reveal").tag(Mode.reveal)
            }
            .pickerStyle(.segmented)
            .onChange(of: mode) { _, _ in reset() }

            pickCard
            if mode == .hide { hideCard } else { revealCard }

            if let errorText { banner(errorText) }
            if mode == .hide, let resultImage, let resultFile { resultCard(resultImage, resultFile) }
            if mode == .reveal, let revealed { revealedCard(revealed) }
        }
        .onChange(of: pickerItem) { _, item in loadImage(item) }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.image]) { loadFile($0) }
        .onChange(of: lock.isLocked) { _, locked in
            guard locked else { return }
            showFileImporter = false
            revealed = nil
            password = ""
        }
        .onDisappear { discardResultFile() }
    }

    private var pickCard: some View {
        let shown = preview
        return VStack(alignment: .leading, spacing: 12) {
            fieldLabel(mode == .hide ? "COVER PHOTO" : "PHOTO WITH A SECRET")
            PhotosPicker(selection: $pickerItem, matching: .images) {
                if let shown {
                    Image(uiImage: shown)
                        .resizable().scaledToFill()
                        .frame(height: 170).frame(maxWidth: .infinity)
                        .clipShape(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous))
                } else {
                    HStack { Image(systemName: "photo.badge.plus"); Text("Choose a photo") }
                        .font(.kHeadline()).foregroundStyle(KTheme.accent)
                        .frame(maxWidth: .infinity).frame(height: 90)
                        .background(FieldBackground())
                }
            }
            Button { showFileImporter = true } label: {
                Label("Choose a file (PNG, JPG…)", systemImage: "folder")
            }
            .buttonStyle(SecondaryButtonStyle())
        }
        .glassCard()
    }

    private var hideCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            fieldLabel("PASSWORD")
            SecureField("shared secret", text: $password)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(FieldBackground())
            fieldLabel("SECRET MESSAGE")
            TextEditor(text: $message)
                .frame(minHeight: 100).scrollContentBackground(.hidden)
                .padding(8).background(FieldBackground())
            Button(action: hide) {
                Label(busy ? "Working…" : "Hide in photo", systemImage: busy ? "hourglass" : "eye.slash.fill")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(busy)
        }
        .glassCard()
    }

    private var revealCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            fieldLabel("PASSWORD")
            SecureField("shared secret", text: $password)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(FieldBackground())
            Button(action: reveal) {
                Label(busy ? "Working…" : "Reveal message", systemImage: busy ? "hourglass" : "eye.fill")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(busy)
        }
        .glassCard()
    }

    private func resultCard(_ image: UIImage, _ file: URL) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            fieldLabel("PHOTO WITH HIDDEN MESSAGE")
            Image(uiImage: image).resizable().scaledToFit()
                .frame(maxHeight: 220)
                .clipShape(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous))
            ShareLink(item: file) { Label("Share as file", systemImage: "square.and.arrow.up") }
                .buttonStyle(PrimaryButtonStyle())
            Text("Send it as a document/file. Sending it as a normal photo will recompress it and destroy the hidden data.")
                .font(.kMono()).foregroundStyle(KTheme.textSecondary)
        }
        .glassCard()
    }

    private func revealedCard(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            fieldLabel("HIDDEN MESSAGE")
            Text(text).font(.kBody()).foregroundStyle(KTheme.textPrimary).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .glassCard()
    }

    private func fieldLabel(_ t: LocalizedStringKey) -> some View {
        Text(t).font(.kLabel()).foregroundStyle(KTheme.textSecondary)
    }

    private func banner(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(KTheme.danger)
            Text(text).font(.kBody()).foregroundStyle(KTheme.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: KTheme.cornerSmall, style: .continuous)
            .fill(KTheme.danger.opacity(0.12)))
    }

    private func hide() {
        errorText = nil; discardResultFile(); resultImage = nil
        guard let cover = sourceData else { errorText = String(localized: "Choose a photo first."); return }
        guard !password.isEmpty else { errorText = String(localized: "Enter a password."); return }
        guard !message.isEmpty else { errorText = String(localized: "Enter some text."); return }
        let secret = password
        let text = message
        busy = true
        Task {
            let outcome = await Task.detached(priority: .userInitiated) {
                StegoWork.hide(cover: cover, password: secret, message: text)
            }.value
            switch outcome {
            case .hidden(let png):
                let thumb = await Task.detached(priority: .userInitiated) {
                    ImageBridge.thumbnail(from: png, maxPixel: StegoWork.previewPixels)
                }.value
                busy = false
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent("kryptos-\(UUID().uuidString).png")
                guard let thumb,
                      (try? png.write(to: url, options: [.atomic, .completeFileProtection])) != nil else {
                    errorText = String(localized: "Could not build the image.")
                    return
                }
                resultImage = thumb
                resultFile = url
            case .failed(let message):
                busy = false
                errorText = message
            case .revealed:
                busy = false
            }
        }
    }

    private func reveal() {
        errorText = nil; revealed = nil
        guard let carrier = sourceData else { errorText = String(localized: "Choose a photo first."); return }
        guard !password.isEmpty else { errorText = String(localized: "Enter a password."); return }
        let secret = password
        busy = true
        Task {
            let outcome = await Task.detached(priority: .userInitiated) {
                StegoWork.reveal(carrier: carrier, password: secret)
            }.value
            busy = false
            switch outcome {
            case .revealed(let text):
                revealed = text
            case .failed(let message):
                errorText = message
            case .hidden:
                break
            }
        }
    }

    private func loadImage(_ item: PhotosPickerItem?) {
        guard let item else { return }
        reset()
        Task {
            guard let data = try? await item.loadTransferable(type: Data.self) else { return }
            await adopt(data)
        }
    }

    private func loadFile(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        reset()
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            errorText = String(localized: "Could not read the file.")
            return
        }
        pickerItem = nil
        Task { await adopt(data) }
    }

    private func adopt(_ data: Data) async {
        let thumb = await Task.detached(priority: .userInitiated) {
            ImageBridge.thumbnail(from: data, maxPixel: StegoWork.previewPixels)
        }.value
        guard let thumb else {
            errorText = String(localized: "Could not read the file.")
            return
        }
        sourceData = data
        preview = thumb
    }

    private func discardResultFile() {
        if let url = resultFile { try? FileManager.default.removeItem(at: url) }
        resultFile = nil
    }

    private func reset() {
        discardResultFile()
        resultImage = nil; revealed = nil; errorText = nil
    }
}
