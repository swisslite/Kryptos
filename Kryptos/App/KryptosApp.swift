import SwiftUI
import CipherCore

@main
struct KryptosApp: App {
    @StateObject private var signal = SignalService()
    @StateObject private var settings = AppSettings()
    @StateObject private var lock = LockGate()
    @Environment(\.scenePhase) private var scenePhase
    @State private var incoming: RevealedIncoming?

    init() {
        #if DEBUG
        NSLog("SIGNAL_SELFTEST=%@", SignalService.selfTestError() ?? "PASS")
        NSLog("SIGNAL_WIRETEST=%@", SignalService.fullWireTestError() ?? "PASS")
        NSLog("PGP_SELFTEST=%@", PGPService.selfTestError() ?? "PASS")
        let probe = Data([0x03, 0x02, 0xAB, 0xCD, 0xEF, 0x10])
        let hidden = TextStego.encode(probe, language: .russian)
        NSLog("STEGO_SELFTEST=%@", TextStego.decode(hidden) == probe ? "PASS" : "FAIL")
        let smartHidden = SmartTextStego.encode(probe, language: .russian)
        NSLog("SMART_STEGO_SELFTEST=%@", SmartTextStego.decode(smartHidden) == probe && TextStego.decode(smartHidden) == nil ? "PASS" : "FAIL")
        NSLog("STEGO_WIRETEST=%@", SignalService.stegoWireTestError() ?? "PASS")
        NSLog("PROVISION_SELFTEST=%@", SignalService.provisioningSelfTestError() ?? "PASS")
        NSLog("CONTACT_DELETE_SELFTEST=%@", SignalService.contactDeletionSelfTestError() ?? "PASS")
        NSLog("PGP_CYCLE=%@", PGPService.exportCycleTestError() ?? "PASS")
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()
                    .environmentObject(signal)
                    .environmentObject(settings)
                if lock.isShielded && !lock.isLocked { PrivacyShield() }
                if lock.isLocked { LockScreen(gate: lock) }
            }
            .tint(KTheme.accent)
            .onChange(of: scenePhase) { _, phase in
                lock.scenePhaseChanged(phase)
                if phase == .active {
                    signal.reloadCurrentFromDisk()
                    if !lock.isLocked, let found = AutoDecrypt.scan(signal: signal) { incoming = found }
                } else if PrivacyConfig.shield || PrivacyConfig.appLock {
                    incoming = nil
                }
            }
            .onChange(of: lock.isLocked) { _, locked in
                if locked { incoming = nil }
                else if scenePhase == .active, let found = AutoDecrypt.scan(signal: signal) { incoming = found }
            }
            .sheet(item: $incoming) { IncomingRevealView(reveal: $0) }
        }
    }
}
