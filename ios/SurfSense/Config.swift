import Foundation

/// App configuration constants
enum Config {
    // MARK: - Environment

    enum Environment {
        case development
        case staging
        case production
    }

    #if DEBUG
    static let environment: Environment = .development
    #else
    static let environment: Environment = .production
    #endif

    // MARK: - API Configuration

    static var apiBaseURL: String {
        switch environment {
        case .development:
            return "https://usage-tracker-backend-917362189743.us-central1.run.app"
        case .staging:
            return "https://usage-tracker-backend-917362189743.us-central1.run.app"
        case .production:
            return "https://usage-tracker-backend-917362189743.us-central1.run.app"
        }
    }

    // MARK: - Timeouts (in seconds)
    static let requestTimeout: TimeInterval = 30
    static let resourceTimeout: TimeInterval = 60

    // MARK: - Sync Configuration
    static let usageSyncIntervalMinutes = 15
    static let linkingCodeExpiryMinutes = 15
    static let linkingCodeLength = 6

    // MARK: - Background Task Identifiers
    static let backgroundSyncTaskId = "com.kreativekoala.surfsense.sync"

    // MARK: - App Group
    static let appGroupId = "group.com.kreativekoala.surfsense"

    // MARK: - Shared UserDefaults Keys (App Group)
    enum SharedKeys {
        static let usageCategorySummary = "usageCategorySummary"
        static let usageLastUpdated = "usageLastUpdated"
    }

    // MARK: - UserDefaults Keys
    enum UserDefaultsKeys {
        static let notificationsEnabled = "notificationsEnabled"
        static let weeklyReportsEnabled = "weeklyReportsEnabled"
        static let dataSyncEnabled = "dataSyncEnabled"
        static let linkedDeviceIds = "linkedDeviceIds"
        static let lastSyncTime = "lastSyncTime"
        static let deviceId = "deviceId"
    }

    // MARK: - Keychain Keys
    enum KeychainKeys {
        static let apiKey = "com.kreativekoala.surfsense.apiKey"
        static let deviceId = "com.kreativekoala.surfsense.deviceId"
    }

    // MARK: - Categories
    static let categories = [
        "Social Media",
        "Entertainment",
        "Work/Productivity",
        "Shopping",
        "Education",
        "News",
        "Finance",
        "Health",
        "Travel",
        "Food",
        "Other"
    ]

    // MARK: - Category Colors (named SwiftUI color strings used by views)
    static let categoryColors: [String: String] = [
        "Social": "blue",
        "Social Media": "blue",
        "Productivity": "green",
        "Work/Productivity": "green",
        "Entertainment": "purple",
        "News": "orange",
        "Education": "cyan",
        "Finance": "green",
        "Health": "pink",
        "Shopping": "yellow",
        "Travel": "indigo",
        "Food": "red",
        "Other": "gray",
        "Uncategorized": "gray"
    ]

    // MARK: - External URLs
    static let privacyPolicyURL = "https://surfsense.app/privacy"
    static let termsOfServiceURL = "https://surfsense.app/terms"
}
