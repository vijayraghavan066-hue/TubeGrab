package com.vj.tubegrab

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Unpack yt-dlp + ffmpeg and refresh yt-dlp in the background on every launch.
        // YouTube changes often; a fresh yt-dlp keeps search and downloads working.
        AppSettings.init(this)
        NetworkMonitor.start(this)
        Engine.warmUp(this)
        // Temporary downloads: delete expired files now and once a day in the background
        ExpiryStore.init(this)
        ExpiryStore.scheduleDailyCleanup(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ExpiryStore.cleanUp(this@App) }
    }
}
