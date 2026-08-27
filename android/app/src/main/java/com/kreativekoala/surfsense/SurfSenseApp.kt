package com.kreativekoala.surfsense

import android.app.Application
import com.google.firebase.analytics.FirebaseAnalytics
import timber.log.Timber

class SurfSenseApp : Application() {
    lateinit var firebaseAnalytics: FirebaseAnalytics
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
    }
}
