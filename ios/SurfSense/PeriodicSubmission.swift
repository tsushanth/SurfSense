import BackgroundTasks
import UIKit

/// Manages periodic background sync of usage data via BGTaskScheduler
enum PeriodicSubmission {

    /// Register the background task with the system. Call from `application(_:didFinishLaunchingWithOptions:)` or App init.
    static func registerBackgroundTask() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Config.backgroundSyncTaskId,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else { return }
            handleBackgroundSync(task: refreshTask)
        }
    }

    /// Schedule the next background sync
    static func scheduleBackgroundSync() {
        let request = BGAppRefreshTaskRequest(identifier: Config.backgroundSyncTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(Config.usageSyncIntervalMinutes * 60))

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Background task scheduling failed - will retry on next app launch
        }
    }

    private static func handleBackgroundSync(task: BGAppRefreshTask) {
        // Schedule the next sync before processing
        scheduleBackgroundSync()

        let syncTask = Task {
            // Only submit if we have reasonably fresh data from the DeviceActivityReport extension
            if let lastUpdated = SharedUsageData.readLastUpdated(),
               let date = ISO8601DateFormatter().date(from: lastUpdated),
               Date().timeIntervalSince(date) < 7200 { // 2 hours
                let service = UsageTrackingService()
                await service.submitCurrentUsage()
            }
        }

        task.expirationHandler = {
            syncTask.cancel()
        }

        Task {
            _ = await syncTask.value
            task.setTaskCompleted(success: true)
        }
    }
}
