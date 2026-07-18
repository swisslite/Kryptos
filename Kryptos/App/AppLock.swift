import SwiftUI
import LocalAuthentication

@MainActor
final class LockGate: ObservableObject {
    @Published private(set) var isLocked: Bool
    @Published private(set) var isShielded = false
    private var authInFlight = false
    private var cameFromBackground = false

    init() {
        isLocked = PrivacyConfig.appLock && LockGate.canAuthenticate
    }

    static var canAuthenticate: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            isShielded = false
            if isLocked, cameFromBackground { unlock() }
            cameFromBackground = false
        case .inactive, .background:
            isShielded = PrivacyConfig.shield || PrivacyConfig.appLock
            if phase == .background {
                cameFromBackground = true
                if PrivacyConfig.appLock, LockGate.canAuthenticate { isLocked = true }
            }
        @unknown default:
            break
        }
    }

    func unlock() {
        guard isLocked, !authInFlight else { return }
        authInFlight = true
        let ctx = LAContext()
        Task {
            defer { authInFlight = false }
            let reason = String(localized: "Unlock Kryptos")
            if (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) == true {
                isLocked = false
            }
        }
    }
}

struct LockScreen: View {
    @ObservedObject var gate: LockGate

    var body: some View {
        ZStack {
            ScreenBackground()
            VStack(spacing: 20) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 44, weight: .semibold)).foregroundStyle(KTheme.accent)
                Text("Kryptos is locked").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                Button { gate.unlock() } label: { Label("Unlock", systemImage: "faceid") }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 220)
            }
            .padding(32)
        }
        .onAppear { gate.unlock() }
    }
}

struct PrivacyShield: View {
    var body: some View {
        ZStack {
            ScreenBackground()
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 56, weight: .semibold)).foregroundStyle(KTheme.accent)
        }
    }
}
