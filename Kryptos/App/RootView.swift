import SwiftUI

struct RootView: View {
    var body: some View {
        TabView {
            SessionsView()
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right.fill") }

            PGPView()
                .tabItem { Label("PGP", systemImage: "envelope.fill") }

            QuickEncryptView()
                .tabItem { Label("Password", systemImage: "lock.fill") }

            StegoView()
                .tabItem { Label("Photo", systemImage: "photo.on.rectangle.angled") }

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .tint(KTheme.accent)
    }
}
