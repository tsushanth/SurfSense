import SwiftUI
import FamilyControls
import DeviceActivity

struct ThisDeviceView: View {
    @EnvironmentObject private var screenTimeService: ScreenTimeService
    @StateObject private var usageService = UsageTrackingService()

    private let reportContext = DeviceActivityReport.Context(rawValue: "TotalActivity")

    private var todayFilter: DeviceActivityFilter {
        let calendar = Calendar.current
        let now = Date()
        let startOfDay = calendar.startOfDay(for: now)
        // Use end of day so .daily produces a complete segment
        let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay) ?? now
        let dateInterval = DateInterval(start: startOfDay, end: endOfDay)

        return DeviceActivityFilter(
            segment: .daily(during: dateInterval)
        )
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                switch screenTimeService.authorizationStatus {
                case .approved:
                    approvedContent

                case .notDetermined:
                    authorizationPrompt

                case .denied:
                    deniedState
                }
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
        .refreshable {
            await usageService.submitCurrentUsage()
            await usageService.loadTodaysUsage()
        }
    }

    // MARK: - Approved State

    @State private var syncStatus: String?

    private var approvedContent: some View {
        VStack(spacing: 16) {
            // DeviceActivityReport renders via the extension
            DeviceActivityReport(reportContext, filter: todayFilter)
                .frame(minHeight: 200)

            // Data status
            if let lastUpdated = SharedUsageData.readLastUpdated() {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                    Text("Data updated: \(lastUpdated)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            } else {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    Text("No data from Screen Time extension yet. Scroll up to check the report above.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            // Sync button
            Button {
                Task {
                    let summary = SharedUsageData.readCategorySummary()
                    if let summary, !summary.isEmpty {
                        syncStatus = "Syncing \(summary.count) categories..."
                        await usageService.submitCurrentUsage()
                        await usageService.loadTodaysUsage()
                        syncStatus = "Synced successfully"
                    } else {
                        syncStatus = "No usage data to sync — use your device and come back"
                    }
                }
            } label: {
                HStack {
                    Image(systemName: "arrow.triangle.2.circlepath")
                    Text("Sync to Server")
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color(.secondarySystemGroupedBackground))
                .cornerRadius(12)
            }

            if usageService.isSubmitting {
                ProgressView("Syncing...")
            }

            if let status = syncStatus {
                Text(status)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)
            }

            if let error = usageService.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
                    .padding(.horizontal)
            }
        }
    }

    // MARK: - Authorization Prompt

    private var authorizationPrompt: some View {
        VStack(spacing: 20) {
            Image(systemName: "hourglass.circle")
                .font(.system(size: 48))
                .foregroundColor(.blue)

            Text("Enable Screen Time Tracking")
                .font(.headline)

            Text("SurfSense needs permission to access your screen time data. This uses Apple's Screen Time framework and requires Face ID or passcode verification.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            Button {
                Task {
                    await screenTimeService.requestAuthorization()
                }
            } label: {
                Label("Grant Access", systemImage: "lock.open")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .padding(.horizontal, 40)
        }
        .padding(.top, 40)
    }

    // MARK: - Denied State

    private var deniedState: some View {
        VStack(spacing: 16) {
            Image(systemName: "xmark.shield")
                .font(.system(size: 48))
                .foregroundColor(.red)

            Text("Screen Time Access Denied")
                .font(.headline)

            Text("Please ensure Screen Time is enabled in iOS Settings, then try granting access again.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            if let error = screenTimeService.lastAuthError {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
                    .padding(.horizontal, 24)
                    .multilineTextAlignment(.center)
            }

            Button {
                Task {
                    await screenTimeService.requestAuthorization()
                }
            } label: {
                Label("Try Again", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .padding(.horizontal, 40)

            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Label("Open Settings", systemImage: "gear")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color(.secondarySystemGroupedBackground))
                    .cornerRadius(12)
            }
            .padding(.horizontal, 40)
        }
        .padding(.top, 40)
    }
}
