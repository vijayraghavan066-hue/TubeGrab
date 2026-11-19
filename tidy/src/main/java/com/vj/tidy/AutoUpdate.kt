package com.vj.tidy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Auto-update: every few hours (in the background, no need to open the app) check GitHub for a
 * newer build, download it and install it.
 *
 * Android rules for apps installed outside the Play Store:
 *  - The FIRST update needs one tap on "Update" (Android asks because a file manager installed the app).
 *  - After that, Phone Tidy is its own installer, so on Android 12+ later updates install silently.
 *  - If Android still wants a tap, you get a notification "Phone Tidy update ready – tap to install".
 */
object AutoUpdate {
    private const val WORK = "tidy-auto-update"
    private const val CHANNEL = "updates"
    private const val NOTE_ID = 42

    /** Set while the app is on screen, so the install prompt can open directly. */
    @Volatile var appVisible = false

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val c = applicationContext
            // Never restart the app in the middle of a scan
            if (Scanner.busy) return Result.retry()
            Updater.check(c, silent = true)
            val available = Updater.state.value as? Updater.State.Available ?: return Result.success()
            Updater.download(c, available.release)
            val ready = Updater.state.value as? Updater.State.ReadyToInstall ?: return Result.retry()
            UpdateLog.add(c, "⬆️ Installing update build ${ready.release.build}")
            install(c, ready.file)
            return Result.success()
        }
    }

    /** Installs through PackageInstaller so later updates can be silent (Android 12+). */
    fun install(c: Context, apk: File) {
        try {
            val pi = c.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(c.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val cb = PendingIntent.getBroadcast(c, id, Intent(c, ResultReceiver::class.java).setPackage(c.packageName), flags)
                s.commit(cb.intentSender)
            }
        } catch (e: Exception) {
            UpdateLog.add(c, "⚠️ Update install failed: ${e.message}")
        }
    }

    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (appVisible) c.startActivity(confirm) else notifyTapToInstall(c, confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // app restarts on the new version
                else -> UpdateLog.add(c, "⚠️ Update not installed (${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status})")
            }
        }
    }

    /** Logs "Updated" after any update and makes sure the background checks keep running. */
    class UpdatedReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            UpdateLog.add(c, "✅ Updated to ${Updater.currentVersionName(c)}")
            schedule(c)
            NotificationManagerCompat.from(c).cancel(NOTE_ID)
        }
    }

    private fun notifyTapToInstall(c: Context, confirm: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= 33 && !Perms.has(c, Manifest.permission.POST_NOTIFICATIONS)) {
            UpdateLog.add(c, "⬆️ Update ready: open Phone Tidy and tap Install")
            return
        }
        val tap = PendingIntent.getActivity(c, 7, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Phone Tidy update ready")
            .setContentText("Tap to install the new version")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTE_ID, n)
    }
}
