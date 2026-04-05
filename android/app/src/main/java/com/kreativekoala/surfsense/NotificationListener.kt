package com.kreativekoala.surfsense

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArrayList

class NotificationListener : NotificationListenerService() {
    companion object {
        const val TAG = "NotificationListener"
        private const val MAX_NOTIFICATIONS = 100

        // Thread-safe list using CopyOnWriteArrayList
        private val _notificationsList = CopyOnWriteArrayList<NotificationData>()

        // Read-only access to notifications
        val notificationsList: List<NotificationData>
            get() = _notificationsList.toList()

        // Clear notifications
        fun clearNotifications() {
            _notificationsList.clear()
        }

        // Get recent notifications (thread-safe)
        fun getRecentNotifications(limit: Int = 50): List<NotificationData> {
            return _notificationsList.takeLast(limit)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val packageName = sbn.packageName
        val title = sbn.notification.extras.getString(NotificationCompat.EXTRA_TITLE) ?: "No title"
        val text = sbn.notification.extras.getString(NotificationCompat.EXTRA_TEXT) ?: "No text"

        val notificationData = NotificationData(packageName, title, text, System.currentTimeMillis())

        // Add to bounded list (remove oldest if full)
        synchronized(_notificationsList) {
            if (_notificationsList.size >= MAX_NOTIFICATIONS) {
                _notificationsList.removeAt(0)
            }
            _notificationsList.add(notificationData)
        }

        Timber.d("Notification from %s: %s", packageName, title)

        // Check user preference for showing notifications
        val prefs = getSharedPreferences(Config.Prefs.PREFS_NAME, MODE_PRIVATE)
        val showNotifications = prefs.getBoolean(Config.Prefs.KEY_NOTIFICATIONS_ENABLED, true)

        if (showNotifications) {
            showAppNotification(title, text)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        Timber.d("Notification removed from %s", sbn.packageName)
    }

    private fun showAppNotification(title: String, text: String) {
        val notificationId = System.currentTimeMillis().toInt()

        val builder = NotificationCompat.Builder(this, "notification_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.new_notification_title, title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(this)) {
                notify(notificationId, builder.build())
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Missing notification permission")
        }
    }
}
