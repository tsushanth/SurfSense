import SwiftUI

struct ContentView: View {
    @State private var selectedTab = 0 // Start with This Device tab
    
    var body: some View {
        NavigationView {
            TabView(selection: $selectedTab) {
                ThisDeviceView()
                    .tabItem {
                        Image(systemName: "iphone")
                        Text("This Device")
                    }
                    .tag(0)
                
                LinkedDevicesView()
                    .tabItem {
                        Image(systemName: "laptopcomputer")
                        Text("Linked Devices")
                    }
                    .tag(1)

                TrendsView()
                    .tabItem {
                        Image(systemName: "chart.line.uptrend.xyaxis")
                        Text("Trends")
                    }
                    .tag(2)

                SettingsView()
                    .tabItem {
                        Image(systemName: "gear")
                        Text("Settings")
                    }
                    .tag(3)
            }
            .navigationTitle(tabTitle(for: selectedTab))
            .navigationBarTitleDisplayMode(.inline)
            .frame(maxWidth: 700) // ✅ Limits overly wide layout on iPad
            .padding()
        }
        .navigationViewStyle(StackNavigationViewStyle()) // ✅ Avoids sidebar layout on iPad
    }
    
    private func tabTitle(for tag: Int) -> String {
        switch tag {
        case 0: return "This Device"
        case 1: return "Linked Devices"
        case 2: return "Trends"
        case 3: return "Settings"
        default: return ""
        }
    }
}
