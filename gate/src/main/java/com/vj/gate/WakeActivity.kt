package com.vj.gate

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible screen: turns the display on, dismisses a lock screen that doesn't need a PIN
 * (e.g. Smart Lock "Trusted place" at home), then opens Gatewise and closes itself.
 */
class WakeActivity : Activity() {

    companion object { const val EXTRA_PKG = "pkg" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pkg = intent.getStringExtra(EXTRA_PKG)
        if (pkg == null) { finish(); return }

        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            launch(pkg)
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = launch(pkg)
            override fun onDismissError() = finish()
            override fun onDismissCancelled() = finish()
        })
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        finish()
    }
}
