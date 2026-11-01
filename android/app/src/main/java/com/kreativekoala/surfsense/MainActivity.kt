package com.kreativekoala.surfsense

import com.kreativekoala.surfsense.usage.UsageAccess
import com.kreativekoala.surfsense.usage.UsageAccessPolicy
import android.os.Bundle
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
        // At most two showings in total; after that the Today screen carries an "Allow usage access" button.
        val policy = UsageAccessPolicy(UsageAccessPolicy.PrefsStore(this))
        if (!policy.shouldShowDialog(UsageAccess.isGranted(this))) return
        policy.recordDialogShown()
        usageAccessDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_access_title)
            .setMessage(R.string.usage_access_message)
            .setPositiveButton(R.string.usage_access_continue) { _, _ -> UsageAccess.openSettings(this) }
            .setNegativeButton(R.string.usage_access_not_now, null)
            .show()
    }

    override fun onDestroy() {
        usageAccessDialog?.dismiss()
        usageAccessDialog = null
        super.onDestroy()
    }

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
