import Foundation
import os

private let logger = Logger(subsystem: "com.kreativekoala.SurfSense", category: "SharedData")

/// Data model shared between the main app and the DeviceActivityReport extension
/// via App Group container. Both targets must include this file.
enum SharedUsageData {

    private static let appGroupId = "group.com.kreativekoala.surfsense"
    private static let summaryFileName = "usageCategorySummary.json"
    private static let metaFileName = "usageMeta.json"

    /// The App Group shared container directory
    private static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId)
    }

    // MARK: - Write (called from extension)

    /// Write category usage summary to shared storage (file-based)
    static func writeCategorySummary(_ summary: [String: Int]) {
        guard let container = containerURL else {
            logger.error("App Group container is nil — group not provisioned")
            return
        }

        let summaryURL = container.appendingPathComponent(summaryFileName)
        let metaURL = container.appendingPathComponent(metaFileName)

        do {
            let data = try JSONEncoder().encode(summary)
            try data.write(to: summaryURL, options: .atomic)

            let timestamp = ISO8601DateFormatter().string(from: Date())
            let meta = ["lastUpdated": timestamp]
            let metaData = try JSONEncoder().encode(meta)
            try metaData.write(to: metaURL, options: .atomic)

            logger.info("Wrote \(summary.count) categories to App Group file")
        } catch {
            logger.error("Failed to write to App Group file: \(error.localizedDescription)")
        }
    }

    // MARK: - Read (called from host app)

    /// Read category usage summary from shared storage
    static func readCategorySummary() -> [String: Int]? {
        guard let container = containerURL else {
            logger.error("App Group container is nil — group not provisioned")
            return nil
        }

        let summaryURL = container.appendingPathComponent(summaryFileName)
        guard let data = try? Data(contentsOf: summaryURL) else {
            return nil
        }
        return try? JSONDecoder().decode([String: Int].self, from: data)
    }

    /// Read the timestamp of the last update
    static func readLastUpdated() -> String? {
        guard let container = containerURL else { return nil }

        let metaURL = container.appendingPathComponent(metaFileName)
        guard let data = try? Data(contentsOf: metaURL),
              let meta = try? JSONDecoder().decode([String: String].self, from: data) else {
            return nil
        }
        return meta["lastUpdated"]
    }

    /// Check if the App Group container is accessible (for diagnostics)
    static func isContainerAccessible() -> Bool {
        return containerURL != nil
    }

    // MARK: - Category Mapping (Apple → SurfSense)

    /// Maps Apple's ActivityCategory display names to SurfSense categories
    static func mapCategory(_ appleCategoryName: String) -> String {
        let normalized = appleCategoryName.lowercased()

        switch normalized {
        case let s where s.contains("social"):
            return "Social Media"
        case let s where s.contains("productivity"):
            return "Work/Productivity"
        case let s where s.contains("entertainment"):
            return "Entertainment"
        case let s where s.contains("news"):
            return "News"
        case let s where s.contains("education"):
            return "Education"
        case let s where s.contains("shopping"):
            return "Shopping"
        case let s where s.contains("finance"):
            return "Finance"
        case let s where s.contains("health") || s.contains("fitness"):
            return "Health"
        case let s where s.contains("travel"):
            return "Travel"
        case let s where s.contains("food") || s.contains("drink"):
            return "Food"
        case let s where s.contains("game"):
            return "Entertainment"
        default:
            return "Other"
        }
    }
}
