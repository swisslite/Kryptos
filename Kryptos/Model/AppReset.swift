import Foundation
import Security

enum AppReset {
    @MainActor
    static func eraseEverythingAndQuit() -> Never {
        SharedStore.eraseAll()
        Keychain.eraseAll()
        PGPService.eraseAllStorage()
        if let bundle = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundle)
        }
        for probeService in ["kryptos.teamid.probe"] {
            SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                           kSecAttrService as String: probeService] as CFDictionary)
        }
        sweepFiles()
        exit(0)
    }

    private static func sweepFiles() {
        let fm = FileManager.default
        var dirs = [AppGroup.container, fm.temporaryDirectory]
        if let support = try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: false) {
            dirs.append(support)
        }
        let prefixes = ["kryptos-", "kcfallback-", "signal-"]
        for dir in dirs {
            guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { continue }
            for url in files where prefixes.contains(where: { url.lastPathComponent.hasPrefix($0) }) {
                try? fm.removeItem(at: url)
            }
        }
    }
}
