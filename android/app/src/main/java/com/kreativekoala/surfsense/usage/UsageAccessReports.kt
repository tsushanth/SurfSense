package com.kreativekoala.surfsense.usage

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the user did not turn Usage Access on. The codes are what the server accepts. */
enum class AccessRefusal(val code: String) {
    NOT_NOW("not_now"),
    RETURNED_WITHOUT_ACCESS("returned_without_access"),
    SETTINGS_UNAVAILABLE("settings_unavailable")
}

/**
 * Tells the server when Usage Access was refused, once per reason per install. A report that cannot be sent is kept and tried
 * again at the next chance. (Success needs no report: a phone with access syncs its first day at once.)
 */
class UsageAccessReports(
    private val store: Store,
    private val send: suspend (reason: String) -> Boolean,
) {
    interface Store {
        fun pending(): Set<String>
        fun sent(): Set<String>
        fun save(pending: Set<String>, sent: Set<String>)
    }

    private val sending = Mutex()

    @Synchronized
    fun record(refusal: AccessRefusal) {
        if (refusal.code in store.sent() || refusal.code in store.pending()) return
        store.save(store.pending() + refusal.code, store.sent())
    }

    suspend fun flush() = sending.withLock {
        for (reason in store.pending()) {
            val delivered = try {
                send(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (delivered) markSent(reason)
        }
    }

    @Synchronized
    private fun markSent(reason: String) = store.save(store.pending() - reason, store.sent() + reason)

    class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun pending(): Set<String> = prefs.getStringSet(KEY_PENDING, null).orEmpty().toSet()
        override fun sent(): Set<String> = prefs.getStringSet(KEY_SENT, null).orEmpty().toSet()
        override fun save(pending: Set<String>, sent: Set<String>) =
            prefs.edit().putStringSet(KEY_PENDING, pending).putStringSet(KEY_SENT, sent).apply()

        private companion object {
            const val PREFS = "surfsense_usage_access_reports"
            const val KEY_PENDING = "pending"
            const val KEY_SENT = "sent"
        }
    }
}
