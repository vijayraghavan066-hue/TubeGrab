package com.vj.tubegrab

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Unpack yt-dlp + ffmpeg and refresh yt-dlp in the background on every launch.
        // YouTube changes often; a fresh yt-dlp keeps search and downloads working.
        AppSettings.init(this)
        NetworkMonitor.start(this)
        Engine.warmUp(this)
    }
}
