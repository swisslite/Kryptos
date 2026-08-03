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

    private nonisolated(unsafe) static var cachedCanAuthenticate: Bool?
    private static let availabilityLock = NSLock()

    static var canAuthenticate: Bool {
        availabilityLock.lock()
        if let cachedCanAuthenticate {
            availabilityLock.unlock()
            return cachedCanAuthenticate
        }
        availabilityLock.unlock()
        let value = LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
        availabilityLock.lock()
        cachedCanAuthenticate = value
        availabilityLock.unlock()
        return value
    }

    static func refreshAuthenticationAvailability() {
        availabilityLock.lock()
        cachedCanAuthenticate = nil
        availabilityLock.unlock()
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            isShielded = false
            if isLocked, cameFromBackground { unlock() }
            cameFromBackground = false
        case .inactive, .background:
            let state = PrivacyConfig.coverState()
            isShielded = state.shield || state.appLock
            if phase == .background {
                cameFromBackground = true
                LockGate.refreshAuthenticationAvailability()
                if state.appLock, LockGate.canAuthenticate { isLocked = true }
            }
        @unknown default:
            break
        }
    }

    private var codeFailures = 0

    var codeThrottle: Duration {
        let over = codeFailures - 4
        guard over > 0 else { return .zero }
        return .seconds(min(30, 1 << min(over - 1, 5)))
    }

    func noteCodeRejected() { if codeFailures < Int.max { codeFailures += 1 } }

    func forceUnlock() {
        codeFailures = 0
        authInFlight = false
        cameFromBackground = false
        isLocked = false
        isShielded = false
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
    let onCode: @MainActor (String) async -> Bool

    @State private var code = ""
    @State private var checking = false
    @State private var wrong = false

    var body: some View {
        ZStack {
            ScreenBackground()
            ScrollView {
                VStack(spacing: 20) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 44, weight: .semibold)).foregroundStyle(KTheme.accent)
                    Text("Kryptos is locked").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                    Button { gate.unlock() } label: { Label("Unlock", systemImage: "faceid") }
                        .buttonStyle(PrimaryButtonStyle())
                        .frame(maxWidth: 220)
                    codeEntry
                }
                .padding(32)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear { gate.unlock() }
    }

    private var codeEntry: some View {
        VStack(spacing: 10) {
            SecureField("Passcode", text: $code)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.go)
                .onSubmit { submit() }
                .padding(12)
                .background(FieldBackground())
            if wrong {
                Text("Wrong passcode.").font(.kLabel()).foregroundStyle(KTheme.danger)
            }
            Button(action: submit) {
                Label(checking ? "Working…" : "Continue", systemImage: checking ? "hourglass" : "arrow.right")
            }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(checking || code.count < LockCode.minLength)
        }
        .frame(maxWidth: 260)
        .padding(.top, 12)
    }

    private func submit() {
        guard !checking, code.count >= LockCode.minLength else { return }
        let entered = code
        code = ""
        wrong = false
        checking = true
        Task { @MainActor in
            let accepted = await onCode(entered)
            checking = false
            wrong = !accepted
        }
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
