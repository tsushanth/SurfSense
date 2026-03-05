import Foundation
import os

private let logger = Logger(subsystem: "com.kreativekoala.SurfSense", category: "LocalStorage")

/// Local storage manager for caching data
class LocalStorage {
    static let shared = LocalStorage()

    private let defaults = UserDefaults.standard
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private init() {}

    // MARK: - Keys
    private enum Keys {
        static let cachedDevices = "cached_linked_devices"
        static let cachedUsageSummary = "cached_usage_summary"
        static let lastFetchTime = "last_fetch_time"
        static let usageHistory = "usage_history"
    }

    // MARK: - Linked Devices Cache

    func cacheLinkedDevices(_ devices: [ClientInfo]) {
        do {
            let data = try encoder.encode(devices)
            defaults.set(data, forKey: Keys.cachedDevices)
            defaults.set(Date(), forKey: Keys.lastFetchTime)
        } catch {
            logger.error("Failed to cache devices: \(error.localizedDescription)")
        }
    }

    func getCachedLinkedDevices() -> [ClientInfo]? {
        guard let data = defaults.data(forKey: Keys.cachedDevices) else { return nil }
        return try? decoder.decode([ClientInfo].self, from: data)
    }

    func clearCachedDevices() {
        defaults.removeObject(forKey: Keys.cachedDevices)
    }

    // MARK: - Usage Summary Cache

    func cacheUsageSummary(_ summary: [String: Int], for deviceId: String, date: String) {
        var allSummaries = getAllCachedSummaries()
        let key = "\(deviceId)_\(date)"
        allSummaries[key] = summary

        do {
            let data = try encoder.encode(allSummaries)
            defaults.set(data, forKey: Keys.cachedUsageSummary)
        } catch {
            logger.error("Failed to cache summary: \(error.localizedDescription)")
        }
    }

    func getCachedUsageSummary(for deviceId: String, date: String) -> [String: Int]? {
        let allSummaries = getAllCachedSummaries()
        let key = "\(deviceId)_\(date)"
        return allSummaries[key]
    }

    private func getAllCachedSummaries() -> [String: [String: Int]] {
        guard let data = defaults.data(forKey: Keys.cachedUsageSummary) else { return [:] }
        return (try? decoder.decode([String: [String: Int]].self, from: data)) ?? [:]
    }

    // MARK: - Usage History (for trends)

    struct DailyUsage: Codable {
        let date: String
        let totalMinutes: Int
        let byCategory: [String: Int]
    }

    func saveUsageHistory(_ usage: DailyUsage) {
        var history = getUsageHistory()

        // Remove old entry for same date if exists
        history.removeAll { $0.date == usage.date }
        history.append(usage)

        // Keep only last 30 days
        let sortedHistory = history.sorted { $0.date > $1.date }
        let trimmedHistory = Array(sortedHistory.prefix(30))

        do {
            let data = try encoder.encode(trimmedHistory)
            defaults.set(data, forKey: Keys.usageHistory)
        } catch {
            logger.error("Failed to save usage history: \(error.localizedDescription)")
        }
    }

    func getUsageHistory() -> [DailyUsage] {
        guard let data = defaults.data(forKey: Keys.usageHistory) else { return [] }
        return (try? decoder.decode([DailyUsage].self, from: data)) ?? []
    }

    func getUsageHistory(days: Int) -> [DailyUsage] {
        return Array(getUsageHistory().prefix(days))
    }

    // MARK: - Last Fetch Time

    func getLastFetchTime() -> Date? {
        return defaults.object(forKey: Keys.lastFetchTime) as? Date
    }

    func shouldRefresh(cacheTimeout: TimeInterval = 300) -> Bool {
        guard let lastFetch = getLastFetchTime() else { return true }
        return Date().timeIntervalSince(lastFetch) > cacheTimeout
    }

    // MARK: - Clear All Cache

    func clearAllCache() {
        defaults.removeObject(forKey: Keys.cachedDevices)
        defaults.removeObject(forKey: Keys.cachedUsageSummary)
        defaults.removeObject(forKey: Keys.lastFetchTime)
        defaults.removeObject(forKey: Keys.usageHistory)
    }
}
