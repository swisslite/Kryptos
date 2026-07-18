import SwiftUI
import PhotosUI
import UIKit
import UniformTypeIdentifiers
import CipherCore

struct StegoView: View {
    enum Mode { case hide, reveal }

    @State private var mode: Mode = .hide
    @State private var pickerItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var sourceImage: UIImage?
    @State private var password = ""
    @State private var message = ""
    @State private var resultImage: UIImage?
    @State private var resultFile: URL?
    @State private var revealed: String?
    @State private var errorText: String?

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
    }

    private var pickCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            fieldLabel(mode == .hide ? "COVER PHOTO" : "PHOTO WITH A SECRET")
            PhotosPicker(selection: $pickerItem, matching: .images) {
                if let sourceImage {
                    Image(uiImage: sourceImage)
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
            Button(action: hide) { Label("Hide in photo", systemImage: "eye.slash.fill") }
                .buttonStyle(PrimaryButtonStyle())
        }
        .glassCard()
    }

    private var revealCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            fieldLabel("PASSWORD")
            SecureField("shared secret", text: $password)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(FieldBackground())
            Button(action: reveal) { Label("Reveal message", systemImage: "eye.fill") }
                .buttonStyle(PrimaryButtonStyle())
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
        errorText = nil; resultImage = nil; resultFile = nil
        guard let img = sourceImage?.normalizedUp else { errorText = String(localized: "Choose a photo first."); return }
        guard !password.isEmpty else { errorText = String(localized: "Enter a password."); return }
        guard !message.isEmpty else { errorText = String(localized: "Enter some text."); return }
        guard let (pixels, w, h) = ImageBridge.rgba(from: img) else { errorText = String(localized: "Could not read the photo."); return }
        do {
            let blob = try PasswordCipher.encrypt(Data(message.utf8), password: password)
            guard blob.count <= LSBStego.capacity(pixelCount: w * h) else {
                errorText = String(localized: "The message is too large for this photo. Use a bigger photo or a shorter message.")
                return
            }
            let stego = try LSBStego.embed(payload: blob, intoRGBA: pixels)
            guard let png = ImageBridge.pngData(fromRGBA: stego, width: w, height: h),
                  let out = UIImage(data: png) else { errorText = String(localized: "Could not build the image."); return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("kryptos-hidden.png")
            try png.write(to: url, options: [.atomic, .completeFileProtection])
            resultImage = out; resultFile = url
        } catch { errorText = String(localized: "Could not hide the message.") }
    }

    private func reveal() {
        errorText = nil; revealed = nil
        guard let img = sourceImage else { errorText = String(localized: "Choose a photo first."); return }
        guard !password.isEmpty else { errorText = String(localized: "Enter a password."); return }
        guard let (pixels, _, _) = ImageBridge.rgba(from: img) else { errorText = String(localized: "Could not read the photo."); return }
        do {
            let blob = try LSBStego.extract(fromRGBA: pixels)
            let plain = try PasswordCipher.decrypt(blob, password: password)
            revealed = String(decoding: plain, as: UTF8.self)
        } catch CipherError.noHiddenData {
            errorText = String(localized: "No hidden message found in this photo.")
        } catch {
            errorText = String(localized: "Wrong password or corrupted data.")
        }
    }

    private func loadImage(_ item: PhotosPickerItem?) {
        guard let item else { return }
        reset()
        Task {
            if let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                await MainActor.run { sourceImage = img }
            }
        }
    }

    private func loadFile(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        reset()
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let img = UIImage(data: data) else {
            errorText = String(localized: "Could not read the file.")
            return
        }
        pickerItem = nil
        sourceImage = img
    }

    private func reset() {
        resultImage = nil; resultFile = nil; revealed = nil; errorText = nil
    }
}
