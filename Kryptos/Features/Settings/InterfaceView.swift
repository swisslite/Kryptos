import SwiftUI

struct InterfaceView: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        List {
            Section {
                Picker("Theme", selection: $settings.uiTheme) {
                    Text("Automatic").tag("auto")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                }
                Picker("App language", selection: $settings.uiLanguage) {
                    Text("Automatic").tag("auto")
                    Text("English").tag("en")
                    Text("Russian").tag("ru")
                    Text("German").tag("de")
                    Text("Chinese").tag("zh-Hans")
                }
            } header: {
                Text("Appearance")
            } footer: {
                Text("Automatic follows the system. A new language applies the next time Kryptos starts.")
            }

            if settings.uiLanguage != AppLanguage.launchLanguage {
                Section {
                    Button {
                        exit(0)
                    } label: {
                        Label("Close Kryptos to apply", systemImage: "arrow.clockwise")
                    }
                }
            }

            Section {
                ForEach(AppTab.allCases.filter(\.canHide)) { tab in
                    Toggle(isOn: Binding(
                        get: { !settings.hiddenTabs.contains(tab) },
                        set: { settings.setTab(tab, visible: $0) },
                    )) {
                        Label(tab.title, systemImage: tab.icon)
                    }
                    .disabled(!settings.hiddenTabs.contains(tab) && settings.hideableOn <= 1)
                }
            } header: {
                Text("Tab bar")
            } footer: {
                Text("Tabs you switch off disappear from the bottom bar.")
            }

            Section {
                Toggle("Kryptos mark on the keyboard", isOn: $settings.keyboardShield)
            } header: {
                Text("Keyboard")
            } footer: {
                Text("Shows the mark in the bar above the keys.")
            }
        }
        .navigationTitle("Interface")
        .navigationBarTitleDisplayMode(.inline)
    }
}
