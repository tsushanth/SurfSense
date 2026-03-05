package com.kreativekoala.surfsense

/**
 * App configuration constants
 * Change these values for different environments (dev/staging/prod)
 */
object Config {
    // API Configuration
    const val API_BASE_URL = "https://usage-tracker-backend-917362189743.us-central1.run.app/"

    // Timeouts (in seconds)
    const val CONNECT_TIMEOUT = 30L
    const val READ_TIMEOUT = 30L
    const val WRITE_TIMEOUT = 30L

    // Worker Configuration
    const val USAGE_SYNC_INTERVAL_MINUTES = 15L

    // Linking Code Configuration
    const val LINKING_CODE_LENGTH = 6
    const val LINKING_CODE_EXPIRY_MINUTES = 15

    // Logging (disable in production)
    const val ENABLE_NETWORK_LOGGING = false

    // SharedPreferences Keys
    object Prefs {
        const val PREFS_NAME = "SurfSensePrefs"
        const val KEY_LINKED_DEVICE_IDS = "linked_device_ids"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
    }

    // Client Type
    const val CLIENT_TYPE = "ANDROID_PHONE"

    // External URLs
    const val privacyPolicyURL = "https://surfsense.app/privacy"
    const val termsOfServiceURL = "https://surfsense.app/terms"

    // Categories
    val CATEGORIES = listOf(
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
    )
}
