package com.vj.gate

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accessibility service on the gate phone. When asked, it wakes the phone, opens Gatewise,
 * finds the gate tile by its text and taps it, then texts the result back.
 */
class GateService : AccessibilityService() {

    companion object {
        @Volatile var instance: GateService? = null

        fun isEnabled(c: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.startsWith("${c.packageName}/") }
        }

        fun statusText(c: Context): String {
            val km = c.getSystemService(KeyguardManager::class.java)
            val pm = c.getSystemService(PowerManager::class.java)
            val bm = c.getSystemService(BatteryManager::class.java)
            val lock = when {
                km.isDeviceLocked -> "🔒 locked (PIN) – gate can't be opened now"
                km.isKeyguardLocked -> "🔓 lock screen showing but unlockable (Smart Lock) – OK"
                else -> "🔓 unlocked – OK"
            }
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val a11y = if (instance != null) "on" else "OFF"
            val gw = if (Phone.gatewisePackage(c) != null) "found" else "NOT found"
            return "Phone $lock. Screen ${if (pm.isInteractive) "on" else "off"}. Battery $battery%. Accessibility $a11y. Gatewise $gw."
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * [replyTo] = number to text the result to (null for the in-app test).
     * [dryRun] = find the button but don't tap it.
     */
    fun openGate(gate: Gate, replyTo: String?, dryRun: Boolean = false) {
        if (job?.isActive == true) {
            replyTo?.let { reply(it, "⏳ Already working on the last request, try again in a few seconds.") }
            return
        }
        job = scope.launch { run(gate, replyTo, dryRun) }
    }

    private fun reply(to: String?, text: String) {
        History.add(this, text)
        if (to != null) Phone.sendSms(this, to, "${Command.REPLY_PREFIX} $text")
    }

    private suspend fun run(gate: Gate, replyTo: String?, dryRun: Boolean) {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val screenWasOn = pm.isInteractive
        val wasLocked = km.isKeyguardLocked

        if (km.isDeviceLocked) {
            reply(replyTo, "❌ Gate phone is locked with PIN/fingerprint, so ${gate.title} was NOT opened. (Smart Lock at home fixes this.)")
            return
        }
        val pkg = Phone.gatewisePackage(this)
        if (pkg == null) {
            reply(replyTo, "❌ Gatewise app not found on the gate phone.")
            return
        }

        // Wake the screen, clear the (non-secure) lock screen, open Gatewise
        startActivity(
            Intent(this, WakeActivity::class.java)
                .putExtra(WakeActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )

        val label = Prefs.label(this, gate)
        val start = System.currentTimeMillis()
        var target: AccessibilityNodeInfo? = null
        var pressedHome = false
        while (System.currentTimeMillis() - start < 15_000) {
            delay(400)
            val root = gatewiseRoot(pkg) ?: continue
            target = findTile(root, label)
            if (target != null) break
            // Gatewise may be on the Invite/Settings tab: go to Home once
            if (!pressedHome && System.currentTimeMillis() - start > 4_000) {
                findByText(root) { it.equals("Home", true) }?.let { click(it) }
                pressedHome = true
            }
        }

        if (target == null) {
            reply(replyTo, "❌ Opened Gatewise but couldn't find the \"$label\" button. ${if (km.isKeyguardLocked) "Lock screen still showing." else ""}".trim())
            finishUp(screenWasOn, wasLocked)
            return
        }

        if (dryRun) {
            reply(replyTo, "🧪 Test OK: found \"$label\" in Gatewise (not tapped).")
            finishUp(screenWasOn, wasLocked)
            return
        }

        val tapped = click(target)
        if (!tapped) {
            reply(replyTo, "❌ Found \"$label\" but the tap didn't go through.")
            finishUp(screenWasOn, wasLocked)
            return
        }

        // Give Gatewise a moment to show its result, and pass along any message it shows
        delay(3_000)
        val note = gatewiseRoot(pkg)?.let { resultText(it) }
        reply(replyTo, "✅ Tapped ${gate.title}." + (note?.let { " Gatewise says: \"$it\"" } ?: ""))
        delay(1_500)
        finishUp(screenWasOn, wasLocked)
    }

    /** Put the phone back how it was: lock it again if it was off/locked, else go back. */
    private fun finishUp(screenWasOn: Boolean, wasLocked: Boolean) {
        if ((!screenWasOn || wasLocked) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun gatewiseRoot(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName == pkg) return it }
        return windows.mapNotNull { it.root }.firstOrNull { it.packageName == pkg }
    }

    /** Exact text match first ("Pedestrian Gate" ≠ "Side Pedestrian Gate"), then starts-with. */
    private fun findTile(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val want = clean(label)
        return findByText(root) { clean(it) == want }
            ?: findByText(root) { clean(it).startsWith(want) }
            ?: findByText(root) { want.startsWith(clean(it)) && clean(it).length >= 6 }
    }

    private fun clean(s: String) = s.replace("…", "").replace("...", "").trim().lowercase()

    private fun findByText(root: AccessibilityNodeInfo, match: (String) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank() && match(t)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** Taps the node or its nearest clickable parent. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 8) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            depth++
        }
        return false
    }

    /** Any short message on screen that looks like a result (opened / error / etc.). */
    private fun resultText(root: AccessibilityNodeInfo): String? {
        val words = listOf("open", "unlock", "success", "error", "fail", "unable", "try again", "denied", "offline")
        val skip = Gate.entries.map { clean(Prefs.label(this, it)) }
        return findByText(root) { t ->
            val c = clean(t)
            c.length in 4..120 && words.any { c.contains(it) } && skip.none { c.startsWith(it) }
        }?.let { (it.text ?: it.contentDescription)?.toString() }
    }
}
