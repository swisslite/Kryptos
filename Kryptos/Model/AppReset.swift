import Foundation
import Security

enum AppReset {
    @MainActor
    static func eraseEverythingAndQuit() -> Never {
        eraseAllStorage()
        exit(0)
    }

    @MainActor
    static func eraseAllStorage() {
        Clipboard.clear()
        PGPService.eraseAllStorage()
        SharedStore.eraseAll()
        Keychain.eraseAll()
        if let bundle = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundle)
            UserDefaults.standard.synchronize()
        }
        SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                       kSecAttrService as String: KeychainProbe.probeAccount] as CFDictionary)
        KeychainProbe.forget()
        sweepFiles()
        ConfigCaches.invalidateAll()
        OwnCipherMarker.forget()
        WipeMarker.bump()
    }

    private static func sweepFiles() {
        let fm = FileManager.default
        var scoped = [AppGroup.container]
        if let support = try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: false) {
            scoped.append(support)
        }
        let prefixes = ["kryptos-", "kcfallback-", "signal-"]
        for dir in scoped {
            guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { continue }
            for url in files where prefixes.contains(where: { url.lastPathComponent.hasPrefix($0) }) {
                try? fm.removeItem(at: url)
            }
        }

        var emptied = [fm.temporaryDirectory]
        if let caches = try? fm.url(for: .cachesDirectory, in: .userDomainMask,
                                    appropriateFor: nil, create: false) {
            emptied.append(caches)
        }
        if let library = try? fm.url(for: .libraryDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: false) {
            emptied.append(library.appendingPathComponent("SplashBoard"))
            emptied.append(library.appendingPathComponent("Saved Application State"))
        }
        for dir in emptied {
            guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { continue }
            for url in files { try? fm.removeItem(at: url) }
        }
    }
}

@MainActor
enum PanicWipe {
    static func run(signal: SignalService, pgp: PGPService, settings: AppSettings) {
        AppReset.eraseAllStorage()
        signal.resetAfterWipe()
        pgp.resetAfterWipe()
        settings.reloadAfterWipe()
    }
}
