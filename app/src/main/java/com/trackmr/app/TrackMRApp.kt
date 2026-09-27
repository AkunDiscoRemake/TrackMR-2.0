package com.trackmr.app

import android.app.Application
import android.util.Log
import java.io.File

/** Records uncaught crashes to app storage so the next session can show them spatially. */
class TrackMRApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "last_crash.txt").writeText("Thread ${t.name}\n" + Log.getStackTraceString(e))
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }
}
