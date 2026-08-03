import SwiftUI

struct RootView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var selection: AppTab = .chats

    var body: some View {
        TabView(selection: $selection) {
            ForEach(settings.visibleTabs) { tab in
                screen(for: tab)
                    .tabItem { Label(tab.title, systemImage: tab.icon) }
                    .tag(tab)
            }
        }
        .tint(KTheme.accent)
        .onChange(of: settings.hiddenTabs) { _, _ in
            if !settings.visibleTabs.contains(selection) {
                selection = settings.visibleTabs.first ?? .settings
            }
        }
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
