import SwiftUI
import LocalAuthentication

@MainActor
final class LockGate: ObservableObject {
    @Published private(set) var isLocked: Bool
    @Published private(set) var isShielded = false
    private var authInFlight = false
    private var cameFromBackground = false
    private var phaseShield = false
    private var captureShield = false

    init() {
        isLocked = PrivacyConfig.appLock && LockGate.lockUsable
        NotificationCenter.default.addObserver(
            forName: UIScreen.capturedDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshCapture() }
        }
        refreshCapture()
    }

    private static var screenIsCaptured: Bool {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .contains { $0.screen.isCaptured }
    }

    private func refreshCapture() {
        captureShield = LockGate.screenIsCaptured && PrivacyConfig.shield
        applyShield()
    }

    private func applyShield() {
        isShielded = phaseShield || captureShield
    }

    static var lockUsable: Bool {
        guard PrivacyConfig.appLockCodeOnly else { return canAuthenticate }
        return LockCodes.app.presence != .absent
    }

    struct LockState: Equatable {
        var enabled: Bool
        var codeOnly: Bool
    }

    static func resolveLockState(_ state: LockState, canSystem: Bool, appCodeSet: Bool) -> LockState {
        var codeOnly = state.codeOnly
        if codeOnly, !appCodeSet {
            codeOnly = false
        } else if !codeOnly, appCodeSet, !canSystem {
            codeOnly = true
        }
        let usable = codeOnly ? appCodeSet : canSystem
        return LockState(enabled: state.enabled && usable, codeOnly: codeOnly)
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
            phaseShield = false
            refreshCapture()
            if isLocked, cameFromBackground { unlock() }
            cameFromBackground = false
            if !isLocked { LockSession.open() }
        case .inactive, .background:
            let state = PrivacyConfig.coverState()
            phaseShield = state.shield || state.appLock
            applyShield()
            if phase == .background {
                cameFromBackground = true
                LockGate.refreshAuthenticationAvailability()
                if state.appLock, LockGate.lockUsable {
                    isLocked = true
                    LockSession.close()
                    LockMarker.bump()
                }
            }
        @unknown default:
            break
        }
    }

    func forceUnlock() {
        LockThrottle.reset()
        authInFlight = false
        cameFromBackground = false
        isLocked = false
        phaseShield = false
        refreshCapture()
        LockSession.open()
    }

    func unlock() {
        guard isLocked, !authInFlight, !PrivacyConfig.appLockCodeOnly else { return }
        authInFlight = true
        let ctx = LAContext()
        Task {
            defer { authInFlight = false }
            let reason = String(localized: "Unlock Kryptos")
            if (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) == true {
                isLocked = false
                LockSession.open()
            }
        }
    }
}

struct LockScreen: View {
    enum Outcome: Sendable { case accepted, rejected, unavailable }

    @ObservedObject var gate: LockGate
    let onCode: @MainActor (String) async -> Outcome

    @State private var code = ""
    @State private var checking = false
    @State private var failure: LocalizedStringKey?
    private let codeOnly = PrivacyConfig.appLockCodeOnly

    var body: some View {
        ZStack {
            ScreenBackground()
            ScrollView {
                VStack(spacing: 20) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 44, weight: .semibold)).foregroundStyle(KTheme.accent)
                    Text("Kryptos is locked").font(.kHeadline()).foregroundStyle(KTheme.textPrimary)
                    if !codeOnly {
                        Button { gate.unlock() } label: { Label("Unlock", systemImage: "faceid") }
                            .buttonStyle(PrimaryButtonStyle())
                            .frame(maxWidth: 220)
                    }
                    codeEntry
                }
                .padding(32)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear { if !codeOnly { gate.unlock() } }
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
            if let failure {
                Text(failure).font(.kLabel()).foregroundStyle(KTheme.danger)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
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
        failure = nil
        checking = true
        Task { @MainActor in
            let outcome = await onCode(entered)
            checking = false
            switch outcome {
            case .accepted: failure = nil
            case .rejected: failure = "Wrong passcode."
            case .unavailable: failure = "Secure storage is unavailable right now. Try again in a moment."
            }
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
