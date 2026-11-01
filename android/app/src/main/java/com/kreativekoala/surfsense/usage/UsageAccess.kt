package com.kreativekoala.surfsense.usage

import android.app.AppOpsManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings

object UsageAccess {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /**
     * Opens the system page where the user turns Usage Access on. Tries the page for this app first (Android 10+,
     * not honoured by every phone maker), then the general Usage Access list, then Settings. Returns false when
     * none of them could be opened.
     */
    fun openSettings(context: Context): Boolean {
        val newTask = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        val attempts = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(newTask))
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next, more general page.
            } catch (_: SecurityException) {
                // Some builds refuse the package form; the general page still works.
            }
        }
        return false
    }
}
