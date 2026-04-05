package com.kreativekoala.surfsense

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ApiClient.initialize(this)

        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        requestUsageAccessPermission()
        schedulePeriodicWork()

        viewModel.initialize()

        setContent {
            SurfSenseTheme {
                SurfSenseApp(viewModel = viewModel)
            }
        }
    }

    private fun requestUsageAccessPermission() {
        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            "android:get_usage_stats",
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
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
