import Foundation

enum TypingSession {
    private static let lock = NSLock()
    private nonisolated(unsafe) static var rollbackToken: String?
    private nonisolated(unsafe) static var rollbackKnown = false
    private nonisolated(unsafe) static var wipeToken: String?
    private nonisolated(unsafe) static var wipeKnown = false

    static func forget() {
        SuggestionEngine.shared.forgetTypingSession()
        PinyinEngine.shared.forgetTypingSession()
        EmojiData.forgetTypingSession()
    }

    static func dropEverything() {
        SuggestionEngine.shared.dropEverything()
        PinyinEngine.shared.dropEverything()
        EmojiData.dropEverything()
    }

    static func sync() -> Bool {
        let rollback = TypingRollbackMarker.token()
        let wipe = WipeMarker.token()
        lock.lock()
        let rollbackChanged = rollbackKnown && rollback != rollbackToken
        let wipeChanged = wipeKnown && wipe != wipeToken
        rollbackToken = rollback
        rollbackKnown = true
        wipeToken = wipe
        wipeKnown = true
        lock.unlock()

        if wipeChanged {
            dropEverything()
            return true
        }
        if rollbackChanged {
            forget()
            SuggestionEngine.shared.dropIfStoreGone()
            PinyinEngine.shared.dropIfStoreGone()
            EmojiData.dropIfStoreGone()
        }
        return false
    }
}
