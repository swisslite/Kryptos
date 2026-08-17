import SwiftUI

struct RootView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var selection: AppTab = .chats

    private var current: AppTab {
        settings.visibleTabs.contains(selection) ? selection : (settings.visibleTabs.first ?? .settings)
    }

    var body: some View {
        TabView(selection: Binding(get: { current }, set: { selection = $0 })) {
            ForEach(settings.visibleTabs) { tab in
                screen(for: tab)
                    .tabItem { Label(tab.title, systemImage: tab.icon) }
                    .tag(tab)
            }
        }
        .tint(KTheme.accent)
    }

    @ViewBuilder
    private func screen(for tab: AppTab) -> some View {
        switch tab {
        case .chats: SessionsView()
        case .pgp: PGPView()
        case .quick: QuickEncryptView()
        case .stego: StegoView()
        case .settings: SettingsView()
        }
    }
}
