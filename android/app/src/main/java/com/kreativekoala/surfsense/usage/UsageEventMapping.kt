package com.kreativekoala.surfsense.usage

/** Maps raw event fields to a [UsageEvent]; null when the platform gave no usable package name. */
fun toUsageEvent(packageName: String?, type: Int, timeMs: Long, className: String?): UsageEvent? {
    if (packageName.isNullOrBlank()) return null
    return UsageEvent(packageName, type, timeMs, className ?: "")
}
