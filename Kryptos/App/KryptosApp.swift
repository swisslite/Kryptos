import SwiftUI
import CipherCore

@main
struct KryptosApp: App {
    @StateObject private var signal = SignalService()
    @StateObject private var settings = AppSettings()
    @StateObject private var pgp = PGPService()
    @StateObject private var lock = LockGate()
    @Environment(\.scenePhase) private var scenePhase
    @State private var incoming: RevealedIncoming?

    init() {
        AppLanguage.captureLaunch(InterfaceConfig.language)
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                if signal.hasBooted {
                    RootView()
                        .environmentObject(signal)
                        .environmentObject(settings)
                        .environmentObject(pgp)
                        .environmentObject(lock)
                } else {
                    BootScreen()
                }
                if lock.isShielded && !lock.isLocked { PrivacyShield() }
                if lock.isLocked { LockScreen(gate: lock, onCode: handleCode) }
            }
            .task {
                signal.start()
                pgp.start()
                if scenePhase == .active, !lock.isLocked { scanClipboard() }
            }
            .preferredColorScheme(settings.colorScheme)
            .tint(KTheme.accent)
            .onChange(of: scenePhase) { _, phase in
                lock.scenePhaseChanged(phase)
                ScreenCover.set(lock.isShielded)
                if phase == .active {
                    SharedStore.revalidateBackend()
                    if signal.hasBooted { signal.reloadCurrentFromDisk() }
                    if !lock.isLocked { scanClipboard() }
                } else {
                    let cover = PrivacyConfig.coverState()
                    if cover.shield || cover.appLock { incoming = nil }
                }
            }
            .onChange(of: lock.isLocked) { _, locked in
                if locked {
                    incoming = nil
                    ScreenCover.dismissSharePresentations()
                } else if scenePhase == .active {
                    scanClipboard()
                }
            }
            .sheet(item: $incoming) { IncomingRevealView(reveal: $0) }
        }
    }

    @MainActor
    private func scanClipboard() {
        guard signal.hasBooted else { return }
        Task { @MainActor in
            if let found = await AutoDecrypt.scan(signal: signal), !lock.isLocked {
                incoming = found
            }
        }
    }

    @MainActor
    private func handleCode(_ code: String) async -> Bool {
        let throttle = lock.codeThrottle
        if throttle > .zero { try? await Task.sleep(for: throttle) }
        switch await LockCodes.classifyOffMain(code) {
        case .rejected:
            lock.noteCodeRejected()
            return false
        case .unlocked:
            lock.forceUnlock()
            return true
        case .wiped:
            incoming = nil
            PanicWipe.run(signal: signal, pgp: pgp, settings: settings)
            lock.forceUnlock()
            return true
        }
    }
}

private struct BootScreen: View {
    var body: some View {
        ZStack {
            ScreenBackground()
            ProgressView().tint(KTheme.accent)
        }
    }
}
