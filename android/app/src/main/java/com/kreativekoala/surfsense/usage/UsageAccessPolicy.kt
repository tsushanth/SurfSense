package com.kreativekoala.surfsense.usage

import android.content.Context

/**
 * Decides how often the Usage Access dialog is shown and notices when access goes from missing to granted.
 *
 * The dialog is capped at [MAX_DIALOG_SHOWINGS] showings in total (not per launch); after that the in-screen
 * "Allow usage access" button is the only prompt. [onChecked] remembers the last answer in the [Store], so a flip
 * from missing to granted is still noticed after the process was killed while the user was in Settings.
 */
class UsageAccessPolicy(private val store: Store) {

    interface Store {
        fun dialogShown(): Int
        fun setDialogShown(count: Int)
        /** null = never checked. */
        fun lastGranted(): Boolean?
        fun setLastGranted(granted: Boolean)
    }

    fun shouldShowDialog(granted: Boolean): Boolean = !granted && store.dialogShown() < MAX_DIALOG_SHOWINGS

    fun recordDialogShown() = store.setDialogShown(store.dialogShown() + 1)

    /** Records [granted]; true only when the previous check said "missing" and this one says "granted". */
    fun onChecked(granted: Boolean): Boolean {
        val was = store.lastGranted()
        if (was != granted) store.setLastGranted(granted)
        return was == false && granted
    }

    class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun dialogShown(): Int = prefs.getInt(KEY_DIALOG_SHOWN, 0)
        override fun setDialogShown(count: Int) = prefs.edit().putInt(KEY_DIALOG_SHOWN, count).apply()
        override fun lastGranted(): Boolean? = if (prefs.contains(KEY_LAST_GRANTED)) prefs.getBoolean(KEY_LAST_GRANTED, false) else null
        override fun setLastGranted(granted: Boolean) = prefs.edit().putBoolean(KEY_LAST_GRANTED, granted).apply()
    }

    companion object {
        const val MAX_DIALOG_SHOWINGS = 2
        private const val PREFS = "surfsense_usage_access"
        private const val KEY_DIALOG_SHOWN = "dialog_shown"
        private const val KEY_LAST_GRANTED = "last_granted"
    }
}
