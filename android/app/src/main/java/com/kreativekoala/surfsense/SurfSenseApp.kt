package com.kreativekoala.surfsense

import android.app.Application
import timber.log.Timber

class SurfSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        com.kreativekoala.surfsense.identity.Identity.init(this)
        // Ratings (RatingKit v2): asked rarely, only after the user has looked at a real week of their data; never at launch.
        com.kreativekoala.ratingkit.RatingKit.init(this, appId = "surfsense")
    }
}
