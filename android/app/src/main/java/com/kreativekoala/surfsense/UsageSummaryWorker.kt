package com.kreativekoala.surfsense

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kreativekoala.surfsense.identity.Identity
import com.kreativekoala.surfsense.usage.CategoryMapper
import com.kreativekoala.surfsense.usage.CategoryOverrides
import com.kreativekoala.surfsense.usage.Day
import com.kreativekoala.surfsense.usage.UsageAccess
import com.kreativekoala.surfsense.usage.UsageCollector
import com.kreativekoala.surfsense.usage.UsageSyncer
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.TimeZone

class UsageSummaryWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Identity.init(applicationContext)
        // Without Usage Access there is nothing to read; succeed quietly so the schedule keeps running.
        if (!UsageAccess.isGranted(applicationContext)) return Result.success()

        val syncer = UsageSyncer(
            clock = System::currentTimeMillis,
            collect = { UsageCollector(applicationContext).todaySeconds(it) },
            mapper = CategoryMapper { ApiClient.create().getAppCategoryMapping(AppCategoryRequest(it)) },
            submit = { day, summary ->
                ApiClient.create().submitUsageReport(
                    UsageReportRequest(Identity.identity().clientId, day, summary.toMutableMap())
                )
            },
            ensureRegistered = { Identity.registrar().ensureRegistered() },
            dayOf = { Day.dayString(it, TimeZone.getDefault()) },
            applyOverrides = CategoryOverrides.applier(CategoryOverrides.SharedPrefsStore(applicationContext)),
        )

        return try {
            when (syncer.run()) {
                UsageSyncer.Outcome.SUCCESS -> Result.success()
                UsageSyncer.Outcome.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                UsageSyncer.Outcome.FAIL -> Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Usage sync crashed")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
