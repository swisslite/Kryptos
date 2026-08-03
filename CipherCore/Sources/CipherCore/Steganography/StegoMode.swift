import Foundation

public enum StegoMode: String, Sendable, CaseIterable, Identifiable {
    case words
    case smart
    case letters

    public var id: String { rawValue }

    public static func resolve(_ raw: String?, legacySmart: Bool) -> StegoMode {
        if let raw, let mode = StegoMode(rawValue: raw) { return mode }
        return legacySmart ? .smart : .words
    }
}
