import SwiftUI
import FamilyControls

struct SettingsView: View {
    @AppStorage(Config.UserDefaultsKeys.notificationsEnabled) private var notificationsEnabled = true
    @AppStorage(Config.UserDefaultsKeys.weeklyReportsEnabled) private var weeklyReportsEnabled = false
    @AppStorage(Config.UserDefaultsKeys.dataSyncEnabled) private var dataSyncEnabled = true

    @EnvironmentObject private var screenTimeService: ScreenTimeService
    @State private var showingResetAlert = false
    @State private var showingAbout = false

    private let deviceId = SecureStorage.shared.deviceId
    private let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    private let buildNumber = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"

    var body: some View {
        NavigationView {
            List {
                // MARK: - Diagnostics
                Section {
                    HStack {
                        Label("Screen Time Auth", systemImage: "lock.shield")
                        Spacer()
                        Text(authStatusText)
                            .foregroundColor(authStatusColor)
                            .font(.footnote)
                    }

                    HStack {
                        Label("API Key", systemImage: "key")
                        Spacer()
                        Text(SecureStorage.shared.apiKey != nil ? "Stored" : "Missing")
                            .foregroundColor(SecureStorage.shared.apiKey != nil ? .green : .red)
                            .font(.footnote)
                    }

                    HStack {
                        Label("Last Data Update", systemImage: "clock.arrow.circlepath")
                        Spacer()
                        Text(SharedUsageData.readLastUpdated() ?? "Never")
                            .foregroundColor(.secondary)
                            .font(.footnote)
                    }

                    if let summary = SharedUsageData.readCategorySummary() {
                        HStack {
                            Label("Cached Categories", systemImage: "square.grid.2x2")
                            Spacer()
                            Text("\(summary.count) (\(summary.values.reduce(0, +) / 60)m)")
                                .foregroundColor(.secondary)
                                .font(.footnote)
                        }
                    }
                } header: {
                    Text("Diagnostics")
                } footer: {
                    Text("Screen Time tracking requires a physical device with Screen Time enabled in iOS Settings.")
                }

                // MARK: - Sync Settings
                Section {
                    Toggle(isOn: $dataSyncEnabled) {
                        Label("Auto Sync", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .tint(.blue)

                    if dataSyncEnabled {
                        HStack {
                            Label("Sync Interval", systemImage: "clock")
                            Spacer()
                            Text("\(Config.usageSyncIntervalMinutes) minutes")
                                .foregroundColor(.secondary)
                        }
                    }
                } header: {
                    Text("Data Sync")
                } footer: {
                    Text("When enabled, usage data syncs automatically across your linked devices.")
                }

                // MARK: - Notifications
                Section {
                    Toggle(isOn: $notificationsEnabled) {
                        Label("Usage Alerts", systemImage: "bell.badge")
                    }
                    .tint(.blue)

                    Toggle(isOn: $weeklyReportsEnabled) {
                        Label("Weekly Reports", systemImage: "chart.bar.doc.horizontal")
                    }
                    .tint(.blue)
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("Receive insights about your device usage patterns.")
                }

                // MARK: - Device Info
                Section {
                    HStack {
                        Label("Device ID", systemImage: "iphone")
                        Spacer()
                        Text(String(deviceId.prefix(8)) + "...")
                            .foregroundColor(.secondary)
                            .font(.footnote)
                    }
                    .contextMenu {
                        Button {
                            UIPasteboard.general.string = deviceId
                        } label: {
                            Label("Copy Full ID", systemImage: "doc.on.doc")
                        }
                    }

                    HStack {
                        Label("Device Name", systemImage: "textformat")
                        Spacer()
                        Text(UIDevice.current.name)
                            .foregroundColor(.secondary)
                    }
                } header: {
                    Text("Device")
                }

                // MARK: - App Info
                Section {
                    Button {
                        showingAbout = true
                    } label: {
                        HStack {
                            Label("About", systemImage: "info.circle")
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary)
                                .font(.footnote)
                        }
                    }
                    .foregroundColor(.primary)

                    HStack {
                        Label("Version", systemImage: "number")
                        Spacer()
                        Text("\(appVersion) (\(buildNumber))")
                            .foregroundColor(.secondary)
                    }

                    if let privacyURL = URL(string: Config.privacyPolicyURL) {
                        Link(destination: privacyURL) {
                            HStack {
                                Label("Privacy Policy", systemImage: "hand.raised")
                                Spacer()
                                Image(systemName: "arrow.up.right")
                                    .foregroundColor(.secondary)
                                    .font(.footnote)
                            }
                        }
                        .foregroundColor(.primary)
                    }

                    if let termsURL = URL(string: Config.termsOfServiceURL) {
                        Link(destination: termsURL) {
                            HStack {
                                Label("Terms of Service", systemImage: "doc.text")
                                Spacer()
                                Image(systemName: "arrow.up.right")
                                    .foregroundColor(.secondary)
                                    .font(.footnote)
                            }
                        }
                        .foregroundColor(.primary)
                    }
                } header: {
                    Text("About")
                }

                // MARK: - Danger Zone
                Section {
                    Button(role: .destructive) {
                        showingResetAlert = true
                    } label: {
                        Label("Reset All Data", systemImage: "trash")
                    }
                } footer: {
                    Text("This will remove all local data and unlink all devices.")
                }
            }
            .navigationTitle("Settings")
            .alert("Reset All Data?", isPresented: $showingResetAlert) {
                Button("Cancel", role: .cancel) { }
                Button("Reset", role: .destructive) {
                    resetAllData()
                }
            } message: {
                Text("This action cannot be undone. All your local data will be deleted and devices will be unlinked.")
            }
            .sheet(isPresented: $showingAbout) {
                AboutView()
            }
        }
    }

    private var authStatusText: String {
        switch screenTimeService.authorizationStatus {
        case .approved: return "Approved"
        case .denied: return "Denied"
        case .notDetermined: return "Not Requested"
        }
    }

    private var authStatusColor: Color {
        switch screenTimeService.authorizationStatus {
        case .approved: return .green
        case .denied: return .red
        case .notDetermined: return .orange
        }
    }

    private func resetAllData() {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: Config.UserDefaultsKeys.linkedDeviceIds)
        defaults.removeObject(forKey: Config.UserDefaultsKeys.lastSyncTime)

        LocalStorage.shared.clearAllCache()
        SecureStorage.shared.clearAll()

        notificationsEnabled = true
        weeklyReportsEnabled = false
        dataSyncEnabled = true
    }
}

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Image(systemName: "chart.bar.xaxis")
                    .font(.system(size: 60))
                    .foregroundColor(.blue)

                Text("SurfSense")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("Track your digital wellness across all your devices")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                Spacer()

                VStack(spacing: 8) {
                    Text("Made with ❤️")
                        .font(.footnote)
                        .foregroundColor(.secondary)

                    Text("© 2025 SurfSense")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .padding(.bottom, 40)
            }
            .padding()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}

#Preview {
    SettingsView()
}
