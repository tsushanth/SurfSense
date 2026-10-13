package com.kreativekoala.surfsense

/**
 * App configuration constants
 * Change these values for different environments (dev/staging/prod)
 */
object Config {
    // API Configuration
    const val API_BASE_URL = "https://usage-tracker-backend.fly.dev/"

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

    // Client Type
    const val CLIENT_TYPE = "ANDROID"

    // External URLs
    const val privacyPolicyURL = "https://kreativekoala.llc/privacy"
    const val termsOfServiceURL = "https://kreativekoala.llc/terms"
}
