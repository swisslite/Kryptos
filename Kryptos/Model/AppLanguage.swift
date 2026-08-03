import Foundation

@MainActor
enum AppLanguage {
    static let supported = InterfaceConfig.supportedLanguages

    private(set) static var launchLanguage = "auto"

    static func captureLaunch(_ code: String) {
        launchLanguage = code
        apply(code)
    }

    static func apply(_ code: String) {
        if supported.contains(code) {
            UserDefaults.standard.set([code], forKey: "AppleLanguages")
        } else {
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
        }
    }
}
