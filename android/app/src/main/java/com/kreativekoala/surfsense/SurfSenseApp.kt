package com.kreativekoala.surfsense

import android.app.Application
import com.facebook.appevents.AppEventsLogger
import timber.log.Timber

class SurfSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        AppEventsLogger.activateApp(this)
    }
}
