package com.kreativekoala.surfsense.usage

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

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
}
