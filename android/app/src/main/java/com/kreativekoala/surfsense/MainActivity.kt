package com.kreativekoala.surfsense

import com.kreativekoala.surfsense.usage.UsageAccess
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kreativekoala.surfsense.ui.SurfSenseApp
import com.kreativekoala.surfsense.ui.theme.SurfSenseTheme
import com.kreativekoala.surfsense.viewmodel.MainViewModel
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var usageAccessDialog: androidx.appcompat.app.AlertDialog? = null
    private var awaitingUsageAccess = false
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) com.kreativekoala.ratingkit.RatingKit.trackAppOpen(this) // records the first launch; never asks

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        if (savedInstanceState == null) requestUsageAccessPermission()
        schedulePeriodicWork()

        viewModel.initialize()

        setContent {
            SurfSenseTheme {
                SurfSenseApp(viewModel = viewModel)
            }
        }
    }

    private fun requestUsageAccessPermission() {
        if (hasUsageStatsPermission()) return
        usageAccessDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_access_title)
            .setMessage(R.string.usage_access_message)
            .setPositiveButton(R.string.usage_access_continue) { _, _ ->
                awaitingUsageAccess = true
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: android.content.ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: android.content.ActivityNotFoundException) {
                        // No settings screen available; nothing more we can do.
                    }
                }
            }
            .setNegativeButton(R.string.usage_access_not_now, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingUsageAccess && hasUsageStatsPermission()) {
            awaitingUsageAccess = false
            viewModel.syncNow()
        }
    }

    override fun onDestroy() {
        usageAccessDialog?.dismiss()
        usageAccessDialog = null
        super.onDestroy()
    }

    private fun hasUsageStatsPermission(): Boolean = UsageAccess.isGranted(this)

    private fun schedulePeriodicWork() {
        val usageWorkRequest = PeriodicWorkRequestBuilder<UsageSummaryWorker>(
            Config.USAGE_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DailyUsageSummary",
            ExistingPeriodicWorkPolicy.KEEP,
            usageWorkRequest
        )
    }
}
