package com.kravz.delimap

import android.app.Application
import com.google.firebase.analytics.FirebaseAnalytics
import com.kravz.delimap.BuildConfig
import timber.log.Timber

class App : Application() {
    private lateinit var firebase: FirebaseAnalytics
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG)
            Timber.plant(Timber.DebugTree())
        firebase = FirebaseAnalytics.getInstance(this)
    }
}