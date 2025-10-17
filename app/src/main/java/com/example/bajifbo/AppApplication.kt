package com.example.bajifbo

import android.app.Application
class AppApplication : Application() {

    companion object {
        private var sInstance: AppApplication? = null

        @JvmStatic
        fun getInstance(): AppApplication {
            if (sInstance == null) {
                throw NullPointerException("AppApplication is not initialized yet!")
            }
            return sInstance!!
        }
    }

    override fun onCreate() {
        super.onCreate()
        sInstance = this
    }
}